package io.github.thewisekd.phopencode.core.api_http.event.stream

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What [EventStream] emits: the state of the connection, and the raw events in between. */
internal sealed interface StreamSignal {
    /**
     * A connection is up. The server replays nothing, so whatever happened while there was none is
     * lost: fetch the state again.
     */
    data object Connected : StreamSignal

    /** One event as it came off the wire. Heartbeats and other comments are never emitted. */
    data class Event(val event: SseEvent) : StreamSignal

    /**
     * An attempt failed, or a connection dropped, and the next attempt follows in [retryIn].
     *
     * @property attempt Failures in a row since the last healthy connection, from 1. Not every
     *   Disconnected follows a [Connected]: one is emitted for each failed attempt too. A collector
     *   that wants one signal per drop takes the first Disconnected after a [Connected], and the
     *   first one of all if the flow starts with a failure.
     */
    data class Disconnected(val reason: ApiError, val attempt: Int, val retryIn: Duration) : StreamSignal
}

/** One way to open connections to the event stream; see [HttpSseConnector]. */
internal interface SseConnector {
    /** True once no connection can be made any more; [EventStream] then fails with [ApiError.Closed]. */
    val isClosed: Boolean

    /**
     * Opens one connection, or throws [ApiException]. Cancelling the caller cancels the attempt and
     * leaves nothing open.
     */
    suspend fun open(): SseConnection
}

/** One open event stream connection, read by one coroutine. */
internal interface SseConnection {
    /**
     * The next item; null when the server ended the body. Throws [ApiException] when reading fails.
     * Returns [SseLineRead] whenever bytes arrived that finished nothing yet, so that the caller can
     * tell a slow event from silence.
     */
    suspend fun next(): SseItem?

    /** Stops the connection and returns once nothing of it runs any more. Safe to call twice. */
    suspend fun close()
}

/**
 * The server's event stream as a flow that keeps itself connected.
 *
 * The flow is cold: each collection opens its own connection. It emits [StreamSignal.Connected] for
 * each connection, then its events, and [StreamSignal.Disconnected] for each failure, then waits as
 * [policy] says and connects again. A connection that sends nothing at all, not one byte of a line
 * nor a heartbeat, for [ReconnectPolicy.silenceTimeout] is dropped with [ApiError.Timeout]; so is an attempt that gets no
 * reply in that time.
 *
 * The flow never completes. It fails with [ApiException] only for an error [policy] does not retry,
 * such as [ApiError.Unauthorized] or [ApiError.Closed]. Cancelling the collection closes the open
 * connection before the flow returns and leaves no timer behind.
 *
 * Events are not decoded here: [StreamSignal.Event] carries the raw [SseEvent].
 *
 * @param timeSource Measures how long a connection stayed up; tests pass virtual time.
 */
internal class EventStream(
    private val connector: SseConnector,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    fun signals(): Flow<StreamSignal> = flow {
        var failures = 0
        while (true) {
            ensureNotClosed()
            val ended = connectOnce()
            if (ended.healthy) failures = 0
            if (!policy.isRetryable(ended.reason)) throw ApiException(ended.reason)
            // A failure that comes from close() reads as Network on some paths: close wins.
            ensureNotClosed()
            failures = (failures + 1).coerceAtMost(Int.MAX_VALUE - 1)
            val wait = policy.delayFor(failures)
            emit(StreamSignal.Disconnected(ended.reason, failures, wait))
            waitBeforeRetry(wait)
        }
    }

    private class Ended(val reason: ApiError, val healthy: Boolean)

    /** One connection, from the attempt until it ends. Errors of [emit] are not caught here. */
    private suspend fun FlowCollector<StreamSignal>.connectOnce(): Ended {
        val connection = try {
            openWithinLimit()
        } catch (e: ApiException) {
            return Ended(e.error, healthy = false)
        } ?: return Ended(ApiError.Timeout, healthy = false)

        // No suspension between here and the try: the finally below always gets the connection.
        val opened = timeSource.markNow()
        try {
            emit(StreamSignal.Connected)
            val reason = pump(connection)
            return Ended(reason, healthy = opened.elapsedNow() >= policy.healthyAfter)
        } finally {
            withContext(NonCancellable) { connection.close() }
        }
    }

    /**
     * Opens a connection within [ReconnectPolicy.silenceTimeout]; null if the time ran out.
     *
     * A connection can come out of [SseConnector.open] after the timeout or a cancellation already
     * hit: the timeout block then completes cancelled and drops its result. So the result is kept
     * aside as soon as it exists, and closed here unless it is returned.
     */
    private suspend fun openWithinLimit(): SseConnection? {
        val produced = AtomicReference<SseConnection?>(null)
        var handedOver = false
        try {
            val connection = withTimeoutOrNull(policy.silenceTimeout) {
                connector.open().also { produced.set(it) }
            }
            handedOver = connection != null
            return connection
        } finally {
            if (!handedOver) produced.get()?.let { withContext(NonCancellable) { it.close() } }
        }
    }

    /** Emits the events of [connection] until it fails; returns why. */
    private suspend fun FlowCollector<StreamSignal>.pump(connection: SseConnection): ApiError {
        while (true) {
            val read = try {
                withTimeoutOrNull(policy.silenceTimeout) { Read(connection.next()) }
            } catch (e: ApiException) {
                return e.error
            } ?: return ApiError.Timeout
            when (val item = read.item) {
                null -> return ApiError.Network
                is SseEvent -> emit(StreamSignal.Event(item))
                // Signs of life only: a heartbeat, or one line of an event still on its way.
                is SseComment, SseLineRead -> Unit
            }
        }
    }

    /** Tells a timeout, null, from the end of the body, a Read of null. */
    private class Read(val item: SseItem?)

    private suspend fun waitBeforeRetry(wait: Duration) {
        var left = wait
        while (left.isPositive()) {
            ensureNotClosed()
            val step = minOf(left, policy.closedCheckInterval)
            delay(step)
            left -= step
        }
    }

    private fun ensureNotClosed() {
        if (connector.isClosed) throw ApiException(ApiError.Closed)
    }
}
