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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

class DefaultProgressPresenterTest {
    private suspend fun TestScope.ready(): ProgressFixture {
        val fx = ProgressFixture(this)
        fx.seed()
        fx.trust()
        return fx
    }

    @Test
    fun `nothing starts in the constructor`() = runProgressTest {
        val fx = ready()
        val presenter = fx.presenter()

        assertEquals(ProgressState(), presenter.state.value)
        assertTrue(fx.connector.attempts.isEmpty())
        assertEquals(ConnectionState.Idle, fx.connectionState)
        assertNull(fx.activeServer.id.value)
    }

    @Test
    fun `nothing starts in the constructor on an immediate dispatcher`() = runProgressTest {
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
    fun `connects to a running server and says so once`() = runProgressTest {
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
    fun `starts a server when none runs`() = runProgressTest {
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
    fun `a running server that does not answer is waited for, not started next to`() = runProgressTest {
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
    fun `no server on the profile's port names the port and offers the server form`() = runProgressTest(timeout = 10.seconds) {
        val fx = ProgressFixture(this)
        fx.seed(profile(opencodePort = 4096))
        fx.trust()
        fx.tools.server = ServerLookup.NotOnPort(4096, setOf(1200L))
        val presenter = fx.presenter()
        val events = fx.events(presenter)

        runCurrent()

        val state = presenter.state.value
        assertEquals(ProgressError.ServerNotOnPort, state.error)
        assertEquals(4096, state.errorPort)
        assertEquals(ProgressStep.Server, state.step)
        assertEquals(setOf(ProgressAction.EditServer, ProgressAction.Retry), state.actions)
        assertEquals(listOf<Int?>(4096), fx.tools.ports)
        assertTrue(fx.tools.starts.isEmpty(), "nothing is started next to a running server")
        assertTrue(events.isEmpty())

        presenter.onIntent(ProgressIntent.Retry)
        runCurrent()
        assertEquals(ProgressError.ServerNotOnPort, presenter.state.value.error)
        fx.tools.server = ServerLookup.Running("127.0.0.1", 4096, 3400, ServerSource.ProcessScan)
        presenter.onIntent(ProgressIntent.Retry)
        runCurrent()
        assertNull(presenter.state.value.error)
        assertNull(presenter.state.value.errorPort, "the port goes with the error")
    }

    @Test
    fun `with a port and no server running, one is started on that port`() = runProgressTest(timeout = 10.seconds) {
        val fx = ProgressFixture(this)
        fx.seed(profile(opencodePort = 4096))
        fx.trust()
        fx.tools.server = ServerLookup.NotRunning()
        val presenter = fx.presenter()

        runCurrent()

        assertEquals(listOf<Int?>(4096), fx.tools.starts.map { it.port })
        assertEquals(ProgressStep.Connected, presenter.state.value.step)
    }

    @Test
    fun `a missing opencode binary fails at the server step`() = runProgressTest {
        val fx = ready()
        fx.tools.server = ServerLookup.NotRunning()
        fx.tools.binary = BinaryLookup.Missing
        val presenter = fx.presenter()

        runCurrent()

        assertEquals(ProgressError.OpencodeMissing, presenter.state.value.error)
        assertEquals(ProgressStep.Server, presenter.state.value.step)
    }

    @Test
    fun `a new host key opens the host key screen once and goes on once approved`() = runProgressTest {
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
    fun `a changed host key offers a review`() = runProgressTest {
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

    /** A presenter stopped on a changed host key: the approved key is [OTHER_KEY], the server shows [SERVER_KEY]. */
    private suspend fun TestScope.atChangedKey(scope: CoroutineScope? = null): Pair<ProgressFixture, DefaultProgressPresenter> {
        val fx = ProgressFixture(this)
        fx.seed()
        fx.trust(OTHER_KEY)
        val presenter = if (scope == null) fx.presenter() else fx.presenter(scope)
        runCurrent()
        assertEquals(ProgressError.HostKeyChanged, presenter.state.value.error)
        assertEquals(1, fx.connector.attempts.size)
        return fx to presenter
    }

    @Test
    fun `an accepted changed host key is followed to the connection`() = runProgressTest {
        val (fx, presenter) = atChangedKey()
        val events = fx.events(presenter)
        val states = fx.states(presenter)

        fx.acceptChangedKey()
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.connectionState)
        val state = presenter.state.value
        assertEquals(ProgressStep.Connected, state.step)
        assertNull(state.error)
        assertEquals(emptySet(), state.actions)
        assertNull(state.debugDetail)
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Connected), events)
        assertEquals(2, fx.connector.attempts.size, "the accepted key's reconnect only")
        val reconnect = states.dropWhile { it.error != null }
        assertTrue(reconnect.isNotEmpty() && reconnect.all { it.error == null && it.actions.isEmpty() })
        assertTrue(
            reconnect.any { it.step == ProgressStep.Ssh || it.step == ProgressStep.Server || it.step == ProgressStep.Api },
            "the reconnect's steps show: $reconnect",
        )

        presenter.onIntent(ProgressIntent.Retry)
        runCurrent()
        assertEquals(2, fx.connector.attempts.size, "nothing to retry once connected")
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Connected), events, "sent exactly once")
    }

    @Test
    fun `a failed reconnect after an accepted host key shows its own error`() = runProgressTest {
        val (fx, presenter) = atChangedKey()
        val events = fx.events(presenter)
        fx.tools.server = ServerLookup.Unresponsive("127.0.0.1", 4096, 100, ServerSource.ProcessScan)

        fx.acceptChangedKey()
        runCurrent()

        val state = presenter.state.value
        assertEquals(ProgressError.ServerUnresponsive, state.error)
        assertEquals(ProgressStep.Server, state.step)
        assertEquals(setOf(ProgressAction.Retry), state.actions)
        assertTrue(events.isEmpty())

        // That error is retried as any other: one connect, which now stands.
        fx.tools.server = ServerLookup.Running("127.0.0.1", 4096, 100, ServerSource.ProcessScan)
        presenter.onIntent(ProgressIntent.Retry)
        runCurrent()
        assertEquals(3, fx.connector.attempts.size)
        assertEquals(ProgressStep.Connected, presenter.state.value.step)
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Connected), events)
    }

    @Test
    fun `retry while the accepted key's reconnect runs connects no second time`() = runProgressTest {
        val (fx, presenter) = atChangedKey()
        val events = fx.events(presenter)
        fx.connector.silent = true

        fx.acceptChangedKey()
        // Before the presenter saw the reconnect start: its state still shows the error.
        assertEquals(ProgressError.HostKeyChanged, presenter.state.value.error)
        presenter.onIntent(ProgressIntent.Retry)
        runCurrent()

        assertEquals(2, fx.connector.attempts.size, "no connect besides the accepted key's")
        assertEquals(ProgressStep.Ssh, presenter.state.value.step)
        assertNull(presenter.state.value.error)

        // And after it saw it.
        presenter.onIntent(ProgressIntent.Retry)
        runCurrent()
        assertEquals(2, fx.connector.attempts.size)

        fx.connector.silent = false
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(2, fx.connector.attempts.size)
        assertEquals(ProgressError.Timeout, presenter.state.value.error)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `retry while the changed key is unaccepted connects once and the key can still be accepted`() = runProgressTest {
        val (fx, presenter) = atChangedKey()
        val events = fx.events(presenter)

        presenter.onIntent(ProgressIntent.Retry)
        runCurrent()

        assertEquals(2, fx.connector.attempts.size)
        assertEquals(ProgressError.HostKeyChanged, presenter.state.value.error)

        fx.acceptChangedKey()
        runCurrent()

        assertEquals(3, fx.connector.attempts.size)
        assertEquals(ProgressStep.Connected, presenter.state.value.step)
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Connected), events)
    }

    @Test
    fun `cancel during the accepted key's reconnect disconnects once`() = runProgressTest {
        val (fx, presenter) = atChangedKey()
        val events = fx.events(presenter)
        fx.connector.silent = true
        fx.acceptChangedKey()
        runCurrent()
        assertEquals(ProgressStep.Ssh, presenter.state.value.step)

        presenter.onIntent(ProgressIntent.Cancel)
        runCurrent()
        fx.awaitEvent(events, ProgressEvent.Close)

        assertEquals(ConnectionState.Idle, fx.connectionState)
        assertEquals(0, fx.connector.halfOpenConnections)
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Close), events)

        // Disconnected once: a connect made now is not dropped.
        fx.connector.silent = false
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.connectionState)
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Close), events)
    }

    @Test
    fun `leaving the screen at a changed key leaves the error, and during its reconnect ends it`() = runProgressTest {
        for (accepted in listOf(false, true)) {
            val fx = ProgressFixture(this)
            val scope = fx.screenScope()
            fx.seed()
            fx.trust(OTHER_KEY)
            fx.presenter(scope)
            runCurrent()
            assertIs<ConnectionState.Error>(fx.connectionState)
            if (accepted) {
                fx.connector.silent = true
                fx.acceptChangedKey()
                runCurrent()
            }

            scope.cancel()
            runCurrent()
            fx.awaitEnded(scope)

            if (accepted) {
                assertEquals(ConnectionState.Idle, fx.connectionState, "an unfinished connect ends with the screen")
                assertEquals(0, fx.connector.halfOpenConnections)
            } else {
                assertIs<ConnectionState.Error>(fx.connectionState, "nothing was open to disconnect")
            }
        }
    }

    @Test
    fun `a connection that stood after an accepted key outlives the screen`() = runProgressTest {
        val fx = ProgressFixture(this)
        val scope = fx.screenScope()
        fx.seed()
        fx.trust(OTHER_KEY)
        fx.presenter(scope)
        runCurrent()
        fx.acceptChangedKey()
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.connectionState)

        scope.cancel()
        runCurrent()
        fx.awaitEnded(scope)

        assertIs<ConnectionState.Connected>(fx.connectionState)
    }

    @Test
    fun `a screen taken over at a changed key follows no reconnect of the newer one`() = runProgressTest {
        val (fx, old) = atChangedKey()
        val oldEvents = fx.events(old)
        val oldState = old.state.value

        val newer = fx.presenter()
        val newerEvents = fx.events(newer)
        runCurrent()
        assertEquals(ProgressError.HostKeyChanged, newer.state.value.error)
        fx.acceptChangedKey()
        runCurrent()

        assertEquals(ProgressStep.Connected, newer.state.value.step)
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Connected), newerEvents)
        assertEquals(oldState, old.state.value, "the old screen shows nothing of the newer connect")
        assertTrue(oldEvents.isEmpty())

        // Its Cancel leaves the newer connection alone.
        old.onIntent(ProgressIntent.Cancel)
        runCurrent()
        fx.awaitEvent(oldEvents, ProgressEvent.Close)
        assertIs<ConnectionState.Connected>(fx.connectionState)
        assertEquals(listOf<ProgressEvent>(ProgressEvent.Close), oldEvents)
    }

    @Test
    fun `no address, user or fingerprint shows in the state around an accepted key`() = runProgressTest {
        val (fx, presenter) = atChangedKey()
        val states = fx.states(presenter)
        fx.acceptChangedKey()
        runCurrent()

        val secrets = listOf(HOST, USER, SERVER_KEY.fingerprintSha256, OTHER_KEY.fingerprintSha256)
        for (state in states) {
            for (text in listOf(state.toString(), state.serverName, state.debugDetail.orEmpty())) {
                for (secret in secrets) assertFalse(secret in text, "state shows a host detail: $state")
            }
        }
    }

    @Test
    fun `a missing login secret offers the server form`() = runProgressTest {
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
    fun `retry connects again with the profile as saved now`() = runProgressTest {
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
    fun `retry does nothing while a connect runs`() = runProgressTest {
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
    fun `a missing server cannot connect`() = runProgressTest {
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
    fun `unreachable profile storage can be retried`() = runProgressTest {
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
    fun `an unknown server password is asked for`() = runProgressTest {
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
    fun `a submitted password is stored under a fresh reference and the connect goes on`() = runProgressTest {
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
    fun `a rejected password is asked for again and the old one is replaced`() = runProgressTest {
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
    fun `a replaced credential that the ssh login shares is kept and the login still works`() = runProgressTest {
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
    fun `a submitted password is wiped once stored, while the connect still waits`() = runProgressTest {
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
    fun `a submitted password is wiped when it cannot be stored`() = runProgressTest {
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
    fun `the password of a server this app started is never deleted`() = runProgressTest {
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
    fun `a password that cannot be stored leaves the profile as it was`() = runProgressTest {
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
    fun `a profile that cannot be saved takes its new password back`() = runProgressTest {
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
    fun `an empty password is not submitted`() = runProgressTest {
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
    fun `cancel disconnects and closes`() = runProgressTest {
        val fx = ProgressFixture(this)
        fx.seed()
        val presenter = fx.presenter()
        val events = fx.events(presenter)
        runCurrent()
        assertIs<ConnectionState.HostApproval>(fx.connectionState)

        presenter.onIntent(ProgressIntent.Cancel)
        runCurrent()
        fx.awaitEvent(events, ProgressEvent.Close)

        assertEquals(ConnectionState.Idle, fx.connectionState)
        assertEquals(listOf(ProgressEvent.OpenHostKey, ProgressEvent.Close), events)
        assertTrue(fx.connector.sessions.none { it.connected.value })
    }

    @Test
    fun `a connection that stood outlives the screen`() = runProgressTest {
        val fx = ready()
        val scope = fx.screenScope()
        fx.presenter(scope)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.connectionState)

        scope.cancel()
        runCurrent()
        fx.awaitEnded(scope)

        assertIs<ConnectionState.Connected>(fx.connectionState)
        assertEquals(SERVER_ID, fx.activeServer.id.value)
    }

    @Test
    fun `an unfinished connect ends with the screen`() = runProgressTest {
        val fx = ProgressFixture(this)
        fx.seed()
        val scope = fx.screenScope()
        fx.presenter(scope)
        runCurrent()
        assertIs<ConnectionState.HostApproval>(fx.connectionState)

        scope.cancel()
        runCurrent()
        fx.awaitEnded(scope)

        assertEquals(ConnectionState.Idle, fx.connectionState)
    }

    @Test
    fun `a screen that ends late leaves a newer screen's connect alone`() = runProgressTest {
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
        fx.awaitEnded(oldScope)

        assertIs<ConnectionState.HostApproval>(fx.connectionState)
        assertTrue(fx.manager.approveHostKey(SERVER_KEY))
        runCurrent()
        assertEquals(ProgressStep.Connected, newer.state.value.step)
    }

    @Test
    fun `a cancel whose disconnect never got to run still leaves nothing open`() = runProgressTest {
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
        fx.awaitEnded(scope)

        assertEquals(ConnectionState.Idle, fx.connectionState)
        assertEquals(0, fx.connector.halfOpenConnections)
        assertTrue(fx.connector.sessions.none { it.connected.value })
    }

    @Test
    fun `a screen that ends before it starts connects nothing`() = runProgressTest {
        val fx = ready()
        val scope = fx.screenScope()
        fx.presenter(scope)

        scope.cancel()
        runCurrent()
        fx.awaitEnded(scope)

        assertTrue(fx.connector.attempts.isEmpty())
        assertEquals(ConnectionState.Idle, fx.connectionState)
    }

    @Test
    fun `a retry cut short by the screen ending leaves nothing open`() = runProgressTest {
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
        fx.awaitEnded(scope)

        assertTrue(fx.connector.sessions.none { it.connected.value })
        assertEquals(0, fx.connector.halfOpenConnections)
        assertFalse(fx.connectionState is ConnectionState.Connected)
    }

    @Test
    fun `an old screen ending while a newer one connects never drops the newer connect`() = runProgressTest {
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
            fx.awaitEnded(oldScope)

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
    fun `a screen taken over before it sends asks for no host key`() = runProgressTest {
        assertEquals(emptyList(), takeOverAt(ProgressStep.HostApproval, trusted = false))
    }

    @Test
    fun `a screen taken over before it sends says nothing of a connection`() = runProgressTest {
        assertEquals(emptyList(), takeOverAt(ProgressStep.Connected, trusted = true))
    }

    @Test
    fun `cancel drops a standing connection whichever cleanup runs first`() = runProgressTest {
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
            fx.awaitEnded(scope)

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
    fun `a screen that never connected leaves the connection alone`() = runProgressTest {
        val fx = ready()
        fx.presenter()
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.connectionState)

        val scope = fx.screenScope()
        fx.presenter(scope, serverId = ServerProfileId("missing"))
        runCurrent()
        scope.cancel()
        runCurrent()
        fx.awaitEnded(scope)

        assertIs<ConnectionState.Connected>(fx.connectionState)
    }

    @Test
    fun `an older connection's state is not taken for this one`() = runProgressTest {
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
    fun `a closed manager is reported`() = runProgressTest {
        val fx = ready()
        fx.manager.close()
        val presenter = fx.presenter()
        runCurrent()

        assertEquals(ProgressError.Unexpected, presenter.state.value.error)
        assertEquals(emptySet(), presenter.state.value.actions)
    }
}
