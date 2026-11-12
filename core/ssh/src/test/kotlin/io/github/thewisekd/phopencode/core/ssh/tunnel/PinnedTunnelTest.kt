package io.github.thewisekd.phopencode.core.ssh.tunnel

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.connect.NoSessionFeatures
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.ssh.connect.password
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout

/** [SshSession.openLoopbackTunnel] against an embedded MINA server, with the default session features. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PinnedTunnelTest {
    private val closeables = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    private val server = TunnelSshServer().closeLater()
    private val echo = EchoServer().closeLater()
    private val session: SshSession = runBlocking {
        SshjConnector().connect(server.target, password(), ACCEPT, SshConnectOptions(keepAliveInterval = null))
    }.closeLater()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    private fun tunnel(port: Int = echo.port): SshSession.LoopbackTunnel = session.openLoopbackTunnel(port).closeLater()

    private fun SshSession.LoopbackTunnel.connected(address: InetSocketAddress = InetSocketAddress(LOOPBACK, echo.port)): Socket =
        socketFactory().createSocket().closeLater().apply { connect(address, 5_000) }

    private fun Socket.echoes(byte: Int): Boolean {
        getOutputStream().apply { write(byte); flush() }
        return getInputStream().read() == byte
    }

    private fun address(host: String, port: Int = echo.port): InetSocketAddress = InetSocketAddress.createUnresolved(host, port)

    private fun address(bytes: ByteArray, port: Int = echo.port): InetSocketAddress =
        InetSocketAddress(InetAddress.getByAddress(bytes), port)

    @Test
    fun `the tunnel reaches its port on the loopback and carries bytes`() {
        val tunnel = tunnel()
        assertEquals(echo.port, tunnel.port)
        assertTrue(tunnel.connected().echoes(42))
        assertTrue(tunnel.connected(address("127.0.0.1")).echoes(43))
        assertEquals(2, server.channelsOpened())
    }

    @Test
    fun `the factory's addressed variants reach the pinned port`() {
        val factory = tunnel().socketFactory()
        listOf(
            factory.createSocket("127.0.0.1", echo.port),
            factory.createSocket(LOOPBACK, echo.port),
            factory.createSocket("127.0.0.1", echo.port, null, 0),
            factory.createSocket(LOOPBACK, echo.port, null, 0),
        ).forEachIndexed { index, socket ->
            socket.closeLater()
            assertTrue(socket.echoes(index))
        }
    }

    @Test
    fun `localhost and the IPv6 loopback are named to the server`() {
        val factory = tunnel().socketFactory()
        val names = listOf(
            address("localhost"),
            address("LocalHost"),
            address("::1"),
            address("[::1]"),
            InetSocketAddress(InetAddress.getByName("::1"), echo.port),
        )
        names.forEachIndexed { index, name ->
            // The echo listens on IPv4 only, so ::1 may be refused by the server; the request still goes out.
            runCatching { factory.createSocket().closeLater().connect(name, 5_000) }
            assertEquals(index + 1, server.connectsRequested(), "requests after $name")
        }
    }

    @Test
    fun `another port is refused before a channel opens`() {
        val factory = tunnel().socketFactory()
        val other = if (echo.port == 65535) echo.port - 1 else echo.port + 1
        listOf(
            InetSocketAddress(LOOPBACK, other),
            address("127.0.0.1", other),
            address("localhost", other),
            address("::1", other),
            InetSocketAddress(LOOPBACK, server.target.port),
        ).forEach { target ->
            assertFailsWith<SocketException>("$target") { factory.createSocket().closeLater().connect(target, 1_000) }
        }
        assertFailsWith<SocketException> { factory.createSocket("127.0.0.1", other).closeLater() }
        assertFailsWith<SocketException> { factory.createSocket(LOOPBACK, other).closeLater() }
        assertEquals(0, server.connectsRequested())
    }

    @Test
    fun `non-loopback hosts are refused before a channel opens`() {
        val factory = tunnel().socketFactory()
        listOf(
            address(byteArrayOf(10, 0, 0, 1)),
            address(byteArrayOf(192.toByte(), 168.toByte(), 1, 10)),
            address(byteArrayOf(0, 0, 0, 0)),
            address(byteArrayOf(127, 0, 0, 2)),
            address("10.0.0.1"),
            address("192.168.1.10"),
            address("127.0.0.2"),
            address("0.0.0.0"),
            address("intranet"),
            address("localhost.example.invalid"),
            address("example.invalid"),
        ).forEach { target ->
            assertFailsWith<SocketException>("$target") { factory.createSocket().closeLater().connect(target, 1_000) }
        }
        assertFailsWith<SocketException> { factory.createSocket("intranet", echo.port).closeLater() }
        assertEquals(0, server.connectsRequested())
    }

    @Test
    fun `the refusal names no host and no port`() {
        val failure = assertFailsWith<SocketException> {
            tunnel().socketFactory().createSocket().closeLater().connect(address("secret-host.example.invalid", 31_337), 1_000)
        }
        val text = generateSequence<Throwable>(failure) { it.cause }.joinToString { it.toString() }
        assertFalse("secret-host" in text, text)
        assertFalse("31337" in text, text)
    }

    @Test
    fun `a port outside 1 to 65535 is refused at creation`() {
        listOf(0, -1, 65_536, Int.MIN_VALUE, Int.MAX_VALUE).forEach { port ->
            assertFailsWith<IllegalArgumentException>("$port") { session.openLoopbackTunnel(port) }
        }
        tunnel(1).close()
        tunnel(65_535).close()
        assertEquals(0, server.connectsRequested())
    }

    @Test
    fun `creating a tunnel opens no channel`() {
        tunnel()
        assertEquals(0, server.connectsRequested())
    }

    @Test
    fun `no local port is listened on`() {
        val before = listeningSocketsOfThisProcess()
        val tunnel = tunnel()
        repeat(3) { assertTrue(tunnel.connected().echoes(it)) }
        val after = listeningSocketsOfThisProcess()
        if (before != null) assertEquals(before, after)
    }

    @Test
    fun `closing the tunnel closes its sockets and refuses new ones`() {
        val tunnel = tunnel()
        val open = List(2) { tunnel.connected() }
        val unconnected = tunnel.socketFactory().createSocket().closeLater()
        assertTrue(eventually { server.openChannels() == 2 })
        tunnel.close()
        tunnel.close()
        open.forEach { assertTrue(it.isClosed) }
        assertTrue(unconnected.isClosed)
        assertTrue(eventually { server.openChannels() == 0 }, "channels left open: ${server.openChannels()}")
        val factory = tunnel.socketFactory()
        assertFailsWith<SocketException> { factory.createSocket() }
        assertFailsWith<SocketException> { factory.createSocket("127.0.0.1", echo.port) }
        assertFailsWith<SocketException> { factory.createSocket(LOOPBACK, echo.port) }
        assertEquals(2, server.channelsOpened())
        assertTrue(session.connected.value, "The session stays connected")
    }

    @Test
    fun `closing one tunnel leaves the session's other sockets alone`() {
        val first = tunnel()
        val second = tunnel()
        val kept = second.connected()
        val general = session.socketFactory().createSocket().closeLater().apply { connect(InetSocketAddress(LOOPBACK, echo.port), 5_000) }
        first.connected()
        first.close()
        assertTrue(kept.echoes(1))
        assertTrue(general.echoes(2))
        assertTrue(second.connected().echoes(3))
    }

    @Test
    fun `closing the session closes the tunnel`() {
        val tunnel = tunnel()
        val socket = tunnel.connected()
        val unconnected = tunnel.socketFactory().createSocket().closeLater()
        session.close()
        assertTrue(socket.isClosed)
        assertTrue(unconnected.isClosed)
        assertFailsWith<SocketException> { tunnel.socketFactory().createSocket() }
    }

    @Test
    fun `a lost connection closes the tunnel`() {
        val tunnel = tunnel()
        val socket = tunnel.connected()
        server.close()
        assertTrue(eventually { socket.isClosed })
        assertFailsWith<IOException> { tunnel.socketFactory().createSocket().closeLater().connect(InetSocketAddress(LOOPBACK, echo.port), 1_000) }
    }

    @Test
    fun `opening a tunnel on a closed session fails with Disconnected`() {
        session.close()
        assertFailsWith<SshException.Disconnected> { session.openLoopbackTunnel(echo.port) }
    }

    @Test
    fun `a range check comes before the connection check`() {
        session.close()
        assertFailsWith<IllegalArgumentException> { session.openLoopbackTunnel(0) }
    }

    @Test
    fun `sessions without the tunnel feature refuse to open one`() {
        val bare = runBlocking {
            SshjConnector(NoSessionFeatures).connect(server.target, password(), ACCEPT, SshConnectOptions(keepAliveInterval = null))
        }.closeLater()
        assertFailsWith<UnsupportedOperationException> { bare.openLoopbackTunnel(echo.port) }
    }

    private companion object {
        val ACCEPT = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }
    }
}
