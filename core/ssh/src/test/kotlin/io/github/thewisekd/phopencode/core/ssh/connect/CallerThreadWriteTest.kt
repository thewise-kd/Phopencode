package io.github.thewisekd.phopencode.core.ssh.connect

import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.ssh.exec.ExecFeature
import io.github.thewisekd.phopencode.core.ssh.exec.ExecTestServer
import io.github.thewisekd.phopencode.core.ssh.exec.RemoteProcess
import io.github.thewisekd.phopencode.core.ssh.sftp.write.TestSftpServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/**
 * Android throws a RuntimeException for a socket write on the main thread, after sshj has already
 * encrypted the packet; the server then reads every later packet as garbage. Here writes from one
 * named thread, the "main" thread, throw the same way. Closing SFTP, a process or the session from
 * that thread must write nothing from it, and a write that does fail must end the session instead
 * of leaving it running out of step with the server.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CallerThreadWriteTest {
    private val closeables = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    private val main: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, MAIN_THREAD).apply { isDaemon = true }
    }

    /** Socket writes the "main" thread attempted; each one threw. */
    private val mainWrites = AtomicInteger()

    private val trap: () -> Unit = {
        if (Thread.currentThread().name == MAIN_THREAD) {
            mainWrites.incrementAndGet()
            throw IllegalStateException("Network on the main thread (test)")
        }
    }

    @AfterTest
    fun tearDown() {
        main.shutdownNow()
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    private fun onMain(block: () -> Unit) {
        main.submit(block).get(10, TimeUnit.SECONDS)
    }

    private suspend fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for: $what")
            delay(20)
        }
    }

    private fun realTime(body: suspend () -> Unit) = runTest(timeout = 10.seconds) {
        withContext(Dispatchers.IO) { body() }
    }

    private suspend fun connect(target: SshTarget, features: SessionFeatures): SshjSession =
        SshjConnector(features, beforeSocketWrite = trap)
            .connect(target, password(), ACCEPT, SshConnectOptions(keepAliveInterval = null))
            .closeLater() as SshjSession

    private fun sftpServer(): TestSftpServer = TestSftpServer().closeLater().apply {
        home.resolve("work").createDirectory().resolve("a.txt").writeText("text")
    }

    private suspend fun SshSession.listsWork(): List<String> =
        withTimeout(5.seconds) { openSftp("/work").use { client -> client.list("").map { it.name } } }

    @Test
    fun `closing an SFTP client on the main thread writes nothing from it and leaves the session working`() = realTime {
        val server = sftpServer()
        val session = connect(server.target, SshjSessionFeatures)
        val client = session.openSftp("/work")
        assertEquals(listOf("a.txt"), client.list("").map { it.name })

        onMain { client.close() }

        assertEquals(0, mainWrites.get(), "the close wrote from the main thread")
        eventually("the channel closed") { server.openSubsystems() == 0 }
        assertTrue(session.connected.value)
        assertEquals(listOf("a.txt"), session.listsWork())
    }

    @Test
    fun `closing the session on the main thread writes nothing from it and ends it cleanly`() = realTime {
        val server = sftpServer()
        val session = connect(server.target, SshjSessionFeatures)
        val client = session.openSftp("/work")
        assertEquals(listOf("a.txt"), client.list("").map { it.name })

        onMain { session.close() }

        assertEquals(0, mainWrites.get(), "the close wrote from the main thread")
        assertFalse(session.connected.value)
        eventually("the channel closed") { server.openSubsystems() == 0 }
        assertFailsWith<SshException.Disconnected> { client.list("") }
        assertFailsWith<SshException.Disconnected> { session.openSftp("/work") }
    }

    @Test
    fun `closing the session waits for a channel release still under way, writing nothing from the main thread`() = realTime {
        val server = sftpServer()
        val session = connect(server.target, SshjSessionFeatures)
        val client = session.openSftp("/work")
        assertEquals(listOf("a.txt"), client.list("").map { it.name })
        val releasing = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val released = AtomicBoolean()
        // A channel close that is slow: held until the gate opens.
        session.release {
            releasing.countDown()
            gate.await()
            released.set(true)
        }
        assertTrue(releasing.await(5, TimeUnit.SECONDS))

        val closing = main.submit { session.close() }
        try {
            closing.get(300, TimeUnit.MILLISECONDS)
            fail("the session closed while a channel release was still under way")
        } catch (_: TimeoutException) {
            // Expected: still waiting for the release.
        }
        gate.countDown()
        closing.get(5, TimeUnit.SECONDS)

        assertTrue(released.get(), "close returned before the release finished")
        assertEquals(0, mainWrites.get(), "the close wrote from the main thread")
        assertFalse(session.connected.value)
        eventually("the channel closed") { server.openSubsystems() == 0 }
    }

    @Test
    fun `a channel release that never finishes does not keep the session from closing`() = realTime {
        val server = sftpServer()
        val session = connect(server.target, SshjSessionFeatures)
        session.closeBudgetMillis = 500
        val socket = session.client.socket
        val releasing = CountDownLatch(1)
        val never = CountDownLatch(1)
        try {
            // A close blocked for good, as one writing to a peer that reads nothing.
            session.release {
                releasing.countDown()
                never.await()
            }
            assertTrue(releasing.await(5, TimeUnit.SECONDS))

            main.submit { session.close() }.get(5, TimeUnit.SECONDS)

            assertEquals(0, mainWrites.get(), "the close wrote from the main thread")
            assertFalse(session.connected.value)
            assertTrue(socket.isClosed, "the socket is still open")
        } finally {
            // Ends the stuck closer thread, which other tests would find left behind.
            never.countDown()
        }
    }

    @Test
    fun `closing a process on the main thread writes nothing from it and leaves the session working`() = realTime {
        val server = ExecTestServer().closeLater()
        val features = object : SessionFeatures by NoSessionFeatures {
            override fun exec(session: SshjSession): RemoteExec = ExecFeature.exec(session)
        }
        val session = connect(server.target, features)
        val process = RemoteProcess.open(session) {}
        process.start("sleep 30")
        process.readPidLine()
        assertEquals(1, server.openChannels())

        onMain { process.close() }

        assertEquals(0, mainWrites.get(), "the close wrote from the main thread")
        eventually("the channel closed") { server.openChannels() == 0 }
        assertTrue(session.connected.value)
        val result = withTimeout(5.seconds) { session.exec.run(ExecRequest("echo ok")) }
        assertEquals("ok\n", result.stdout.decodeToString())
    }

    @Test
    fun `a socket write that throws a RuntimeException ends the session`() = realTime {
        val server = sftpServer()
        val session = connect(server.target, SshjSessionFeatures)
        assertEquals(listOf("a.txt"), session.listsWork())

        // Straight to sshj, as nothing in the session does any more.
        onMain { runCatching { session.client.connection.sendGlobalRequest("keepalive@openssh.com", true, ByteArray(0)) } }

        assertEquals(1, mainWrites.get())
        eventually("the session ended") { !session.connected.value }
        assertFailsWith<SshException.Disconnected> { session.openSftp("/work") }
    }

    private companion object {
        const val MAIN_THREAD = "test-main"
        val ACCEPT = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }
    }
}
