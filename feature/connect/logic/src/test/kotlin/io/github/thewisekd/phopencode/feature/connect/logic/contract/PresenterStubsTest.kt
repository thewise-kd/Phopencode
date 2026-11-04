package io.github.thewisekd.phopencode.feature.connect.logic.contract

import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshConnector
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeProfileRepository
import io.github.thewisekd.phopencode.core.testing.storage.FakeRecentFoldersStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSshKeyIndex
import io.github.thewisekd.phopencode.feature.connect.logic.folder.DefaultFolderPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.hostkey.DefaultHostKeyPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.keys.DefaultKeysPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.progress.DefaultProgressPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.serverform.DefaultServerFormPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.serverlist.DefaultServerListPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/**
 * Every presenter is built with the constructor the contract fixes, as `ConnectModule` builds it,
 * and shows its screen's initial state before anything has run. The logic tasks fill the presenters
 * in; these hold for the filled-in ones too.
 */
class PresenterStubsTest {
    private val serverId = ServerProfileId("server-1")
    private val profiles = FakeProfileRepository()
    private val secrets = FakeSecretStore()
    private val knownHosts = FakeKnownHostsStore()
    private val keyIndex = FakeSshKeyIndex()
    private val recentFolders = FakeRecentFoldersStore()

    private fun connection(scope: CoroutineScope) = ConnectionManager(
        connector = FakeSshConnector(serverKey = HostKey("ssh-ed25519", ByteArray(32) { 7 })),
        secrets = secrets,
        knownHosts = knownHosts,
        servers = ServerResolver(secrets),
        clients = OpencodeClientFactory { _, _, _ -> error("No client in this test") },
        scope = scope,
    )

    @Test
    fun `the server list starts loading`() = runTest {
        val presenter: ServerListPresenter = DefaultServerListPresenter(
            profiles = profiles,
            secrets = secrets,
            keyIndex = keyIndex,
            recentFolders = recentFolders,
            scope = backgroundScope,
        )

        assertEquals(ServerListState(), presenter.state.value)
    }

    @Test
    fun `the server form starts as a new server without an ID`() = runTest {
        val presenter: ServerFormPresenter = DefaultServerFormPresenter(
            serverId = null,
            profiles = profiles,
            secrets = secrets,
            keyIndex = keyIndex,
            scope = backgroundScope,
        )

        assertEquals(ServerFormState(isNew = true), presenter.state.value)
    }

    @Test
    fun `the server form edits the server it is given`() = runTest {
        val presenter: ServerFormPresenter = DefaultServerFormPresenter(
            serverId = serverId,
            profiles = profiles,
            secrets = secrets,
            keyIndex = keyIndex,
            scope = backgroundScope,
        )

        assertEquals(ServerFormState(isNew = false), presenter.state.value)
    }

    @Test
    fun `key management starts loading`() = runTest {
        val presenter: KeysPresenter = DefaultKeysPresenter(
            keyIndex = keyIndex,
            secrets = secrets,
            profiles = profiles,
            scope = backgroundScope,
        )

        assertEquals(KeysState(), presenter.state.value)
    }

    @Test
    fun `host key approval starts waiting for the connection`() = runTest {
        val presenter: HostKeyPresenter = DefaultHostKeyPresenter(
            serverId = serverId,
            connection = connection(backgroundScope),
            knownHosts = knownHosts,
            profiles = profiles,
            scope = backgroundScope,
        )

        assertEquals(HostKeyState(), presenter.state.value)
    }

    @Test
    fun `the folder picker starts loading`() = runTest {
        val presenter: FolderPresenter = DefaultFolderPresenter(
            connection = connection(backgroundScope),
            activeServer = ActiveServer(),
            recentFolders = recentFolders,
            scope = backgroundScope,
        )

        assertEquals(FolderState(), presenter.state.value)
    }

    @Test
    fun `the connection flow starts at the SSH step`() = runTest {
        val presenter: ProgressPresenter = DefaultProgressPresenter(
            serverId = serverId,
            connection = connection(backgroundScope),
            profiles = profiles,
            secrets = secrets,
            activeServer = ActiveServer(),
            scope = backgroundScope,
        )

        assertEquals(ProgressState(), presenter.state.value)
    }
}
