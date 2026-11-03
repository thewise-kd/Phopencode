package io.github.thewisekd.phopencode.core.api_http.event.stream

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import okio.BufferedSink

/**
 * An event stream body the test writes to while the client reads it. It stays open until [end] or
 * [release]; a write to a client that hung up ends it too.
 */
internal class StreamingBody : MockResponseBody {
    private val chunks = LinkedBlockingQueue<String>()

    @Volatile
    private var released = false

    /** Counts down once the server has started writing this body. */
    val started = CountDownLatch(1)

    /** Counts down once this body has stopped, for whatever reason. */
    val stopped = CountDownLatch(1)

    override val contentLength: Long = -1L

    fun write(text: String) {
        chunks += text
    }

    fun event(data: String) = write("data: $data\n\n")

    fun heartbeat() = write(": heartbeat\n\n")

    /** Ends the body cleanly after what was written. */
    fun end() = write(END)

    /** Lets the server thread go without writing more, for teardown. */
    fun release() {
        released = true
    }

    override fun writeTo(sink: BufferedSink) {
        started.countDown()
        try {
            // Sends the headers now, as the real server does by writing its first heartbeat at once.
            sink.flush()
            while (!released) {
                val chunk = chunks.poll(20, TimeUnit.MILLISECONDS) ?: continue
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
        .build()

    private companion object {
        val END = String(charArrayOf('\u0000'))
    }
}
