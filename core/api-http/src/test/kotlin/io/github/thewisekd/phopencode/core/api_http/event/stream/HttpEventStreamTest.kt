package io.github.thewisekd.phopencode.core.api_http.event.stream

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api_http.base.TEST_PASSWORD
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Credentials

/** The stream against a real HTTP server, in real time. */
class HttpEventStreamTest {
    private val fast = ReconnectPolicy(
        initialDelay = 50.milliseconds,
        maxDelay = 200.milliseconds,
        jitter = 0.0,
        silenceTimeout = 5.seconds,
        closedCheckInterval = 20.milliseconds,
    )

    private class Collected(scope: CoroutineScope, stream: EventStream) {
        private val signals = Channel<StreamSignal>(Channel.UNLIMITED)

        @Volatile
        var failure: Throwable? = null

        val job = scope.launch {
            try {
                stream.signals().collect { signals.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = e
            } finally {
                signals.close()
            }
        }

        suspend fun next(): StreamSignal = withTimeout(10_000) { signals.receive() }

        suspend fun failure(): Throwable {
            withTimeout(10_000) { job.join() }
            return checkNotNull(failure) { "the flow did not fail" }
        }
    }

    /** A test server whose streaming bodies are let go before it closes. */
    private class Harness : AutoCloseable {
        val test = TestServer()
        val transport = test.transport
        private val bodies = mutableListOf<StreamingBody>()

        fun stream(): StreamingBody = StreamingBody().also {
            bodies += it
            test.server.enqueue(it.response())
        }

        fun connector(types: Set<String> = emptySet()) = HttpSseConnector(transport, types)

        override fun close() {
            bodies.forEach { it.release() }
            test.close()
        }
    }

    private fun realTime(block: suspend CoroutineScope.(Harness) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            Harness().use { block(it) }
        }
    }

    private fun sse(body: String): MockResponse =
        MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build()

    private fun Harness.collect(
        policy: ReconnectPolicy = fast,
        connector: HttpSseConnector = connector(),
        scope: CoroutineScope,
    ) = Collected(scope, EventStream(connector, policy))

    private fun assertDisconnected(reason: ApiError, attempt: Int, signal: StreamSignal) {
        val disconnected = assertIs<StreamSignal.Disconnected>(signal)
        assertEquals(reason, disconnected.reason)
        assertEquals(attempt, disconnected.attempt)
    }

    @Test
    fun `the stream asks for events with credentials, the stream type and the chosen types`() = realTime { h ->
        h.stream()
        val run = h.collect(connector = h.connector(setOf("session.text.delta", "form.created")), scope = this)
        assertEquals(StreamSignal.Connected, run.next())

        val request = h.test.server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/api/event", request.url.encodedPath)
        assertEquals(listOf("form.created", "session.text.delta"), request.url.queryParameterValues("types"))
        assertEquals("text/event-stream", request.headers["Accept"])
        assertEquals(Credentials.basic("opencode", TEST_PASSWORD), request.headers["Authorization"])
        run.job.cancelAndJoin()
    }

    @Test
    fun `the recorded stream arrives in order, without its heartbeat`() = realTime { h ->
        h.test.server.enqueue(sse(fixture("startup/sse-wire.txt")))
        h.stream()
        val run = h.collect(scope = this)
        val expected = fixture("startup/events.jsonl").lines().filter { it.isNotEmpty() }

        assertEquals(StreamSignal.Connected, run.next())
        val got = List(expected.size) { assertIs<StreamSignal.Event>(run.next()).event.data }
        assertEquals(expected, got)
        // The recorded body ends there: that is a drop, and the stream reconnects.
        assertDisconnected(ApiError.Network, 1, run.next())
        assertEquals(StreamSignal.Connected, run.next())
        run.job.cancelAndJoin()
    }

    @Test
    fun `a 401 ends the flow and is not retried`() = realTime { h ->
        repeat(5) { h.test.enqueue(status = 401, body = """{"_tag":"Unauthorized","message":"no"}""") }
        val run = h.collect(scope = this)
        val failure = assertIs<ApiException>(run.failure())
        assertEquals(ApiError.Unauthorized, failure.error)
        assertNull(failure.cause)
        delay(300)
        assertEquals(1, h.test.server.requestCount)
    }

    @Test
    fun `a server error backs off and reconnects`() = realTime { h ->
        h.test.enqueue(status = 503)
        h.stream()
        val run = h.collect(scope = this)
        val disconnected = assertIs<StreamSignal.Disconnected>(run.next())
        assertEquals(ApiError.Server(503), disconnected.reason)
        assertEquals(50.milliseconds, disconnected.retryIn)
        assertEquals(StreamSignal.Connected, run.next())
        run.job.cancelAndJoin()
    }

    @Test
    fun `a reply that is not an event stream counts as malformed`() = realTime { h ->
        h.test.server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body("{}").build())
        h.stream()
        val run = h.collect(scope = this)
        assertDisconnected(ApiError.MalformedResponse, 1, run.next())
        assertEquals(StreamSignal.Connected, run.next())
        run.job.cancelAndJoin()
    }

    @Test
    fun `events and heartbeats keep the stream up, silence brings it down`() = realTime { h ->
        val first = h.stream()
        h.stream()
        val connector = h.connector()
        val run = h.collect(policy = ReconnectPolicy(jitter = 0.0, initialDelay = 50.milliseconds, silenceTimeout = 3.seconds), connector = connector, scope = this)
        assertEquals(StreamSignal.Connected, run.next())
        val started = System.nanoTime()
        first.event("one")
        assertEquals("one", assertIs<StreamSignal.Event>(run.next()).event.data)
        // On a busy machine the first attempt can be slow: the limit leaves room for it.
        repeat(8) {
            delay(500)
            first.heartbeat()
        }
        // Then nothing at all.
        assertDisconnected(ApiError.Timeout, 1, run.next())
        val elapsed = (System.nanoTime() - started).toDuration()
        assertTrue(elapsed >= 4.seconds + 3.seconds, "heartbeats must keep it up: $elapsed")
        assertEquals(StreamSignal.Connected, run.next())
        assertEquals(2, h.test.server.requestCount)
        assertEquals(1, connector.activeReaders, "the silent connection's reader must be gone")
        run.job.cancelAndJoin()
    }

    @Test
    fun `cancelling the collector cancels the call and leaves no reader or connection`() = realTime { h ->
        val body = h.stream()
        val connector = h.connector()
        val run = h.collect(connector = connector, scope = this)
        assertEquals(StreamSignal.Connected, run.next())
        body.event("one")
        run.next()
        assertEquals(1, connector.activeReaders)

        run.job.cancelAndJoin()

        assertEquals(0, connector.activeReaders)
        assertEquals(0, h.transport.runningCalls())
        assertTrue(h.transport.allCallsCanceled())
        assertEquals(0, h.transport.client.connectionPool.connectionCount())
        // The server sees the hang-up once a write fails; the first ones may still fit in a buffer.
        val hungUp = withTimeout(5_000) {
            while (!body.stopped.await(0, TimeUnit.MILLISECONDS)) {
                body.heartbeat()
                delay(20)
            }
            true
        }
        assertTrue(hungUp, "the server still holds the stream")
        delay(200)
        assertEquals(1, h.test.server.requestCount, "nothing reconnects after cancellation")
    }

    @Test
    fun `cancelling while the reply is still on its way cancels the call`() = realTime { h ->
        h.test.server.enqueue(sse(": heartbeat\n\n").newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
        val connector = h.connector()
        val run = h.collect(connector = connector, scope = this)
        h.test.server.takeRequest(5, TimeUnit.SECONDS)
        run.job.cancelAndJoin()

        assertTrue(h.transport.allCallsCanceled())
        assertEquals(0, connector.activeReaders)
        withTimeout(5_000) { while (h.transport.runningCalls() > 0) delay(5) }
    }

    @Test
    fun `closing the transport while connected fails the flow with Closed`() = realTime { h ->
        h.stream()
        val connector = h.connector()
        val run = h.collect(connector = connector, scope = this)
        assertEquals(StreamSignal.Connected, run.next())
        h.transport.close()

        assertEquals(ApiError.Closed, assertIs<ApiException>(run.failure()).error)
        assertEquals(0, connector.activeReaders)
        assertEquals(1, h.test.server.requestCount)
    }

    @Test
    fun `closing the transport during a long backoff fails the flow at once`() = realTime { h ->
        h.test.dropEveryConnection()
        val slow = ReconnectPolicy(initialDelay = 30.seconds, jitter = 0.0, closedCheckInterval = 20.milliseconds)
        val run = h.collect(policy = slow, scope = this)
        assertDisconnected(ApiError.Network, 1, run.next())
        val seen = h.test.server.requestCount
        h.transport.close()

        val failure = withTimeout(2_000) { run.failure() }
        assertEquals(ApiError.Closed, assertIs<ApiException>(failure).error)
        assertEquals(seen, h.test.server.requestCount)
    }

    @Test
    fun `collecting after close fails with Closed and sends nothing`() = realTime { h ->
        h.transport.close()
        val run = h.collect(scope = this)
        assertEquals(ApiError.Closed, assertIs<ApiException>(run.failure()).error)
        assertEquals(0, h.test.server.requestCount)
    }

    @Test
    fun `a dropped connection is retried until the server answers again`() = realTime { h ->
        h.test.dropEveryConnection()
        val run = h.collect(scope = this)
        assertDisconnected(ApiError.Network, 1, run.next())
        assertDisconnected(ApiError.Network, 2, run.next())

        val body = StreamingBody()
        h.test.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = body.response()
        }
        var signal = run.next()
        while (signal is StreamSignal.Disconnected) signal = run.next()
        assertEquals(StreamSignal.Connected, signal)
        body.event("back")
        assertEquals("back", assertIs<StreamSignal.Event>(run.next()).event.data)
        run.job.cancelAndJoin()
        body.release()
    }

    @Test
    fun `a body cut off mid-event is a drop and the partial event is dropped`() = realTime { h ->
        h.test.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body("data: whole\n\ndata: half")
                .onResponseEnd(SocketEffect.CloseSocket())
                .build(),
        )
        h.stream()
        val run = h.collect(scope = this)
        assertEquals(StreamSignal.Connected, run.next())
        assertEquals("whole", assertIs<StreamSignal.Event>(run.next()).event.data)
        assertDisconnected(ApiError.Network, 1, run.next())
        assertEquals(StreamSignal.Connected, run.next())
        run.job.cancelAndJoin()
    }

    @Test
    fun `an oversized event is malformed and the stream reconnects`() = realTime { h ->
        h.test.server.enqueue(sse("data: " + "x".repeat(100) + "\n\n"))
        h.stream()
        val connector = HttpSseConnector(h.transport, maxEventBytes = 64)
        val run = h.collect(connector = connector, scope = this)
        assertEquals(StreamSignal.Connected, run.next())
        assertDisconnected(ApiError.MalformedResponse, 1, run.next())
        assertEquals(StreamSignal.Connected, run.next())
        run.job.cancelAndJoin()
    }

    @Test
    fun `an event whose lines arrive slowly arrives whole, and only real silence drops the stream`() = realTime { h ->
        val body = h.stream()
        val policy = ReconnectPolicy(jitter = 0.0, initialDelay = 50.milliseconds, silenceTimeout = 3.seconds)
        val run = h.collect(policy = policy, scope = this)
        assertEquals(StreamSignal.Connected, run.next())
        body.heartbeat()
        // 9 s for the whole event and 5 s for its middle line, both more than the limit; 1 s
        // between writes, well within it.
        for (piece in listOf("data: a\n", "data: b", "b", "b", "b", "b\n", ": a comment between lines\n", "data: c\n", "\n")) {
            delay(1_000)
            body.write(piece)
        }
        assertEquals("a\nbbbbb\nc", assertIs<StreamSignal.Event>(run.next()).event.data)
        assertDisconnected(ApiError.Timeout, 1, run.next())
        run.job.cancelAndJoin()
    }

    @Test
    fun `the event limit counts bytes on the wire`() = realTime { h ->
        // 10 bytes: ç is 2, the emoji 4.
        h.test.server.enqueue(sse("data: ç\ndata: ç🙂a\n\n"))
        // 11 bytes.
        h.test.server.enqueue(sse("data: çç\ndata: ç🙂\n\n"))
        h.stream()
        val connector = HttpSseConnector(h.transport, maxEventBytes = 10)
        val run = h.collect(connector = connector, scope = this)
        assertEquals(StreamSignal.Connected, run.next())
        assertEquals("ç\nç🙂a", assertIs<StreamSignal.Event>(run.next()).event.data)
        assertDisconnected(ApiError.Network, 1, run.next())
        assertEquals(StreamSignal.Connected, run.next())
        assertDisconnected(ApiError.MalformedResponse, 2, run.next())
        assertEquals(StreamSignal.Connected, run.next())
        run.job.cancelAndJoin()
    }

    private fun Long.toDuration(): Duration = this.toDouble().let { (it / 1_000_000).milliseconds }
}
