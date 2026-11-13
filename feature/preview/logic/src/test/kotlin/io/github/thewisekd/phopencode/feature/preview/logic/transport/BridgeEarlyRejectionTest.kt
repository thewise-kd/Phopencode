package io.github.thewisekd.phopencode.feature.preview.logic.transport

import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelEnd
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Timeout

/**
 * A dev server that rejects an upload early: it sends its 413 head, then resets its read side, so
 * the bridge's next write of the body fails, and the rest of its answer arrives only after that.
 * The bridge must stop uploading without dropping the connection, and relay the whole 413.
 *
 * The tunnel socket is scripted in memory, so the order of these events is fixed: the 413 body is
 * delivered only once the bridge has reacted to the failed write, by half-closing or by closing.
 */
@Timeout(30)
class BridgeEarlyRejectionTest {
    private val closers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = closers.reversed().forEach { runCatching { it.close() } }

    @Test
    fun `an early 413 is relayed whole when the dev server stops reading the upload`() {
        val upstream = RejectingSocket(acceptBytes = 64 * 1024)
        val bridge = LoopbackBridgeFactory(limits = BridgeLimits()).open(OneSocketTunnel(upstream)).also { closers += it }
        val cookie = bridge.cookie()
        val size = 1024 * 1024
        Client(bridge.origin.bridgePort, timeoutMillis = 10_000).use { client ->
            client.send("POST /upload HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nContent-Length: $size\r\n\r\n")
            Thread {
                runCatching {
                    val piece = ByteArray(16 * 1024)
                    repeat(size / piece.size) { client.socket.getOutputStream().write(piece) }
                }
            }.apply {
                isDaemon = true
                start()
            }
            val reply = client.reply()
            assertEquals(413, reply.status)
            assertEquals("too much", reply.text)
        }
        assertEquals(false, upstream.closedBeforeAnswer, "The bridge closed the tunnel socket under the answer")
    }

    /** A tunnel whose only socket is [socket]. */
    private class OneSocketTunnel(private val socket: Socket) : PreviewTunnel {
        override val port = 5173
        override val end = MutableStateFlow<PreviewTunnelEnd?>(null)
        override val loopback = object : SshSession.LoopbackTunnel {
            override val port = 5173

            override fun socketFactory(): SocketFactory = object : SocketFactory() {
                override fun createSocket(): Socket = socket

                override fun createSocket(host: String, port: Int): Socket = unsupported()

                override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = unsupported()

                override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()

                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                    unsupported()

                private fun unsupported(): Nothing = throw SocketException("Only createSocket() then connect")
            }

            override fun close() = Unit
        }

        override fun close() = Unit
    }

    /**
     * Takes [acceptBytes] of what the bridge writes, then answers with a 413 head and fails every
     * later write, as a dev server that reset its read side would. The 413 body and the end of the
     * stream follow when the bridge half-closes its output; if the bridge closes the socket instead,
     * they never arrive and reads fail, as on a closed socket.
     */
    private class RejectingSocket(private val acceptBytes: Int) : Socket() {
        private val incoming = LinkedBlockingQueue<Int>()
        private var written = 0

        @Volatile private var rejected = false
        @Volatile private var closed = false
        @Volatile private var timeout = 0

        @Volatile var closedBeforeAnswer = false
            private set

        private val input = object : InputStream() {
            override fun read(): Int {
                val next = if (timeout == 0) incoming.take() else incoming.poll(timeout.toLong(), TimeUnit.MILLISECONDS)
                when (next) {
                    null -> throw SocketTimeoutException("Read timed out")
                    END -> {
                        incoming.put(END)
                        return -1
                    }
                    CLOSED -> {
                        incoming.put(CLOSED)
                        throw SocketException("Socket closed")
                    }
                    else -> return next
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val first = read()
                if (first < 0) return -1
                b[off] = first.toByte()
                var count = 1
                while (count < len) {
                    val next = incoming.peek()
                    if (next == null || next < 0) break
                    b[off + count++] = incoming.remove().toByte()
                }
                return count
            }
        }

        private val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) = synchronized(this) {
                if (closed) throw SocketException("Socket closed")
                if (rejected) throw SocketException("Connection reset")
                written += len
                if (written > acceptBytes) {
                    rejected = true
                    deliver("HTTP/1.1 413 Content Too Large\r\nContent-Length: 8\r\nConnection: close\r\n\r\n")
                    throw SocketException("Connection reset")
                }
            }
        }

        private fun deliver(text: String) = text.toByteArray().forEach { incoming.put(it.toInt() and 0xff) }

        override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit

        override fun getInputStream(): InputStream = input

        override fun getOutputStream(): OutputStream = output

        override fun setSoTimeout(timeout: Int) {
            this.timeout = timeout
        }

        override fun getSoTimeout(): Int = timeout

        override fun shutdownOutput() {
            if (closed) throw SocketException("Socket closed")
            deliver("too much")
            incoming.put(END)
        }

        override fun isClosed(): Boolean = closed

        override fun close() {
            if (closed) return
            closed = true
            if (rejected && incoming.none { it == END }) closedBeforeAnswer = true
            incoming.put(CLOSED)
        }

        private companion object {
            const val END = -1
            const val CLOSED = -2
        }
    }
}
