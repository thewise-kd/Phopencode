package io.github.thewisekd.phopencode.feature.connect.logic.progress

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.remote.detect.BinaryLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class DefaultProgressPresenterTest {
    private suspend fun TestScope.ready(): ProgressFixture {
        val fx = ProgressFixture(this)
        fx.seed()
        fx.trust()
        return fx
    }

    @Test
    fun `nothing starts in the constructor`() = runTest {
        val fx = ready()
        val presenter = fx.presenter()

        assertEquals(ProgressState(), presenter.state.value)
        assertTrue(fx.connector.attempts.isEmpty())
        assertEquals(ConnectionState.Idle, fx.connectionState)
        assertNull(fx.activeServer.id.value)
    }

    @Test
    fun `nothing starts in the constructor on an immediate dispatcher`() = runTest {
        val fx = ready()
        // Like Dispatchers.Main.immediate on the main thread: a launch runs at once, a yield dispatches.
        val queue = StandardTestDispatcher(testScheduler)
        val immediate = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

            override fun dispatch(context: CoroutineContext, block: Runnable) = queue.dispatch(context, block)
        }
        val scope = CoroutineScope(fx.screenScope().coroutineContext + immediate)
        val presenter = fx.presenter(scope)

        assertEquals(ProgressState(), presenter.state.value)
        assertTrue(fx.connector.attempts.isEmpty())

        runCurrent()
        assertEquals(ProgressStep.Connected, presenter.state.value.step)
    }

    @Test
    fun `connects to a running server and says so once`() = runTest {
        val fx = ready()
        val presenter = fx.presenter()
        val events = fx.events(presenter)

        runCurrent()

        assertIs<ConnectionState.Connected>(fx.connectionState)
        val state = presenter.state.value
        assertEquals("Test server", state.serverName)
        assertEquals(ProgressStep.Connected, state.step)
        assertNull(state.error)
        assertEquals(emptySet(), state.actions)
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Connected), events)
        assertEquals(SERVER_ID, fx.activeServer.id.value)
        assertTrue(fx.tools.starts.isEmpty(), "a running server is never started again")
        assertEquals(listOf(OPENCODE_PASSWORD), fx.clients.passwords)
    }

    @Test
    fun `starts a server when none runs`() = runTest {
        val fx = ready()
        fx.tools.server = ServerLookup.NotRunning()
        val presenter = fx.presenter()
        val events = fx.events(presenter)

        runCurrent()

        assertEquals(1, fx.tools.starts.size)
        assertEquals(ProgressStep.Connected, presenter.state.value.step)
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Connected), events)
    }

    @Test
    fun `a running server that does not answer is waited for, not started next to`() = runTest {
        val fx = ready()
        fx.tools.server = ServerLookup.Unresponsive("127.0.0.1", 4096, 100, ServerSource.ProcessScan)
        val presenter = fx.presenter()
        val events = fx.events(presenter)

        runCurrent()

        val state = presenter.state.value
        assertEquals(ProgressError.ServerUnresponsive, state.error)
        assertEquals(ProgressStep.Server, state.step, "the step that failed")
        assertEquals(setOf(ProgressAction.Retry), state.actions)
        assertTrue(fx.tools.starts.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a missing opencode binary fails at the server step`() = runTest {
        val fx = ready()
        fx.tools.server = ServerLookup.NotRunning()
        fx.tools.binary = BinaryLookup.Missing
        val presenter = fx.presenter()

        runCurrent()

        assertEquals(ProgressError.OpencodeMissing, presenter.state.value.error)
        assertEquals(ProgressStep.Server, presenter.state.value.step)
    }

    @Test
    fun `a new host key opens the host key screen once and goes on once approved`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed()
        val presenter = fx.presenter()
        val events = fx.events(presenter)

        runCurrent()

        assertEquals(ProgressStep.HostApproval, presenter.state.value.step)
        assertNull(presenter.state.value.error)
        assertEquals(listOf<ProgressEvent>(ProgressEvent.OpenHostKey), events)

        assertTrue(fx.manager.approveHostKey(SERVER_KEY))
        runCurrent()

        assertEquals(ProgressStep.Connected, presenter.state.value.step)
        assertEquals(listOf(ProgressEvent.OpenHostKey, ProgressEvent.Connected), events)
    }

    @Test
    fun `a changed host key offers a review`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed()
        fx.trust(OTHER_KEY)
        val presenter = fx.presenter()
        val events = fx.events(presenter)

        runCurrent()

        val state = presenter.state.value
        assertEquals(ProgressError.HostKeyChanged, state.error)
        assertEquals(ProgressStep.Ssh, state.step)
        assertTrue(ProgressAction.ReviewHostKey in state.actions)
        assertTrue(events.isEmpty())

        presenter.onIntent(ProgressIntent.ReviewHostKey)
        runCurrent()
        assertEquals(listOf<ProgressEvent>(ProgressEvent.OpenHostKey), events)
    }

    @Test
    fun `a missing login secret offers the server form`() = runTest {
        val fx = ProgressFixture(this)
        fx.profiles.save(profile())
        fx.trust()
        val presenter = fx.presenter()
        val events = fx.events(presenter)

        runCurrent()

        assertEquals(ProgressError.SecretMissing, presenter.state.value.error)
        assertTrue(ProgressAction.EditServer in presenter.state.value.actions)

        presenter.onIntent(ProgressIntent.EditServer)
        runCurrent()
        assertEquals(listOf<ProgressEvent>(ProgressEvent.OpenServerForm), events)
    }

    @Test
    fun `retry connects again with the profile as saved now`() = runTest {
        val fx = ProgressFixture(this)
        fx.profiles.save(profile())
        fx.trust()
        val presenter = fx.presenter()
        val events = fx.events(presenter)
        runCurrent()
        assertEquals(ProgressError.SecretMissing, presenter.state.value.error)

        fx.seed()
        presenter.onIntent(ProgressIntent.Retry)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(ProgressStep.Connected, presenter.state.value.step)
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Connected), events)
    }

    @Test
    fun `retry does nothing while a connect runs`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed()
        val presenter = fx.presenter()
        runCurrent()
        assertEquals(ProgressStep.HostApproval, presenter.state.value.step)

        presenter.onIntent(ProgressIntent.Retry)
        runCurrent()

        assertEquals(1, fx.connector.attempts.size)
        assertIs<ConnectionState.HostApproval>(fx.connectionState)
    }

    @Test
    fun `a missing server cannot connect`() = runTest {
        val fx = ProgressFixture(this)
        val presenter = fx.presenter()
        val events = fx.events(presenter)

        runCurrent()

        assertEquals(ProgressError.InvalidProfile, presenter.state.value.error)
        assertEquals(emptySet(), presenter.state.value.actions)
        assertTrue(fx.connector.attempts.isEmpty())
        assertEquals(ConnectionState.Idle, fx.connectionState)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `unreachable profile storage can be retried`() = runTest {
        val fx = ready()
        fx.profiles.unavailable = true
        val presenter = fx.presenter()
        runCurrent()

        assertEquals(ProgressError.StorageUnavailable, presenter.state.value.error)
        assertEquals(setOf(ProgressAction.Retry), presenter.state.value.actions)
        assertTrue(fx.connector.attempts.isEmpty())

        fx.profiles.unavailable = false
        presenter.onIntent(ProgressIntent.Retry)
        runCurrent()
        assertEquals(ProgressStep.Connected, presenter.state.value.step)
    }

    @Test
    fun `an unknown server password is asked for`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed(profile(opencodeCredential = null))
        fx.trust()
        val presenter = fx.presenter()
        runCurrent()

        val state = presenter.state.value
        assertEquals(ProgressError.ServerPasswordUnknown, state.error)
        assertTrue(state.askingServerPassword)
        assertEquals(setOf(ProgressAction.EnterServerPassword, ProgressAction.Retry), state.actions)
        assertTrue(fx.tools.starts.isEmpty(), "a foreign server is never replaced")

        presenter.onIntent(ProgressIntent.DismissServerPassword)
        runCurrent()
        assertFalse(presenter.state.value.askingServerPassword)
        assertEquals(ProgressError.ServerPasswordUnknown, presenter.state.value.error)

        presenter.onIntent(ProgressIntent.EnterServerPassword)
        runCurrent()
        assertTrue(presenter.state.value.askingServerPassword)
    }

    @Test
    fun `a submitted password is stored under a fresh reference and the connect goes on`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed(profile(opencodeCredential = null))
        fx.trust()
        fx.clients.serverPassword = OTHER_PASSWORD
        val presenter = fx.presenter()
        val events = fx.events(presenter)
        runCurrent()
        assertTrue(presenter.state.value.askingServerPassword)

        presenter.onIntent(ProgressIntent.SubmitServerPassword(OTHER_PASSWORD))
        runCurrent()

        val ref = SecretRef("new-ref-1")
        assertEquals(ref, fx.storedProfile()?.opencodeCredential)
        assertEquals(OTHER_PASSWORD, fx.storedText(ref))
        val state = presenter.state.value
        assertFalse(state.askingServerPassword)
        assertNull(state.error)
        assertEquals(ProgressStep.Connected, state.step)
        assertFalse(OTHER_PASSWORD in state.toString())
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Connected), events)
        assertEquals(OTHER_PASSWORD, fx.clients.passwords.last())
    }

    @Test
    fun `a rejected password is asked for again and the old one is replaced`() = runTest {
        val fx = ready()
        fx.clients.serverPassword = OTHER_PASSWORD
        val presenter = fx.presenter()
        runCurrent()

        assertEquals(ProgressError.ServerPasswordRejected, presenter.state.value.error)
        assertTrue(presenter.state.value.askingServerPassword)
        assertEquals(ProgressStep.Api, presenter.state.value.step)

        presenter.onIntent(ProgressIntent.SubmitServerPassword(OTHER_PASSWORD))
        runCurrent()

        assertEquals(ProgressStep.Connected, presenter.state.value.step)
        assertEquals(SecretRef("new-ref-1"), fx.storedProfile()?.opencodeCredential)
        // Whether anything else still refers to it cannot be told here (the key index is not at
        // hand), so the replaced secret is kept, at worst as an orphan.
        assertEquals(OPENCODE_PASSWORD, fx.storedText(OPENCODE_REF))
    }

    @Test
    fun `a replaced credential that the ssh login shares is kept and the login still works`() = runTest {
        val fx = ProgressFixture(this)
        val shared = profile().copy(opencodeCredential = SSH_REF)
        // One secret for both: the ssh password, which the server refuses as its own.
        fx.seed(shared, opencodePassword = null)
        fx.trust()
        fx.clients.serverPassword = OTHER_PASSWORD
        val presenter = fx.presenter()
        runCurrent()
        assertEquals(ProgressError.ServerPasswordRejected, presenter.state.value.error)

        presenter.onIntent(ProgressIntent.SubmitServerPassword(OTHER_PASSWORD))
        runCurrent()

        assertEquals(ProgressStep.Connected, presenter.state.value.step)
        assertEquals(SSH_PASSWORD, fx.storedText(SSH_REF))

        val again = fx.presenter()
        runCurrent()
        assertEquals(ProgressStep.Connected, again.state.value.step, "the ssh login still works")
    }

    @Test
    fun `a submitted password is wiped once stored, while the connect still waits`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed(profile(opencodeCredential = null))
        fx.trust()
        val capturing = CapturingSecrets(fx.secrets)
        val presenter = fx.presenter(secretStore = capturing)
        runCurrent()
        assertTrue(presenter.state.value.askingServerPassword)
        // The next connect stops at host approval and stays there.
        fx.knownHosts.delete(HostEndpoint(HOST, ServerProfile.DEFAULT_SSH_PORT))

        presenter.onIntent(ProgressIntent.SubmitServerPassword(OTHER_PASSWORD))
        runCurrent()

        assertIs<ConnectionState.HostApproval>(fx.connectionState)
        val given = capturing.received.single()
        assertFailsWith<IllegalStateException> { given.revealBytes() }
        assertEquals(OTHER_PASSWORD, fx.storedText(SecretRef("new-ref-1")))
    }

    @Test
    fun `a submitted password is wiped when it cannot be stored`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed(profile(opencodeCredential = null))
        fx.trust()
        val capturing = CapturingSecrets(fx.secrets)
        val presenter = fx.presenter(secretStore = capturing)
        runCurrent()

        fx.secrets.unavailable = true
        presenter.onIntent(ProgressIntent.SubmitServerPassword(OTHER_PASSWORD))
        runCurrent()
        fx.secrets.unavailable = false

        assertEquals(ProgressError.StorageUnavailable, presenter.state.value.error)
        assertFailsWith<IllegalStateException> { capturing.received.single().revealBytes() }
    }

    @Test
    fun `the password of a server this app started is never deleted`() = runTest {
        val fx = ready()
        val started = ServerResolver.startedPasswordRef(profile())
        fx.profiles.save(profile(opencodeCredential = started))
        SecretValue.ofText(OPENCODE_PASSWORD).use { fx.secrets.put(started, it) }
        fx.clients.serverPassword = OTHER_PASSWORD
        val presenter = fx.presenter()
        runCurrent()
        assertEquals(ProgressError.ServerPasswordRejected, presenter.state.value.error)

        presenter.onIntent(ProgressIntent.SubmitServerPassword(OTHER_PASSWORD))
        runCurrent()

        assertEquals(ProgressStep.Connected, presenter.state.value.step)
        assertEquals(OPENCODE_PASSWORD, fx.storedText(started))
    }

    @Test
    fun `a password that cannot be stored leaves the profile as it was`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed(profile(opencodeCredential = null))
        fx.trust()
        val presenter = fx.presenter()
        runCurrent()
        assertTrue(presenter.state.value.askingServerPassword)
        val entries = fx.secrets.entryCount

        fx.secrets.unavailable = true
        presenter.onIntent(ProgressIntent.SubmitServerPassword(OTHER_PASSWORD))
        runCurrent()
        fx.secrets.unavailable = false

        assertEquals(ProgressError.StorageUnavailable, presenter.state.value.error)
        assertTrue(ProgressAction.EnterServerPassword in presenter.state.value.actions)
        assertNull(fx.storedProfile()?.opencodeCredential)
        assertEquals(entries, fx.secrets.entryCount)
        assertEquals(1, fx.connector.attempts.size, "no new connect")
    }

    @Test
    fun `a profile that cannot be saved takes its new password back`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed(profile(opencodeCredential = null))
        fx.trust()
        val presenter = fx.presenter()
        runCurrent()
        val entries = fx.secrets.entryCount

        fx.profileStore.failSaves = true
        presenter.onIntent(ProgressIntent.SubmitServerPassword(OTHER_PASSWORD))
        runCurrent()

        assertEquals(ProgressError.StorageUnavailable, presenter.state.value.error)
        assertEquals(entries, fx.secrets.entryCount)
        assertNull(fx.storedText(SecretRef("new-ref-1")))
        assertNull(fx.storedProfile()?.opencodeCredential)
        assertEquals(1, fx.connector.attempts.size, "no new connect")
    }

    @Test
    fun `an empty password is not submitted`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed(profile(opencodeCredential = null))
        fx.trust()
        val presenter = fx.presenter()
        runCurrent()

        presenter.onIntent(ProgressIntent.SubmitServerPassword(""))
        runCurrent()

        assertTrue(presenter.state.value.askingServerPassword)
        assertNull(fx.storedProfile()?.opencodeCredential)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `cancel disconnects and closes`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed()
        val presenter = fx.presenter()
        val events = fx.events(presenter)
        runCurrent()
        assertIs<ConnectionState.HostApproval>(fx.connectionState)

        presenter.onIntent(ProgressIntent.Cancel)
        runCurrent()

        assertEquals(ConnectionState.Idle, fx.connectionState)
        assertEquals(listOf(ProgressEvent.OpenHostKey, ProgressEvent.Close), events)
        assertTrue(fx.connector.sessions.none { it.connected.value })
    }

    @Test
    fun `a connection that stood outlives the screen`() = runTest {
        val fx = ready()
        val scope = fx.screenScope()
        fx.presenter(scope)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.connectionState)

        scope.cancel()
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.connectionState)
        assertEquals(SERVER_ID, fx.activeServer.id.value)
    }

    @Test
    fun `an unfinished connect ends with the screen`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed()
        val scope = fx.screenScope()
        fx.presenter(scope)
        runCurrent()
        assertIs<ConnectionState.HostApproval>(fx.connectionState)

        scope.cancel()
        runCurrent()

        assertEquals(ConnectionState.Idle, fx.connectionState)
    }

    @Test
    fun `a screen that ends late leaves a newer screen's connect alone`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed()
        val oldScope = fx.screenScope()
        fx.presenter(oldScope)
        runCurrent()
        assertIs<ConnectionState.HostApproval>(fx.connectionState)

        val newer = fx.presenter()
        runCurrent()
        assertEquals(2, fx.connector.attempts.size)
        oldScope.cancel()
        runCurrent()

        assertIs<ConnectionState.HostApproval>(fx.connectionState)
        assertTrue(fx.manager.approveHostKey(SERVER_KEY))
        runCurrent()
        assertEquals(ProgressStep.Connected, newer.state.value.step)
    }

    @Test
    fun `a cancel whose disconnect never got to run still leaves nothing open`() = runTest {
        val fx = ProgressFixture(this)
        fx.seed()
        val scope = fx.screenScope()
        val presenter = fx.presenter(scope)
        runCurrent()
        assertIs<ConnectionState.HostApproval>(fx.connectionState)

        presenter.onIntent(ProgressIntent.Cancel)
        // The screen goes away before the cancel's coroutine is dispatched.
        scope.cancel()
        runCurrent()

        assertEquals(ConnectionState.Idle, fx.connectionState)
        assertEquals(0, fx.connector.halfOpenConnections)
        assertTrue(fx.connector.sessions.none { it.connected.value })
    }

    @Test
    fun `a screen that ends before it starts connects nothing`() = runTest {
        val fx = ready()
        val scope = fx.screenScope()
        fx.presenter(scope)

        scope.cancel()
        runCurrent()

        assertTrue(fx.connector.attempts.isEmpty())
        assertEquals(ConnectionState.Idle, fx.connectionState)
    }

    @Test
    fun `a retry cut short by the screen ending leaves nothing open`() = runTest {
        val fx = ProgressFixture(this)
        fx.profiles.save(profile())
        fx.trust()
        val scope = fx.screenScope()
        val presenter = fx.presenter(scope)
        runCurrent()
        assertEquals(ProgressError.SecretMissing, presenter.state.value.error)
        fx.seed()

        presenter.onIntent(ProgressIntent.Retry)
        scope.cancel()
        runCurrent()

        assertTrue(fx.connector.sessions.none { it.connected.value })
        assertEquals(0, fx.connector.halfOpenConnections)
        assertFalse(fx.connectionState is ConnectionState.Connected)
    }

    @Test
    fun `an old screen ending while a newer one connects never drops the newer connect`() = runTest {
        for (gateFirst in listOf(true, false)) {
            val fx = ProgressFixture(this)
            fx.seed()
            val oldScope = fx.screenScope()
            fx.presenter(oldScope)
            runCurrent()
            assertIs<ConnectionState.HostApproval>(fx.connectionState)

            val gated = GatedProfiles(fx.profileStore)
            val newer = fx.presenter(profiles = gated)
            val events = fx.events(newer)
            runCurrent()
            assertEquals(1, fx.connector.attempts.size, "the newer screen waits at the gate")

            if (gateFirst) gated.gate.complete(Unit)
            oldScope.cancel()
            if (!gateFirst) gated.gate.complete(Unit)
            runCurrent()

            assertEquals(2, fx.connector.attempts.size, "gateFirst=$gateFirst")
            assertIs<ConnectionState.HostApproval>(fx.connectionState, "gateFirst=$gateFirst")
            assertEquals(ProgressStep.HostApproval, newer.state.value.step)
            assertTrue(fx.manager.approveHostKey(SERVER_KEY))
            runCurrent()
            assertEquals(ProgressStep.Connected, newer.state.value.step, "gateFirst=$gateFirst")
            assertEquals(listOf(ProgressEvent.OpenHostKey, ProgressEvent.Connected), events)
        }
    }

    /**
     * A newer presenter takes the manager over while the old one is between showing a step and
     * sending its event: a collector of the old state, run inline, opens the newer one's gate, and
     * the newer one, on a dispatcher that runs at once, connects before the old one goes on.
     */
    private suspend fun TestScope.takeOverAt(step: ProgressStep, trusted: Boolean): List<ProgressEvent> {
        val fx = ProgressFixture(this)
        fx.seed()
        if (trusted) fx.trust()
        val queue = StandardTestDispatcher(testScheduler)
        val immediate = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

            override fun dispatch(context: CoroutineContext, block: Runnable) = queue.dispatch(context, block)
        }
        val gated = GatedProfiles(fx.profileStore)
        val newer = fx.presenter(CoroutineScope(fx.screenScope().coroutineContext + immediate), profiles = gated)
        runCurrent()
        assertTrue(fx.connector.attempts.isEmpty(), "the newer screen waits at the gate")

        val old = fx.presenter()
        val oldEvents = fx.events(old)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            old.state.first { it.step == step }
            gated.gate.complete(Unit)
        }
        runCurrent()

        assertTrue(gated.gate.isCompleted, "the old screen reached $step")
        assertEquals(2, fx.connector.attempts.size, "the newer screen connected")
        assertTrue(newer.state.value.error == null)
        return oldEvents
    }

    @Test
    fun `a screen taken over before it sends asks for no host key`() = runTest {
        assertEquals(emptyList(), takeOverAt(ProgressStep.HostApproval, trusted = false))
    }

    @Test
    fun `a screen taken over before it sends says nothing of a connection`() = runTest {
        assertEquals(emptyList(), takeOverAt(ProgressStep.Connected, trusted = true))
    }

    @Test
    fun `cancel drops a standing connection whichever cleanup runs first`() = runTest {
        for (cancelFirst in listOf(true, false)) {
            val fx = ready()
            val scope = fx.screenScope()
            val presenter = fx.presenter(scope)
            val events = fx.events(presenter)
            runCurrent()
            assertIs<ConnectionState.Connected>(fx.connectionState)

            if (cancelFirst) {
                presenter.onIntent(ProgressIntent.Cancel)
                scope.cancel()
            } else {
                // The scope's cleanup is queued before the cancel's.
                scope.cancel()
                presenter.onIntent(ProgressIntent.Cancel)
            }
            runCurrent()

            assertEquals(ConnectionState.Idle, fx.connectionState, "cancelFirst=$cancelFirst")
            assertTrue(fx.connector.sessions.none { it.connected.value }, "cancelFirst=$cancelFirst")
            assertEquals(listOf(ProgressEvent.Connected, ProgressEvent.Close), events, "cancelFirst=$cancelFirst")

            // Disconnected once: a connect made now is not dropped by the other cleanup.
            fx.manager.connect(profile())
            runCurrent()
            assertIs<ConnectionState.Connected>(fx.connectionState, "cancelFirst=$cancelFirst")
        }
    }

    @Test
    fun `a screen that never connected leaves the connection alone`() = runTest {
        val fx = ready()
        fx.presenter()
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.connectionState)

        val scope = fx.screenScope()
        fx.presenter(scope, serverId = ServerProfileId("missing"))
        runCurrent()
        scope.cancel()
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.connectionState)
    }

    @Test
    fun `an older connection's state is not taken for this one`() = runTest {
        val fx = ready()
        val other = ServerProfileId("server-2")
        fx.profiles.save(profile(id = other))
        val first = fx.presenter(serverId = other)
        runCurrent()
        assertEquals(ProgressStep.Connected, first.state.value.step)

        val presenter = fx.presenter()
        val events = fx.events(presenter)
        runCurrent()

        assertEquals(listOf<ProgressEvent>(ProgressEvent.Connected), events)
        assertEquals(SERVER_ID, fx.activeServer.id.value)
        assertEquals(2, fx.connector.attempts.size, "this screen made its own connection")
    }

    @Test
    fun `a closed manager is reported`() = runTest {
        val fx = ready()
        fx.manager.close()
        val presenter = fx.presenter()
        runCurrent()

        assertEquals(ProgressError.Unexpected, presenter.state.value.error)
        assertEquals(emptySet(), presenter.state.value.actions)
    }
}
