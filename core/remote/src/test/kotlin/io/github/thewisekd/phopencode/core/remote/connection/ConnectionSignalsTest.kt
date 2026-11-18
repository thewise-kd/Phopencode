package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * P9.40: the signals the app gives [ConnectionManager] through [ConnectionSignals]: the default
 * network coming and going, and the app coming to the foreground.
 */
class ConnectionSignalsTest {
    /** Two networks, as the platform names them; only compared for equality. */
    private val wifi = Any()
    private val mobile = Any()

    /** Holds every SSH connect after [hold] until [release]. */
    private class GatedConnector(private val real: SshConnector) : SshConnector {
        @Volatile
        private var gate: CompletableDeferred<Unit>? = null

        fun hold() {
            gate = CompletableDeferred()
        }

        fun release() {
            gate?.complete(Unit)
            gate = null
        }

        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession {
            gate?.await()
            return real.connect(target, credential, hostKeyVerifier, options)
        }
    }

    private suspend fun TestScope.connected(fx: Fixture): ConnectionState.Connected {
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()
        return assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    /** Connected, then dropped with every retry failing until the attempts run out. */
    private suspend fun TestScope.exhausted(fx: Fixture) {
        connected(fx)
        repeat(3) { fx.connector.failNext(SshException.ConnectionFailed()) }
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(ConnectionState.Error(ConnectionError.Unreachable), fx.manager.state.value)
        assertEquals(4, fx.connector.attempts.size)
    }

    private fun waitingForNetwork(attempt: Int, cause: ConnectionError) = ConnectionState.Reconnecting(
        attempt,
        options.reconnect.delayBefore(attempt),
        cause,
        waitingForNetwork = true,
    )

    private val options = ConnectionOptions(
        reconnect = ReconnectPolicy(delays = listOf(1.seconds, 2.seconds, 4.seconds), maxAttempts = 3),
    )

    @Test
    fun `until told otherwise the network counts as available`() = runTest {
        val fx = Fixture(this)
        connected(fx)

        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.Disconnected), fx.manager.state.value)
        assertFalse(assertIs<ConnectionState.Reconnecting>(fx.manager.state.value).waitingForNetwork)

        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `while the network is gone no attempt is made, and the wait counts no attempt`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.manager.networkLost(wifi)

        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertEquals(waitingForNetwork(1, ConnectionError.Disconnected), fx.manager.state.value)

        advanceTimeBy(30.minutes)
        runCurrent()
        assertEquals(1, fx.connector.attempts.size, "no attempt while offline")
        assertEquals(waitingForNetwork(1, ConnectionError.Disconnected), fx.manager.state.value)

        fx.manager.networkAvailable(wifi)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(2, fx.connector.attempts.size)
        assertEquals(ConnectionState.Ssh(reconnectAttempt = 1), fx.states.takeLast(4).first())
    }

    @Test
    fun `the network going away during the timer turns the wait into a wait for the network`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.Disconnected), fx.manager.state.value)

        fx.manager.networkLost(wifi)
        runCurrent()
        assertEquals(waitingForNetwork(1, ConnectionError.Disconnected), fx.manager.state.value)
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(1, fx.connector.attempts.size)

        fx.manager.networkAvailable(wifi)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `offline stretches between failed attempts never use attempts up`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        repeat(3) { fx.connector.failNext(SshException.ConnectionFailed()) }
        fx.connector.sessions.single().loseConnection()
        runCurrent()

        repeat(5) {
            fx.manager.networkLost(wifi)
            advanceTimeBy(10.minutes)
            runCurrent()
            fx.manager.networkAvailable(wifi)
            runCurrent()
        }

        // Each return of the network made one attempt at once; three failed, the attempts ran out.
        assertEquals(ConnectionState.Error(ConnectionError.Unreachable), fx.manager.state.value)
        assertEquals(4, fx.connector.attempts.size)
    }

    @Test
    fun `an attempt that fails after the network went away is made again, uncounted, once it is back`() = runTest {
        lateinit var gated: GatedConnector
        val fx = Fixture(this, options = options, wrapConnector = { GatedConnector(it).also { gated = it } })
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.connector.sessions.single().loseConnection()
        runCurrent()

        gated.hold()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(ConnectionState.Ssh(reconnectAttempt = 1), fx.manager.state.value)

        fx.manager.networkLost(wifi)
        fx.connector.failNext(SshException.ConnectionFailed())
        gated.release()
        runCurrent()
        assertEquals(waitingForNetwork(1, ConnectionError.Unreachable), fx.manager.state.value)

        fx.manager.networkAvailable(wifi)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertTrue(ConnectionState.Ssh(reconnectAttempt = 2) !in fx.states, "states: ${fx.states}")
    }

    @Test
    fun `a reconnect asked for right after the network went away makes no attempt`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.Disconnected), fx.manager.state.value)

        // Back to back: the request and the loss reach the waiting run together.
        fx.manager.networkLost(wifi)
        fx.manager.reconnect()
        runCurrent()
        assertEquals(waitingForNetwork(1, ConnectionError.Disconnected), fx.manager.state.value)
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(1, fx.connector.attempts.size, "no attempt while offline")

        fx.manager.networkAvailable(wifi)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(ConnectionState.Ssh(reconnectAttempt = 1), fx.states.takeLast(4).first())
    }

    @Test
    fun `a timer that runs out right after the network went away makes no attempt`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.connector.sessions.single().loseConnection()
        runCurrent()

        advanceTimeBy(1.seconds - 1.milliseconds)
        fx.manager.networkLost(wifi)
        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertEquals(waitingForNetwork(1, ConnectionError.Disconnected), fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `a quick loss and return of the network during the timer starts the next attempt at once`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.Disconnected), fx.manager.state.value)

        // Back to back, with no yield: the waiting run never sees the network gone.
        fx.manager.networkLost(wifi)
        fx.manager.networkAvailable(wifi)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(2, fx.connector.attempts.size)
    }

    /**
     * Runs [signal] once, inline, the moment the manager publishes the first [ConnectionState.Reconnecting]:
     * after the publish and before the run has started its wait.
     */
    private fun TestScope.onFirstReconnecting(fx: Fixture, signal: () -> Unit) {
        var done = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fx.manager.state.collect {
                if (it is ConnectionState.Reconnecting && !done) {
                    done = true
                    signal()
                }
            }
        }
    }

    @Test
    fun `a quick loss and return right after the wait is published starts the next attempt at once`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        onFirstReconnecting(fx) {
            fx.manager.networkLost(wifi)
            fx.manager.networkAvailable(wifi)
        }

        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(2, fx.connector.attempts.size)
    }

    @Test
    fun `a switch right after the wait is published starts the next attempt at once`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        onFirstReconnecting(fx) { fx.manager.networkAvailable(mobile) }

        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `the foreground right after the wait is published starts the next attempt at once`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        onFirstReconnecting(fx) { fx.manager.appForegrounded() }

        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `a loss right after the wait is published makes no attempt`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        onFirstReconnecting(fx) { fx.manager.networkLost(wifi) }

        fx.connector.sessions.single().loseConnection()
        runCurrent()
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(waitingForNetwork(1, ConnectionError.Disconnected), fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `a foreground while the attempts ran out and the network is gone makes no attempt until it is back`() = runTest {
        val fx = Fixture(this)
        exhausted(fx)
        fx.manager.networkAvailable(wifi)

        // Back to back: lost, foreground, and a quick visit of the network that ends offline.
        fx.manager.networkLost(wifi)
        fx.manager.appForegrounded()
        fx.manager.networkAvailable(wifi)
        fx.manager.networkLost(wifi)
        runCurrent()
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(waitingForNetwork(1, ConnectionError.Unreachable), fx.manager.state.value)
        assertEquals(4, fx.connector.attempts.size)

        fx.manager.networkAvailable(wifi)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `the wait for the network never misses a quick return`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.manager.networkLost(wifi)
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertEquals(waitingForNetwork(1, ConnectionError.Disconnected), fx.manager.state.value)

        // Back to back: a short visit ends offline again, so still no attempt.
        fx.manager.networkAvailable(wifi)
        fx.manager.networkLost(wifi)
        runCurrent()
        assertEquals(waitingForNetwork(1, ConnectionError.Disconnected), fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)

        // Back to back: lost again, then back for good.
        fx.manager.networkAvailable(mobile)
        fx.manager.networkLost(mobile)
        fx.manager.networkAvailable(mobile)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(2, fx.connector.attempts.size)
    }

    @Test
    fun `a new network while waiting on the timer starts the next attempt at once`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)

        fx.manager.networkAvailable(mobile)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `the same network again while waiting on the timer changes nothing`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.connector.sessions.single().loseConnection()
        runCurrent()

        fx.manager.networkAvailable(wifi)
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.Disconnected), fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `coming to the foreground while reconnecting starts the next attempt at once`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.connector.failNext(SshException.ConnectionFailed())
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(2, 2.seconds, ConnectionError.Unreachable), fx.manager.state.value)

        fx.manager.appForegrounded()
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(3, fx.connector.attempts.size)
    }

    @Test
    fun `coming to the foreground while the network is gone makes no attempt`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.manager.networkLost(wifi)
        fx.connector.sessions.single().loseConnection()
        runCurrent()

        fx.manager.appForegrounded()
        runCurrent()
        assertEquals(waitingForNetwork(1, ConnectionError.Disconnected), fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)

        // Nor does the foreground signal linger: the network's return makes one attempt, which stands.
        fx.manager.networkAvailable(wifi)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(2, fx.connector.attempts.size)
    }

    @Test
    fun `coming to the foreground after the retries ran out reconnects with a fresh attempt count`() = runTest {
        val fx = Fixture(this)
        exhausted(fx)

        fx.manager.appForegrounded()
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(5, fx.connector.attempts.size)
        assertEquals(ConnectionState.Ssh(reconnectAttempt = 1), fx.states.takeLast(4).first())
    }

    @Test
    fun `the reconnect from the foreground has its own full set of attempts`() = runTest {
        val fx = Fixture(this)
        exhausted(fx)
        repeat(3) { fx.connector.failNext(SshException.ConnectionFailed()) }

        fx.manager.appForegrounded()
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(2, 2.seconds, ConnectionError.Unreachable), fx.manager.state.value)
        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.Unreachable), fx.manager.state.value)
        assertEquals(7, fx.connector.attempts.size)
        fx.assertNothingOpen()
    }

    @Test
    fun `repeated foreground signals start one reconnect`() = runTest {
        lateinit var gated: GatedConnector
        val fx = Fixture(this, options = options, wrapConnector = { GatedConnector(it).also { gated = it } })
        exhausted(fx)
        gated.hold()

        repeat(3) {
            fx.manager.appForegrounded()
            runCurrent()
        }
        assertEquals(ConnectionState.Ssh(reconnectAttempt = 1), fx.manager.state.value)

        gated.release()
        runCurrent()
        fx.manager.appForegrounded()
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(5, fx.connector.attempts.size)
        assertEquals(2, fx.connector.sessions.size, "one session from the foreground")
    }

    @Test
    fun `retries that ran out while the network is gone resume as a wait for the network`() = runTest {
        val fx = Fixture(this)
        exhausted(fx)
        fx.manager.networkAvailable(wifi)
        fx.manager.networkLost(wifi)

        fx.manager.appForegrounded()
        runCurrent()
        assertEquals(waitingForNetwork(1, ConnectionError.Unreachable), fx.manager.state.value)
        assertEquals(4, fx.connector.attempts.size)

        fx.manager.networkAvailable(wifi)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(5, fx.connector.attempts.size)
    }

    @Test
    fun `the foreground never reconnects after a disconnect`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.disconnect()

        fx.manager.appForegrounded()
        fx.manager.networkAvailable(wifi)
        fx.manager.networkAvailable(mobile)
        runCurrent()
        assertEquals(ConnectionState.Idle, fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `the foreground never reconnects after a disconnect while waiting for the network`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.manager.networkLost(wifi)
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        fx.manager.disconnect()

        fx.manager.networkAvailable(wifi)
        fx.manager.appForegrounded()
        runCurrent()
        advanceTimeBy(1.minutes)
        assertEquals(ConnectionState.Idle, fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `the foreground never retries an error retrying cannot help`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.clients.failures += ApiError.Unauthorized
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(ConnectionState.Error(ConnectionError.Api(ApiError.Unauthorized)), fx.manager.state.value)

        fx.manager.appForegrounded()
        runCurrent()
        assertEquals(ConnectionState.Error(ConnectionError.Api(ApiError.Unauthorized)), fx.manager.state.value)
        assertEquals(2, fx.connector.attempts.size)
    }

    @Test
    fun `the foreground never retries a first connect that never stood`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        fx.trustServerKey()
        fx.connector.failNext(SshException.ConnectionFailed())
        fx.manager.connect(profile())
        runCurrent()
        assertEquals(ConnectionState.Error(ConnectionError.Unreachable), fx.manager.state.value)

        fx.manager.appForegrounded()
        runCurrent()
        assertEquals(ConnectionState.Error(ConnectionError.Unreachable), fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `the foreground never retries a host key decision`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        assertTrue(fx.manager.rejectHostKey(SERVER_KEY))
        runCurrent()
        assertEquals(ConnectionState.Error(ConnectionError.HostKeyRejected(SERVER_KEY)), fx.manager.state.value)

        fx.manager.appForegrounded()
        runCurrent()
        assertEquals(ConnectionState.Error(ConnectionError.HostKeyRejected(SERVER_KEY)), fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `the foreground never reconnects after a close`() = runTest {
        val fx = Fixture(this)
        exhausted(fx)
        fx.manager.close()

        fx.manager.appForegrounded()
        fx.manager.networkAvailable(mobile)
        runCurrent()
        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        assertEquals(4, fx.connector.attempts.size)
    }

    @Test
    fun `the foreground leaves a standing connection alone`() = runTest {
        val fx = Fixture(this)
        val first = connected(fx)

        fx.manager.appForegrounded()
        runCurrent()
        assertEquals(first, fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `the first network callback after registering never reconnects`() = runTest {
        val fx = Fixture(this)
        val first = connected(fx)

        fx.manager.networkAvailable(wifi)
        fx.manager.networkAvailable(wifi)
        runCurrent()
        assertEquals(first, fx.manager.state.value)
        assertTrue(fx.connector.sessions.single().connected.value)
    }

    @Test
    fun `a change to another network while connected reconnects once`() = runTest {
        val fx = Fixture(this)
        val first = connected(fx)
        fx.manager.networkAvailable(wifi)

        fx.manager.networkAvailable(mobile)
        fx.manager.networkAvailable(mobile)
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.Disconnected), fx.manager.state.value)
        assertFalse(fx.connector.sessions.single().connected.value)

        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        val second = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(first.generation + 1, second.generation)
        fx.manager.networkAvailable(mobile)
        runCurrent()
        assertEquals(second, fx.manager.state.value)
        assertEquals(1, fx.states.count { it is ConnectionState.Reconnecting })
    }

    @Test
    fun `losing the network while connected disconnects nothing, and its return on the same network neither`() = runTest {
        val fx = Fixture(this)
        val first = connected(fx)
        fx.manager.networkAvailable(wifi)

        fx.manager.networkLost(wifi)
        runCurrent()
        advanceTimeBy(1.minutes)
        assertEquals(first, fx.manager.state.value)
        assertTrue(fx.connector.sessions.single().connected.value)

        fx.manager.networkAvailable(wifi)
        runCurrent()
        assertEquals(first, fx.manager.state.value)
    }

    @Test
    fun `a switch through a lost network to another one while connected reconnects`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)

        fx.manager.networkLost(wifi)
        fx.manager.networkAvailable(mobile)
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.Disconnected), fx.manager.state.value)
    }

    @Test
    fun `the late loss of a network that is no longer the default is ignored`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.manager.networkAvailable(mobile)
        runCurrent()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)

        fx.manager.networkLost(wifi)
        fx.connector.sessions.last().loseConnection()
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.Disconnected), fx.manager.state.value)
    }

    @Test
    fun `network signals while idle start nothing`() = runTest {
        val fx = Fixture(this)
        fx.manager.networkAvailable(wifi)
        fx.manager.networkLost(wifi)
        fx.manager.networkAvailable(mobile)
        fx.manager.appForegrounded()
        runCurrent()
        assertEquals(ConnectionState.Idle, fx.manager.state.value)
        assertEquals(0, fx.connector.attempts.size)
    }

    @Test
    fun `a first connect while the network is known gone still tries once and fails as before`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.networkAvailable(wifi)
        fx.manager.networkLost(wifi)
        fx.connector.failNext(SshException.ConnectionFailed())

        fx.manager.connect(profile())
        runCurrent()
        assertEquals(ConnectionState.Error(ConnectionError.Unreachable), fx.manager.state.value)
    }

    @Test
    fun `signal-driven ends record and log no address or user`() = runTest {
        val logged = mutableListOf<Throwable>()
        val fx = Fixture(this, diagnostics = Diagnostics(enabled = true), endLog = { logged += it })
        connected(fx)
        fx.manager.networkAvailable(wifi)
        fx.manager.networkAvailable(mobile)
        runCurrent()
        fx.manager.networkLost(mobile)
        runCurrent()
        fx.manager.networkAvailable(mobile)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)

        assertTrue(logged.isNotEmpty())
        val text = (logged.map { it.toString() } + fx.states.map { it.toString() }).joinToString("\n")
        listOf(HOST, USER, SSH_PASSWORD, OPENCODE_PASSWORD).forEach { assertFalse(it in text, it) }
    }

    private fun Fixture.assertNothingOpen() {
        assertTrue(connector.sessions.all { !it.connected.value }, "a session is still open")
        assertTrue(clients.made.all { it.client.control.isClosed }, "a client is still open")
    }
}
