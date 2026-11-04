package io.github.thewisekd.phopencode.feature.connect.logic.hostkey

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionError
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.remote.connection.ServerTools
import io.github.thewisekd.phopencode.core.remote.detect.BinaryLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.start.ServerStartOptions
import io.github.thewisekd.phopencode.core.remote.start.StartedServer
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshConnector
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeProfileRepository
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyRequest
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultHostKeyPresenterTest {
    private val host = "ssh.example"
    private val port = 2222
    private val endpoint = HostEndpoint(host, port)
    private val secretRef = SecretRef("login")

    /** Made-up keys: their fingerprints belong to no real server. */
    private val serverKey = HostKey("ssh-ed25519", ByteArray(32) { it.toByte() })
    private val serverFingerprint = HostKeyFingerprint(serverKey.fingerprintSha256)
    private val oldFingerprint = HostKeyFingerprint(HostKey("ssh-ed25519", ByteArray(32) { 9 }).fingerprintSha256)
    private val thirdFingerprint = HostKeyFingerprint(HostKey("ssh-ed25519", ByteArray(32) { 5 }).fingerprintSha256)

    private val profile = ServerProfile(
        id = ServerProfileId("server-1"),
        name = "Test server",
        host = host,
        port = port,
        username = "tester",
        auth = SshAuth.Password(secretRef),
    )

    private val profiles = FakeProfileRepository()
    private val secrets = FakeSecretStore()
    private val knownHosts = FakeKnownHostsStore()
    private val connector = FakeSshConnector(serverKey).apply { addServer(SshTarget(host, port, "tester")) }

    /** The server step never ends, so a connection that got past the host key stays there. */
    private val tools = object : ServerTools {
        override suspend fun findServer(session: SshSession): ServerLookup = awaitCancellation()

        override suspend fun findBinary(session: SshSession): BinaryLookup = awaitCancellation()

        override suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer =
            awaitCancellation()
    }

    private lateinit var manager: ConnectionManager

    private suspend fun TestScope.setUp(storeProfile: Boolean = true) {
        SecretValue.ofText("test-" + "login").use { secrets.put(secretRef, it) }
        if (storeProfile) profiles.save(profile)
        manager = ConnectionManager(
            connector = connector,
            secrets = secrets,
            knownHosts = knownHosts,
            servers = ServerResolver(secrets, tools),
            clients = OpencodeClientFactory { _, _, _ -> error("No client in this test") },
            scope = backgroundScope,
        )
    }

    private fun TestScope.presenter() = DefaultHostKeyPresenter(
        serverId = profile.id,
        connection = manager,
        knownHosts = knownHosts,
        profiles = profiles,
        scope = backgroundScope,
    )

    private fun TestScope.collectEvents(presenter: DefaultHostKeyPresenter): List<HostKeyEvent> {
        val events = mutableListOf<HostKeyEvent>()
        backgroundScope.launch { presenter.events.collect { events += it } }
        return events
    }

    /** Every error the presenter showed, in order; recorded as each state is published. */
    private fun TestScope.collectErrors(presenter: DefaultHostKeyPresenter): List<HostKeyError> {
        val errors = mutableListOf<HostKeyError>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            presenter.state.collect { state -> state.error?.let { if (errors.lastOrNull() != it) errors += it } }
        }
        return errors
    }

    /** Connects and waits until the manager asks about the new key. */
    private fun TestScope.connectToNewKey() {
        manager.connect(profile)
        runCurrent()
        assertIs<ConnectionState.HostApproval>(manager.state.value)
    }

    /** Connects to a server whose approved key differs from the one it presents. */
    private suspend fun TestScope.connectToChangedKey() {
        knownHosts.approve(endpoint, oldFingerprint)
        manager.connect(profile)
        runCurrent()
        val error = assertIs<ConnectionState.Error>(manager.state.value).error
        assertIs<ConnectionError.HostKeyChanged>(error)
    }

    private val firstSeen = HostKeyRequest.FirstSeen("ssh-ed25519", serverFingerprint)
    private val changed = HostKeyRequest.Changed("ssh-ed25519", serverFingerprint, oldFingerprint)

    @Test
    fun `nothing runs in the constructor, even on an immediate dispatcher`() = runTest {
        setUp()
        connectToNewKey()
        // Like Dispatchers.Main.immediate on the main thread: a launch runs at once, a yield dispatches.
        val queue = StandardTestDispatcher(testScheduler)
        val immediate = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

            override fun dispatch(context: CoroutineContext, block: Runnable) = queue.dispatch(context, block)
        }
        val presenter = DefaultHostKeyPresenter(
            serverId = profile.id,
            connection = manager,
            knownHosts = knownHosts,
            profiles = profiles,
            scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]) + immediate),
        )

        assertEquals(HostKeyState(), presenter.state.value)
        runCurrent()
        assertEquals(firstSeen, presenter.state.value.request)
    }

    @Test
    fun `waits while the connection is still logging in`() = runTest {
        setUp()
        connector.silent = true
        manager.connect(profile)
        val presenter = presenter()
        runCurrent()

        assertEquals(HostKeyState(), presenter.state.value)
    }

    @Test
    fun `a key seen for the first time asks for approval`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        runCurrent()

        assertEquals(HostKeyState(loading = false, request = firstSeen), presenter.state.value)
    }

    @Test
    fun `a key that appears after the screen opened is shown`() = runTest {
        setUp()
        val presenter = presenter()
        runCurrent()
        connectToNewKey()
        runCurrent()

        assertEquals(firstSeen, presenter.state.value.request)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `approving stores the key and lets the connection go on`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        presenter.onIntent(HostKeyIntent.Approve)
        runCurrent()

        assertEquals(serverFingerprint, knownHosts.approved(endpoint))
        assertIs<ConnectionState.Server>(manager.state.value)
        assertEquals(listOf<HostKeyEvent>(HostKeyEvent.Close), events)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `rejecting stores nothing and stops the connection`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        presenter.onIntent(HostKeyIntent.Reject)
        runCurrent()

        assertNull(knownHosts.approved(endpoint))
        val error = assertIs<ConnectionState.Error>(manager.state.value).error
        assertEquals(ConnectionError.HostKeyRejected(serverKey), error)
        assertEquals(listOf<HostKeyEvent>(HostKeyEvent.Close), events)
    }

    @Test
    fun `accepting a changed key is not possible for a first seen key`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        presenter.onIntent(HostKeyIntent.AcceptChangedKey)
        runCurrent()

        assertNull(knownHosts.approved(endpoint))
        assertIs<ConnectionState.HostApproval>(manager.state.value)
        assertEquals(firstSeen, presenter.state.value.request)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a changed key stops the connection and shows both fingerprints`() = runTest {
        setUp()
        connectToChangedKey()
        val presenter = presenter()
        runCurrent()

        assertEquals(HostKeyState(loading = false, request = changed), presenter.state.value)
        assertEquals(oldFingerprint, knownHosts.approved(endpoint))
        assertEquals(1, connector.attempts.size)
    }

    @Test
    fun `approving a changed key replaces nothing`() = runTest {
        setUp()
        connectToChangedKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        presenter.onIntent(HostKeyIntent.Approve)
        runCurrent()

        assertEquals(oldFingerprint, knownHosts.approved(endpoint))
        assertEquals(1, connector.attempts.size)
        assertIs<ConnectionState.Error>(manager.state.value)
        assertEquals(changed, presenter.state.value.request)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `rejecting a changed key keeps the connection stopped`() = runTest {
        setUp()
        connectToChangedKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        presenter.onIntent(HostKeyIntent.Reject)
        runCurrent()

        assertEquals(oldFingerprint, knownHosts.approved(endpoint))
        assertEquals(1, connector.attempts.size)
        assertIs<ConnectionState.Error>(manager.state.value)
        assertEquals(listOf<HostKeyEvent>(HostKeyEvent.Close), events)
    }

    @Test
    fun `accepting a changed key replaces it and connects again`() = runTest {
        setUp()
        connectToChangedKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        presenter.onIntent(HostKeyIntent.AcceptChangedKey)
        runCurrent()

        assertEquals(serverFingerprint, knownHosts.approved(endpoint))
        assertEquals(2, connector.attempts.size)
        assertIs<ConnectionState.Server>(manager.state.value)
        assertEquals(listOf<HostKeyEvent>(HostKeyEvent.Close), events)
    }

    @Test
    fun `a request ended by a disconnect shows no request`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        runCurrent()

        backgroundScope.launch { manager.disconnect() }
        runCurrent()

        assertEquals(HostKeyState(loading = false, error = HostKeyError.NoRequest), presenter.state.value)
    }

    @Test
    fun `an approval sent as a disconnect lands is not applied`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        backgroundScope.launch { manager.disconnect() }
        presenter.onIntent(HostKeyIntent.Approve)
        runCurrent()

        assertNull(knownHosts.approved(endpoint))
        assertEquals(ConnectionState.Idle, manager.state.value)
        assertEquals(HostKeyError.NoRequest, presenter.state.value.error)
        assertNull(presenter.state.value.request)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `an approval never applies to a later request for the same key`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        val errors = collectErrors(presenter)
        runCurrent()

        // The user approves what is shown; before that runs, a new connection asks again.
        presenter.onIntent(HostKeyIntent.Approve)
        manager.connect(profile)
        runCurrent()

        assertNull(knownHosts.approved(endpoint))
        assertIs<ConnectionState.HostApproval>(manager.state.value)
        assertEquals(listOf(HostKeyError.NoRequest), errors)
        assertTrue(events.isEmpty())

        // The new request is shown without the error about the old one, and approving it works.
        assertEquals(firstSeen, presenter.state.value.request)
        assertNull(presenter.state.value.error)
        presenter.onIntent(HostKeyIntent.Approve)
        runCurrent()
        assertEquals(serverFingerprint, knownHosts.approved(endpoint))
        assertEquals(listOf<HostKeyEvent>(HostKeyEvent.Close), events)
    }

    @Test
    fun `a connection that asks nothing gives no request`() = runTest {
        setUp()
        val presenter = presenter()
        runCurrent()

        assertEquals(HostKeyState(loading = false, error = HostKeyError.NoRequest), presenter.state.value)

        presenter.onIntent(HostKeyIntent.DismissError)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `one decision at a time`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        presenter.onIntent(HostKeyIntent.Approve)
        assertTrue(presenter.state.value.deciding)
        presenter.onIntent(HostKeyIntent.Reject)
        runCurrent()

        assertEquals(serverFingerprint, knownHosts.approved(endpoint))
        assertIs<ConnectionState.Server>(manager.state.value)
        assertEquals(listOf<HostKeyEvent>(HostKeyEvent.Close), events)
    }

    @Test
    fun `the state prints no fingerprint`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        runCurrent()

        assertFalse(presenter.state.value.toString().contains(serverFingerprint.sha256))
    }

    @Test
    fun `a key approved meanwhile is not replaced`() = runTest {
        setUp()
        connectToChangedKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        val errors = collectErrors(presenter)
        runCurrent()
        knownHosts.replace(endpoint, oldFingerprint, thirdFingerprint)

        presenter.onIntent(HostKeyIntent.AcceptChangedKey)
        runCurrent()

        assertEquals(thirdFingerprint, knownHosts.approved(endpoint))
        assertEquals(listOf(HostKeyError.Superseded), errors)
        assertFalse(presenter.state.value.deciding)
        assertTrue(events.isEmpty())
        // The manager connected again and found the key changed against the newer one: that is a new
        // request, never the old one.
        assertEquals(2, connector.attempts.size)
        assertEquals(HostKeyRequest.Changed("ssh-ed25519", serverFingerprint, thirdFingerprint), presenter.state.value.request)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `accepting replaces the key of the endpoint the connection used, not an edited profile's`() = runTest {
        setUp()
        val otherEndpoint = HostEndpoint("other.example", port)
        knownHosts.approve(otherEndpoint, oldFingerprint)
        connectToChangedKey()
        val presenter = presenter()
        runCurrent()
        profiles.save(profile.copy(host = "other.example"))

        presenter.onIntent(HostKeyIntent.AcceptChangedKey)
        runCurrent()

        assertEquals(serverFingerprint, knownHosts.approved(endpoint))
        assertEquals(oldFingerprint, knownHosts.approved(otherEndpoint))
    }

    @Test
    fun `a storage failure on replace clears the request`() = runTest {
        setUp()
        connectToChangedKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()
        knownHosts.unavailable = true

        presenter.onIntent(HostKeyIntent.AcceptChangedKey)
        runCurrent()

        assertEquals(HostKeyState(loading = false, error = HostKeyError.StorageFailed), presenter.state.value)
        assertEquals(ConnectionError.StorageUnavailable, assertIs<ConnectionState.Error>(manager.state.value).error)
        assertEquals(1, connector.attempts.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a replace that finds the request stale clears it`() = runTest {
        setUp()
        connectToChangedKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        // The disconnect lands before the decision reaches the manager.
        backgroundScope.launch { manager.disconnect() }
        presenter.onIntent(HostKeyIntent.AcceptChangedKey)
        runCurrent()

        assertEquals(oldFingerprint, knownHosts.approved(endpoint))
        assertEquals(1, connector.attempts.size)
        assertEquals(ConnectionState.Idle, manager.state.value)
        assertEquals(HostKeyState(loading = false, error = HostKeyError.NoRequest), presenter.state.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a request for another profile is not shown`() = runTest {
        setUp()
        val other = profile.copy(id = ServerProfileId("server-2"))
        profiles.save(other)
        manager.connect(other)
        runCurrent()
        assertIs<ConnectionState.HostApproval>(manager.state.value)
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        assertEquals(HostKeyState(loading = false, error = HostKeyError.NoRequest), presenter.state.value)
        presenter.onIntent(HostKeyIntent.Approve)
        runCurrent()
        assertNull(knownHosts.approved(endpoint))
        assertIs<ConnectionState.HostApproval>(manager.state.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a changed key for another profile is not shown`() = runTest {
        setUp()
        val other = profile.copy(id = ServerProfileId("server-2"))
        knownHosts.approve(endpoint, oldFingerprint)
        manager.connect(other)
        runCurrent()
        val presenter = presenter()
        runCurrent()

        assertNull(presenter.state.value.request)
        presenter.onIntent(HostKeyIntent.AcceptChangedKey)
        runCurrent()
        assertEquals(oldFingerprint, knownHosts.approved(endpoint))
    }

    @Test
    fun `rejecting a superseded changed key does not close`() = runTest {
        setUp()
        connectToChangedKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        val errors = collectErrors(presenter)
        runCurrent()

        // A new connection supersedes the request before the rejection runs.
        manager.connect(profile)
        presenter.onIntent(HostKeyIntent.Reject)
        runCurrent()

        assertTrue(events.isEmpty())
        assertEquals(listOf(HostKeyError.NoRequest), errors)
        assertEquals(2, connector.attempts.size)
        // The new connection's request is shown, without the error about the old one.
        assertEquals(changed, presenter.state.value.request)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `rejecting a superseded first seen key does not close`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()

        backgroundScope.launch { manager.disconnect() }
        presenter.onIntent(HostKeyIntent.Reject)
        runCurrent()

        assertTrue(events.isEmpty())
        assertEquals(HostKeyState(loading = false, error = HostKeyError.NoRequest), presenter.state.value)
    }

    @Test
    fun `a failed approval clears the obsolete request`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        val events = collectEvents(presenter)
        runCurrent()
        knownHosts.unavailable = true

        presenter.onIntent(HostKeyIntent.Approve)
        runCurrent()

        assertTrue(events.isEmpty())
        assertIs<ConnectionState.Error>(manager.state.value)
        assertEquals(HostKeyState(loading = false, error = HostKeyError.StorageFailed), presenter.state.value)
    }

    @Test
    fun `a first seen key arriving after no request is shown without the error`() = runTest {
        setUp()
        val presenter = presenter()
        runCurrent()
        assertEquals(HostKeyError.NoRequest, presenter.state.value.error)

        connectToNewKey()
        runCurrent()

        assertEquals(HostKeyState(loading = false, request = firstSeen), presenter.state.value)
    }

    @Test
    fun `a changed key arriving after no request is shown without the error`() = runTest {
        setUp()
        val presenter = presenter()
        runCurrent()
        assertEquals(HostKeyError.NoRequest, presenter.state.value.error)

        connectToChangedKey()
        runCurrent()

        assertEquals(HostKeyState(loading = false, request = changed), presenter.state.value)
    }

    @Test
    fun `a storage error stays when a new request arrives`() = runTest {
        setUp()
        connectToNewKey()
        val presenter = presenter()
        runCurrent()
        knownHosts.unavailable = true
        presenter.onIntent(HostKeyIntent.Approve)
        runCurrent()
        assertEquals(HostKeyError.StorageFailed, presenter.state.value.error)

        knownHosts.unavailable = false
        connectToNewKey()
        runCurrent()

        assertEquals(HostKeyState(loading = false, request = firstSeen, error = HostKeyError.StorageFailed), presenter.state.value)
    }
}
