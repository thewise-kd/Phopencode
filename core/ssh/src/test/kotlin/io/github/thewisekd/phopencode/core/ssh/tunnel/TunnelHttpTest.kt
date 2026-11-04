package io.github.thewisekd.phopencode.core.ssh.tunnel

import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.BufferedSink
import org.junit.jupiter.api.Timeout

/** OkHttp over the tunnel, against a MockWebServer that listens on the "remote" loopback. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TunnelHttpTest {
    private val closeables = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    private val server = TunnelSshServer().closeLater()
    private val web = MockWebServer().closeLater().apply { start(LOOPBACK, 0) }
    private val session: SshSession = runBlocking { connectWithTunnel(server) }.closeLater()

    private val clients = mutableListOf<OkHttpClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    private fun client(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder()
            .socketFactory(session.socketFactory())
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .apply(configure)
            .build()
            .also { clients += it }

    private fun url(path: String) = "http://127.0.0.1:${web.port}$path"

    @Test
    fun `a plain request and response flow through the tunnel`() {
        web.enqueue(MockResponse.Builder().body("merhaba").build())
        val client = client()
        client.newCall(Request.Builder().url(url("/global/health")).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("merhaba", response.body.string())
        }
        assertEquals("/global/health", web.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
        assertEquals(1, server.channelsOpened())
    }

    @Test
    fun `a POST body flows through the tunnel and the connection is reused`() {
        repeat(3) { web.enqueue(MockResponse.Builder().body("ok-$it").build()) }
        val client = client()
        repeat(3) { index ->
            val request = Request.Builder().url(url("/session"))
                .post("body-$index".toRequestBody()).build()
            client.newCall(request).execute().use { assertEquals("ok-$index", it.body.string()) }
            assertEquals("body-$index", web.takeRequest(5, TimeUnit.SECONDS)?.body?.utf8())
        }
        assertEquals(1, server.channelsOpened(), "Keep-alive reuses one tunnel channel")
    }

    @Test
    fun `an SSE stream delivers events one by one through the tunnel`() {
        val firstSeen = CountDownLatch(1)
        val body = object : MockResponseBody {
            override val contentLength: Long = -1

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("event: message\ndata: one\n\n").flush()
                // The next events go out only after the client saw the first one.
                check(firstSeen.await(10, TimeUnit.SECONDS)) { "The first event did not arrive" }
                sink.writeUtf8("data: two\n\n").flush()
                sink.writeUtf8("data: üç\n\n").flush()
            }
        }
        web.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(body)
                // Without a length, the end of the stream is the end of the connection.
                .onResponseEnd(SocketEffect.ShutdownConnection)
                .build(),
        )

        val events = CopyOnWriteArrayList<String>()
        val closed = CompletableFuture<Throwable?>()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                events += data
                if (data == "one") firstSeen.countDown()
            }

            override fun onClosed(eventSource: EventSource) {
                closed.complete(null)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                closed.complete(t ?: IOException("failed"))
            }
        }
        EventSources.createFactory(client()).newEventSource(Request.Builder().url(url("/event")).build(), listener)

        assertEquals(null, closed.get(15, TimeUnit.SECONDS))
        assertEquals(listOf("one", "two", "üç"), events)
        assertEquals(1, server.channelsOpened())
    }

    @Test
    fun `the read timeout fires on a silent stream`() {
        val silent = SilentTarget().closeLater()
        val client = client { readTimeout(400, TimeUnit.MILLISECONDS) }
        val started = TimeSource.Monotonic.markNow()
        val failure = assertFailsWith<IOException> {
            client.newCall(Request.Builder().url("http://127.0.0.1:${silent.port}/event").build()).execute()
        }
        val took = started.elapsedNow()
        assertTrue(failure is InterruptedIOException, "$failure")
        assertTrue(took >= 300.milliseconds && took < 5.seconds, "Took $took")
        // OkHttp drops the timed-out connection; its channel and target connection close.
        assertTrue(eventually { server.openChannels() == 0 })
        assertTrue(eventually { silent.openConnections() == 0 })
    }

    @Test
    fun `an SSE silence ends with a timeout failure`() {
        val firstSent = CountDownLatch(1)
        val mayEnd = CountDownLatch(1)
        closeables += AutoCloseable { mayEnd.countDown() }
        val body = object : MockResponseBody {
            override val contentLength: Long = -1

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("data: one\n\n").flush()
                firstSent.countDown()
                mayEnd.await(20, TimeUnit.SECONDS)
            }
        }
        web.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build())

        val failed = CompletableFuture<Throwable?>()
        val listener = object : EventSourceListener() {
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                failed.complete(t)
            }

            override fun onClosed(eventSource: EventSource) {
                failed.complete(IllegalStateException("closed without a failure"))
            }
        }
        val client = client { readTimeout(500, TimeUnit.MILLISECONDS) }
        EventSources.createFactory(client).newEventSource(Request.Builder().url(url("/event")).build(), listener)
        val failure = failed.get(10, TimeUnit.SECONDS)
        assertNotNull(failure)
        assertTrue(failure is InterruptedIOException, "$failure")
        mayEnd.countDown()
        assertTrue(eventually { server.openChannels() == 0 })
    }

    @Test
    fun `closing the session fails an open SSE stream and closes its channel`() {
        val mayEnd = CountDownLatch(1)
        closeables += AutoCloseable { mayEnd.countDown() }
        val body = object : MockResponseBody {
            override val contentLength: Long = -1

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("data: one\n\n").flush()
                mayEnd.await(20, TimeUnit.SECONDS)
            }
        }
        web.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build())

        val firstSeen = CountDownLatch(1)
        val ended = CompletableFuture<Throwable?>()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                firstSeen.countDown()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                ended.complete(t)
            }

            override fun onClosed(eventSource: EventSource) {
                ended.complete(null)
            }
        }
        val client = client { readTimeout(0, TimeUnit.MILLISECONDS) }
        EventSources.createFactory(client).newEventSource(Request.Builder().url(url("/event")).build(), listener)
        assertTrue(firstSeen.await(10, TimeUnit.SECONDS))

        session.close()
        assertNotNull(ended.get(5, TimeUnit.SECONDS))
        mayEnd.countDown()
        assertTrue(eventually { server.openChannels() == 0 })
        assertTrue(eventually { tunnelThreads().isEmpty() }, "${tunnelThreads()}")
    }

    @Test
    fun `cancelling an SSE stream closes its channel`() {
        val mayEnd = CountDownLatch(1)
        closeables += AutoCloseable { mayEnd.countDown() }
        val body = object : MockResponseBody {
            override val contentLength: Long = -1

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("data: one\n\n").flush()
                mayEnd.await(20, TimeUnit.SECONDS)
            }
        }
        web.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build())

        val firstSeen = CountDownLatch(1)
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                firstSeen.countDown()
            }
        }
        val client = client { readTimeout(0, TimeUnit.MILLISECONDS) }
        val source = EventSources.createFactory(client).newEventSource(Request.Builder().url(url("/event")).build(), listener)
        assertTrue(firstSeen.await(10, TimeUnit.SECONDS))
        assertTrue(eventually { server.openChannels() == 1 })

        source.cancel()
        assertTrue(eventually { server.openChannels() == 0 })
        assertTrue(eventually { tunnelThreads().isEmpty() }, "${tunnelThreads()}")
        assertTrue(session.connected.value)
    }
}
