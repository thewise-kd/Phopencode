package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.storage.FakeRecentFoldersStore
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ActiveServer
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** P9.31: entries whose name starts with `.` are hidden until the user asks to see them. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderHiddenTest {
    private class Env(val test: TestScope) {
        val connection = FolderConnection(test.backgroundScope)
        val activeServer = ActiveServer()
        val recent = FakeRecentFoldersStore()
        val events = mutableListOf<FolderEvent>()

        val presenter: DefaultFolderPresenter by lazy {
            DefaultFolderPresenter(
                connection = connection.manager,
                activeServer = activeServer,
                recentFolders = recent,
                scope = test.backgroundScope,
                closer = StandardTestDispatcher(test.testScheduler),
            ).also { presenter ->
                test.backgroundScope.launch { presenter.events.collect { events += it } }
            }
        }

        val state: FolderState get() = presenter.state.value

        val names: List<String> get() = state.entries.map { it.name }

        /** Every SFTP request made so far, by every client. */
        val requests: Int get() = synchronized(connection.log) { connection.log.opened.sumOf { it.inner.requests } }
    }

    /**
     * The server's tree, with the login home at `/proj`:
     * ```
     * /proj/.cache/   /proj/.git/objects/   /proj/src/   /proj/Docs/   /proj/.env (file)
     * /proj/.link -> /proj/src              /only/.a/    /only/.b/     /none/
     * ```
     */
    private suspend fun TestScope.env(): Env {
        val env = Env(this)
        env.connection.files.apply {
            addDirectory("/proj/.cache")
            addDirectory("/proj/.git/objects")
            addDirectory("/proj/src")
            addDirectory("/proj/Docs")
            addFile("/proj/.env", "x".encodeToByteArray())
            addSymlink("/proj/.link", "/proj/src")
            addDirectory("/only/.a")
            addDirectory("/only/.b")
            addDirectory("/none")
            home = "/proj"
        }
        env.activeServer.set(PROFILE_ID)
        env.connection.connect()
        runCurrent()
        assertIs<ConnectionState.Connected>(env.connection.manager.state.value)
        return env
    }

    private fun TestScope.send(env: Env, intent: FolderIntent) {
        env.presenter.onIntent(intent)
        runCurrent()
    }

    @Test
    fun `dot folders are hidden by default and counted`() = runTest {
        val env = env()
        env.presenter
        runCurrent()

        assertEquals("/proj", env.state.path)
        assertFalse(env.state.showHidden)
        assertEquals(listOf("Docs", "src"), env.names)
        // .cache, .git and the link .link; the file .env is no folder, so it is not counted.
        assertEquals(3, env.state.hiddenCount)
    }

    @Test
    fun `showing hidden folders reveals them in name order without listing again`() = runTest {
        val env = env()
        env.presenter
        runCurrent()
        val before = env.requests

        send(env, FolderIntent.SetShowHidden(true))

        assertTrue(env.state.showHidden)
        assertEquals(listOf(".cache", ".git", ".link", "Docs", "src"), env.names)
        assertEquals(0, env.state.hiddenCount)
        assertEquals(before, env.requests, "toggling only filters again")
        assertFalse(env.state.loading)

        send(env, FolderIntent.SetShowHidden(false))

        assertFalse(env.state.showHidden)
        assertEquals(listOf("Docs", "src"), env.names)
        assertEquals(3, env.state.hiddenCount)
        assertEquals(before, env.requests, "toggling only filters again")
    }

    @Test
    fun `the choice holds across opening a hidden folder, going up and a refresh`() = runTest {
        val env = env()
        env.presenter
        runCurrent()
        send(env, FolderIntent.SetShowHidden(true))

        send(env, FolderIntent.Open("/proj/.git"))
        assertEquals("/proj/.git", env.state.path)
        assertTrue(env.state.showHidden)
        assertEquals(listOf("objects"), env.names)

        send(env, FolderIntent.Up)
        assertEquals("/proj", env.state.path)
        assertTrue(env.state.showHidden)
        assertEquals(listOf(".cache", ".git", ".link", "Docs", "src"), env.names)

        send(env, FolderIntent.Retry)
        assertTrue(env.state.showHidden)
        assertEquals(listOf(".cache", ".git", ".link", "Docs", "src"), env.names)
    }

    @Test
    fun `hiding holds across navigation and a refresh too`() = runTest {
        val env = env()
        env.presenter
        runCurrent()

        send(env, FolderIntent.Open("/only"))
        assertEquals(emptyList(), env.names)
        assertEquals(2, env.state.hiddenCount)

        send(env, FolderIntent.Up)
        send(env, FolderIntent.Open("/proj"))
        send(env, FolderIntent.Retry)
        assertFalse(env.state.showHidden)
        assertEquals(listOf("Docs", "src"), env.names)
        assertEquals(3, env.state.hiddenCount)
    }

    @Test
    fun `a folder of only hidden folders shows none but counts them, and an empty one counts none`() = runTest {
        val env = env()
        env.presenter
        runCurrent()

        send(env, FolderIntent.Open("/only"))
        assertEquals(emptyList(), env.names)
        assertEquals(2, env.state.hiddenCount)

        send(env, FolderIntent.SetShowHidden(true))
        assertEquals(listOf(".a", ".b"), env.names)

        send(env, FolderIntent.Open("/none"))
        assertEquals(emptyList(), env.names)
        assertEquals(0, env.state.hiddenCount)
    }

    @Test
    fun `a choice made before the first listing applies to it`() = runTest {
        val env = env()
        env.presenter.onIntent(FolderIntent.SetShowHidden(true))
        assertTrue(env.state.showHidden)

        runCurrent()

        assertEquals(listOf(".cache", ".git", ".link", "Docs", "src"), env.names)
    }

    @Test
    fun `a typed or selected hidden path is never filtered`() = runTest {
        val env = env()
        env.presenter
        runCurrent()

        send(env, FolderIntent.Open("/proj/.git"))
        assertEquals("/proj/.git", env.state.path)
        assertEquals(listOf("objects"), env.names)
        assertNull(env.state.error)

        send(env, FolderIntent.Select("/proj/.cache"))
        assertEquals(listOf<FolderEvent>(FolderEvent.Selected("/proj/.cache")), env.events)
        assertEquals(listOf("/proj/.cache"), env.recent.list(PROFILE_ID))
    }

    @Test
    fun `recent folders are never filtered`() = runTest {
        val env = env()
        env.recent.add(PROFILE_ID, "/proj/.git")
        env.recent.add(PROFILE_ID, "/proj/src")
        env.presenter
        runCurrent()

        assertFalse(env.state.showHidden)
        assertEquals(listOf("/proj/src", "/proj/.git"), env.state.recent)
    }

    @Test
    fun `a failed listing keeps the folder shown and the choice`() = runTest {
        val env = env()
        env.presenter
        runCurrent()
        send(env, FolderIntent.SetShowHidden(true))
        env.connection.failNextSftp(SshException.Sftp(SftpFailure.PermissionDenied))

        send(env, FolderIntent.Open("/none"))

        assertEquals(FolderError.PermissionDenied, env.state.error)
        assertEquals("/proj", env.state.path)
        assertTrue(env.state.showHidden)
        assertEquals(listOf(".cache", ".git", ".link", "Docs", "src"), env.names)

        send(env, FolderIntent.SetShowHidden(false))
        assertEquals(listOf("Docs", "src"), env.names)
        assertEquals(FolderError.PermissionDenied, env.state.error, "toggling leaves the error alone")
    }
}
