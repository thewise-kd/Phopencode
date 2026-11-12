package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.testing.ssh.FakeLoopbackTunnel
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import io.github.thewisekd.phopencode.core.testing.ssh.FakeTunnelSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ConnectionPreviewTunnelTest {
    /** A preview tunnel that counts how often it is closed. */
    private class CountingTunnel(val inner: FakeLoopbackTunnel) : SshSession.LoopbackTunnel by inner {
        val closes = AtomicInteger()

        override fun close() {
            closes.incrementAndGet()
            inner.close()
        }
    }

    /** Sees every preview tunnel opened on the sessions of [ProbeConnector]. */
    private class TunnelProbe {
        val opened = mutableListOf<CountingTunnel>()

        /**
         * When false, closing a session leaves its preview tunnels open, so a test sees whether the
         * manager closes them itself instead of counting on the session.
         */
        @Volatile
        var sessionClosesTunnels = true
    }

    private class ProbeSession(private val inner: FakeSshSession, private val probe: TunnelProbe) : SshSession by inner {
        override fun openLoopbackTunnel(port: Int): SshSession.LoopbackTunnel =
            CountingTunnel(inner.openLoopbackTunnel(port)).also { synchronized(probe) { probe.opened += it } }

        override fun close() {
            if (probe.sessionClosesTunnels) inner.close()
        }
    }

    private class ProbeConnector(private val real: SshConnector, private val probe: TunnelProbe) : SshConnector {
        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession = ProbeSession(real.connect(target, credential, hostKeyVerifier, options) as FakeSshSession, probe)
    }

    private fun TestScope.fixture(probe: TunnelProbe = TunnelProbe()) = Fixture(this, wrapConnector = { ProbeConnector(it, probe) })

    private suspend fun TestScope.connected(fx: Fixture): ConnectionState.Connected {
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()
        return assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    private fun SshSession.LoopbackTunnel.connectTo(host: String, port: Int = this.port): FakeTunnelSocket =
        (socketFactory().createSocket() as FakeTunnelSocket).apply { connect(InetSocketAddress.createUnresolved(host, port)) }

    private fun assertRefusesSockets(tunnel: SshSession.LoopbackTunnel) {
        assertFailsWith<SocketException> { tunnel.connectTo("127.0.0.1") }
    }

    @Test
    fun `opens a tunnel pinned to the port on the connected session`() = runTest {
        val probe = TunnelProbe()
        val fx = fixture(probe)
        connected(fx)
        val session = fx.connector.sessions.single()

        val tunnel = fx.manager.openPreviewTunnel(PREVIEW_PORT)

        assertEquals(PREVIEW_PORT, tunnel.port)
        assertEquals(listOf(PREVIEW_PORT), session.loopbackTunnels.map { it.port })
        assertFalse(session.loopbackTunnels.single().isClosed)
        val socket = tunnel.connectTo("127.0.0.1")
        assertTrue(socket.isConnected)
        assertEquals("127.0.0.1" to PREVIEW_PORT, session.tunnels.last())
    }

    @Test
    fun `the tunnel stays pinned to its port and to loopback`() = runTest {
        val fx = fixture()
        connected(fx)
        val tunnel = fx.manager.openPreviewTunnel(PREVIEW_PORT)
        val before = fx.connector.sessions.single().tunnels.size

        assertFailsWith<SocketException> { tunnel.connectTo("127.0.0.1", port = PREVIEW_PORT + 1) }
        assertFailsWith<SocketException> { tunnel.connectTo("10.0.0.1") }

        assertEquals(before, fx.connector.sessions.single().tunnels.size, "no channel opened for a refused target")
    }

    @Test
    fun `a port outside 1 to 65535 is refused`() = runTest {
        val fx = fixture()
        connected(fx)

        assertFailsWith<IllegalArgumentException> { fx.manager.openPreviewTunnel(0) }
        assertFailsWith<IllegalArgumentException> { fx.manager.openPreviewTunnel(65536) }
        assertFailsWith<IllegalArgumentException> { fx.manager.openPreviewTunnel(-1) }

        assertTrue(fx.connector.sessions.single().loopbackTunnels.isEmpty())
    }

    @Test
    fun `the opencode server's port is refused and the connection stays`() = runTest {
        val probe = TunnelProbe()
        val fx = fixture(probe)
        connected(fx)

        val refused = assertFailsWith<OpencodePortRefusedException> { fx.manager.openPreviewTunnel(OPENCODE_PORT) }

        assertEquals(OPENCODE_PORT, refused.port)
        assertTrue(probe.opened.isEmpty(), "nothing opened for the opencode port")
        assertTrue(fx.connector.sessions.single().loopbackTunnels.isEmpty())
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(PREVIEW_PORT, fx.manager.openPreviewTunnel(PREVIEW_PORT).port, "another port still opens")
    }

    @Test
    fun `the refused port is the one this connection's server listens on`() = runTest {
        val fx = fixture()
        fx.tools.server = ServerLookup.Running("127.0.0.1", 5000, 100, ServerSource.ProcessScan)
        connected(fx)

        assertFailsWith<OpencodePortRefusedException> { fx.manager.openPreviewTunnel(5000) }
        assertEquals(OPENCODE_PORT, fx.manager.openPreviewTunnel(OPENCODE_PORT).port)
    }

    @Test
    fun `the port of a server the connect started is refused`() = runTest {
        val fx = fixture()
        fx.tools.server = ServerLookup.NotRunning()
        connected(fx)
        assertEquals(1, fx.tools.startedWith.size)

        assertFailsWith<OpencodePortRefusedException> { fx.manager.openPreviewTunnel(STARTED_PORT) }
    }

    @Test
    fun `an IPv6 server address is understood`() = runTest {
        val fx = fixture()
        fx.tools.server = ServerLookup.Running("::1", 5001, 100, ServerSource.ProcessScan)
        connected(fx)

        assertFailsWith<OpencodePortRefusedException> { fx.manager.openPreviewTunnel(5001) }
    }

    @Test
    fun `fails with NotConnected when idle`() = runTest {
        val fx = fixture()

        assertFailsWith<NotConnectedException> { fx.manager.openPreviewTunnel(PREVIEW_PORT) }
    }

    @Test
    fun `fails with NotConnected while connecting`() = runTest {
        val probe = TunnelProbe()
        val fx = fixture(probe)
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.HostApproval>(fx.manager.state.value)

        assertFailsWith<NotConnectedException> { fx.manager.openPreviewTunnel(PREVIEW_PORT) }
        assertTrue(probe.opened.isEmpty())
    }

    @Test
    fun `fails with NotConnected while waiting to reconnect`() = runTest {
        val probe = TunnelProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)

        assertFailsWith<NotConnectedException> { fx.manager.openPreviewTunnel(PREVIEW_PORT) }
        assertTrue(probe.opened.isEmpty())
    }

    @Test
    fun `fails with NotConnected after an error, a disconnect and a close`() = runTest {
        val fx = fixture()
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        fx.manager.rejectHostKey(SERVER_KEY)
        runCurrent()
        assertIs<ConnectionState.Error>(fx.manager.state.value)
        assertFailsWith<NotConnectedException> { fx.manager.openPreviewTunnel(PREVIEW_PORT) }

        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        fx.manager.disconnect()
        assertFailsWith<NotConnectedException> { fx.manager.openPreviewTunnel(PREVIEW_PORT) }

        fx.manager.close()
        assertFailsWith<NotConnectedException> { fx.manager.openPreviewTunnel(PREVIEW_PORT) }
    }

    @Test
    fun `a session that ended unseen fails with NotConnected`() = runTest {
        val fx = fixture(TunnelProbe())
        connected(fx)
        val session = fx.connector.sessions.single()
        // The session reports the end; the manager has not run its drop handling yet.
        session.loseConnection()

        assertFailsWith<NotConnectedException> { fx.manager.openPreviewTunnel(PREVIEW_PORT) }
    }

    @Test
    fun `a dropped connection closes every preview tunnel and a reconnect opens on the new session`() = runTest {
        val probe = TunnelProbe()
        val fx = fixture(probe)
        val first = connected(fx)
        val one = fx.manager.openPreviewTunnel(PREVIEW_PORT)
        val two = fx.manager.openPreviewTunnel(OTHER_PREVIEW_PORT)

        fx.connector.sessions.single().loseConnection()
        runCurrent()

        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)
        assertEquals(listOf(1, 1), probe.opened.map { it.closes.get() }, "each closed once, as soon as the connection drops")
        assertRefusesSockets(one)
        assertRefusesSockets(two)

        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        val second = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(first.generation + 1, second.generation)

        val fresh = fx.manager.openPreviewTunnel(PREVIEW_PORT)

        assertNotSame(one, fresh)
        assertEquals(listOf(PREVIEW_PORT), fx.connector.sessions[1].loopbackTunnels.map { it.port })
        assertEquals(2, fx.connector.sessions[0].loopbackTunnels.size, "nothing new on the old session")
        fresh.connectTo("localhost")
        assertEquals("localhost" to PREVIEW_PORT, fx.connector.sessions[1].tunnels.last())
        assertRefusesSockets(one)
    }

    @Test
    fun `the manager closes the tunnels itself when the session would not`() = runTest {
        val probe = TunnelProbe().apply { sessionClosesTunnels = false }
        val fx = fixture(probe)
        connected(fx)
        val tunnel = fx.manager.openPreviewTunnel(PREVIEW_PORT)

        fx.manager.reconnect()
        runCurrent()

        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)
        assertEquals(1, probe.opened.single().closes.get())
        assertTrue(fx.connector.sessions.single().loopbackTunnels.single().isClosed)
        assertRefusesSockets(tunnel)
    }

    @Test
    fun `tunnels close on disconnect`() = runTest {
        val probe = TunnelProbe().apply { sessionClosesTunnels = false }
        val fx = fixture(probe)
        connected(fx)
        fx.manager.openPreviewTunnel(PREVIEW_PORT)
        fx.manager.openPreviewTunnel(OTHER_PREVIEW_PORT)

        fx.manager.disconnect()

        assertEquals(listOf(1, 1), probe.opened.map { it.closes.get() })
    }

    @Test
    fun `tunnels close on close`() = runTest {
        val probe = TunnelProbe().apply { sessionClosesTunnels = false }
        val fx = fixture(probe)
        connected(fx)
        fx.manager.openPreviewTunnel(PREVIEW_PORT)

        fx.manager.close()

        assertEquals(1, probe.opened.single().closes.get())
    }

    @Test
    fun `tunnels close when a new connect replaces the connection`() = runTest {
        val probe = TunnelProbe().apply { sessionClosesTunnels = false }
        val fx = fixture(probe)
        connected(fx)
        val old = fx.manager.openPreviewTunnel(PREVIEW_PORT)

        fx.manager.connect(profile())
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(1, probe.opened.single().closes.get())
        assertRefusesSockets(old)
    }

    @Test
    fun `a tunnel the caller closes early is not closed again`() = runTest {
        val probe = TunnelProbe()
        val fx = fixture(probe)
        connected(fx)
        val tunnel = fx.manager.openPreviewTunnel(PREVIEW_PORT)

        tunnel.close()
        tunnel.close()
        assertEquals(1, probe.opened.single().closes.get())
        assertRefusesSockets(tunnel)

        fx.manager.disconnect()
        assertEquals(1, probe.opened.single().closes.get())
    }

    @Test
    fun `a tunnel the manager closed is not closed again by the caller`() = runTest {
        val probe = TunnelProbe()
        val fx = fixture(probe)
        connected(fx)
        val tunnel = fx.manager.openPreviewTunnel(PREVIEW_PORT)

        fx.manager.disconnect()
        tunnel.close()

        assertEquals(1, probe.opened.single().closes.get())
    }

    @Test
    fun `a tunnel prints no port`() = runTest {
        val fx = fixture()
        connected(fx)

        val tunnel = fx.manager.openPreviewTunnel(PREVIEW_PORT)

        assertFalse(PREVIEW_PORT.toString() in tunnel.toString())
    }

    @Test
    fun `opening a tunnel changes no state`() = runTest {
        val fx = fixture()
        val connected = connected(fx)
        val seen = fx.states.size

        val tunnel = fx.manager.openPreviewTunnel(PREVIEW_PORT)
        tunnel.close()
        runCurrent()

        assertEquals(seen, fx.states.size)
        assertEquals(connected, fx.manager.state.value)
    }

    private companion object {
        const val PREVIEW_PORT = 8080
        const val OTHER_PREVIEW_PORT = 5173
        const val OPENCODE_PORT = 4096
        const val STARTED_PORT = 4100
    }
}
