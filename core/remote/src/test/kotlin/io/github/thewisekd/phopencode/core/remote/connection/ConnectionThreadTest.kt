package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Closing an SSH resource writes to the network, which Android refuses on the main thread, after
 * sshj has already counted the packet. So the manager must never close anything on the thread that
 * calls it; presenters call it on the main thread.
 */
class ConnectionThreadTest {
    /** Records each close: the thread it ran on, and that it finished. */
    private class Closes {
        val threads = ConcurrentLinkedQueue<String>()
        val finished = AtomicInteger()

        /** When set, each close waits for it after [closing] counts down. */
        @Volatile
        var gate: CountDownLatch? = null
        val closing = CountDownLatch(1)

        fun record(close: () -> Unit) {
            threads += Thread.currentThread().name
            gate?.let { gate ->
                closing.countDown()
                gate.await()
            }
            // Long enough that a caller who does not wait would return before it ends.
            Thread.sleep(CLOSE_MILLIS)
            close()
            finished.incrementAndGet()
        }
    }

    private class RecordingSftp(private val real: SftpClient, private val closes: Closes) : SftpClient by real {
        override fun close() = closes.record(real::close)
    }

    private class RecordingSession(private val real: FakeSshSession, private val closes: Closes) : SshSession by real {
        override suspend fun openSftp(root: String): SftpClient = RecordingSftp(real.openSftp(root), closes)

        override fun close() = closes.record(real::close)
    }

    private class RecordingConnector(private val real: SshConnector, private val closes: Closes) : SshConnector {
        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession = RecordingSession(real.connect(target, credential, hostKeyVerifier, options) as FakeSshSession, closes)
    }

    @Test
    fun `connect and disconnect on the main thread close nothing on it, and disconnect waits for the closes`() =
        runTest(timeout = 10.seconds) {
            val closes = Closes()
            val managerScope = CoroutineScope(Dispatchers.Default + Job())
            val fx = Fixture(this, wrapConnector = { RecordingConnector(it, closes) }, managerScope = managerScope)
            val mainThread = Executors.newSingleThreadExecutor { task -> Thread(task, MAIN_THREAD).apply { isDaemon = true } }
            val main = mainThread.asCoroutineDispatcher()
            try {
                fx.seedSecrets()
                fx.trustServerKey()
                repeat(2) { index ->
                    withContext(main) { fx.manager.connect(profile()) }
                    awaitConnected(fx)
                    fx.connector.sessions[index].files.addDirectory(PROJECT)
                    fx.manager.openSftp(PROJECT)
                }

                withContext(main) { fx.manager.disconnect() }

                assertEquals(4, closes.threads.size, "every session and SFTP client closes once: ${closes.threads}")
                assertEquals(4, closes.finished.get(), "disconnect returned before everything was closed")
                assertTrue(MAIN_THREAD !in closes.threads, "closed on the main thread: ${closes.threads}")
                assertTrue(fx.connector.sessions.none { it.connected.value })
                assertTrue(fx.clients.made.all { it.client.control.isClosed })
                assertEquals(ConnectionState.Idle, fx.manager.state.value)
            } finally {
                managerScope.cancel()
                main.close()
            }
        }

    @Test
    fun `close on the main thread closes nothing on it, and waits for the closes`() = runTest(timeout = 10.seconds) {
        val closes = Closes()
        val managerScope = CoroutineScope(Dispatchers.Default + Job())
        val fx = Fixture(this, wrapConnector = { RecordingConnector(it, closes) }, managerScope = managerScope)
        val mainThread = Executors.newSingleThreadExecutor { task -> Thread(task, MAIN_THREAD).apply { isDaemon = true } }
        val main = mainThread.asCoroutineDispatcher()
        try {
            fx.seedSecrets()
            fx.trustServerKey()
            withContext(main) { fx.manager.connect(profile()) }
            awaitConnected(fx)
            fx.connector.sessions.single().files.addDirectory(PROJECT)
            fx.manager.openSftp(PROJECT)

            withContext(main) { fx.manager.close() }

            assertEquals(2, closes.finished.get(), "close returned before everything was closed")
            assertTrue(MAIN_THREAD !in closes.threads, "closed on the main thread: ${closes.threads}")
            assertEquals(ConnectionState.Closed, fx.manager.state.value)
        } finally {
            managerScope.cancel()
            main.close()
        }
    }

    @Test
    fun `a connect on the main thread does not wait for a cleanup stuck in a slow close`() = runTest(timeout = 10.seconds) {
        val closes = Closes()
        val managerScope = CoroutineScope(Dispatchers.Default + Job())
        val fx = Fixture(this, wrapConnector = { RecordingConnector(it, closes) }, managerScope = managerScope)
        val mainThread = Executors.newSingleThreadExecutor { task -> Thread(task, MAIN_THREAD).apply { isDaemon = true } }
        val main = mainThread.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        try {
            fx.seedSecrets()
            fx.trustServerKey()
            withContext(main) { fx.manager.connect(profile()) }
            awaitConnected(fx)

            // The drop's cleanup closes the session, and that close hangs, holding the cleanup.
            closes.gate = gate
            fx.connector.sessions.single().loseConnection()
            assertTrue(closes.closing.await(5, TimeUnit.SECONDS), "the cleanup never started closing")

            val returned = mainThread.submit { fx.manager.connect(profile()) }
            try {
                returned.get(1, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                fail("connect blocked the main thread while a cleanup was closing")
            }
            assertTrue(MAIN_THREAD !in closes.threads, "closed on the main thread: ${closes.threads}")
        } finally {
            gate.countDown()
            managerScope.cancel()
            main.close()
        }
    }

    private suspend fun awaitConnected(fx: Fixture) {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { fx.manager.state.first { it is ConnectionState.Connected } }
        }
    }

    private companion object {
        const val MAIN_THREAD = "test-main"
        const val CLOSE_MILLIS = 100L
        const val PROJECT = "/srv/project"
    }
}
