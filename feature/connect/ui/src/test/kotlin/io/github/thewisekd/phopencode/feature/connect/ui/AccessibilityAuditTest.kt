package io.github.thewisekd.phopencode.feature.connect.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormAuthMethod
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormField
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormFieldError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListState
import io.github.thewisekd.phopencode.feature.connect.ui.folder.FolderScreen
import io.github.thewisekd.phopencode.feature.connect.ui.folder.RECENT_ONE
import io.github.thewisekd.phopencode.feature.connect.ui.folder.RECENT_TWO
import io.github.thewisekd.phopencode.feature.connect.ui.folder.browsing
import io.github.thewisekd.phopencode.feature.connect.ui.hostkey.HostKeyScreen
import io.github.thewisekd.phopencode.feature.connect.ui.hostkey.TEST_ROUTE
import io.github.thewisekd.phopencode.feature.connect.ui.hostkey.asking
import io.github.thewisekd.phopencode.feature.connect.ui.hostkey.changed
import io.github.thewisekd.phopencode.feature.connect.ui.hostkey.firstSeen
import io.github.thewisekd.phopencode.feature.connect.ui.keys.KeysScreen
import io.github.thewisekd.phopencode.feature.connect.ui.keys.testKey
import io.github.thewisekd.phopencode.feature.connect.ui.progress.ProgressScreen
import io.github.thewisekd.phopencode.feature.connect.ui.progress.ROUTE
import io.github.thewisekd.phopencode.feature.connect.ui.progress.SERVER_NAME
import io.github.thewisekd.phopencode.feature.connect.ui.serverform.HOME_KEY
import io.github.thewisekd.phopencode.feature.connect.ui.serverform.NEW_SERVER
import io.github.thewisekd.phopencode.feature.connect.ui.serverform.ServerFormScreen
import io.github.thewisekd.phopencode.feature.connect.ui.serverform.WORK_KEY
import io.github.thewisekd.phopencode.feature.connect.ui.serverform.loadedState
import io.github.thewisekd.phopencode.feature.connect.ui.serverlist.ServerListScreen
import io.github.thewisekd.phopencode.feature.connect.ui.serverlist.firstServer
import io.github.thewisekd.phopencode.feature.connect.ui.serverlist.secondServer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every connection screen, loaded, empty, failed and loading, at the largest font: every control is
 * named and big enough to tap, and no text is cut off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class AccessibilityAuditTest {
    @get:Rule
    val compose = createComposeRule()

    private fun audit(content: @Composable () -> Unit) {
        compose.setThemedAt { Surface(content = content) }
        compose.assertAccessible()
    }

    private fun serverList(state: ServerListState) = audit { ServerListScreen(ConnectRoute.ServerList, state, {}) }

    @Test
    fun `the server list`() =
        serverList(ServerListState(loading = false, servers = listOf(firstServer, secondServer)))

    @Test
    fun `the server list while loading`() = serverList(ServerListState())

    @Test
    fun `the server list empty`() = serverList(ServerListState(loading = false))

    @Test
    fun `the server list failed`() {
        serverList(ServerListState(loading = false, error = ServerListError.LoadFailed))
        compose.assertErrorAnnounced(R.string.connect_serverlist_error_load)
    }

    @Test
    fun `a new server`() = audit { ServerFormScreen(NEW_SERVER, loadedState(), {}) }

    @Test
    fun `a server with a key, its fields wrong and its saving failed`() {
        val state = loadedState(isNew = false).copy(
            authMethod = ServerFormAuthMethod.Key,
            keys = listOf(WORK_KEY, HOME_KEY),
            fieldErrors = mapOf(
                ServerFormField.Name to ServerFormFieldError.Required,
                ServerFormField.Port to ServerFormFieldError.Invalid,
            ),
            error = ServerFormError.SaveFailed,
        )
        audit { ServerFormScreen(NEW_SERVER, state, {}) }
        compose.assertErrorAnnounced(R.string.connect_serverform_error_save_failed)
    }

    @Test
    fun `a new host key`() = audit { HostKeyScreen(TEST_ROUTE, asking(firstSeen), {}) }

    @Test
    fun `a changed host key`() = audit { HostKeyScreen(TEST_ROUTE, asking(changed), {}) }

    @Test
    fun `a host key while loading`() = audit { HostKeyScreen(TEST_ROUTE, HostKeyState(), {}) }

    @Test
    fun `a host key failed`() {
        audit { HostKeyScreen(TEST_ROUTE, HostKeyState(loading = false, error = HostKeyError.ServerMissing), {}) }
        compose.assertErrorAnnounced(R.string.connect_hostkey_error_server_missing)
    }

    @Test
    fun `the keys`() =
        audit { KeysScreen(ConnectRoute.Keys, KeysState(loading = false, keys = listOf(testKey("1"), testKey("2"))), {}) }

    @Test
    fun `the keys while loading`() = audit { KeysScreen(ConnectRoute.Keys, KeysState(), {}) }

    @Test
    fun `no keys`() = audit { KeysScreen(ConnectRoute.Keys, KeysState(loading = false), {}) }

    @Test
    fun `the keys failed`() {
        audit { KeysScreen(ConnectRoute.Keys, KeysState(loading = false, error = KeysError.LoadFailed), {}) }
        compose.assertErrorAnnounced(R.string.connect_keys_error_load_failed)
    }

    @Test
    fun `connecting`() = audit { ProgressScreen(ROUTE, ProgressState(serverName = SERVER_NAME), {}) }

    @Test
    fun `connecting failed`() {
        val state = ProgressState(
            serverName = SERVER_NAME,
            step = ProgressStep.Server,
            error = ProgressError.Unreachable,
            actions = setOf(ProgressAction.Retry, ProgressAction.EditServer),
        )
        audit { ProgressScreen(ROUTE, state, {}) }
        compose.assertErrorAnnounced(R.string.connect_progress_error_unreachable)
    }

    @Test
    fun `a folder`() = audit { FolderScreen(ConnectRoute.FolderPicker, browsing.copy(recent = listOf(RECENT_ONE, RECENT_TWO)), {}) }

    @Test
    fun `a folder while loading`() = audit { FolderScreen(ConnectRoute.FolderPicker, FolderState(), {}) }

    @Test
    fun `an empty folder`() = audit { FolderScreen(ConnectRoute.FolderPicker, browsing.copy(entries = emptyList()), {}) }

    @Test
    fun `a folder failed`() {
        audit {
            FolderScreen(ConnectRoute.FolderPicker, browsing.copy(entries = emptyList(), error = FolderError.PermissionDenied), {})
        }
        compose.assertErrorAnnounced(R.string.connect_folder_error_permission_denied)
    }
}
