package io.github.thewisekd.phopencode.core.api_http.event.stream

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Reconnection, silence and cancellation, in virtual time. */
class EventStreamTest {
    private val policy = ReconnectPolicy(
        initialDelay = 1.seconds,
        maxDelay = 30.seconds,
        jitter = 0.0,
        silenceTimeout = 45.seconds,
        healthyAfter = 10.seconds,
        closedCheckInterval = 500.milliseconds,
    )

    private class Run(val job: Job) {
        val seen = mutableListOf<Pair<Long, StreamSignal>>()
        var failure: Throwable? = null

        val signals: List<StreamSignal>
            get() = seen.map { it.second }
    }

    /**
     * Collects in the background, or in the foreground when [foreground]: only foreground work keeps
     * advanceUntilIdle going, so a foreground collection lets a test see a timer it left behind.
     */
    private fun TestScope.collect(connector: FakeSseConnector, foreground: Boolean = false): Run {
        lateinit var run: Run
        val job = (if (foreground) this else backgroundScope).launch {
            try {
                EventStream(connector, policy, testScheduler.timeSource).signals().collect { run.seen += testScheduler.currentTime to it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                run.failure = e
            }
        }
        run = Run(job)
        runCurrent()
        return run
    }

    private fun disconnected(reason: ApiError, attempt: Int, seconds: Int) =
        StreamSignal.Disconnected(reason, attempt, seconds.seconds)

    @Test
    fun `nothing connects until the flow is collected`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        EventStream(connector, policy, testScheduler.timeSource).signals()
        advanceUntilIdle()
        assertEquals(emptyList(), connector.attempts)
    }

    @Test
    fun `a connection emits Connected, then its events, and no heartbeat`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        val connection = connector.accept()
        val run = collect(connector)
        connection.event("one")
        connection.heartbeat()
        connection.event("two")
        runCurrent()

        assertEquals(
            listOf(
                StreamSignal.Connected,
                StreamSignal.Event(SseEvent(null, null, "one")),
                StreamSignal.Event(SseEvent(null, null, "two")),
            ),
            run.signals,
        )
    }

    @Test
    fun `heartbeats keep a quiet stream connected`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        val connection = connector.accept()
        val run = collect(connector)
        repeat(40) {
            advanceTimeBy(15.seconds)
            connection.heartbeat()
            runCurrent()
        }
        assertEquals(listOf(StreamSignal.Connected), run.signals)
        assertEquals(1, connector.attempts.size)
    }

    @Test
    fun `a silent stream counts as disconnected and is reconnected`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        val first = connector.accept()
        val second = connector.accept()
        val run = collect(connector)
        first.heartbeat()
        advanceTimeBy(45.seconds - 1.milliseconds)
        runCurrent()
        assertEquals(listOf(StreamSignal.Connected), run.signals, "still within the limit")

        advanceTimeBy(2.milliseconds)
        assertEquals(1, first.closes, "the silent connection is closed")
        // It stayed up past healthyAfter, so the delay starts over.
        assertEquals(45_000L to disconnected(ApiError.Timeout, 1, 1), run.seen[1])

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(0L, 46_000L), connector.attempts)
        assertEquals(StreamSignal.Connected, run.signals.last())
        second.event("after")
        runCurrent()
        assertEquals(StreamSignal.Event(SseEvent(null, null, "after")), run.signals.last())
    }

    @Test
    fun `an event whose lines arrive slowly is not taken for silence`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        val connection = connector.accept()
        val run = collect(connector)
        // Four lines 30 s apart: 120 s in all, well past the 45 s limit, each gap within it.
        repeat(4) {
            advanceTimeBy(30.seconds)
            connection.partialLine()
            runCurrent()
        }
        advanceTimeBy(30.seconds)
        connection.event("slow")
        runCurrent()

        assertEquals(listOf(StreamSignal.Connected, StreamSignal.Event(SseEvent(null, null, "slow"))), run.signals)
        assertEquals(1, connector.attempts.size)

        // Nothing at all after that is silence again.
        advanceTimeBy(45.seconds + 1.milliseconds)
        assertEquals(disconnected(ApiError.Timeout, 1, 1), run.signals.last())
    }

    @Test
    fun `a stream with no first byte at all also counts as silent`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        connector.accept()
        val run = collect(connector)
        advanceTimeBy(45.seconds + 1.milliseconds)
        assertEquals(disconnected(ApiError.Timeout, 1, 1), run.signals[1])
    }

    @Test
    fun `a connection that arrives as the attempt times out is closed, not leaked`() = runTest {
        for (lateMs in listOf(45_000L, 45_001L, 60_000L)) {
            val connector = FakeSseConnector(testScheduler)
            val late = connector.acceptLate(lateMs)
            val next = connector.accept()
            val run = collect(connector)
            advanceTimeBy(lateMs.milliseconds + 1.seconds + 1.milliseconds)

            assertEquals(1, late.closes, "late by $lateMs ms: the connection must be closed")
            assertEquals(disconnected(ApiError.Timeout, 1, 1), run.signals.first(), "late by $lateMs ms")
            assertEquals(StreamSignal.Connected, run.signals[1], "late by $lateMs ms")
            assertEquals(0, next.closes, "late by $lateMs ms")
            run.job.cancel()
            runCurrent()
            assertEquals(1, next.closes, "late by $lateMs ms")
        }
    }

    @Test
    fun `a connection that arrives after the collection was cancelled is closed`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        val late = connector.acceptLate(10_000)
        val run = collect(connector, foreground = true)
        advanceTimeBy(5.seconds)
        run.job.cancel()
        advanceUntilIdle()

        assertTrue(run.job.isCompleted)
        assertEquals(1, late.closes)
        assertEquals(emptyList(), run.signals)
    }

    @Test
    fun `failed attempts back off exponentially up to the cap`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        val run = collect(connector)
        advanceTimeBy(5.minutes)

        assertEquals(listOf(0L, 1, 3, 7, 15, 31, 61, 91, 121, 151, 181, 211, 241, 271).map { it * 1_000 }, connector.attempts.take(14))
        assertEquals(
            listOf(1, 2, 4, 8, 16, 30, 30).mapIndexed { i, s -> disconnected(ApiError.Network, i + 1, s) },
            run.signals.take(7),
        )
    }

    @Test
    fun `a healthy connection resets the delay`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        repeat(3) { connector.refuse(ApiError.Server(503)) }
        val healthy = connector.accept()
        val run = collect(connector)
        advanceTimeBy(7.seconds)
        runCurrent()
        assertEquals(listOf(0L, 1_000, 3_000, 7_000), connector.attempts)
        assertEquals(disconnected(ApiError.Server(503), 3, 4), run.signals[2])

        advanceTimeBy(10.seconds)
        healthy.fail(ApiError.Network)
        runCurrent()
        assertEquals(disconnected(ApiError.Network, 1, 1), run.signals.last())
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(18_000L, connector.attempts.last())
    }

    @Test
    fun `a connection that drops at once does not reset the delay`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        connector.refuse(ApiError.Network)
        connector.accept().end()
        val run = collect(connector)
        advanceTimeBy(1.seconds + 1.milliseconds)

        assertEquals(
            listOf(
                disconnected(ApiError.Network, 1, 1),
                StreamSignal.Connected,
                disconnected(ApiError.Network, 2, 2),
            ),
            run.signals,
        )
    }

    @Test
    fun `the end of the body is a drop`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        val connection = connector.accept()
        val run = collect(connector)
        connection.event("one")
        connection.end()
        runCurrent()
        assertEquals(disconnected(ApiError.Network, 1, 1), run.signals.last())
        assertEquals(1, connection.closes)
    }

    @Test
    fun `refused credentials end the flow after one attempt`() = runTest {
        for (error in listOf(ApiError.Unauthorized, ApiError.Forbidden, ApiError.NotFound)) {
            val connector = FakeSseConnector(testScheduler)
            connector.refuse(error)
            val run = collect(connector)
            advanceTimeBy(10.minutes)

            val failure = assertIs<ApiException>(run.failure, "$error")
            assertEquals(error, failure.error)
            assertNull(failure.cause)
            assertEquals(1, connector.attempts.size, "$error must not be retried")
            assertEquals(emptyList(), run.signals, "$error")
        }
    }

    @Test
    fun `a 401 after a drop also ends the flow`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        connector.accept().end()
        connector.refuse(ApiError.Unauthorized)
        val run = collect(connector)
        advanceTimeBy(10.minutes)
        assertEquals(ApiError.Unauthorized, assertIs<ApiException>(run.failure).error)
        assertEquals(2, connector.attempts.size)
    }

    @Test
    fun `a failure that ends the flow while connected closes the connection`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        val connection = connector.accept()
        val run = collect(connector)
        connection.fail(ApiError.Closed)
        runCurrent()
        assertEquals(ApiError.Closed, assertIs<ApiException>(run.failure).error)
        assertEquals(1, connection.closes)
    }

    @Test
    fun `closing during a long backoff fails the flow soon, without another attempt`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        repeat(5) { connector.refuse(ApiError.Network) }
        val run = collect(connector)
        advanceTimeBy(31.seconds + 1.milliseconds)
        assertEquals(6, connector.attempts.size)
        connector.isClosed = true
        advanceTimeBy(500.milliseconds)
        runCurrent()

        assertEquals(ApiError.Closed, assertIs<ApiException>(run.failure).error)
        assertEquals(6, connector.attempts.size)
        assertTrue(run.job.isCompleted)
    }

    @Test
    fun `cancelling while connected closes the connection and leaves no timer`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        val connection = connector.accept()
        val run = collect(connector, foreground = true)
        connection.event("one")
        runCurrent()
        run.job.cancel()
        runCurrent()

        assertTrue(run.job.isCompleted)
        assertEquals(1, connection.closes)
        val before = testScheduler.currentTime
        advanceUntilIdle()
        assertEquals(before, testScheduler.currentTime, "a timer outlived the collection")
        assertEquals(1, connector.attempts.size)
    }

    @Test
    fun `cancelling during a backoff stops every later attempt`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        val run = collect(connector, foreground = true)
        advanceTimeBy(500.milliseconds)
        run.job.cancel()
        runCurrent()

        assertTrue(run.job.isCompleted)
        val before = testScheduler.currentTime
        advanceUntilIdle()
        assertEquals(before, testScheduler.currentTime)
        assertEquals(1, connector.attempts.size)
    }

    @Test
    fun `a failing collector is not swallowed and closes the connection`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        val connection = connector.accept()
        connection.event("boom")
        var failure: Throwable? = null
        backgroundScope.launch {
            try {
                EventStream(connector, policy, testScheduler.timeSource).signals().collect {
                    if (it is StreamSignal.Event) throw ApiException(ApiError.Unauthorized)
                }
            } catch (e: ApiException) {
                failure = e
            }
        }
        advanceTimeBy(5.minutes)
        assertEquals(ApiError.Unauthorized, (failure as ApiException).error)
        assertEquals(1, connection.closes)
        assertEquals(1, connector.attempts.size)
    }

    @Test
    fun `a new collection gets a connection of its own`() = runTest {
        val connector = FakeSseConnector(testScheduler)
        connector.accept()
        connector.accept()
        val stream = EventStream(connector, policy, testScheduler.timeSource)
        backgroundScope.launch { stream.signals().collect() }
        backgroundScope.launch { stream.signals().collect() }
        runCurrent()
        assertEquals(2, connector.connections.size)
    }
}
