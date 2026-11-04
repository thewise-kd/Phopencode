package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyUpdate
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** P4.20: states name the connect they belong to, and host key decisions are bound to the shown request. */
class ConnectionServerTest {
    private val endpoint = HostEndpoint(HOST, SSH_PORT)
    private val otherEndpoint = HostEndpoint(OTHER_HOST, OTHER_PORT)
    private val server = ConnectionServer(profile().id, endpoint)
    private val otherServer = ConnectionServer(otherProfile().id, otherEndpoint)

    private val serverFingerprint = HostKeyFingerprint(SERVER_KEY.fingerprintSha256)
    private val oldFingerprint = HostKeyFingerprint(OTHER_KEY.fingerprintSha256)
    private val thirdFingerprint = HostKeyFingerprint(HostKey("ssh-ed25519", ByteArray(32) { 7 }).fingerprintSha256)

    /**
     * Records every approve and replace, and can hold a replace open inside the store, where a
     * cancellation would reach it if the manager let one.
     */
    private class GatedKnownHosts(private val inner: KnownHostsStore) : KnownHostsStore by inner {
        val replaced = mutableListOf<Triple<HostEndpoint, HostKeyFingerprint, HostKeyFingerprint>>()
        val approvals = mutableListOf<Pair<HostEndpoint, HostKeyFingerprint>>()

        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        val entered = CompletableDeferred<Unit>()

        override suspend fun approve(endpoint: HostEndpoint, fingerprint: HostKeyFingerprint): HostKeyUpdate {
            approvals += endpoint to fingerprint
            return inner.approve(endpoint, fingerprint)
        }

        override suspend fun replace(
            endpoint: HostEndpoint,
            expected: HostKeyFingerprint,
            replacement: HostKeyFingerprint,
        ): HostKeyUpdate {
            replaced += Triple(endpoint, expected, replacement)
            entered.complete(Unit)
            gate?.await()
            return inner.replace(endpoint, expected, replacement)
        }
    }

    /**
     * Holds every known-host write in the window between the caller's checks and the manager's
     * own check before the store call, as a slow thread would, ignoring cancellation. Completes the
     * first deferred once a write is held; completing the second lets it go on.
     */
    private fun Fixture.holdBeforeStoreWrite(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        manager.beforeStoreWrite = {
            entered.complete(Unit)
            withContext(NonCancellable) { gate.await() }
        }
        return entered to gate
    }

    private fun TestScope.gatedFixture(managerScope: CoroutineScope = backgroundScope): Pair<Fixture, GatedKnownHosts> {
        var gated: GatedKnownHosts? = null
        val fx = Fixture(
            this,
            managerScope = managerScope,
            wrapKnownHosts = { inner -> GatedKnownHosts(inner).also { gated = it } },
        )
        fx.connector.addServer(OTHER_TARGET)
        return fx to checkNotNull(gated)
    }

    /** Connects to a server whose approved key is [oldFingerprint], so that it ends in a changed key. */
    private suspend fun TestScope.changedKey(fx: Fixture): ConnectionState.Error {
        fx.seedSecrets()
        fx.knownHosts.approve(endpoint, oldFingerprint)
        fx.manager.connect(profile())
        runCurrent()
        val shown = assertIs<ConnectionState.Error>(fx.manager.state.value)
        assertIs<ConnectionError.HostKeyChanged>(shown.error)
        return shown
    }

    @Test
    fun `a connected state names its profile and the SSH endpoint`() = runTest {
        val (fx) = gatedFixture()
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()

        val connected = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(server, connected.server)
    }

    @Test
    fun `a connect to another profile names the other profile and endpoint`() = runTest {
        val (fx) = gatedFixture()
        fx.seedSecrets()
        fx.trustServerKey()
        fx.knownHosts.approve(otherEndpoint, serverFingerprint)
        fx.manager.connect(profile())
        runCurrent()
        val first = assertIs<ConnectionState.Connected>(fx.manager.state.value)

        fx.manager.connect(otherProfile())
        runCurrent()

        val second = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(server, first.server)
        assertEquals(otherServer, second.server)
        assertEquals(first.generation + 1, second.generation)
        assertEquals(OTHER_TARGET, fx.connector.attempts.last().target)
    }

    @Test
    fun `a reconnect after a drop keeps naming the same profile`() = runTest {
        val (fx) = gatedFixture()
        fx.seedSecrets()
        fx.knownHosts.approve(otherEndpoint, serverFingerprint)
        fx.manager.connect(otherProfile())
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)

        fx.connector.sessions.single().loseConnection()
        runCurrent()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()

        val again = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(otherServer, again.server)
        assertEquals(2L, again.generation)
    }

    @Test
    fun `a host approval names its profile and endpoint`() = runTest {
        val (fx) = gatedFixture()
        fx.seedSecrets()
        fx.manager.connect(otherProfile())
        runCurrent()

        val approval = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)
        assertEquals(otherServer, approval.server)
        assertEquals(SERVER_KEY, approval.key)
    }

    @Test
    fun `a changed host key names its profile and endpoint`() = runTest {
        val (fx) = gatedFixture()
        val shown = changedKey(fx)

        assertEquals(
            ConnectionError.HostKeyChanged(SERVER_KEY, oldFingerprint, server),
            shown.error,
        )
    }

    @Test
    fun `approving the shown request stores the key and connects`() = runTest {
        val (fx) = gatedFixture()
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        val shown = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)

        assertTrue(fx.manager.approveHostKey(shown))
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(serverFingerprint, fx.knownHosts.approved(endpoint))
        assertFalse(fx.manager.approveHostKey(shown), "a request is answered once")
    }

    @Test
    fun `a collector that answers the moment it sees a request is not refused`() = runTest {
        val (fx) = gatedFixture()
        fx.seedSecrets()
        val answers = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fx.manager.state.collect { if (it is ConnectionState.HostApproval) answers += fx.manager.approveHostKey(it) }
        }
        fx.knownHosts.approve(otherEndpoint, oldFingerprint)
        val replies = mutableListOf<HostKeyReplacement>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fx.manager.state.collect { if (it is ConnectionState.Error) replies += fx.manager.acceptChangedHostKey(it) }
        }

        fx.manager.connect(profile())
        runCurrent()
        assertEquals(listOf(true), answers)
        assertIs<ConnectionState.Connected>(fx.manager.state.value)

        fx.manager.connect(otherProfile())
        runCurrent()
        assertEquals(listOf<HostKeyReplacement>(HostKeyReplacement.Replaced), replies)
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `an approval the store fails is false, and the state tells why by then`() = runTest {
        val (fx) = gatedFixture()
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        val shown = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)
        fx.knownHosts.unavailable = true

        var result: Boolean? = null
        var stateOnReturn: ConnectionState? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            result = fx.manager.approveHostKey(shown)
            stateOnReturn = fx.manager.state.value
        }
        runCurrent()

        assertEquals(false, result)
        assertEquals(ConnectionState.Error(ConnectionError.StorageUnavailable), stateOnReturn)
        assertEquals(ConnectionState.Error(ConnectionError.StorageUnavailable), fx.manager.state.value)
        assertEquals(0, fx.connector.credentialsReceived)
    }

    @Test
    fun `an approval the store refuses is false, and the state tells why by then`() = runTest {
        val (fx) = gatedFixture()
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        val shown = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)
        // Another key is approved for the endpoint while the request is shown.
        fx.knownHosts.approve(endpoint, oldFingerprint)

        var result: Boolean? = null
        var stateOnReturn: ConnectionState? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            result = fx.manager.approveHostKey(shown)
            stateOnReturn = fx.manager.state.value
        }
        runCurrent()

        val changed = ConnectionState.Error(ConnectionError.HostKeyChanged(SERVER_KEY, oldFingerprint, server))
        assertEquals(false, result)
        assertEquals(changed, stateOnReturn)
        assertEquals(changed, fx.manager.state.value)
        assertEquals(oldFingerprint, fx.knownHosts.approved(endpoint))
    }

    @Test
    fun `an approval of a request from an earlier connect does nothing`() = runTest {
        val (fx) = gatedFixture()
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        val stale = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)

        // The same key, asked again by a new connect to the same profile.
        fx.manager.connect(profile())
        runCurrent()
        val fresh = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)
        assertEquals(stale, fresh, "the two requests look alike")
        assertNotSame(stale, fresh)

        assertFalse(fx.manager.approveHostKey(stale))
        assertFalse(fx.manager.rejectHostKey(stale))
        runCurrent()
        assertSame(fresh, fx.manager.state.value)
        assertNull(fx.knownHosts.approved(endpoint))

        assertTrue(fx.manager.rejectHostKey(fresh))
        runCurrent()
        assertEquals(
            ConnectionError.HostKeyRejected(SERVER_KEY),
            assertIs<ConnectionState.Error>(fx.manager.state.value).error,
        )
    }

    @Test
    fun `an approval after a disconnect does nothing`() = runTest {
        val (fx) = gatedFixture()
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        val shown = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)

        fx.manager.disconnect()

        assertFalse(fx.manager.approveHostKey(shown))
        assertNull(fx.knownHosts.approved(endpoint))
        assertEquals(ConnectionState.Idle, fx.manager.state.value)
    }

    @Test
    fun `accepting a changed key replaces it at the connection's endpoint and reconnects`() = runTest {
        val (fx, store) = gatedFixture()
        val shown = changedKey(fx)
        val attemptsBefore = fx.connector.attempts.size

        assertEquals(HostKeyReplacement.Replaced, fx.manager.acceptChangedHostKey(shown))
        runCurrent()

        assertEquals(listOf(Triple(endpoint, oldFingerprint, serverFingerprint)), store.replaced)
        assertEquals(serverFingerprint, fx.knownHosts.approved(endpoint))
        val connected = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(server, connected.server)
        assertEquals(TARGET, fx.connector.attempts.drop(attemptsBefore).single().target)
    }

    @Test
    fun `accepting a changed key ignores edits to the profile made meanwhile`() = runTest {
        val (fx, store) = gatedFixture()
        val shown = changedKey(fx)
        // The profile is edited to point elsewhere; the manager is not told, and the decision is
        // about the endpoint the connection used.
        val edited = profile().copy(host = OTHER_HOST, port = OTHER_PORT)
        assertEquals(edited.id, assertIs<ConnectionError.HostKeyChanged>(shown.error).server?.profileId)

        assertEquals(HostKeyReplacement.Replaced, fx.manager.acceptChangedHostKey(shown))
        runCurrent()

        assertEquals(endpoint, store.replaced.single().first)
        assertNull(fx.knownHosts.approved(otherEndpoint), "the edited endpoint is untouched")
        assertTrue(fx.connector.attempts.all { it.target == TARGET })
    }

    @Test
    fun `accepting the same changed key twice replaces once`() = runTest {
        val (fx, store) = gatedFixture()
        val shown = changedKey(fx)

        assertEquals(HostKeyReplacement.Replaced, fx.manager.acceptChangedHostKey(shown))
        assertEquals(HostKeyReplacement.Stale, fx.manager.acceptChangedHostKey(shown))
        runCurrent()

        assertEquals(1, store.replaced.size)
    }

    @Test
    fun `accepting after a disconnect does nothing`() = runTest {
        val (fx, store) = gatedFixture()
        val shown = changedKey(fx)
        fx.manager.disconnect()
        val attemptsBefore = fx.connector.attempts.size

        assertEquals(HostKeyReplacement.Stale, fx.manager.acceptChangedHostKey(shown))
        runCurrent()

        assertTrue(store.replaced.isEmpty())
        assertEquals(oldFingerprint, fx.knownHosts.approved(endpoint))
        assertEquals(ConnectionState.Idle, fx.manager.state.value)
        assertEquals(attemptsBefore, fx.connector.attempts.size)
    }

    @Test
    fun `accepting after a new connect does nothing`() = runTest {
        val (fx, store) = gatedFixture()
        val shown = changedKey(fx)
        fx.knownHosts.approve(otherEndpoint, serverFingerprint)
        fx.manager.connect(otherProfile())
        runCurrent()
        val other = assertIs<ConnectionState.Connected>(fx.manager.state.value)

        assertEquals(HostKeyReplacement.Stale, fx.manager.acceptChangedHostKey(shown))
        runCurrent()

        assertTrue(store.replaced.isEmpty())
        assertEquals(oldFingerprint, fx.knownHosts.approved(endpoint))
        assertSame(other, fx.manager.state.value)
    }

    @Test
    fun `accepting an older changed key does nothing after a newer one`() = runTest {
        val (fx, store) = gatedFixture()
        val older = changedKey(fx)
        fx.manager.connect(profile())
        runCurrent()
        val newer = assertIs<ConnectionState.Error>(fx.manager.state.value)
        assertEquals(older, newer, "the two errors look alike")
        assertNotSame(older, newer)

        assertEquals(HostKeyReplacement.Stale, fx.manager.acceptChangedHostKey(older))
        runCurrent()
        assertTrue(store.replaced.isEmpty())
        assertSame(newer, fx.manager.state.value)

        assertEquals(HostKeyReplacement.Replaced, fx.manager.acceptChangedHostKey(newer))
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `accepting anything but a changed key does nothing`() = runTest {
        val (fx, store) = gatedFixture()
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        assertTrue(fx.manager.rejectHostKey(SERVER_KEY))
        runCurrent()
        val rejected = assertIs<ConnectionState.Error>(fx.manager.state.value)

        assertEquals(HostKeyReplacement.Stale, fx.manager.acceptChangedHostKey(rejected))
        assertTrue(store.replaced.isEmpty())
        assertSame(rejected, fx.manager.state.value)
    }

    @Test
    fun `a key approved meanwhile is not overwritten, and the reconnect shows it`() = runTest {
        val (fx) = gatedFixture()
        val shown = changedKey(fx)
        fx.knownHosts.replace(endpoint, oldFingerprint, thirdFingerprint)

        assertEquals(HostKeyReplacement.Mismatch(thirdFingerprint), fx.manager.acceptChangedHostKey(shown))
        runCurrent()

        assertEquals(thirdFingerprint, fx.knownHosts.approved(endpoint))
        assertEquals(
            ConnectionError.HostKeyChanged(SERVER_KEY, thirdFingerprint, server),
            assertIs<ConnectionState.Error>(fx.manager.state.value).error,
        )
        assertEquals(0, fx.connector.credentialsReceived)
    }

    @Test
    fun `a store failure during the replace is an error`() = runTest {
        val (fx) = gatedFixture()
        val shown = changedKey(fx)
        fx.knownHosts.unavailable = true

        // An unconfined caller resumes the moment the result is known, and must find the state told.
        var result: HostKeyReplacement? = null
        var stateOnReturn: ConnectionState? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            result = fx.manager.acceptChangedHostKey(shown)
            stateOnReturn = fx.manager.state.value
        }
        runCurrent()

        assertEquals(HostKeyReplacement.Failed(ConnectionError.StorageUnavailable), result)
        assertEquals(ConnectionState.Error(ConnectionError.StorageUnavailable), stateOnReturn)
        assertEquals(ConnectionState.Error(ConnectionError.StorageUnavailable), fx.manager.state.value)
    }

    @Test
    fun `a disconnect between the accept's checks and the store call stops the replace`() = runTest {
        val (fx, store) = gatedFixture()
        val shown = changedKey(fx)
        val (held, gate) = fx.holdBeforeStoreWrite()
        val attemptsBefore = fx.connector.attempts.size

        val result = async { fx.manager.acceptChangedHostKey(shown) }
        runCurrent()
        assertTrue(held.isCompleted, "the replace waits just before the store call")

        val disconnected = launch { fx.manager.disconnect() }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(disconnected.isCompleted)
        assertEquals(HostKeyReplacement.Stale, result.await())
        assertTrue(store.replaced.isEmpty(), "no store write")
        assertEquals(oldFingerprint, fx.knownHosts.approved(endpoint))
        assertEquals(ConnectionState.Idle, fx.manager.state.value)
        assertEquals(attemptsBefore, fx.connector.attempts.size, "no reconnect")
    }

    @Test
    fun `a new connect between the accept's checks and the store call stops the replace`() = runTest {
        val (fx, store) = gatedFixture()
        val shown = changedKey(fx)
        fx.knownHosts.approve(otherEndpoint, serverFingerprint)
        val (held, gate) = fx.holdBeforeStoreWrite()

        val result = async { fx.manager.acceptChangedHostKey(shown) }
        runCurrent()
        assertTrue(held.isCompleted)

        fx.manager.connect(otherProfile())
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(HostKeyReplacement.Stale, result.await())
        assertTrue(store.replaced.isEmpty(), "no store write")
        assertEquals(oldFingerprint, fx.knownHosts.approved(endpoint))
        assertEquals(otherServer, assertIs<ConnectionState.Connected>(fx.manager.state.value).server)
    }

    @Test
    fun `a disconnect between the approval and the store call stops the approval`() = runTest {
        val (fx, store) = gatedFixture()
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        val shown = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)
        val (held, gate) = fx.holdBeforeStoreWrite()

        val approved = async { fx.manager.approveHostKey(shown) }
        runCurrent()
        assertTrue(held.isCompleted, "the approval waits just before the store call")

        val disconnected = launch { fx.manager.disconnect() }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(disconnected.isCompleted)
        assertFalse(approved.await())
        assertTrue(store.approvals.isEmpty(), "no store write")
        assertNull(fx.knownHosts.approved(endpoint))
        assertEquals(ConnectionState.Idle, fx.manager.state.value)
        assertEquals(0, fx.connector.credentialsReceived)
    }

    @Test
    fun `a new connect between the approval and the store call stops the approval`() = runTest {
        val (fx, store) = gatedFixture()
        fx.seedSecrets()
        fx.knownHosts.approve(otherEndpoint, serverFingerprint)
        fx.manager.connect(profile())
        runCurrent()
        val shown = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)
        val (held, gate) = fx.holdBeforeStoreWrite()

        val approved = async { fx.manager.approveHostKey(shown) }
        runCurrent()
        assertTrue(held.isCompleted)

        fx.manager.connect(otherProfile())
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertFalse(approved.await())
        assertTrue(store.approvals.isEmpty(), "no store write")
        assertNull(fx.knownHosts.approved(endpoint))
        assertEquals(otherServer, assertIs<ConnectionState.Connected>(fx.manager.state.value).server)
    }

    @Test
    fun `the manager's scope ending between the accept's checks and the store call stops the replace`() = runTest {
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val (fx, store) = gatedFixture(managerScope)
        val shown = changedKey(fx)
        val (held, gate) = fx.holdBeforeStoreWrite()

        val result = async { fx.manager.acceptChangedHostKey(shown) }
        runCurrent()
        assertTrue(held.isCompleted)

        managerScope.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(HostKeyReplacement.Stale, result.await())
        assertTrue(store.replaced.isEmpty(), "no store write")
        assertEquals(oldFingerprint, fx.knownHosts.approved(endpoint))
        assertEquals(ConnectionState.Closed, fx.manager.state.value)
    }

    @Test
    fun `the manager's scope ending between the approval and the store call stops the approval`() = runTest {
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val (fx, store) = gatedFixture(managerScope)
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        val shown = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)
        val (held, gate) = fx.holdBeforeStoreWrite()

        val approved = async { fx.manager.approveHostKey(shown) }
        runCurrent()
        assertTrue(held.isCompleted)

        managerScope.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertFalse(approved.await())
        assertTrue(store.approvals.isEmpty(), "no store write")
        assertNull(fx.knownHosts.approved(endpoint))
        assertEquals(ConnectionState.Closed, fx.manager.state.value)
    }

    @Test
    fun `a disconnect during a replace already in the store waits for it and does not reconnect`() = runTest {
        val (fx, store) = gatedFixture()
        val shown = changedKey(fx)
        store.gate = CompletableDeferred()
        val attemptsBefore = fx.connector.attempts.size

        val result = async { fx.manager.acceptChangedHostKey(shown) }
        runCurrent()
        assertTrue(store.entered.isCompleted, "the store write is under way")

        val disconnected = launch { fx.manager.disconnect() }
        runCurrent()
        assertFalse(disconnected.isCompleted, "the disconnect waits for the write under way")
        assertEquals(oldFingerprint, fx.knownHosts.approved(endpoint))
        store.gate?.complete(Unit)
        runCurrent()

        assertTrue(disconnected.isCompleted)
        // The write started while the request was current, so it stands; nothing follows it.
        assertEquals(HostKeyReplacement.Replaced, result.await())
        assertEquals(serverFingerprint, fx.knownHosts.approved(endpoint))
        assertEquals(ConnectionState.Idle, fx.manager.state.value)
        assertEquals(attemptsBefore, fx.connector.attempts.size, "no reconnect")
    }

    @Test
    fun `an accept racing a disconnect on real threads never replaces after the disconnect or reconnects`() = runTest {
        repeat(RACE_ROUNDS) { round ->
            val managerScope = CoroutineScope(Dispatchers.Default + Job())
            var gated: GatedKnownHosts? = null
            val fx = Fixture(
                this,
                managerScope = managerScope,
                wrapKnownHosts = { inner -> GatedKnownHosts(inner).also { gated = it } },
            )
            try {
                fx.seedSecrets()
                fx.knownHosts.approve(endpoint, oldFingerprint)
                fx.manager.connect(profile())
                val shown = withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) { fx.manager.state.first { it is ConnectionState.Error } }
                } as ConnectionState.Error

                val start = CountDownLatch(1)
                var result: HostKeyReplacement? = null
                var storedAtDisconnect: HostKeyFingerprint? = null
                var attemptsAtDisconnect = -1
                val accepter = thread { start.await(); result = runBlocking { fx.manager.acceptChangedHostKey(shown) } }
                val disconnecter = thread {
                    start.await()
                    runBlocking {
                        fx.manager.disconnect()
                        storedAtDisconnect = fx.knownHosts.approved(endpoint)
                        attemptsAtDisconnect = fx.connector.attempts.size
                    }
                }
                start.countDown()
                accepter.join(10_000)
                disconnecter.join(10_000)
                assertFalse(accepter.isAlive || disconnecter.isAlive, "round $round hung")
                // Give a stale reconnect, if any, time to show itself.
                Thread.sleep(5)

                val stored = fx.knownHosts.approved(endpoint)
                assertEquals(storedAtDisconnect, stored, "round $round: the store changed after the disconnect returned")
                assertEquals(attemptsAtDisconnect, fx.connector.attempts.size, "round $round: a connect after the disconnect")
                assertTrue(gated!!.replaced.size <= 1, "round $round")
                when (result) {
                    HostKeyReplacement.Replaced -> assertEquals(serverFingerprint, stored, "round $round")
                    HostKeyReplacement.Stale -> assertEquals(oldFingerprint, stored, "round $round")
                    else -> error("round $round: unexpected $result")
                }
                assertEquals(ConnectionState.Idle, fx.manager.state.value, "round $round")
            } finally {
                managerScope.cancel()
            }
        }
    }

    @Test
    fun `a new connect during a replace already in the store starts after it`() = runTest {
        val (fx, store) = gatedFixture()
        val shown = changedKey(fx)
        fx.knownHosts.approve(otherEndpoint, serverFingerprint)
        store.gate = CompletableDeferred()

        val result = async { fx.manager.acceptChangedHostKey(shown) }
        runCurrent()
        assertTrue(store.entered.isCompleted)
        val attemptsBefore = fx.connector.attempts.size

        fx.manager.connect(otherProfile())
        runCurrent()
        assertEquals(attemptsBefore, fx.connector.attempts.size, "the new connect waits for the write")
        assertEquals(ConnectionState.Ssh(), fx.manager.state.value)
        store.gate?.complete(Unit)
        runCurrent()

        assertEquals(HostKeyReplacement.Replaced, result.await())
        assertEquals(serverFingerprint, fx.knownHosts.approved(endpoint))
        assertEquals(listOf(OTHER_TARGET), fx.connector.attempts.drop(attemptsBefore).map { it.target }, "no reconnect to the old server")
        assertEquals(otherServer, assertIs<ConnectionState.Connected>(fx.manager.state.value).server)
    }

    private companion object {
        const val RACE_ROUNDS = 200
    }
}
