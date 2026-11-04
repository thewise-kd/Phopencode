package io.github.thewisekd.phopencode.core.ssh.tunnel

import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.connect.FreezableProxy
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout

@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TunnelSocketTest {
    private val closeables = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    private val server = TunnelSshServer().closeLater()
    private val echo = EchoServer().closeLater()
    private val session: SshSession = runBlocking { connectWithTunnel(server) }.closeLater()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    private fun connected(port: Int = echo.port, timeoutMillis: Int = 5_000): Socket =
        session.socketFactory().createSocket().closeLater().apply {
            connect(InetSocketAddress(LOOPBACK, port), timeoutMillis)
        }

    private fun Socket.readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var done = 0
        while (done < count) {
            val read = getInputStream().read(out, done, count - done)
            if (read < 0) error("Stream ended after $done bytes")
            done += read
        }
        return out
    }

    // Connecting

    @Test
    fun `connect opens one direct-tcpip channel and carries bytes both ways`() {
        val socket = connected()
        assertTrue(socket.isConnected)
        assertFalse(socket.isClosed)
        assertEquals(1, server.channelsOpened())
        assertTrue(eventually { echo.connectionsAccepted() == 1 })

        val message = "merhaba tünel ".repeat(1_000).toByteArray()
        socket.getOutputStream().apply { write(message); flush() }
        assertContentEquals(message, socket.readExactly(message.size))
    }

    @Test
    fun `a large transfer flows in both directions at once`() {
        val socket = connected()
        val payload = ByteArray(2 * 1024 * 1024) { (it * 31).toByte() }
        val writer = thread(name = "test-writer") {
            socket.getOutputStream().apply { write(payload); flush() }
        }
        assertContentEquals(payload, socket.readExactly(payload.size))
        writer.join(10_000)
    }

    @Test
    fun `the socket names its target and binds nothing locally`() {
        val socket = connected()
        assertEquals(LOOPBACK, socket.inetAddress)
        assertEquals(echo.port, socket.port)
        assertEquals(InetSocketAddress(LOOPBACK, echo.port), socket.remoteSocketAddress)
        assertEquals(-1, socket.localPort)
        assertFalse(socket.isBound)
    }

    @Test
    fun `no local port is listened on`() {
        val before = listeningSocketsOfThisProcess()
        val sockets = List(3) { connected() }
        sockets.forEach { socket ->
            socket.getOutputStream().apply { write(1); flush() }
            assertEquals(1, socket.getInputStream().read())
        }
        val after = listeningSocketsOfThisProcess()
        if (before != null) assertEquals(before, after)
    }

    @Test
    fun `the factory's addressed variants connect at once`() {
        val factory = session.socketFactory()
        listOf(
            factory.createSocket("127.0.0.1", echo.port),
            factory.createSocket(LOOPBACK, echo.port),
            factory.createSocket("localhost", echo.port, null, 0),
            factory.createSocket(LOOPBACK, echo.port, null, 0),
        ).forEach { socket ->
            socket.closeLater()
            assertTrue(socket.isConnected)
            socket.getOutputStream().apply { write(7); flush() }
            assertEquals(7, socket.getInputStream().read())
        }
    }

    @Test
    fun `a local bind is refused`() {
        assertFailsWith<SocketException> {
            session.socketFactory().createSocket(LOOPBACK, echo.port, LOOPBACK, 40_000)
        }
        val socket = session.socketFactory().createSocket().closeLater()
        assertFailsWith<SocketException> { socket.bind(InetSocketAddress(LOOPBACK, 0)) }
    }

    @Test
    fun `targets other than loopback are refused before anything is sent`() {
        val factory = session.socketFactory()
        val documentation = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
        assertFailsWith<SocketException> { factory.createSocket().closeLater().connect(InetSocketAddress(documentation, 80), 1_000) }
        assertFailsWith<SocketException> {
            factory.createSocket().closeLater().connect(InetSocketAddress.createUnresolved("example.invalid", 80), 1_000)
        }
        assertFailsWith<SocketException> { factory.createSocket("example.invalid", 80) }
        assertEquals(0, server.connectsRequested())
    }

    @Test
    fun `the refusal names no host`() {
        val failure = assertFailsWith<SocketException> {
            session.socketFactory().createSocket().closeLater()
                .connect(InetSocketAddress.createUnresolved("secret-host.example.invalid", 31_337), 1_000)
        }
        val text = failure.toString()
        assertFalse("secret-host" in text, text)
        assertFalse("31337" in text, text)
    }

    @Test
    fun `a closed remote port gives ConnectException and leaves no channel`() {
        val socket = session.socketFactory().createSocket().closeLater()
        assertFailsWith<ConnectException> { socket.connect(InetSocketAddress(LOOPBACK, unusedLoopbackPort()), 5_000) }
        assertFalse(socket.isConnected)
        assertTrue(eventually { server.openChannels() == 0 })
        assertTrue(eventually { tunnelThreads().isEmpty() }, "${tunnelThreads()}")
    }

    @Test
    fun `connect honours its timeout, and a channel confirmed later is closed`() {
        server.holdConnects()
        val socket = session.socketFactory().createSocket().closeLater()
        val started = TimeSource.Monotonic.markNow()
        assertFailsWith<SocketTimeoutException> { socket.connect(InetSocketAddress(LOOPBACK, echo.port), 300) }
        val took = started.elapsedNow()
        assertTrue(took >= 250.milliseconds && took < 3.seconds, "Took $took")
        assertFalse(socket.isConnected)

        server.releaseConnects()
        // The server opens the channel late; the client must close it at once.
        assertTrue(eventually { server.channelsOpened() == 1 })
        assertTrue(eventually { server.openChannels() == 0 })
        assertTrue(eventually { echo.openConnections() == 0 })
        // The session survives the late confirmation.
        assertTrue(session.connected.value)
        val next = connected()
        next.getOutputStream().apply { write(3); flush() }
        assertEquals(3, next.getInputStream().read())
    }

    @Test
    fun `connect honours its timeout while the transport cannot send`() {
        val proxy = FreezableProxy(server.target.port).closeLater()
        val stalled = runBlocking { connectWithTunnel(server, target = proxy.target()) }.closeLater()
        val factory = stalled.socketFactory()
        proxy.freeze()
        // A key exchange the server never answers holds every other write of the transport.
        thread(isDaemon = true, name = "test-kex") { runCatching { (stalled as SshjSession).client.transport.doKex() } }
        Thread.sleep(300)

        val socket = factory.createSocket().closeLater()
        val started = TimeSource.Monotonic.markNow()
        assertFailsWith<SocketTimeoutException> { socket.connect(InetSocketAddress(LOOPBACK, echo.port), 300) }
        val took = started.elapsedNow()
        assertTrue(took >= 250.milliseconds && took < 2.seconds, "Took $took")

        proxy.thaw()
        // Once the transport moves again, the late channel is closed and nothing lingers.
        assertTrue(eventually(10.seconds) { server.openChannels() == 0 && tunnelThreads().isEmpty() }, "${tunnelThreads()}")
        assertTrue(stalled.connected.value)
    }

    @Test
    fun `a channel is registered on creation, so the server's confirmation opens it`() {
        // TunnelChannel sends the open request itself instead of calling sshj's open(); sshj
        // registers the channel with the connection in its constructor, which this relies on.
        val channel = TunnelChannel((session as SshjSession).client.connection, "127.0.0.1", echo.port)
        assertEquals(TunnelChannel.Opening.Opened, channel.openWithin(5_000))
        assertTrue(channel.confirmed)
        assertTrue(eventually { server.openChannels() == 1 })
        channel.requestClose()
        assertTrue(eventually { server.openChannels() == 0 })
        assertTrue(eventually { tunnelThreads().isEmpty() }, "${tunnelThreads()}")
        assertTrue(session.connected.value)
    }

    @Test
    fun `connecting twice is refused`() {
        val socket = connected()
        assertFailsWith<SocketException> { socket.connect(InetSocketAddress(LOOPBACK, echo.port), 1_000) }
        assertEquals(1, server.channelsOpened())
    }

    @Test
    fun `a closed socket cannot connect`() {
        val socket = session.socketFactory().createSocket().closeLater()
        socket.close()
        assertFailsWith<SocketException> { socket.connect(InetSocketAddress(LOOPBACK, echo.port), 1_000) }
        assertEquals(0, server.connectsRequested())
    }

    // Reading

    @Test
    fun `soTimeout limits a read and leaves the socket usable`() {
        val socket = connected()
        socket.soTimeout = 200
        assertEquals(200, socket.soTimeout)
        val started = TimeSource.Monotonic.markNow()
        assertFailsWith<SocketTimeoutException> { socket.getInputStream().read() }
        val took = started.elapsedNow()
        assertTrue(took >= 150.milliseconds && took < 3.seconds, "Took $took")

        assertFalse(socket.isClosed)
        socket.getOutputStream().apply { write(42); flush() }
        assertEquals(42, socket.getInputStream().read())
    }

    @Test
    fun `soTimeout zero waits for data without limit`() {
        val socket = connected()
        socket.soTimeout = 0
        val result = CompletableFuture<Int>()
        thread(name = "test-reader") { result.complete(runCatching { socket.getInputStream().read() }.getOrElse { -2 }) }
        Thread.sleep(500)
        assertFalse(result.isDone)
        socket.getOutputStream().apply { write(9); flush() }
        assertEquals(9, result.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `soTimeout can change while a read waits`() {
        val socket = connected()
        socket.soTimeout = 0
        val result = CompletableFuture<Throwable?>()
        thread(name = "test-reader") {
            socket.soTimeout = 100
            result.complete(runCatching { socket.getInputStream().read() }.exceptionOrNull())
        }
        assertTrue(result.get(5, TimeUnit.SECONDS) is SocketTimeoutException)
    }

    @Test
    fun `setting soTimeout from another thread bounds a read that waits without limit`() {
        val socket = connected()
        socket.soTimeout = 0
        val result = CompletableFuture<Throwable?>()
        thread(name = "test-reader") { result.complete(runCatching { socket.getInputStream().read() }.exceptionOrNull()) }
        Thread.sleep(300)
        assertFalse(result.isDone)
        val started = TimeSource.Monotonic.markNow()
        socket.soTimeout = 100
        val failure = result.get(5, TimeUnit.SECONDS)
        val took = started.elapsedNow()
        assertTrue(failure is SocketTimeoutException, "$failure")
        assertTrue(took < 2.seconds, "Took $took")
        assertFalse(socket.isClosed)
    }

    @Test
    fun `an interrupted read throws InterruptedIOException`() {
        val socket = connected()
        val result = CompletableFuture<Throwable?>()
        val reader = thread(name = "test-reader") {
            result.complete(runCatching { socket.getInputStream().read() }.exceptionOrNull())
        }
        Thread.sleep(200)
        reader.interrupt()
        assertTrue(result.get(5, TimeUnit.SECONDS) is InterruptedIOException)
    }

    @Test
    fun `available reports buffered bytes`() {
        val socket = connected()
        socket.getOutputStream().apply { write(byteArrayOf(1, 2, 3)); flush() }
        assertTrue(eventually { socket.getInputStream().available() == 3 })
        assertEquals(3, socket.getInputStream().read(ByteArray(10)))
    }

    // Shutting down

    @Test
    fun `shutdownOutput sends end of file and the reply still arrives`() {
        val socket = connected()
        socket.getOutputStream().apply { write("bye".toByteArray()); flush() }
        val output = socket.getOutputStream()
        socket.shutdownOutput()
        assertTrue(socket.isOutputShutdown)
        assertFailsWith<SocketException> { output.write(1) }
        assertFailsWith<SocketException> { socket.getOutputStream() }
        assertContentEquals("bye".toByteArray(), socket.readExactly(3))
        // The echo server ends its side after our end of file.
        assertEquals(-1, socket.getInputStream().read())
        assertFalse(socket.isClosed)
    }

    @Test
    fun `shutdownInput makes reads return end of stream`() {
        val socket = connected()
        val input = socket.getInputStream()
        socket.shutdownInput()
        assertTrue(socket.isInputShutdown)
        assertEquals(-1, input.read())
        assertFailsWith<SocketException> { socket.getInputStream() }
        // Writing still works, and echoed bytes are dropped without harm.
        socket.getOutputStream().apply { write(ByteArray(100_000)); flush() }
        assertEquals(-1, input.read())
        assertTrue(session.connected.value)
    }

    @Test
    fun `streams of an unconnected socket are refused`() {
        val socket = session.socketFactory().createSocket().closeLater()
        assertFailsWith<SocketException> { socket.getInputStream() }
        assertFailsWith<SocketException> { socket.getOutputStream() }
        assertFailsWith<SocketException> { socket.shutdownInput() }
        assertFailsWith<SocketException> { socket.shutdownOutput() }
    }

    // Closing

    @Test
    fun `close is idempotent, closes the channel and ends the reader thread`() {
        val socket = connected()
        assertTrue(eventually { server.openChannels() == 1 })
        assertEquals(1, tunnelThreads().size)

        socket.close()
        socket.close()
        assertTrue(socket.isClosed)
        assertTrue(socket.isConnected, "isConnected stays true after close, like java.net.Socket")
        assertTrue(eventually { server.openChannels() == 0 })
        assertTrue(eventually { echo.openConnections() == 0 })
        assertTrue(eventually { tunnelThreads().isEmpty() }, "${tunnelThreads()}")
        assertFailsWith<SocketException> { socket.getInputStream() }
        assertFailsWith<SocketException> { socket.getOutputStream() }
        assertTrue(session.connected.value)
    }

    @Test
    fun `closing the streams closes the socket`() {
        val socket = connected()
        val input = socket.getInputStream()
        input.close()
        assertTrue(socket.isClosed)
        assertTrue(eventually { server.openChannels() == 0 })

        val other = connected()
        other.getOutputStream().close()
        assertTrue(other.isClosed)
        assertTrue(eventually { server.openChannels() == 0 })
    }

    @Test
    fun `close wakes a blocked read and fails later writes`() {
        val socket = connected()
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val result = CompletableFuture<Throwable?>()
        thread(name = "test-reader") { result.complete(runCatching { input.read() }.exceptionOrNull()) }
        Thread.sleep(200)
        socket.close()
        val failure = result.get(5, TimeUnit.SECONDS)
        assertNotNull(failure)
        assertTrue(failure is SocketException, "$failure")
        assertFailsWith<SocketException> { input.read() }
        assertFailsWith<SocketException> { output.write(1) }
    }

    @Test
    fun `closing the session closes every channel, wakes readers and ends threads`() {
        val sockets = List(3) { connected() }
        assertTrue(eventually { server.openChannels() == 3 })
        assertTrue(eventually { echo.openConnections() == 3 })
        val blocked = CompletableFuture<Throwable?>()
        thread(name = "test-reader") { blocked.complete(runCatching { sockets[0].getInputStream().read() }.exceptionOrNull()) }
        Thread.sleep(200)

        session.close()
        assertTrue(blocked.get(5, TimeUnit.SECONDS) is IOException)
        sockets.forEach { assertTrue(it.isClosed) }
        assertTrue(eventually { server.openChannels() == 0 })
        assertTrue(eventually { echo.openConnections() == 0 })
        assertTrue(eventually { tunnelThreads().isEmpty() }, "${tunnelThreads()}")
    }

    @Test
    fun `a dropped connection fails reads instead of hanging`() {
        val socket = connected()
        val blocked = CompletableFuture<Throwable?>()
        thread(name = "test-reader") { blocked.complete(runCatching { socket.getInputStream().read() }.exceptionOrNull()) }
        Thread.sleep(200)
        server.close()
        assertTrue(blocked.get(10, TimeUnit.SECONDS) is IOException)
        assertTrue(eventually(10.seconds) { tunnelThreads().isEmpty() }, "${tunnelThreads()}")
    }

    @Test
    fun `a closed session gives no sockets`() {
        val factory = session.socketFactory()
        session.close()
        assertFailsWith<IOException> { factory.createSocket().closeLater().connect(InetSocketAddress(LOOPBACK, echo.port), 1_000) }
        assertEquals(0, server.connectsRequested())
    }
}
