package io.github.thewisekd.phopencode.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `direct-tcpip` from the emulator, through the session's tunnel socket factory, to servers that
 * listen only on the runner's loopback. The stats server counts the echo connections still open on
 * the runner, which shows that a closed socket or a closed session really closed its channel.
 */
@RunWith(AndroidJUnit4::class)
class TunnelTest {
    private lateinit var server: SshTestServer

    @Before
    fun setUp() {
        server = SshTestServer.fromArguments()
    }

    @Test
    fun echoesByteForByteAndClosingTheSocketClosesTheChannel() = runBlocking<Unit> {
        server.connect().use { session ->
            awaitOpenEchoConnections(session, 0)

            val socket = session.socketFactory().createSocket(LOOPBACK, server.echoPort)
            socket.use {
                it.soTimeout = READ_TIMEOUT_MILLIS
                val payload = Random(SEED).nextBytes(PAYLOAD_SIZE)
                assertArrayEquals(payload, echo(it, payload))
                assertEquals(1, openEchoConnections(session))
            }

            awaitOpenEchoConnections(session, 0)
        }
    }

    @Test
    fun closingTheSessionClosesItsTunnels() = runBlocking<Unit> {
        val session = server.connect()
        val socket = try {
            awaitOpenEchoConnections(session, 0)
            session.socketFactory().createSocket(LOOPBACK, server.echoPort).also {
                it.soTimeout = READ_TIMEOUT_MILLIS
                assertArrayEquals(GREETING, echo(it, GREETING))
                assertEquals(1, openEchoConnections(session))
            }
        } finally {
            session.close()
        }

        assertFalse(session.connected.value)
        assertTrue(socket.isClosed)
        assertThrows(IOException::class.java) { socket.getOutputStream().write(GREETING) }

        // A new connection sees what the closed one left behind on the runner: nothing.
        server.connect().use { awaitOpenEchoConnections(it, 0) }
    }

    @Test
    fun aTargetOutsideTheServersLoopbackIsRefused() = runBlocking<Unit> {
        server.connect().use { session ->
            assertThrows(SocketException::class.java) {
                session.socketFactory().createSocket(DOCUMENTATION_ADDRESS, server.echoPort).close()
            }
        }
    }

    /** Writes on another thread while reading here, so neither side's buffers can stall the other. */
    private fun echo(socket: Socket, payload: ByteArray): ByteArray {
        val writer = Executors.newSingleThreadExecutor()
        try {
            val written = writer.submit(
                Callable {
                    socket.getOutputStream().apply {
                        write(payload)
                        flush()
                    }
                },
            )
            val received = ByteArray(payload.size)
            DataInputStream(socket.getInputStream()).readFully(received)
            written.get(READ_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            return received
        } finally {
            writer.shutdownNow()
        }
    }

    /** One question to the stats server, over its own tunnel. */
    private fun openEchoConnections(session: SshSession): Int =
        session.socketFactory().createSocket(LOOPBACK, server.statsPort).use { socket ->
            socket.soTimeout = READ_TIMEOUT_MILLIS
            checkNotNull(socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()).trim().toInt()
        }

    /** The runner closes a forwarded connection a moment after its channel closes, so this polls. */
    private fun awaitOpenEchoConnections(session: SshSession, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MILLIS)
        var open = openEchoConnections(session)
        while (open != expected && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            open = openEchoConnections(session)
        }
        assertEquals("open echo connections on the runner", expected, open)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        /** RFC 5737; refused before any request leaves the phone. */
        const val DOCUMENTATION_ADDRESS = "192.0.2.1"
        const val SEED = 0x7e1
        const val PAYLOAD_SIZE = 1024 * 1024 + 3
        const val READ_TIMEOUT_MILLIS = 30_000
        const val CLOSE_WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 100L
        val GREETING = "phopencode\n".toByteArray(Charsets.US_ASCII)
    }
}
