package io.github.thewisekd.phopencode.core.remote.detect

import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SocketPortProbeTest {
    /** Sockets whose connect fails with [failure], or succeeds when it is null. */
    private class ScriptedFactory(private val failure: IOException?) : SocketFactory() {
        var attempts = 0

        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                attempts++
                failure?.let { throw it }
            }
        }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    @Test
    fun `a refused or timed-out connection did not answer`(): Unit = runBlocking {
        assertFalse(SocketPortProbe(ScriptedFactory(ConnectException("refused"))).answers("127.0.0.1", 4096))
        assertFalse(SocketPortProbe(ScriptedFactory(SocketTimeoutException("slow"))).answers("127.0.0.1", 4096))
    }

    @Test
    fun `a failure after the session ended is a lost connection`(): Unit = runBlocking {
        val probe = SocketPortProbe(ScriptedFactory(IOException("channel closed")), connected = { false })
        assertFailsWith<SshException.Disconnected> { probe.answers("127.0.0.1", 4096) }
    }

    @Test
    fun `a lost connection thrown by the factory passes through`(): Unit = runBlocking {
        val probe = SocketPortProbe(ScriptedFactory(SshException.Disconnected()))
        assertFailsWith<SshException.Disconnected> { probe.answers("127.0.0.1", 4096) }
    }

    @Test
    fun `an open port answers`(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            assertTrue(SocketPortProbe(SocketFactory.getDefault()).answers("127.0.0.1", server.localPort))
        }
        val factory = ScriptedFactory(null)
        assertTrue(SocketPortProbe(factory).answers("::1", 1))
        assertEquals(1, factory.attempts)
    }

    @Test
    fun `refuses a host that is not loopback before connecting`(): Unit = runBlocking {
        val factory = ScriptedFactory(null)
        assertFailsWith<IllegalArgumentException> { SocketPortProbe(factory).answers("192.168.1.2", 4096) }
        assertEquals(0, factory.attempts)
    }
}
