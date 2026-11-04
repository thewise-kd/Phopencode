package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeRecentFoldersStore
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ActiveServer
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultFolderPresenterTest {
    private class Env(val test: TestScope) {
        val connection = FolderConnection(test.backgroundScope)
        val activeServer = ActiveServer()
        val recent = FakeRecentFoldersStore()
        val events = mutableListOf<FolderEvent>()

        fun presenter(
            scope: CoroutineScope = test.backgroundScope,
            store: RecentFoldersStore = recent,
            // Closes on the test's virtual time, so runCurrent sees them done.
            closer: CoroutineDispatcher = StandardTestDispatcher(test.testScheduler),
        ): DefaultFolderPresenter = DefaultFolderPresenter(
            connection = connection.manager,
            activeServer = activeServer,
            recentFolders = store,
            scope = scope,
            closer = closer,
        ).also { presenter ->
            test.backgroundScope.launch { presenter.events.collect { events += it } }
        }
    }

    /**
     * The server's tree:
     * ```
     * /data/inner/          /srv/alpha/   /srv/app/   /srv/Beta/   /srv/locked/ (denied)
     * /data/readme          /srv/notes.txt
     *                       /srv/link-dir -> /data, /srv/link-file -> /srv/notes.txt,
     *                       /srv/broken -> /missing
     * ```
     */
    private suspend fun TestScope.env(connect: Boolean = true, active: Boolean = true): Env {
        val env = Env(this)
        env.connection.files.apply {
            addDirectory("/data/inner")
            addFile("/data/readme", "data".encodeToByteArray())
            addDirectory("/srv/alpha")
            addDirectory("/srv/app")
            addDirectory("/srv/Beta")
            addDirectory("/srv/locked")
            addFile("/srv/notes.txt", "notes".encodeToByteArray())
            addSymlink("/srv/link-dir", "/data")
            addSymlink("/srv/link-file", "/srv/notes.txt")
            addSymlink("/srv/broken", "/missing")
            deny("/srv/locked")
        }
        if (active) env.activeServer.set(PROFILE_ID)
        if (connect) {
            env.connection.connect()
            runCurrent()
            assertIs<ConnectionState.Connected>(env.connection.manager.state.value)
        }
        return env
    }

    /** Holds every task until [release], so that a close suspends for as long as a test wants. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val held = mutableListOf<Runnable>()

        val waiting: Int get() = synchronized(held) { held.size }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(held) { held += block }
        }

        fun release() {
            val tasks = synchronized(held) { held.toList().also { held.clear() } }
            tasks.forEach(Runnable::run)
        }
    }

    private val DefaultFolderPresenter.names: List<String> get() = state.value.entries.map { it.name }

    private fun TestScope.open(presenter: DefaultFolderPresenter, path: String) {
        presenter.onIntent(FolderIntent.Open(path))
        runCurrent()
    }

    @Test
    fun `the initial state holds until the scope runs, then the server root is listed`() = runTest {
        val env = env()

        val presenter = env.presenter()

        assertEquals(FolderState(), presenter.state.value)
        assertTrue(env.connection.log.roots.isEmpty(), "nothing opens in the constructor")
        runCurrent()
        val state = presenter.state.value
        assertFalse(state.loading)
        assertEquals("/", state.path)
        assertEquals(listOf("data", "srv"), presenter.names)
        assertNull(state.error)
        assertFalse(state.canGoUp)
    }

    @Test
    fun `loading does not run in the constructor even on an immediate dispatcher`() = runTest {
        val env = env()
        // Like Dispatchers.Main.immediate on the main thread: a launch runs at once, a yield dispatches.
        val queue = StandardTestDispatcher(testScheduler)
        val immediate = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

            override fun dispatch(context: CoroutineContext, block: Runnable) = queue.dispatch(context, block)
        }
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]) + immediate)

        val presenter = env.presenter(scope)

        assertEquals(FolderState(), presenter.state.value)
        assertTrue(env.connection.log.roots.isEmpty())
        runCurrent()
        assertEquals("/", presenter.state.value.path)
    }

    @Test
    fun `the whole server is browsed through one client with root slash`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        open(presenter, "/srv")
        open(presenter, "/srv/app")
        presenter.onIntent(FolderIntent.Up)
        runCurrent()

        assertEquals(listOf("/"), env.connection.log.roots)
        assertEquals(1, env.connection.log.opened.size)
    }

    @Test
    fun `only folders and links to folders are listed, by name ignoring case`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        open(presenter, "/srv")

        val state = presenter.state.value
        assertEquals("/srv", state.path)
        assertEquals(listOf("alpha", "app", "Beta", "link-dir", "locked"), presenter.names)
        assertEquals(
            listOf("/srv/alpha", "/srv/app", "/srv/Beta", "/srv/link-dir", "/srv/locked"),
            state.entries.map { it.path },
        )
        assertEquals(FileEntryType.Symlink, state.entries.single { it.name == "link-dir" }.type)
        assertTrue(state.entries.filter { it.name != "link-dir" }.all { it.type == FileEntryType.Directory })
        assertTrue(state.canGoUp)
    }

    @Test
    fun `a link to a folder can be opened`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        open(presenter, "/srv/link-dir")

        assertEquals("/srv/link-dir", presenter.state.value.path)
        assertEquals(listOf("inner"), presenter.names)
    }

    @Test
    fun `open takes the path in canonical form`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        open(presenter, "//srv/./app/../app/")

        assertEquals("/srv/app", presenter.state.value.path)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a relative path opens below the current folder`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/srv")

        open(presenter, "app")

        assertEquals("/srv/app", presenter.state.value.path)
    }

    @Test
    fun `up goes to the parent and stops at the root`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/srv/app")

        presenter.onIntent(FolderIntent.Up)
        runCurrent()
        assertEquals("/srv", presenter.state.value.path)

        presenter.onIntent(FolderIntent.Up)
        runCurrent()
        assertEquals("/", presenter.state.value.path)
        assertEquals(listOf("data", "srv"), presenter.names)

        val requests = env.connection.lastClient().requests
        presenter.onIntent(FolderIntent.Up)
        runCurrent()
        assertEquals("/", presenter.state.value.path)
        assertEquals(requests, env.connection.lastClient().requests, "up at the root asks nothing")
    }

    @Test
    fun `the last of several quick opens wins`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        presenter.onIntent(FolderIntent.Open("/srv"))
        presenter.onIntent(FolderIntent.Open("/data"))
        runCurrent()

        assertEquals("/data", presenter.state.value.path)
        assertEquals(listOf("inner"), presenter.names)
    }

    @Test
    fun `a folder without permission shows PermissionDenied and the client stays usable`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/srv")

        open(presenter, "/srv/locked")

        val state = presenter.state.value
        assertEquals(FolderError.PermissionDenied, state.error)
        assertFalse(state.loading)
        assertEquals("/srv", state.path, "the folder shown stays")
        assertEquals(listOf("alpha", "app", "Beta", "link-dir", "locked"), presenter.names)

        open(presenter, "/srv/app")

        assertEquals("/srv/app", presenter.state.value.path)
        assertNull(presenter.state.value.error)
        assertEquals(1, env.connection.log.opened.size)
        assertEquals(0, env.connection.log.opened.single().closes.get())
    }

    @Test
    fun `a missing folder shows NotFound`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        open(presenter, "/srv/missing")

        assertEquals(FolderError.NotFound, presenter.state.value.error)
        assertEquals("/", presenter.state.value.path)
    }

    @Test
    fun `any other SFTP failure shows Failed`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        env.connection.failNextSftp(SshException.Sftp(SftpFailure.Failed))
        open(presenter, "/srv")

        assertEquals(FolderError.Failed, presenter.state.value.error)
        assertEquals("/", presenter.state.value.path)
    }

    @Test
    fun `opening a file shows Failed`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        open(presenter, "/srv/notes.txt")

        assertEquals(FolderError.Failed, presenter.state.value.error)
    }

    @Test
    fun `a path the client cannot take shows Failed`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        open(presenter, "/srv/a\u0000b")

        assertEquals(FolderError.Failed, presenter.state.value.error)
        assertEquals("/", presenter.state.value.path)
    }

    @Test
    fun `without an active server nothing is opened and NotConnected shows`() = runTest {
        val env = env(active = false)
        val presenter = env.presenter()
        runCurrent()

        val state = presenter.state.value
        assertEquals(FolderError.NotConnected, state.error)
        assertFalse(state.loading)
        assertNull(state.path)
        assertTrue(env.connection.log.roots.isEmpty())
    }

    @Test
    fun `without a connection NotConnected shows, and retry lists once connected`() = runTest {
        val env = env(connect = false)
        val presenter = env.presenter()
        runCurrent()
        assertEquals(FolderError.NotConnected, presenter.state.value.error)
        assertFalse(presenter.state.value.loading)

        env.connection.connect()
        runCurrent()
        presenter.onIntent(FolderIntent.Retry)
        runCurrent()

        assertEquals("/", presenter.state.value.path)
        assertNull(presenter.state.value.error)
        assertEquals(listOf("data", "srv"), presenter.names)
    }

    @Test
    fun `a dropped session shows NotConnected and retry opens a new client`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/srv")

        env.connection.failNextSftp(SshException.Disconnected())
        open(presenter, "/srv/app")
        assertEquals(FolderError.NotConnected, presenter.state.value.error)
        assertEquals("/srv", presenter.state.value.path)

        presenter.onIntent(FolderIntent.Retry)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals("/srv", presenter.state.value.path)
        val opened = env.connection.log.opened
        assertEquals(2, opened.size)
        assertEquals(1, opened.first().closes.get(), "the broken client is closed")
    }

    @Test
    fun `after a reconnect retry opens a client on the new connection`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/srv")

        env.connection.connector.sessions.single().loseConnection()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(env.connection.manager.state.value)
        open(presenter, "/srv/app")
        assertEquals(FolderError.NotConnected, presenter.state.value.error)

        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertIs<ConnectionState.Connected>(env.connection.manager.state.value)
        presenter.onIntent(FolderIntent.Retry)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals("/srv", presenter.state.value.path)
        assertEquals(listOf("alpha", "app", "Beta", "link-dir", "locked"), presenter.names)
        assertEquals(2, env.connection.log.opened.size)
        open(presenter, "/srv/app")
        assertEquals("/srv/app", presenter.state.value.path)
    }

    @Test
    fun `retry lists the current folder again`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/data")
        env.connection.files.addDirectory("/data/added")

        presenter.onIntent(FolderIntent.Retry)
        runCurrent()

        assertEquals("/data", presenter.state.value.path)
        assertEquals(listOf("added", "inner"), presenter.names)
    }

    @Test
    fun `dismiss error clears the error only`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/srv/missing")

        presenter.onIntent(FolderIntent.DismissError)

        assertNull(presenter.state.value.error)
        assertEquals("/", presenter.state.value.path)
    }

    @Test
    fun `select stores the canonical path for the active server, then reports it`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        presenter.onIntent(FolderIntent.Select("/srv/app/"))
        runCurrent()

        assertEquals(listOf("/srv/app"), env.recent.list(PROFILE_ID))
        assertEquals(listOf<FolderEvent>(FolderEvent.Selected("/srv/app")), env.events)
    }

    @Test
    fun `select of a relative path takes it below the current folder`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/srv")

        presenter.onIntent(FolderIntent.Select("app"))
        runCurrent()

        assertEquals(listOf<FolderEvent>(FolderEvent.Selected("/srv/app")), env.events)
    }

    @Test
    fun `select moves a recent folder to the front`() = runTest {
        val env = env()
        env.recent.add(PROFILE_ID, "/srv/app")
        env.recent.add(PROFILE_ID, "/data")
        val presenter = env.presenter()
        runCurrent()

        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()

        assertEquals(listOf("/srv/app", "/data"), presenter.state.value.recent)
    }

    @Test
    fun `a failed store shows StorageFailed and still reports the pick`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()
        env.recent.unavailable = true

        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()

        assertEquals(FolderError.StorageFailed, presenter.state.value.error)
        assertEquals(listOf<FolderEvent>(FolderEvent.Selected("/srv/app")), env.events)
    }

    @Test
    fun `select without an active server reports nothing`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()
        env.activeServer.set(null)

        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()

        assertEquals(FolderError.NotConnected, presenter.state.value.error)
        assertTrue(env.events.isEmpty())
        assertTrue(env.recent.list(PROFILE_ID).isEmpty())
    }

    @Test
    fun `select before any listing has finished stores under the serving connection's profile`() = runTest {
        val env = env()
        val presenter = env.presenter()

        // A tap on a recent folder while the root is still loading.
        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()

        assertEquals(listOf("/srv/app"), env.recent.list(PROFILE_ID))
        assertEquals(listOf<FolderEvent>(FolderEvent.Selected("/srv/app")), env.events)
    }

    @Test
    fun `select after the first listing failed stores under the serving connection's profile`() = runTest {
        val env = env(connect = false)
        val presenter = env.presenter()
        runCurrent()
        // The root failed, so nothing has been listed; then the connection stands.
        assertEquals(FolderError.NotConnected, presenter.state.value.error)
        assertNull(presenter.state.value.path)
        env.connection.connect()
        runCurrent()

        presenter.onIntent(FolderIntent.Select("/data"))
        runCurrent()

        assertEquals(listOf("/data"), env.recent.list(PROFILE_ID))
        assertEquals(listOf<FolderEvent>(FolderEvent.Selected("/data")), env.events)
    }

    @Test
    fun `select before any listing without a connection shows NotConnected and stores nothing`() = runTest {
        val env = env(connect = false)
        val presenter = env.presenter()
        runCurrent()
        assertNull(presenter.state.value.path)

        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()

        assertEquals(FolderError.NotConnected, presenter.state.value.error)
        assertTrue(env.events.isEmpty())
        assertTrue(env.recent.list(PROFILE_ID).isEmpty())
    }

    @Test
    fun `select before any listing while another profile's connection stands shows NotConnected`() = runTest {
        val env = env(active = false)
        env.activeServer.set(OTHER_PROFILE_ID)
        val presenter = env.presenter()
        assertEquals(PROFILE_ID, assertIs<ConnectionState.Connected>(env.connection.manager.state.value).server.profileId)

        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()

        assertEquals(FolderError.NotConnected, presenter.state.value.error)
        assertTrue(env.events.isEmpty())
        assertTrue(env.recent.list(PROFILE_ID).isEmpty())
        assertTrue(env.recent.list(OTHER_PROFILE_ID).isEmpty())
    }

    @Test
    fun `a server switch while the pick is being stored keeps it in the original server's history`() = runTest {
        val env = env()
        val other = ServerProfileId("other-profile")
        val gate = CompletableDeferred<Unit>()
        val addedFor = mutableListOf<ServerProfileId>()
        val gated = object : RecentFoldersStore by env.recent {
            override suspend fun add(profile: ServerProfileId, path: String) {
                addedFor += profile
                gate.await()
                env.recent.add(profile, path)
            }
        }
        val presenter = env.presenter(store = gated)
        runCurrent()

        presenter.onIntent(FolderIntent.Select("/srv/app"))
        env.activeServer.set(other)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(PROFILE_ID), addedFor)
        assertEquals(listOf("/srv/app"), env.recent.list(PROFILE_ID))
        assertTrue(env.recent.list(other).isEmpty())
        assertEquals(listOf<FolderEvent>(FolderEvent.Selected("/srv/app")), env.events)
    }

    @Test
    fun `a pick from a listing of another server is refused`() = runTest {
        val env = env()
        val other = ServerProfileId("other-profile")
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/srv")

        env.activeServer.set(other)
        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()

        assertEquals(FolderError.Failed, presenter.state.value.error)
        assertTrue(env.events.isEmpty())
        assertTrue(env.recent.list(PROFILE_ID).isEmpty())
        assertTrue(env.recent.list(other).isEmpty())
    }

    @Test
    fun `a server switch on the old connection shows NotConnected and stores nothing under the new server`() = runTest {
        val env = env()
        val other = ServerProfileId("other-profile")
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/srv")
        val generation = assertIs<ConnectionState.Connected>(env.connection.manager.state.value).generation

        // As the connection flow does: the active server changes just before its connect.
        env.activeServer.set(other)
        presenter.onIntent(FolderIntent.Retry)
        runCurrent()

        assertEquals(generation, assertIs<ConnectionState.Connected>(env.connection.manager.state.value).generation)
        assertEquals(FolderError.NotConnected, presenter.state.value.error)
        assertEquals("/srv", presenter.state.value.path)
        val opened = env.connection.log.opened
        assertEquals(1, opened.size, "no client is opened on the previous server's connection")
        assertEquals(1, opened.single().closes.get())

        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()

        assertTrue(env.recent.list(other).isEmpty())
        assertTrue(env.recent.list(PROFILE_ID).isEmpty())
        assertTrue(env.events.isEmpty())
    }

    @Test
    fun `once the new server's connection stands, retry lists on its client and picks go under it`() = runTest {
        val env = env()
        val other = ServerProfileId("other-profile")
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/srv")
        val old = assertIs<ConnectionState.Connected>(env.connection.manager.state.value).generation
        env.activeServer.set(other)
        presenter.onIntent(FolderIntent.Retry)
        runCurrent()
        assertEquals(FolderError.NotConnected, presenter.state.value.error)

        env.connection.connect(OTHER_PROFILE)
        runCurrent()
        val fresh = assertIs<ConnectionState.Connected>(env.connection.manager.state.value).generation
        assertTrue(fresh > old)
        presenter.onIntent(FolderIntent.Retry)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals("/srv", presenter.state.value.path)
        assertEquals(listOf("alpha", "app", "Beta", "link-dir", "locked"), presenter.names)
        val opened = env.connection.log.opened
        assertEquals(2, opened.size)
        assertEquals(0, opened.last().closes.get())

        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()

        assertEquals(listOf("/srv/app"), env.recent.list(other))
        assertTrue(env.recent.list(PROFILE_ID).isEmpty())
        assertEquals(listOf<FolderEvent>(FolderEvent.Selected("/srv/app")), env.events)
    }

    @Test
    fun `a connection to another profile does not serve the active one, even when the picker opens on it`() = runTest {
        val env = env(active = false)
        env.activeServer.set(OTHER_PROFILE_ID)
        val presenter = env.presenter()
        runCurrent()

        val connected = assertIs<ConnectionState.Connected>(env.connection.manager.state.value)
        assertEquals(PROFILE_ID, connected.server.profileId)
        assertEquals(FolderError.NotConnected, presenter.state.value.error)
        assertNull(presenter.state.value.path)
        assertTrue(env.connection.log.roots.isEmpty(), "no client is opened on another profile's connection")

        presenter.onIntent(FolderIntent.Retry)
        runCurrent()
        assertEquals(FolderError.NotConnected, presenter.state.value.error)
        assertTrue(env.connection.log.roots.isEmpty())

        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()
        assertTrue(env.events.isEmpty())
        assertTrue(env.recent.list(OTHER_PROFILE_ID).isEmpty())
        assertTrue(env.recent.list(PROFILE_ID).isEmpty())
    }

    @Test
    fun `the new profile's connection serves on retry when it stands before the active server changes`() = runTest {
        switchThenRetry(connectFirst = true)
    }

    @Test
    fun `the new profile's connection serves on retry when the active server changes before it stands`() = runTest {
        switchThenRetry(connectFirst = false)
    }

    /** Switches from [PROFILE] to [OTHER_PROFILE], the connection and [ActiveServer] in either order. */
    private suspend fun TestScope.switchThenRetry(connectFirst: Boolean) {
        val env = env()
        val presenter = env.presenter()
        runCurrent()
        open(presenter, "/srv")

        if (connectFirst) {
            env.connection.connect(OTHER_PROFILE)
            runCurrent()
            // The presenter sees the new connection while the previous profile is still active.
            presenter.onIntent(FolderIntent.Retry)
            runCurrent()
            assertEquals(FolderError.NotConnected, presenter.state.value.error)
            env.activeServer.set(OTHER_PROFILE_ID)
            runCurrent()
        } else {
            env.activeServer.set(OTHER_PROFILE_ID)
            runCurrent()
            presenter.onIntent(FolderIntent.Retry)
            runCurrent()
            assertEquals(FolderError.NotConnected, presenter.state.value.error)
            env.connection.connect(OTHER_PROFILE)
            runCurrent()
        }
        val connected = assertIs<ConnectionState.Connected>(env.connection.manager.state.value)
        assertEquals(OTHER_PROFILE_ID, connected.server.profileId)

        presenter.onIntent(FolderIntent.Retry)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals("/srv", presenter.state.value.path)
        assertEquals(listOf("alpha", "app", "Beta", "link-dir", "locked"), presenter.names)
        assertEquals(0, env.connection.log.opened.last().closes.get())

        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()

        assertEquals(listOf("/srv/app"), env.recent.list(OTHER_PROFILE_ID))
        assertTrue(env.recent.list(PROFILE_ID).isEmpty())
        assertEquals(listOf<FolderEvent>(FolderEvent.Selected("/srv/app")), env.events)
    }

    /**
     * Lists `/srv` on [PROFILE], reconnects it so the client is stale, then asks for a Retry whose
     * close of the stale client is held; while it is held, [OTHER_PROFILE] connects and becomes
     * active. Returns with the close still held.
     */
    private suspend fun TestScope.switchWhileDiscarding(env: Env, closer: HeldDispatcher): DefaultFolderPresenter {
        val presenter = env.presenter(closer = closer)
        runCurrent()
        open(presenter, "/srv")
        assertEquals(1, env.connection.log.opened.size)

        env.connection.connector.sessions.single().loseConnection()
        runCurrent()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(PROFILE_ID, assertIs<ConnectionState.Connected>(env.connection.manager.state.value).server.profileId)

        presenter.onIntent(FolderIntent.Retry)
        runCurrent()
        assertEquals(1, closer.waiting, "the stale client's close is held")

        env.activeServer.set(OTHER_PROFILE_ID)
        env.connection.connect(OTHER_PROFILE)
        runCurrent()
        assertEquals(
            OTHER_PROFILE_ID,
            assertIs<ConnectionState.Connected>(env.connection.manager.state.value).server.profileId,
        )
        return presenter
    }

    @Test
    fun `a listing made after a switch during the close of a stale client belongs to the new profile`() = runTest {
        val env = env()
        val closer = HeldDispatcher()
        val presenter = switchWhileDiscarding(env, closer)

        closer.release()
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals("/srv", presenter.state.value.path)
        presenter.onIntent(FolderIntent.Select("/srv/app"))
        runCurrent()
        assertEquals(listOf("/srv/app"), env.recent.list(OTHER_PROFILE_ID))
        assertTrue(env.recent.list(PROFILE_ID).isEmpty())
        assertEquals(listOf<FolderEvent>(FolderEvent.Selected("/srv/app")), env.events)
    }

    @Test
    fun `a switch during the close of a stale client cannot put the new server's listing under the previous profile`() = runTest {
        val env = env()
        val closer = HeldDispatcher()
        val presenter = switchWhileDiscarding(env, closer)
        // Back to the first profile while the other one's connection stands.
        env.activeServer.set(PROFILE_ID)
        runCurrent()

        closer.release()
        runCurrent()
        closer.release()
        runCurrent()

        assertEquals(FolderError.NotConnected, presenter.state.value.error)
        assertEquals(1, env.connection.log.opened.size, "no client is opened on the other profile's connection")
        assertEquals(1, env.connection.log.opened.single().closes.get())
    }

    @Test
    fun `recent folders come back after a failed observation`() = runTest {
        val env = env()
        env.recent.add(PROFILE_ID, "/srv/app")
        var observations = 0
        val flaky = object : RecentFoldersStore by env.recent {
            override fun observe(profile: ServerProfileId): Flow<List<String>> =
                if (++observations == 1) flow { throw IOException("unreadable") } else env.recent.observe(profile)
        }
        val presenter = env.presenter(store = flaky)
        runCurrent()
        assertEquals(FolderError.StorageFailed, presenter.state.value.error)
        assertEquals(emptyList(), presenter.state.value.recent)

        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(listOf("/srv/app"), presenter.state.value.recent)

        presenter.onIntent(FolderIntent.Select("/srv"))
        runCurrent()

        assertEquals(listOf("/srv", "/srv/app"), presenter.state.value.recent)
        assertEquals(2, observations, "one retry, no busy loop")
    }

    @Test
    fun `select of a path with a NUL byte shows Failed and reports nothing`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        presenter.onIntent(FolderIntent.Select("/srv/a\u0000b"))
        runCurrent()

        assertEquals(FolderError.Failed, presenter.state.value.error)
        assertTrue(env.events.isEmpty())
        assertTrue(env.recent.list(PROFILE_ID).isEmpty())
    }

    @Test
    fun `recent folders follow the store and the active server`() = runTest {
        val env = env()
        val other = ServerProfileId("other-profile")
        env.recent.add(PROFILE_ID, "/srv/app")
        env.recent.add(other, "/data")
        val presenter = env.presenter()
        runCurrent()
        assertEquals(listOf("/srv/app"), presenter.state.value.recent)

        env.recent.add(PROFILE_ID, "/srv")
        runCurrent()
        assertEquals(listOf("/srv", "/srv/app"), presenter.state.value.recent)

        env.activeServer.set(other)
        runCurrent()
        assertEquals(listOf("/data"), presenter.state.value.recent)

        env.activeServer.set(null)
        runCurrent()
        assertEquals(emptyList(), presenter.state.value.recent)
    }

    @Test
    fun `remove recent removes one folder`() = runTest {
        val env = env()
        env.recent.add(PROFILE_ID, "/srv/app")
        env.recent.add(PROFILE_ID, "/data")
        val presenter = env.presenter()
        runCurrent()

        presenter.onIntent(FolderIntent.RemoveRecent("/srv/app"))
        runCurrent()

        assertEquals(listOf("/data"), env.recent.list(PROFILE_ID))
        assertEquals(listOf("/data"), presenter.state.value.recent)
    }

    @Test
    fun `a failed remove shows StorageFailed`() = runTest {
        val env = env()
        env.recent.add(PROFILE_ID, "/srv/app")
        val presenter = env.presenter()
        runCurrent()
        env.recent.unavailable = true

        presenter.onIntent(FolderIntent.RemoveRecent("/srv/app"))
        runCurrent()

        assertEquals(FolderError.StorageFailed, presenter.state.value.error)
        assertEquals(listOf("/srv/app"), presenter.state.value.recent)
    }

    @Test
    fun `recent folders that cannot be read show StorageFailed and browsing still works`() = runTest {
        val env = env()
        val broken = object : RecentFoldersStore by env.recent {
            override fun observe(profile: ServerProfileId): Flow<List<String>> = flow { throw IOException("unreadable") }
        }
        val presenter = env.presenter(store = broken)
        runCurrent()

        assertEquals(FolderError.StorageFailed, presenter.state.value.error)
        assertEquals("/", presenter.state.value.path)
        assertEquals(listOf("data", "srv"), presenter.names)
    }

    @Test
    fun `close is reported`() = runTest {
        val env = env()
        val presenter = env.presenter()
        runCurrent()

        presenter.onIntent(FolderIntent.Close)
        runCurrent()

        assertEquals(listOf<FolderEvent>(FolderEvent.Close), env.events)
    }

    @Test
    fun `the client is closed when the presenter's scope ends`() = runTest {
        val env = env()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        env.presenter(scope)
        runCurrent()
        val client = env.connection.log.opened.single()
        assertEquals(0, client.closes.get())

        scope.cancel()
        runCurrent()

        assertEquals(1, client.closes.get())
        assertIs<ConnectionState.Connected>(env.connection.manager.state.value, "the connection stays")
    }
}
