package io.github.thewisekd.phopencode.core.api_http.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.SocketEffect
import okio.BufferedSink

/**
 * One event stream connection as `opencode serve` writes it: `server.connected` first, then what the
 * test sends, each event only if [types] lets it through. It stays open until [end], [release], or
 * a write fails because the client hung up; [ping] makes such a write happen, so that a connection
 * the client dropped is noticed.
 */
internal class EventBody(private val types: Set<String>, connectedId: String) : MockResponseBody {
    private val chunks = LinkedBlockingQueue<String>()
    private val stopped = CountDownLatch(1)

    @Volatile
    private var released = false

    override val contentLength: Long = -1L

    init {
        chunks += "data: {\"id\":\"$connectedId\",\"type\":\"server.connected\",\"data\":{}}\n\n"
    }

    val isOpen: Boolean
        get() = stopped.count > 0

    fun send(type: String, envelope: String) {
        if (types.isEmpty() || type in types) chunks += "data: $envelope\n\n"
    }

    fun heartbeat() {
        chunks += ": heartbeat\n\n"
    }

    /** A comment the client ignores; writing it fails once the client has gone. */
    fun ping() {
        chunks += ": ping\n\n"
    }

    /** Ends the body cleanly after what was written, as a dropped connection looks to the client. */
    fun end() {
        chunks += END
    }

    /** Lets the server thread go, for teardown. */
    fun release() {
        released = true
    }

    override fun writeTo(sink: BufferedSink) {
        try {
            // Sends the headers at once, as the real server does.
            sink.flush()
            while (!released) {
                val chunk = chunks.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                if (chunk === END) return
                sink.writeUtf8(chunk)
                sink.flush()
            }
        } finally {
            stopped.countDown()
        }
    }

    fun response(): MockResponse = MockResponse.Builder()
        .addHeader("Content-Type", "text/event-stream")
        .body(this)
        // The body has no length, so only a closed socket tells the client it ended.
        .onResponseEnd(SocketEffect.CloseSocket())
        .build()

    private companion object {
        val END = String(charArrayOf('\u0000'))
        const val POLL_MS = 10L
    }
}
