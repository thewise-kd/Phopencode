package io.github.thewisekd.phopencode.core.api_http.event.stream

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext

/** A connection a test feeds by hand. */
internal class FakeSseConnection : SseConnection {
    private val items = Channel<SseItem>(Channel.UNLIMITED)
    var closes = 0
        private set

    fun send(item: SseItem) {
        check(items.trySend(item).isSuccess)
    }

    fun event(data: String) = send(SseEvent(null, null, data))

    fun heartbeat() = send(SseComment("heartbeat"))

    /** A line arrived that does not finish an event, such as one `data:` line of several. */
    fun partialLine() = send(SseLineRead)

    /** The server ends the body. */
    fun end() {
        items.close()
    }

    /** Reading fails, as a dropped socket would. */
    fun fail(error: ApiError) {
        items.close(ApiException(error))
    }

    override suspend fun next(): SseItem? {
        val result = items.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull()
    }

    override suspend fun close() {
        closes++
    }
}

/**
 * Plays scripted outcomes, one per [open]: a connection, or a failure. With nothing scripted it
 * fails with [ApiError.Network]. Records the virtual time of every attempt.
 */
internal class FakeSseConnector(private val scheduler: TestCoroutineScheduler) : SseConnector {
    private class Step(val lateMs: Long, val outcome: () -> SseConnection)

    private val script = ArrayDeque<Step>()
    val attempts = mutableListOf<Long>()
    val connections = mutableListOf<FakeSseConnection>()

    override var isClosed: Boolean = false

    /** The next attempt connects; returns its connection. */
    fun accept(): FakeSseConnection = acceptLate(0)

    /**
     * The next attempt connects after [delayMs] of virtual time, and ignores cancellation while it
     * waits: its connection arrives even if the caller has given up, as a reply already on the wire
     * would.
     */
    fun acceptLate(delayMs: Long): FakeSseConnection {
        val connection = FakeSseConnection()
        script += Step(delayMs) { connection.also { connections += it } }
        return connection
    }

    /** The next attempt fails with [error]. */
    fun refuse(error: ApiError) {
        script += Step(0) { throw ApiException(error) }
    }

    override suspend fun open(): SseConnection {
        attempts += scheduler.currentTime
        if (isClosed) throw ApiException(ApiError.Closed)
        val next = script.removeFirstOrNull() ?: throw ApiException(ApiError.Network)
        if (next.lateMs > 0) withContext(NonCancellable) { delay(next.lateMs) }
        return next.outcome()
    }
}
