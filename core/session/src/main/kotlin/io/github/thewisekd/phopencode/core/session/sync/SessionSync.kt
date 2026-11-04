package io.github.thewisekd.phopencode.core.session.sync

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.InteractionApi
import io.github.thewisekd.phopencode.core.api.MessageApi
import io.github.thewisekd.phopencode.core.api.MessageQuery
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.PageCursor
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionApi
import io.github.thewisekd.phopencode.core.api.SessionEvent
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.session.reducer.SeenEvents
import io.github.thewisekd.phopencode.core.session.reducer.SessionReducer
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield

/**
 * Keeps one session's [SessionState] in step with the server across connections.
 *
 * The server replays nothing after a drop, so each [ServerEvent.Connected] starts a resync: the
 * session, its inbox, every page of its messages (following the cursors, oldest first), its pending
 * forms and permissions, and whether it runs. Once all of that has arrived, a fresh [SessionReducer]
 * is seeded with it, the events that arrived since the connection came up are applied on top in
 * arrival order, and the result replaces what was shown. So whatever was missed while the stream was down, the state
 * after a resync is the fetched truth plus the events after it. Events that the fetch already
 * reflects are applied again on purpose: the reducer is built to take an event onto a seeded list.
 *
 * Rules:
 *
 * - A repeated event has no effect: events are recognised by ID, else durable position, across
 *   connections too. One seen before a drop happened before the fetch that follows, so it is already
 *   part of what that fetch returns.
 * - No partly fetched state is ever shown. Until a resync completes, [state] keeps the previous
 *   session, which still takes live events, with [SyncPhase.Syncing] or [SyncPhase.Retrying].
 * - A fetch that fails with [ApiError.Network], [ApiError.Timeout], [ApiError.Server],
 *   [ApiError.Unexpected] or [ApiError.MalformedResponse] is dropped whole and started over after
 *   [RetryDelays], keeping the events buffered since the connection came up. Any other error, such as
 *   [ApiError.Unauthorized] or [ApiError.NotFound] for a deleted session, ends [run] with it, as does
 *   a failed event stream.
 * - A drop or a new connection abandons a running fetch and the events buffered for it; the next
 *   connection's fetch covers them.
 * - A fetch result is taken as soon as it is ready, before any event that is waiting, so a stream
 *   that never pauses cannot hold a resync back.
 * - At most [maxBuffered] events are held for a fetch. One more empties the buffer and is not held:
 *   all of them arrived before any call that the next fetch makes, so its answers cover them. A fetch
 *   that has started its calls is therefore started over at once; one waiting to retry keeps waiting.
 *   A stream that fills the buffer faster than a fetch completes keeps starting it over.
 * - The fetched status ([SessionApi.activeStatuses], asked for last) is placed among the held events
 *   by arrival from the stream, counted as each event is received, still queued ones included. Those
 *   received before the status was asked for are applied first, so an execution's end or start that
 *   the answer already reflects cannot override it; those after it are applied after it. A fetch
 *   that completes waits for the events received before that point to be handled.
 *
 * The inbox the fetch returns replaces the one shown, so a reconnect shows the prompts still queued
 * and drops those delivered or cancelled while the stream was down. It is read before the messages:
 * an item delivered between the two calls is then in both, and shows once, as its message; one
 * delivered after both is in the inbox, and its delivery event, held for the fetch, turns it into its
 * message. Read the other way round, such an item would be in neither.
 *
 * Nothing here logs, and [state] prints counts only.
 *
 * @param events The event stream, as [io.github.thewisekd.phopencode.core.api.EventApi.events]
 *   gives it: a [ServerEvent.Connected] starts each connection. [run] collects it once.
 * @param pageSize Messages asked for per page.
 * @param maxBuffered The most events held while a fetch runs; see the rules above.
 */
class SessionSync(
    val sessionId: SessionId,
    private val sessions: SessionApi,
    private val messages: MessageApi,
    private val interactions: InteractionApi,
    private val events: Flow<ServerEvent>,
    private val retryDelays: RetryDelays = RetryDelays(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxBuffered: Int = DEFAULT_MAX_BUFFERED,
) {
    /** Follows [sessionId] on [client], over a connection of its own. */
    constructor(
        client: OpencodeClient,
        sessionId: SessionId,
        retryDelays: RetryDelays = RetryDelays(),
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maxBuffered: Int = DEFAULT_MAX_BUFFERED,
    ) : this(
        sessionId,
        client.sessions,
        client.messages,
        client.interactions,
        client.events.events(),
        retryDelays,
        pageSize,
        maxBuffered,
    )

    init {
        require(pageSize > 0) { "pageSize must be positive" }
        require(maxBuffered > 0) { "maxBuffered must be positive" }
    }

    private val mutableState = MutableStateFlow(SyncState())
    private val started = AtomicBoolean(false)

    /** The most events held for a fetch at once, for tests. */
    @Volatile
    internal var bufferHighWater: Int = 0
        private set

    val state: StateFlow<SyncState> = mutableState.asStateFlow()

    /**
     * Follows the session until cancelled. Cancelling closes the connection and stops any fetch or
     * wait. Call it once per instance.
     *
     * @throws ApiException With an error that retrying cannot help, after setting [SyncPhase.Failed].
     */
    suspend fun run() {
        check(started.compareAndSet(false, true)) { "run was already called" }
        try {
            coroutineScope {
                val run = Run(this)
                run.loop(events.map { Received(run.received.incrementAndGet(), it) }.produceIn(this))
            }
        } catch (e: ApiException) {
            mutableState.update { it.copy(phase = SyncPhase.Failed(e.error)) }
            throw e
        }
    }

    private class Truth(
        val session: Session,
        val inbox: List<InboxItem>,
        val messages: List<Message>,
        val forms: List<Form>,
        val permissions: List<PermissionRequest>,
        val activity: SessionActivity?,
    )

    private sealed interface Fetched {
        class Done(val truth: Truth) : Fetched

        class Failed(val error: ApiError) : Fetched
    }

    /** An event and its place among those received from the stream, counted from 1. */
    private class Received(val seq: Long, val event: ServerEvent)

    private class Held(val seq: Long, val event: SessionEvent)

    /** One try at fetching. Its fetch runs apart from the loop, so what both touch is volatile. */
    private class Attempt {
        lateinit var result: Deferred<Fetched>

        /** Set before the first call: events held from then on may not be in the answers. */
        @Volatile var reading = false

        /** How many events had been received from the stream when the status was asked for. */
        @Volatile var statusMark = 0L

        /** The answers, waiting for the events received before [statusMark] to be handled. */
        var done: Truth? = null
    }

    /** The state of one [run]; touched from its loop only, but for [received] and what [Attempt] says. */
    private inner class Run(private val scope: CoroutineScope) {
        /** Events received from the stream so far, counted where they arrive, before any queue. */
        val received = AtomicLong()

        /** The place of the last event handled. */
        private var handled = 0L
        private val seen = SeenEvents()
        private var reducer: SessionReducer? = null
        private var fetch: Attempt? = null
        private var attempt = 0

        /** This session's events since the connection came up, for the fetch to take. */
        private val buffered = ArrayList<Held>()

        suspend fun loop(incoming: ReceiveChannel<Received>) {
            while (true) {
                val current = fetch
                val ended = select {
                    // The result first: select prefers its first clause, and events may never pause.
                    if (current != null && current.done == null) {
                        current.result.onAwait { fetched ->
                            fetched(current, fetched)
                            false
                        }
                    }
                    incoming.onReceiveCatching { result ->
                        val item = result.getOrNull()
                        if (item == null) {
                            result.exceptionOrNull()?.let { throw it }
                            true
                        } else {
                            handled = item.seq
                            receive(item)
                            false
                        }
                    }
                }
                if (ended) break
                fetch?.let { if (it.done != null && handled >= it.statusMark) finish(it) }
                // Receiving a ready event does not suspend; this lets the fetch run in between.
                if (fetch != null) yield()
            }
            abandonFetch()
        }

        private fun receive(item: Received) {
            when (val event = item.event) {
                is ServerEvent.Connected -> {
                    abandonFetch()
                    attempt = 0
                    startFetch(Duration.ZERO)
                    publish(SyncPhase.Syncing)
                }
                is ServerEvent.Disconnected -> {
                    abandonFetch()
                    publish(SyncPhase.Offline(event.reason))
                }
                is SessionEvent -> {
                    if (event.sessionId != sessionId || !seen.firstTime(event.meta)) return
                    fetch?.let { hold(it, Held(item.seq, event)) }
                    val current = reducer ?: return
                    if (current.apply(event)) publish(phase = null)
                }
                else -> Unit
            }
        }

        private fun hold(current: Attempt, held: Held) {
            if (buffered.size < maxBuffered) {
                buffered += held
                if (buffered.size > bufferHighWater) bufferHighWater = buffered.size
                return
            }
            // Full: every held event, this one too, came before any call of the next fetch, which
            // covers them all.
            buffered.clear()
            if (current.reading) {
                current.result.cancel()
                startFetch(Duration.ZERO)
            }
        }

        private fun startFetch(wait: Duration) {
            val next = Attempt()
            next.result = scope.async { fetchTruth(next, wait) }
            fetch = next
        }

        private fun fetched(current: Attempt, result: Fetched) {
            when (result) {
                is Fetched.Done -> current.done = result.truth
                is Fetched.Failed -> {
                    fetch = null
                    if (!result.error.isRetryable()) throw ApiException(result.error)
                    attempt++
                    val wait = retryDelays.after(attempt)
                    startFetch(wait)
                    publish(SyncPhase.Retrying(result.error, attempt, wait))
                }
            }
        }

        /** Seeds a fresh reducer with the answers and the events held for them. */
        private fun finish(current: Attempt) {
            fetch = null
            val truth = checkNotNull(current.done)
            val (before, after) = buffered.partition { it.seq <= current.statusMark }
            reducer = seed(truth).apply {
                before.forEach { apply(it.event) }
                statusOf(truth.activity)?.let { apply(ServerEvent.SessionStatusChanged(sessionId, it)) }
                after.forEach { apply(it.event) }
            }
            buffered.clear()
            publish(SyncPhase.Live)
        }

        private fun abandonFetch() {
            fetch?.result?.cancel()
            fetch = null
            buffered.clear()
        }

        /** Shows the current reducer's state, with [phase] or else the phase shown now. */
        private fun publish(phase: SyncPhase?) {
            val session: SessionState? = reducer?.state
            mutableState.update { SyncState(session, phase ?: it.phase) }
        }

        private suspend fun fetchTruth(current: Attempt, wait: Duration): Fetched {
            if (wait.isPositive()) delay(wait)
            current.reading = true
            return try {
                val session = sessions.get(sessionId)
                // Before the messages; see the class KDoc.
                val inbox = messages.inbox(sessionId)
                val history = allMessages()
                val forms = interactions.listForms(sessionId)
                val permissions = interactions.listPermissions(sessionId)
                current.statusMark = received.get()
                val activity = sessions.activeStatuses()[sessionId]
                Fetched.Done(Truth(session, inbox, history, forms, permissions, activity))
            } catch (e: ApiException) {
                Fetched.Failed(e.error)
            }
        }
    }

    /**
     * A fresh reducer holding [truth]. The pending items have no seed, so they go in as events. An
     * inbox item that is already a message is skipped by the reducer, so it shows once.
     */
    private fun seed(truth: Truth): SessionReducer = SessionReducer(sessionId, truth.session, truth.messages).apply {
        truth.inbox.forEach { apply(ServerEvent.InboxEnqueued(it)) }
        truth.forms.forEach { apply(ServerEvent.FormCreated(it)) }
        truth.permissions.forEach { apply(ServerEvent.PermissionAsked(it)) }
    }

    /** The status an answer of [SessionApi.activeStatuses] gives; null for one this app does not know. */
    private fun statusOf(activity: SessionActivity?): SessionStatus? = when (activity) {
        null -> SessionStatus.Idle
        SessionActivity.Running -> SessionStatus.Running
        is SessionActivity.Unknown -> null
    }

    /** Every page, oldest first. A cursor the server hands out twice would loop, so it fails. */
    private suspend fun allMessages(): List<Message> {
        val all = ArrayList<Message>()
        val cursors = HashSet<PageCursor>()
        var cursor: PageCursor? = null
        while (true) {
            val query = MessageQuery(limit = pageSize, order = SortOrder.Ascending, cursor = cursor)
            val page = messages.list(sessionId, query)
            all += page.items
            val next = page.nextCursor
            if (page.isEnd || next == null) return all
            if (!cursors.add(next)) throw ApiException(ApiError.MalformedResponse)
            cursor = next
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 100
        const val DEFAULT_MAX_BUFFERED: Int = 4_096
    }
}

/** Whether fetching again later can succeed. */
internal fun ApiError.isRetryable(): Boolean = when (this) {
    ApiError.Network, ApiError.Timeout, ApiError.MalformedResponse -> true
    is ApiError.Server, is ApiError.Unexpected -> true
    else -> false
}
