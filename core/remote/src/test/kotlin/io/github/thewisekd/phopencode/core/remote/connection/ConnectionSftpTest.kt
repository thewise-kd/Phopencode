package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSftpClient
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ConnectionSftpTest {
    /** An SFTP client that counts how often it is closed; a close can be held at [SftpProbe.closeGate]. */
    private class CountingSftp(val inner: SftpClient, private val probe: SftpProbe) : SftpClient by inner {
        val closes = AtomicInteger()
        val closeStarted = CountDownLatch(1)

        @Volatile
        var closeFinished = false

        /** Holds this client's close; [SftpProbe.closeGate] holds every client's. */
        @Volatile
        var gate: CountDownLatch? = null

        override fun close() {
            closes.incrementAndGet()
            closeStarted.countDown()
            (gate ?: probe.closeGate)?.await(10, TimeUnit.SECONDS)
            probe.closeMillis.takeIf { it > 0 }?.let(Thread::sleep)
            inner.close()
            closeFinished = true
        }
    }

    /** Sees every SFTP open on the sessions of [ProbeConnector]; an open can be held at [gate]. */
    private class SftpProbe {
        val opened = mutableListOf<CountingSftp>()
        val sessionsAsked = mutableListOf<FakeSshSession>()

        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        /** The open returns a client even when the session has ended meanwhile, as a racing one may. */
        @Volatile
        var ignoreSessionEnd = false

        var cancelled = 0

        /** When set, every client's close waits here before it closes the real client. */
        @Volatile
        var closeGate: CountDownLatch? = null

        /** How long every client's close takes, so that a close still under way can be seen. */
        @Volatile
        var closeMillis = 0L
    }

    private class ProbeSession(private val inner: FakeSshSession, private val probe: SftpProbe) : SshSession by inner {
        override suspend fun openSftp(root: String): SftpClient {
            synchronized(probe) { probe.sessionsAsked += inner }
            probe.gate?.let { gate ->
                try {
                    gate.await()
                } catch (cancelled: CancellationException) {
                    synchronized(probe) { probe.cancelled++ }
                    throw cancelled
                }
            }
            val client = if (probe.ignoreSessionEnd) FakeSftpClient(root, inner.files) else inner.openSftp(root)
            return CountingSftp(client, probe).also { synchronized(probe) { probe.opened += it } }
        }

        override fun close() = inner.close()
    }

    private class ProbeConnector(private val real: SshConnector, private val probe: SftpProbe) : SshConnector {
        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession = ProbeSession(real.connect(target, credential, hostKeyVerifier, options) as FakeSshSession, probe)
    }

    private fun TestScope.fixture(probe: SftpProbe) = Fixture(this, wrapConnector = { ProbeConnector(it, probe) })

    private suspend fun TestScope.connected(fx: Fixture): ConnectionState.Connected {
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()
        return assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    private fun Fixture.addProject(session: Int = 0) {
        connector.sessions[session].files.apply {
            addDirectory(PROJECT)
            addFile("$PROJECT/notes.txt", "hello".encodeToByteArray())
        }
    }

    @Test
    fun `opens a client confined to the root on the connected session`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()

        val client = fx.manager.openSftp(PROJECT)

        assertEquals(PROJECT, client.root)
        assertEquals(setOf("notes.txt"), client.list("").map { it.name }.toSet())
        assertEquals(listOf(fx.connector.sessions.single()), probe.sessionsAsked)
        assertEquals(PROJECT, probe.opened.single().root)
    }

    @Test
    fun `the session's SFTP failures reach the caller as they are`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)

        val failure = assertFailsWith<SshException.Sftp> { fx.manager.openSftp("/srv/missing") }

        assertEquals(SftpFailure.NotFound, failure.failure)
        assertIs<ConnectionState.Connected>(fx.manager.state.value, "a failed open leaves the connection alone")
    }

    @Test
    fun `fails clearly when idle`() = runTest {
        val fx = fixture(SftpProbe())

        assertFailsWith<NotConnectedException> { fx.manager.openSftp(PROJECT) }
    }

    @Test
    fun `fails clearly while connecting`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.HostApproval>(fx.manager.state.value)

        assertFailsWith<NotConnectedException> { fx.manager.openSftp(PROJECT) }
        assertTrue(probe.sessionsAsked.isEmpty())
    }

    @Test
    fun `fails clearly after disconnect and after close`() = runTest {
        val fx = fixture(SftpProbe())
        connected(fx)
        fx.addProject()

        fx.manager.disconnect()
        assertFailsWith<NotConnectedException> { fx.manager.openSftp(PROJECT) }

        fx.manager.close()
        assertFailsWith<NotConnectedException> { fx.manager.openSftp(PROJECT) }
    }

    @Test
    fun `fails clearly while waiting to reconnect`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)

        assertFailsWith<NotConnectedException> { fx.manager.openSftp(PROJECT) }
        assertTrue(probe.sessionsAsked.isEmpty())
    }

    @Test
    fun `fails clearly after the connection ended in an error`() = runTest {
        val fx = fixture(SftpProbe())
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        fx.manager.rejectHostKey(SERVER_KEY)
        runCurrent()
        assertIs<ConnectionState.Error>(fx.manager.state.value)

        assertFailsWith<NotConnectedException> { fx.manager.openSftp(PROJECT) }
    }

    @Test
    fun `clients are closed on disconnect`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        fx.manager.openSftp(PROJECT)
        fx.manager.openSftp(PROJECT)

        fx.manager.disconnect()

        assertEquals(listOf(1, 1), probe.opened.map { it.closes.get() })
    }

    @Test
    fun `clients are closed on close`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        fx.manager.openSftp(PROJECT)

        fx.manager.close()

        assertEquals(1, probe.opened.single().closes.get())
    }

    @Test
    fun `clients are closed when a new connect replaces the connection`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        fx.manager.openSftp(PROJECT)

        fx.manager.connect(profile())
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(1, probe.opened.single().closes.get())
    }

    @Test
    fun `clients are closed at a drop and a reconnect opens on the new session`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        val first = connected(fx)
        fx.addProject()
        val old = fx.manager.openSftp(PROJECT)

        fx.connector.sessions.single().loseConnection()
        runCurrent()

        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)
        assertEquals(1, probe.opened.single().closes.get(), "closed as soon as the connection drops")

        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        val second = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(first.generation + 1, second.generation)
        fx.addProject(session = 1)

        val fresh = fx.manager.openSftp(PROJECT)

        assertNotSame(old, fresh)
        assertEquals(fx.connector.sessions[1], probe.sessionsAsked.last())
        assertEquals(setOf("notes.txt"), fresh.list("").map { it.name }.toSet())
        assertEquals(listOf(1, 0), probe.opened.map { it.closes.get() })
    }

    @Test
    fun `clients are closed when reconnect is asked for`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        fx.manager.openSftp(PROJECT)

        fx.manager.reconnect()
        runCurrent()

        assertEquals(1, probe.opened.single().closes.get())
        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)
    }

    @Test
    fun `a client the caller closes early is not closed again`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        val client = fx.manager.openSftp(PROJECT)

        client.close()
        client.close()
        assertEquals(1, probe.opened.single().closes.get())

        fx.manager.disconnect()
        assertEquals(1, probe.opened.single().closes.get())
    }

    @Test
    fun `a client the manager closed is not closed again by the caller`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        val client = fx.manager.openSftp(PROJECT)

        fx.manager.disconnect()
        client.close()

        assertEquals(1, probe.opened.single().closes.get())
    }

    @Test
    fun `a client closed early is not kept until the connection ends`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        repeat(3) { fx.manager.openSftp(PROJECT).close() }
        val kept = fx.manager.openSftp(PROJECT)

        fx.manager.disconnect()

        assertEquals(listOf(1, 1, 1, 1), probe.opened.map { it.closes.get() })
        assertFailsWith<SshException> { kept.list("") }
    }

    @Test
    fun `cancelling an open leaks nothing`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        probe.gate = CompletableDeferred()

        val opening = async { fx.manager.openSftp(PROJECT) }
        runCurrent()
        opening.cancel()
        runCurrent()

        assertEquals(1, probe.cancelled)
        assertTrue(probe.opened.isEmpty())
        probe.gate = null
        val later = fx.manager.openSftp(PROJECT)
        fx.manager.disconnect()
        assertEquals(1, probe.opened.single().closes.get())
        assertEquals(PROJECT, later.root)
    }

    @Test
    fun `an open that finishes after disconnect is closed and fails`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        probe.gate = CompletableDeferred()
        probe.ignoreSessionEnd = true

        val opening = async { runCatching { fx.manager.openSftp(PROJECT) } }
        runCurrent()
        fx.manager.disconnect()
        probe.gate!!.complete(Unit)
        runCurrent()

        assertIs<NotConnectedException>(opening.await().exceptionOrNull())
        assertEquals(1, probe.opened.single().closes.get())
    }

    @Test
    fun `an open that finishes after a drop is closed and fails`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        probe.gate = CompletableDeferred()
        probe.ignoreSessionEnd = true

        val opening = async { runCatching { fx.manager.openSftp(PROJECT) } }
        runCurrent()
        fx.manager.reconnect()
        runCurrent()
        probe.gate!!.complete(Unit)
        runCurrent()

        assertIs<NotConnectedException>(opening.await().exceptionOrNull())
        assertEquals(1, probe.opened.single().closes.get())
        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)
    }

    @Test
    fun `an open that finishes on a session that ended fails`() = runTest {
        val probe = SftpProbe()
        val fx = fixture(probe)
        connected(fx)
        fx.addProject()
        probe.gate = CompletableDeferred()

        val opening = async { runCatching { fx.manager.openSftp(PROJECT) } }
        runCurrent()
        fx.manager.disconnect()
        probe.gate!!.complete(Unit)
        runCurrent()

        assertIs<SshException.Disconnected>(opening.await().exceptionOrNull())
        assertTrue(probe.opened.isEmpty())
    }

    @Test
    fun `disconnect waits for a close the caller has under way`() = runTest {
        val probe = SftpProbe()
        val managerScope = CoroutineScope(Dispatchers.Default + Job())
        val fx = Fixture(this, wrapConnector = { ProbeConnector(it, probe) }, managerScope = managerScope)
        val gate = CountDownLatch(1)
        try {
            fx.seedSecrets()
            fx.trustServerKey()
            fx.manager.connect(profile())
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) { fx.manager.state.first { it is ConnectionState.Connected } }
            }
            fx.addProject()
            val client = fx.manager.openSftp(PROJECT)
            val counting = probe.opened.single()

            // The caller's close is held half-way, inside the real client's close.
            probe.closeGate = gate
            val caller = thread { client.close() }
            assertTrue(counting.closeStarted.await(10, TimeUnit.SECONDS))

            val disconnecter = thread { runBlocking { fx.manager.disconnect() } }
            disconnecter.join(500)
            if (!disconnecter.isAlive) {
                assertTrue(counting.closeFinished, "disconnect returned while the client was still closing")
            }

            gate.countDown()
            caller.join(10_000)
            disconnecter.join(10_000)
            assertFalse(caller.isAlive)
            assertFalse(disconnecter.isAlive)
            assertTrue(counting.closeFinished)
            assertEquals(1, counting.closes.get())
            assertEquals(ConnectionState.Idle, fx.manager.state.value)
        } finally {
            gate.countDown()
            managerScope.cancel()
        }
    }

    @Test
    fun `disconnect never returns before a client the caller is closing is closed`() = runTest {
        cleanupWaitsForCallerClose { manager -> runBlocking { manager.disconnect() } }
    }

    @Test
    fun `a replacing connect never returns before a client the caller is closing is closed`() = runTest {
        cleanupWaitsForCallerClose { manager -> manager.connect(profile()) }
    }

    /**
     * The caller's close starts while the manager's [cleanup] is closing another client and holds
     * the resources: the caller has to wait. Whichever side then closes the client, [cleanup] must
     * not return before the real client is closed, and it is closed once.
     */
    private suspend fun TestScope.cleanupWaitsForCallerClose(cleanup: (ConnectionManager) -> Unit) {
        val probe = SftpProbe()
        val managerScope = CoroutineScope(Dispatchers.Default + Job())
        val fx = Fixture(this, wrapConnector = { ProbeConnector(it, probe) }, managerScope = managerScope)
        val gate = CountDownLatch(1)
        try {
            fx.seedSecrets()
            fx.trustServerKey()
            fx.manager.connect(profile())
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) { fx.manager.state.first { it is ConnectionState.Connected } }
            }
            fx.addProject()
            val first = fx.manager.openSftp(PROJECT)
            fx.manager.openSftp(PROJECT)
            val (countingFirst, countingSecond) = probe.opened
            // Cleanup closes newest first: it stops in the second client's close.
            countingSecond.gate = gate
            probe.closeMillis = CLOSE_MILLIS

            val disconnecter = thread { cleanup(fx.manager) }
            assertTrue(countingSecond.closeStarted.await(10, TimeUnit.SECONDS))
            val caller = thread { first.close() }
            caller.awaitBlocked()

            gate.countDown()
            disconnecter.join(10_000)
            assertFalse(disconnecter.isAlive)
            assertTrue(countingFirst.closeFinished, "cleanup returned while the caller's close was still to come")

            caller.join(10_000)
            assertFalse(caller.isAlive)
            assertEquals(listOf(1, 1), probe.opened.map { it.closes.get() })
        } finally {
            gate.countDown()
            managerScope.cancel()
        }
    }

    /** Waits until this thread waits for a lock. */
    private fun Thread.awaitBlocked() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (state != Thread.State.BLOCKED) {
            check(isAlive) { "The thread ended instead of waiting" }
            check(System.nanoTime() < deadline) { "The thread never waited for a lock" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val CLOSE_MILLIS = 200L
        const val PROJECT = "/srv/project"
    }
}
