package io.github.thewisekd.phopencode.feature.sessions.logic.contract

import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshConnector
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeRecentFoldersStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.feature.sessions.logic.create.DefaultSessionCreatePresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.list.DefaultSessionListPresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.manage.DefaultSessionManagePresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/**
 * Every presenter is built with the constructor the contract fixes, as `SessionsModule` builds it,
 * and shows its screen's initial state before anything has run. The logic tasks fill the presenters
 * in; these hold for the filled-in ones too.
 */
class PresenterStubsTest {
    private val secrets = FakeSecretStore()
    private val recentFolders = FakeRecentFoldersStore()

    private fun connection(scope: CoroutineScope) = ConnectionManager(
        connector = FakeSshConnector(serverKey = HostKey("ssh-ed25519", ByteArray(32) { 7 })),
        secrets = secrets,
        knownHosts = FakeKnownHostsStore(),
        servers = ServerResolver(secrets),
        clients = OpencodeClientFactory { _, _, _ -> error("No client in this test") },
        scope = scope,
    )

    @Test
    fun `the list of every folder starts loading`() = runTest {
        val presenter: SessionListPresenter = DefaultSessionListPresenter(
            directory = null,
            connection = connection(backgroundScope),
            scope = backgroundScope,
        )

        assertEquals(SessionListState(), presenter.state.value)
    }

    @Test
    fun `the list of one folder starts loading that folder`() = runTest {
        val presenter: SessionListPresenter = DefaultSessionListPresenter(
            directory = "/srv/app",
            connection = connection(backgroundScope),
            scope = backgroundScope,
        )

        assertEquals(SessionListState(directory = "/srv/app"), presenter.state.value)
    }

    @Test
    fun `creation starts loading with nothing preselected`() = runTest {
        val presenter: SessionCreatePresenter = DefaultSessionCreatePresenter(
            directory = null,
            skillId = null,
            connection = connection(backgroundScope),
            recentFolders = recentFolders,
            scope = backgroundScope,
        )

        assertEquals(SessionCreateState(), presenter.state.value)
    }

    @Test
    fun `creation starts in the folder and with the skill it is given`() = runTest {
        val presenter: SessionCreatePresenter = DefaultSessionCreatePresenter(
            directory = "/srv/app",
            skillId = "skill-1",
            connection = connection(backgroundScope),
            recentFolders = recentFolders,
            scope = backgroundScope,
        )

        assertEquals(SessionCreateState(directory = "/srv/app", skillId = "skill-1"), presenter.state.value)
    }

    @Test
    fun `management starts loading its session`() = runTest {
        val presenter: SessionManagePresenter = DefaultSessionManagePresenter(
            sessionId = SessionId("ses_1"),
            connection = connection(backgroundScope),
            scope = backgroundScope,
        )

        assertEquals(SessionManageState(), presenter.state.value)
    }
}
