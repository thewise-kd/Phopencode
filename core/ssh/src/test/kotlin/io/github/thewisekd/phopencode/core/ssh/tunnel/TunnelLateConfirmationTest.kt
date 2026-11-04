package io.github.thewisekd.phopencode.core.ssh.tunnel

import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout

/**
 * A channel the caller gave up on, confirmed while the transport cannot send because the client's
 * key exchange is held. Its close request must wait for the transport on a thread of its own:
 * neither the connecting caller nor sshj's reader thread may block on it. The reader in particular
 * has to stay free, because only it can finish the key exchange.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TunnelLateConfirmationTest {
    private val closeables = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    private val server = TunnelSshServer().closeLater()
    private val echo = EchoServer().closeLater()
    private val proxy = OneWayFreezeProxy(server.target.port).closeLater()
    private val session: SshSession = runBlocking { connectWithTunnel(server, target = proxy.target) }.closeLater()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    /** Starts a key exchange the server does not see until [OneWayFreezeProxy.thaw]. */
    private fun holdKeyExchange(): Thread {
        proxy.freezeUpstream()
        val kex = thread(isDaemon = true, name = "test-kex") { runCatching { (session as SshjSession).client.transport.doKex() } }
        Thread.sleep(300)
        return kex
    }

    private fun assertSessionWorks() {
        val socket: Socket = session.socketFactory().createSocket().closeLater()
        socket.connect(InetSocketAddress(LOOPBACK, echo.port), 5_000)
        socket.getOutputStream().apply { write(5); flush() }
        assertEquals(5, socket.getInputStream().read())
        socket.close()
    }

    @Test
    fun `a confirmation arriving as the timeout fires does not hold the caller`() {
        server.holdConnects()
        lateinit var channel: TunnelChannel
        var kex: Thread? = null
        var waitEnded = TimeSource.Monotonic.markNow()
        channel = TunnelChannel((session as SshjSession).client.connection, "127.0.0.1", echo.port, afterTimeout = {
            kex = holdKeyExchange()
            server.releaseConnects()
            check(eventually { channel.confirmed }) { "No confirmation" }
            waitEnded = TimeSource.Monotonic.markNow()
        })

        assertEquals(TunnelChannel.Opening.TimedOut, channel.openWithin(300))
        val held = waitEnded.elapsedNow()
        assertTrue(held < 1.seconds, "The caller was held for $held")

        proxy.thaw()
        assertTrue(eventually(10.seconds) { kex?.isAlive == false }, "The key exchange did not finish")
        assertTrue(eventually(10.seconds) { server.openChannels() == 0 })
        assertTrue(session.connected.value)
        assertSessionWorks()
        assertTrue(eventually { tunnelThreads().isEmpty() }, "${tunnelThreads()}")
    }

    @Test
    fun `a late confirmation during a held key exchange does not block the reader`() {
        server.holdConnects()
        val socket = session.socketFactory().createSocket().closeLater()
        assertFailsWith<SocketTimeoutException> { socket.connect(InetSocketAddress(LOOPBACK, echo.port), 300) }

        val kex = holdKeyExchange()
        server.releaseConnects()
        assertTrue(eventually { server.channelsOpened() == 1 })
        // Give the confirmation time to reach the client's reader.
        Thread.sleep(300)

        proxy.thaw()
        assertTrue(eventually(5.seconds) { !kex.isAlive }, "The key exchange did not finish")
        assertTrue(eventually(5.seconds) { server.openChannels() == 0 })
        assertTrue(session.connected.value)
        assertSessionWorks()
        assertTrue(eventually { tunnelThreads().isEmpty() }, "${tunnelThreads()}")
    }
}
