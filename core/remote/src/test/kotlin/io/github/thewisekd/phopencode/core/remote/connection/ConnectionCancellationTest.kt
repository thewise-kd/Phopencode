package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyCheck
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyUpdate
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import io.github.thewisekd.phopencode.core.api.ServerEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job

/**
 * P9.24: a [CancellationException] that neither the manager's scope nor a stop caused ends the
 * connection with [ConnectionError.Unexpected]; it never closes the manager for good.
 */
class ConnectionCancellationTest {
    /** Runs [fail] instead of the real connect for the first [times] attempts. */
    private class FailingConnector(
        private val real: SshConnector,
        private var times: Int = 1,
        private val fail: suspend () -> Nothing,
    ) : SshConnector {
        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession {
            if (times > 0) {
                times--
                fail()
            }
            return real.connect(target, credential, hostKeyVerifier, options)
        }
    }

    /** Hangs in connect until cancelled. */
    private class HangingConnector : SshConnector {
        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession = awaitCancellation()
    }

    /** A store whose first [check] or [replace] throws what [checkFails] or [replaceFails] makes. */
    private class FailingStore(
        private val real: KnownHostsStore,
        @Volatile var checkFails: (() -> Throwable)? = null,
        @Volatile var replaceFails: (() -> Throwable)? = null,
    ) : KnownHostsStore by real {
        override suspend fun check(endpoint: HostEndpoint, presented: HostKeyFingerprint): HostKeyCheck {
            checkFails?.let { checkFails = null; throw it() }
            return real.check(endpoint, presented)
        }

        override suspend fun replace(
            endpoint: HostEndpoint,
            expected: HostKeyFingerprint,
            replacement: HostKeyFingerprint,
        ): HostKeyUpdate {
            replaceFails?.let { replaceFails = null; throw it() }
            return real.replace(endpoint, expected, replacement)
        }
    }

    private fun Fixture.assertNothingOpen() {
        assertTrue(connector.sessions.all { !it.connected.value }, "a session is still open")
        assertTrue(clients.made.all { it.client.control.isClosed }, "a client is still open")
    }

    private fun Fixture.unexpected(): ConnectionError.Unexpected {
        val error = assertIs<ConnectionState.Error>(manager.state.value, "state: ${manager.state.value}").error
        return assertIs<ConnectionError.Unexpected>(error)
    }

    /** [cause] is the test's stray cancellation, or the copy coroutines' stack trace recovery made. */
    private fun assertStray(cause: Throwable?) {
        assertIs<CancellationException>(cause)
        assertEquals("stray", cause.message)
    }

    /** The manager still works: a new connect reaches Connected. */
    private fun TestScope.assertConnectsAgain(fx: Fixture) {
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    /**
     * Cancels, from inside, the run's own job: the ancestor of the calling coroutine that is a
     * direct child of [managerJob]. Neither a stop nor the end of the scope: a stray.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun cancelRunFromInside(managerJob: Job) {
        val own = currentCoroutineContext().job
        val run = generateSequence(own) { it.parent }.first { it.parent === managerJob }
        run.cancel(CancellationException("stray"))
    }

    /** Nothing a Connected state exposed is reachable any more. */
    private suspend fun Fixture.assertNothingReachable() {
        assertFailsWith<NotConnectedException> { manager.openSftp("/srv") }
        var ran = false
        assertFailsWith<NotConnectedException> { manager.withConnection { ran = true } }
        assertFalse(ran, "the block ran on an ended connection")
    }

    @Test
    fun `a connected run cancelled from inside ends in Unexpected and exposes nothing of its connection`() = runTest {
        val fx = Fixture(this)
        val managerJob = backgroundScope.coroutineContext.job
        fx.clients.events = flow {
            emit(ServerEvent.Connected())
            cancelRunFromInside(managerJob)
            awaitCancellation()
        }
        fx.seedSecrets()
        fx.trustServerKey()

        fx.manager.connect(profile())
        runCurrent()

        assertIs<CancellationException>(fx.unexpected().cause)
        assertTrue(ConnectionState.Closed !in fx.states)
        assertTrue(fx.states.any { it is ConnectionState.Connected }, "the run stood first")
        fx.assertNothingReachable()
        fx.assertNothingOpen()
        fx.clients.events = null
        assertConnectsAgain(fx)
    }

    @Test
    fun `a run cancelled from inside a step ends in Unexpected and the manager stays usable`() = runTest {
        val managerJob = backgroundScope.coroutineContext.job
        val fx = Fixture(this, wrapConnector = { real ->
            FailingConnector(real) {
                cancelRunFromInside(managerJob)
                awaitCancellation()
            }
        })
        fx.seedSecrets()
        fx.trustServerKey()

        fx.manager.connect(profile())
        runCurrent()

        assertIs<CancellationException>(fx.unexpected().cause)
        assertTrue(ConnectionState.Closed !in fx.states)
        fx.assertNothingReachable()
        fx.assertNothingOpen()
        assertConnectsAgain(fx)
    }

    @Test
    fun `a bare cancellation from the SSH step is an Unexpected error and the manager stays usable`() = runTest {
        val stray = CancellationException("stray")
        val fx = Fixture(this, wrapConnector = { FailingConnector(it) { throw stray } })
        fx.seedSecrets()
        fx.trustServerKey()

        fx.manager.connect(profile())
        runCurrent()

        assertSame(stray, fx.unexpected().cause)
        assertTrue(ConnectionState.Closed !in fx.states)
        fx.assertNothingOpen()
        assertConnectsAgain(fx)
    }

    @Test
    fun `a timeout leaking from an inner withTimeout is an Unexpected error, not a close`() = runTest {
        val fx = Fixture(this, wrapConnector = { real ->
            FailingConnector(real) { withTimeout(10.milliseconds) { awaitCancellation() } }
        })
        fx.seedSecrets()
        fx.trustServerKey()

        fx.manager.connect(profile())
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertIs<TimeoutCancellationException>(fx.unexpected().cause)
        assertTrue(ConnectionState.Closed !in fx.states)
        assertConnectsAgain(fx)
    }

    @Test
    fun `a cancellation from the server step is an Unexpected error and the manager stays usable`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        fx.trustServerKey()
        fx.tools.gate = CompletableDeferred<Unit>().also { it.completeExceptionally(CancellationException("stray")) }

        fx.manager.connect(profile())
        runCurrent()

        assertIs<CancellationException>(fx.unexpected().cause)
        fx.assertNothingOpen()
        fx.tools.gate = null
        assertConnectsAgain(fx)
    }

    @Test
    fun `a cancellation from the host key check is an Unexpected error`() = runTest {
        val stray = CancellationException("stray")
        val fx = Fixture(this, wrapKnownHosts = { real -> FailingStore(real, checkFails = { stray }) })
        fx.seedSecrets()
        fx.trustServerKey()

        fx.manager.connect(profile())
        runCurrent()

        assertSame(stray, fx.unexpected().cause)
        fx.assertNothingOpen()
        assertConnectsAgain(fx)
    }

    @Test
    fun `a cancellation from the event watcher while it is active is an Unexpected error, not a close`() = runTest {
        val stray = CancellationException("stray")
        val fx = Fixture(this)
        fx.clients.breakEvents = { stray }
        fx.seedSecrets()
        fx.trustServerKey()

        fx.manager.connect(profile())
        runCurrent()

        assertSame(stray, fx.unexpected().cause)
        assertTrue(ConnectionState.Closed !in fx.states)
        fx.assertNothingOpen()
        advanceTimeBy(60.seconds)
        assertEquals(1, fx.connector.attempts.size, "Unexpected is not retried")
        fx.clients.breakEvents = null
        assertConnectsAgain(fx)
    }

    @Test
    fun `a cancellation from a changed key's store write fails the replace and keeps the manager`() = runTest {
        val stray = CancellationException("stray")
        lateinit var store: FailingStore
        val fx = Fixture(this, wrapKnownHosts = { real -> FailingStore(real).also { store = it } })
        fx.seedSecrets()
        fx.knownHosts.approve(HostEndpoint(HOST, SSH_PORT), HostKeyFingerprint(OTHER_KEY.fingerprintSha256))
        fx.manager.connect(profile())
        runCurrent()
        val shown = assertIs<ConnectionState.Error>(fx.manager.state.value)
        assertIs<ConnectionError.HostKeyChanged>(shown.error)
        store.replaceFails = { stray }

        val outcome = fx.manager.acceptChangedHostKey(shown)
        runCurrent()

        val failed = assertIs<HostKeyReplacement.Failed>(outcome)
        // The write runs in another context, so debug builds may hand over a recovered copy.
        assertStray(assertIs<ConnectionError.Unexpected>(failed.error).cause)
        assertStray(fx.unexpected().cause)
        assertTrue(ConnectionState.Closed !in fx.states)
    }

    @Test
    fun `a real cancel of the manager's scope during a step still closes the manager`() = runTest {
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val fx = Fixture(this, managerScope = managerScope, wrapConnector = { HangingConnector() })
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.Ssh>(fx.manager.state.value)

        managerScope.cancel()
        runCurrent()

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        assertTrue(fx.states.none { it is ConnectionState.Error })
    }

    @Test
    fun `disconnect during a step gives Idle and never an error`() = runTest {
        val fx = Fixture(this, wrapConnector = { HangingConnector() })
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()

        fx.manager.disconnect()
        runCurrent()

        assertEquals(ConnectionState.Idle, fx.manager.state.value)
        assertTrue(fx.states.none { it is ConnectionState.Error || it == ConnectionState.Closed })
    }

    @Test
    fun `disconnect of a standing connection gives Idle and never an error`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)

        fx.manager.disconnect()
        runCurrent()
        advanceTimeBy(60.seconds)

        assertEquals(ConnectionState.Idle, fx.manager.state.value)
        assertTrue(fx.states.none { it is ConnectionState.Error || it == ConnectionState.Closed })
        fx.assertNothingOpen()
    }

    @Test
    fun `a new connect during a step stops the old run without an error`() = runTest {
        val fx = Fixture(this, wrapConnector = { real -> FailingConnector(real) { awaitCancellation() } })
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()

        fx.manager.connect(profile())
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertTrue(fx.states.none { it is ConnectionState.Error || it == ConnectionState.Closed })
    }
}
