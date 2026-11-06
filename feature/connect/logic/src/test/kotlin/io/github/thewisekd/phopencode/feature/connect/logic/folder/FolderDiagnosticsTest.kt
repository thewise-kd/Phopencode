package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.Diagnostics
import io.github.thewisekd.phopencode.core.testing.storage.FakeRecentFoldersStore
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ActiveServer
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * P9.25: with diagnostics on, the folder picker's NotConnected and Failed carry a debug detail with
 * the full failure chain and why the connection last ended; with them off, none, and the
 * release-safe detail is the same either way.
 */
class FolderDiagnosticsTest {
    private class Env(test: TestScope, enabled: Boolean) {
        val connection = FolderConnection(test.backgroundScope, Diagnostics(enabled))
        val activeServer = ActiveServer().apply { set(PROFILE_ID) }
        val presenter by lazy {
            DefaultFolderPresenter(
                connection = connection.manager,
                activeServer = activeServer,
                recentFolders = FakeRecentFoldersStore(),
                scope = test.backgroundScope,
                closer = StandardTestDispatcher(test.testScheduler),
            )
        }
    }

    private suspend fun TestScope.connectedEnv(enabled: Boolean): Env {
        val env = Env(this, enabled)
        env.connection.files.addDirectory("/srv/app")
        env.connection.connect()
        runCurrent()
        assertIs<ConnectionState.Connected>(env.connection.manager.state.value)
        env.presenter
        runCurrent()
        assertEquals("/", env.presenter.state.value.path)
        return env
    }

    private fun TestScope.open(env: Env, path: String) {
        env.presenter.onIntent(FolderIntent.Open(path))
        runCurrent()
    }

    private fun assertNoSecret(state: FolderState) {
        val text = state.debugDetail.orEmpty() + state.errorDetail.orEmpty()
        assertFalse(SSH_PASSWORD in text, "the SSH password leaked")
        assertFalse(OPENCODE_PASSWORD in text, "the opencode password leaked")
    }

    /** A named caller, so that its frame can be looked for in the debug detail. */
    private suspend fun disconnectFromTheTest(env: Env) {
        env.connection.manager.disconnect()
    }

    @Test
    fun `with diagnostics on a failed listing carries the full chain with messages and frames`() = runTest(timeout = 10.seconds) {
        val env = connectedEnv(enabled = true)

        env.connection.log.failNextList.set(IllegalStateException("broken listing", java.io.IOException("socket said no")))
        open(env, "/srv")

        val state = env.presenter.state.value
        assertEquals(FolderError.Failed, state.error)
        assertEquals("java.lang.IllegalStateException <- java.io.IOException", state.errorDetail)
        val debug = assertNotNull(state.debugDetail)
        assertTrue("java.lang.IllegalStateException: broken listing" in debug, debug)
        assertTrue("Caused by: java.io.IOException: socket said no" in debug, debug)
        assertTrue("    at " in debug, debug)
        assertFalse("Connection last ended" in debug, debug)
        assertNoSecret(state)
    }

    @Test
    fun `with diagnostics on NotConnected carries why the connection last ended`() = runTest(timeout = 10.seconds) {
        val env = connectedEnv(enabled = true)

        disconnectFromTheTest(env)
        runCurrent()
        open(env, "/srv")

        val state = env.presenter.state.value
        assertEquals(FolderError.NotConnected, state.error)
        assertEquals("Idle", state.errorDetail)
        val debug = assertNotNull(state.debugDetail)
        assertTrue("NotConnectedException" in debug, debug)
        assertTrue("Connection last ended:" in debug, debug)
        assertTrue("disconnect() called" in debug, debug)
        assertTrue("disconnectFromTheTest" in debug, debug)
        assertNoSecret(state)
    }

    @Test
    fun `the debug detail follows the connection's last end while NotConnected shows`() = runTest(timeout = 10.seconds) {
        val env = connectedEnv(enabled = true)
        disconnectFromTheTest(env)
        runCurrent()
        open(env, "/srv")
        assertTrue("disconnect() called" in env.presenter.state.value.debugDetail.orEmpty())

        env.connection.manager.close()
        runCurrent()

        val state = env.presenter.state.value
        assertEquals("Closed", state.errorDetail)
        val debug = assertNotNull(state.debugDetail)
        assertTrue("close() called" in debug, debug)
        assertFalse("disconnect() called" in debug, debug)
    }

    @Test
    fun `a listing, a retry and a dismiss clear the debug detail`() = runTest(timeout = 10.seconds) {
        val env = connectedEnv(enabled = true)

        env.connection.log.failNextList.set(IllegalStateException("broken"))
        open(env, "/srv")
        assertNotNull(env.presenter.state.value.debugDetail)
        open(env, "/srv")
        assertNull(env.presenter.state.value.debugDetail)

        env.connection.log.failNextList.set(IllegalStateException("broken"))
        open(env, "/srv/app")
        assertNotNull(env.presenter.state.value.debugDetail)
        env.presenter.onIntent(FolderIntent.DismissError)
        assertNull(env.presenter.state.value.debugDetail)

        env.connection.log.failNextList.set(IllegalStateException("broken"))
        open(env, "/srv/app")
        assertNotNull(env.presenter.state.value.debugDetail)
        env.presenter.onIntent(FolderIntent.Retry)
        assertNull(env.presenter.state.value.debugDetail)
    }

    @Test
    fun `other errors carry no debug detail`() = runTest(timeout = 10.seconds) {
        val env = connectedEnv(enabled = true)

        open(env, "/missing")

        assertEquals(FolderError.NotFound, env.presenter.state.value.error)
        assertNull(env.presenter.state.value.debugDetail)
    }

    @Test
    fun `with diagnostics off there is never a debug detail and the release detail is unchanged`() = runTest(timeout = 10.seconds) {
        val env = connectedEnv(enabled = false)

        env.connection.log.failNextList.set(IllegalStateException("broken listing"))
        open(env, "/srv")
        assertEquals(FolderError.Failed, env.presenter.state.value.error)
        assertEquals("java.lang.IllegalStateException", env.presenter.state.value.errorDetail)
        assertNull(env.presenter.state.value.debugDetail)

        disconnectFromTheTest(env)
        runCurrent()
        open(env, "/srv")
        assertEquals(FolderError.NotConnected, env.presenter.state.value.error)
        assertEquals("Idle", env.presenter.state.value.errorDetail)
        assertNull(env.presenter.state.value.debugDetail)

        env.connection.manager.close()
        runCurrent()
        assertEquals("Closed", env.presenter.state.value.errorDetail)
        assertNull(env.presenter.state.value.debugDetail)
        assertNull(env.connection.manager.lastEnd)
    }
}
