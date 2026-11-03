package io.github.thewisekd.phopencode.core.testing.api

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.EventApi
import io.github.thewisekd.phopencode.core.api.ServerEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * An event stream a test drives by hand.
 *
 * Each collection is a connection of its own, and starts with [ServerEvent.Connected]. [emit] reaches
 * the connections that are up, so call [awaitConnections] first: as on the real server, an event
 * nobody is connected for is lost.
 *
 * The real flow only ends by failing. [end] is the one thing here it never does: use it to finish a
 * collection in a test.
 *
 * @see drop Emits [ServerEvent.Disconnected], and the connections stay down until [reconnect].
 * @see fail Fails the flow with what retrying cannot help.
 */
class FakeEventApi(private val control: FakeApiControl = FakeApiControl()) : EventApi {
    private sealed interface Signal {
        data class Event(val event: ServerEvent) : Signal

        data class Failure(val error: ApiError) : Signal

        data object End : Signal
    }

    private class Connection(val types: Set<String>) {
        val signals = Channel<Signal>(Channel.UNLIMITED)
        var up = true
    }

    private class Delivery(val connection: Connection, val signal: Signal)

    // Guards all state here, the outbox included. Nothing is ever sent while holding it: an unconfined
    // collector runs inside the send, and may take the session lock that another emitter holds.
    private val connections = mutableListOf<Connection>()

    // One queue with one thread draining it keeps the order states changed in, also when a collector
    // emits from inside a send, or two threads emit a drop and a reconnect.
    private val outbox = ArrayDeque<Delivery>()
    private var draining = false
    private val active = MutableStateFlow(0)
    private val types = mutableListOf<Set<String>>()
    private var opened = 0
    private var refusal: ApiError? = null

    init {
        control.onClose { terminate(Signal.Failure(ApiError.Closed)) }
    }

    /** The collections running now. */
    val activeConnections: StateFlow<Int> = active.asStateFlow()

    /** Every connection made so far, reconnects included. */
    val connectionsOpened: Int
        get() = synchronized(connections) { opened }

    /** The connections [emit] would reach or that are down: the ones holding a queue. */
    internal val registeredConnections: Int
        get() = synchronized(connections) { connections.size }

    /** The `types` of each collection, oldest first. */
    val requestedTypes: List<Set<String>>
        get() = synchronized(connections) { types.toList() }

    override fun events(types: Set<String>): Flow<ServerEvent> = flow {
        val connection = open(types)
        // Outside the lock too: an unconfined waiter of the count runs inside this update.
        active.update { it + 1 }
        try {
            for (signal in connection.signals) {
                when (signal) {
                    is Signal.Event -> emit(signal.event)
                    is Signal.Failure -> throw ApiException(signal.error)
                    Signal.End -> return@flow
                }
            }
        } finally {
            synchronized(connections) { connections -= connection }
            active.update { it - 1 }
        }
    }

    private fun open(types: Set<String>): Connection = synchronized(connections) {
        control.checkOpen()
        refusal?.let { throw ApiException(it) }
        val connection = Connection(types)
        // Safe under the lock, and only here: its collector is this coroutine, which is not receiving yet.
        connection.signals.trySend(Signal.Event(ServerEvent.Connected()))
        connections += connection
        this.types += types
        opened++
        connection
    }

    /** Waits until at least [count] collections are running. */
    suspend fun awaitConnections(count: Int) {
        active.first { it >= count }
    }

    /**
     * Sends [event] to the connections that are up and asked for its type. [ServerEvent.Connected] and
     * [ServerEvent.Disconnected] pass any filter, and move a connection up or down as they would.
     *
     * Events keep their order. If another thread is delivering, or this is called from a collector,
     * this returns at once and the event follows the ones before it.
     */
    fun emit(event: ServerEvent) = post { deliveries ->
        when (event) {
            is ServerEvent.Disconnected -> connections.filter { it.up }.forEach {
                it.up = false
                deliveries += Delivery(it, Signal.Event(event))
            }
            is ServerEvent.Connected -> connections.forEach {
                if (!it.up) opened++
                it.up = true
                deliveries += Delivery(it, Signal.Event(event))
            }
            else -> connections.filter { it.up && it.wants(event) }.forEach {
                deliveries += Delivery(it, Signal.Event(event))
            }
        }
    }

    private fun Connection.wants(event: ServerEvent) = types.isEmpty() || event.wireType() in types

    /** Changes the state and queues what follows from it under the lock, then delivers outside it. */
    private fun post(change: (MutableList<Delivery>) -> Unit) {
        val drainHere = synchronized(connections) {
            change(outbox)
            val idle = !draining
            draining = true
            idle
        }
        if (drainHere) drain()
    }

    private fun drain() {
        while (true) {
            val next = synchronized(connections) {
                outbox.removeFirstOrNull().also { if (it == null) draining = false }
            } ?: return
            next.connection.signals.trySend(next.signal)
        }
    }

    /** The connection drops: emits [ServerEvent.Disconnected] and sends nothing until [reconnect]. */
    fun drop(reason: ApiError = ApiError.Network) = emit(ServerEvent.Disconnected(reason))

    /** Connections that are down come back, each with [ServerEvent.Connected]. */
    fun reconnect() = post { deliveries ->
        connections.filter { !it.up }.forEach {
            it.up = true
            opened++
            deliveries += Delivery(it, Signal.Event(ServerEvent.Connected()))
        }
    }

    /**
     * Fails every running collection with [error], after what was already sent. A real flow retries
     * what can pass, so an error like that is refused here: use [drop] for those.
     */
    fun fail(error: ApiError) {
        require(error != ApiError.Network && error != ApiError.Timeout && error !is ApiError.Server) {
            "A real stream retries $error: use drop"
        }
        terminate(Signal.Failure(error))
    }

    /** New collections fail with [error] at once; null accepts them again. */
    fun refuseConnections(error: ApiError?) {
        synchronized(connections) { refusal = error }
    }

    /** Completes every running collection. See the class comment. */
    fun end() = terminate(Signal.End)

    private fun terminate(signal: Signal) = post { deliveries ->
        connections.forEach { deliveries += Delivery(it, signal) }
        connections.clear()
    }
}

/** The server's name for an event, which is what `types` selects on. Null for the client's own. */
internal fun ServerEvent.wireType(): String? = when (this) {
    is ServerEvent.Connected -> "server.connected"
    is ServerEvent.Disconnected -> null
    is ServerEvent.Unknown -> rawType
    is ServerEvent.SessionCreated -> "session.created"
    is ServerEvent.SessionDeleted -> "session.deleted"
    is ServerEvent.SessionRenamed -> "session.renamed"
    is ServerEvent.SessionArchived -> "session.archived"
    is ServerEvent.SessionUnarchived -> "session.unarchived"
    is ServerEvent.SessionForked -> "session.forked"
    is ServerEvent.SessionStatusChanged -> "session.status"
    is ServerEvent.ExecutionStarted -> "session.execution.started"
    is ServerEvent.ExecutionSucceeded -> "session.execution.succeeded"
    is ServerEvent.ExecutionFailed -> "session.execution.failed"
    is ServerEvent.ExecutionInterrupted -> "session.execution.interrupted"
    is ServerEvent.RetryScheduled -> "session.retry.scheduled"
    is ServerEvent.StepStarted -> "session.step.started"
    is ServerEvent.StepStreamed -> "session.step.streamed"
    is ServerEvent.StepEnded -> "session.step.ended"
    is ServerEvent.StepFailed -> "session.step.failed"
    is ServerEvent.TextStarted -> "session.text.started"
    is ServerEvent.TextDelta -> "session.text.delta"
    is ServerEvent.TextEnded -> "session.text.ended"
    is ServerEvent.ReasoningStarted -> "session.reasoning.started"
    is ServerEvent.ReasoningDelta -> "session.reasoning.delta"
    is ServerEvent.ReasoningEnded -> "session.reasoning.ended"
    is ServerEvent.ToolInputStarted -> "session.tool.input.started"
    is ServerEvent.ToolInputDelta -> "session.tool.input.delta"
    is ServerEvent.ToolInputEnded -> "session.tool.input.ended"
    is ServerEvent.ToolCalled -> "session.tool.called"
    is ServerEvent.ToolProgress -> "session.tool.progress"
    is ServerEvent.ToolSucceeded -> "session.tool.success"
    is ServerEvent.ToolFailed -> "session.tool.failed"
    is ServerEvent.UsageUpdated -> "session.usage.updated"
    is ServerEvent.FormCreated -> "form.created"
    is ServerEvent.FormReplied -> "form.replied"
    is ServerEvent.FormCancelled -> "form.cancelled"
    is ServerEvent.PermissionAsked -> "permission.asked"
    is ServerEvent.PermissionReplied -> "permission.replied"
    is ServerEvent.CompactionStarted -> "session.compaction.started"
    is ServerEvent.CompactionDelta -> "session.compaction.delta"
    is ServerEvent.CompactionEnded -> "session.compaction.ended"
    is ServerEvent.CompactionFailed -> "session.compaction.failed"
    is ServerEvent.RevertStaged -> "session.revert.staged"
    is ServerEvent.RevertCleared -> "session.revert.cleared"
    is ServerEvent.RevertCommitted -> "session.revert.committed"
    is ServerEvent.ShellStarted -> "session.shell.started"
    is ServerEvent.ShellEnded -> "session.shell.ended"
    is ServerEvent.InboxEnqueued -> "session.inbox.enqueued"
    is ServerEvent.InboxDelivered -> "session.inbox.delivered"
    is ServerEvent.InboxCancelled -> "session.inbox.cancelled"
    is ServerEvent.InboxDeliveryChanged -> "session.inbox.delivery.changed"
    is ServerEvent.AgentSelected -> "session.agent.selected"
    is ServerEvent.ModelSelected -> "session.model.selected"
}
