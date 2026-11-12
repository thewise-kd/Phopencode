package io.github.thewisekd.phopencode.feature.preview.logic.service

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.remote.connection.OpencodePortRefusedException
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.ssh.FakeTunnelSocket
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelEnd
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val PREVIEW_PORT = 5173

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionPreviewTunnelsTest {
    private suspend fun failure(block: suspend () -> Unit): PreviewFailure = assertFailsWith<PreviewException> { block() }.failure

    private suspend fun TestScope.connected(): PreviewConnection {
        val connection = PreviewConnection(backgroundScope)
        connection.connect()
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
        return connection
    }

    private fun TestScope.tunnels(connection: PreviewConnection) = ConnectionPreviewTunnels(connection.manager, backgroundScope)

    private fun PreviewTunnel.connectTo(host: String = "127.0.0.1"): FakeTunnelSocket {
        val target = InetSocketAddress.createUnresolved(host, port)
        return (loopback.socketFactory().createSocket() as FakeTunnelSocket).apply { connect(target) }
    }

    @Test
    fun `without a connection opening fails as not connected`() = runTest {
        val connection = PreviewConnection(backgroundScope)

        assertEquals(PreviewFailure.NotConnected, failure { tunnels(connection).open(PREVIEW_PORT) })
    }

    @Test
    fun `a port outside 1 to 65535 is refused, connected or not, and nothing opens`() = runTest {
        val idle = PreviewConnection(backgroundScope)
        assertFailsWith<IllegalArgumentException> { tunnels(idle).open(0) }

        val connection = connected()
        val source = tunnels(connection)
        for (port in listOf(0, -1, 65536)) {
            val thrown = assertFailsWith<IllegalArgumentException> { source.open(port) }
            assertFalse(thrown is OpencodePortRefusedException)
        }
        assertTrue(connection.sessions.single().loopbackTunnels.isEmpty())
    }

    @Test
    fun `opens a tunnel pinned to the port on the connected session`() = runTest {
        val connection = connected()
        val session = connection.sessions.single()

        val tunnel = tunnels(connection).open(PREVIEW_PORT)

        assertEquals(PREVIEW_PORT, tunnel.port)
        assertEquals(PREVIEW_PORT, tunnel.loopback.port)
        assertNull(tunnel.end.value)
        assertEquals(listOf(PREVIEW_PORT), session.loopbackTunnels.map { it.port })
        assertTrue(tunnel.connectTo().isConnected)
        assertEquals("127.0.0.1" to PREVIEW_PORT, session.tunnels.last())
        assertFailsWith<SocketException> { tunnel.connectTo("10.0.0.1") }
    }

    @Test
    fun `the opencode port is refused and the connection stays`() = runTest {
        val connection = connected()

        assertEquals(PreviewFailure.OpencodePort, failure { tunnels(connection).open(OPENCODE_PORT) })
        assertTrue(connection.sessions.single().loopbackTunnels.isEmpty())
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
    }

    @Test
    fun `closing ends the tunnel as closed, once`() = runTest {
        val connection = connected()
        val tunnel = tunnels(connection).open(PREVIEW_PORT)

        tunnel.close()
        tunnel.close()
        runCurrent()

        assertEquals(PreviewTunnelEnd.Closed, tunnel.end.value)
        assertTrue(connection.sessions.single().loopbackTunnels.single().isClosed)
        assertFailsWith<SocketException> { tunnel.connectTo() }
    }

    @Test
    fun `a closed tunnel stays closed when the connection drops later`() = runTest {
        val connection = connected()
        val tunnel = tunnels(connection).open(PREVIEW_PORT)
        tunnel.close()

        connection.sessions.single().loseConnection()
        runCurrent()

        assertEquals(PreviewTunnelEnd.Closed, tunnel.end.value)
    }

    @Test
    fun `a dropped connection ends the tunnel as lost and a reconnect opens a new one on the new session`() = runTest {
        val connection = connected()
        val source = tunnels(connection)
        val first = source.open(PREVIEW_PORT)

        connection.sessions.single().loseConnection()
        runCurrent()

        assertEquals(PreviewTunnelEnd.ConnectionLost, first.end.value)
        assertTrue(connection.sessions.first().loopbackTunnels.single().isClosed)
        assertEquals(PreviewFailure.NotConnected, failure { source.open(PREVIEW_PORT) }, "while waiting to reconnect")

        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)

        val second = source.open(PREVIEW_PORT)
        assertNotSame(first, second)
        assertNull(second.end.value)
        assertEquals(2, connection.sessions.size)
        assertEquals(listOf(PREVIEW_PORT), connection.sessions.last().loopbackTunnels.map { it.port })
        assertEquals(PreviewTunnelEnd.ConnectionLost, first.end.value, "the old end stays")

        first.close()
        assertEquals(PreviewTunnelEnd.ConnectionLost, first.end.value, "closing after the end changes nothing")
        assertNull(second.end.value)
    }

    @Test
    fun `a disconnect ends the tunnel as lost`() = runTest {
        val connection = connected()
        val tunnel = tunnels(connection).open(PREVIEW_PORT)

        connection.manager.disconnect()
        runCurrent()

        assertEquals(PreviewTunnelEnd.ConnectionLost, tunnel.end.value)
        assertTrue(connection.sessions.single().loopbackTunnels.single().isClosed)
    }

    @Test
    fun `every error of the connection manager maps to a contract failure`() {
        assertEquals(PreviewFailure.NotConnected, NotConnectedException().toTunnelFailure())
        assertEquals(PreviewFailure.OpencodePort, OpencodePortRefusedException(OPENCODE_PORT).toTunnelFailure())
        assertEquals(PreviewFailure.ConnectionLost, SshException.Disconnected().toTunnelFailure())
        assertEquals(PreviewFailure.ConnectionLost, SshException.Timeout().toTunnelFailure())
        assertEquals(PreviewFailure.Unknown, IOException("/home/tester").toTunnelFailure())
        assertEquals(PreviewFailure.Unknown, IllegalStateException().toTunnelFailure())
    }

    @Test
    fun `failures keep no cause`() = runTest {
        val connection = connected()

        val thrown = assertFailsWith<PreviewException> { tunnels(connection).open(OPENCODE_PORT) }

        assertNull(thrown.cause)
        assertFalse("$OPENCODE_PORT" in thrown.message.orEmpty())
    }

    @Test
    fun `a tunnel prints no port`() = runTest {
        val connection = connected()
        val tunnel = tunnels(connection).open(PREVIEW_PORT)

        assertFalse("$PREVIEW_PORT" in tunnel.toString())
    }

    @Test
    fun `a cancellation from the open propagates and is never a failure`() = runTest {
        val connection = connected()
        connection.tunnelFailure = CancellationException("stop")

        assertFailsWith<CancellationException>("not wrapped") { tunnels(connection).open(PREVIEW_PORT) }

        assertTrue(connection.sessions.single().loopbackTunnels.isEmpty())
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
    }

    @Test
    fun `a caller cancelled before the open gets no tunnel`() = runTest {
        val connection = connected()
        val source = tunnels(connection)

        val call = async {
            currentCoroutineContext().cancel()
            source.open(PREVIEW_PORT)
        }
        runCurrent()

        assertTrue(call.isCancelled)
        assertFailsWith<CancellationException> { call.await() }
        assertTrue(connection.sessions.single().loopbackTunnels.isEmpty())
    }

    @Test
    fun `a caller cancelled during the open gets no tunnel and the opened one is closed`() = runTest {
        val connection = connected()
        val source = tunnels(connection)
        val caller = Job()
        connection.afterTunnelOpen = { caller.cancel() }

        val call = async(caller) { source.open(PREVIEW_PORT) }
        runCurrent()

        assertTrue(call.isCancelled)
        assertFailsWith<CancellationException> { call.await() }
        assertTrue(connection.sessions.single().loopbackTunnels.single().isClosed, "the tunnel opened is closed")
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
    }

    @Test
    fun `a connection that ends between the snapshot and the open is lost, not absent`() = runTest {
        val connection = connected()
        // The session ends under the open, before the manager has seen the drop.
        connection.tunnelFailure = SshException.Disconnected()

        assertEquals(PreviewFailure.ConnectionLost, failure { tunnels(connection).open(PREVIEW_PORT) })
        assertTrue(connection.sessions.single().loopbackTunnels.isEmpty())
    }
}
