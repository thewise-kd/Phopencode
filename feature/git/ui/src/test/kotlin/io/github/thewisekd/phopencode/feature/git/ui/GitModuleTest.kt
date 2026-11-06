package io.github.thewisekd.phopencode.feature.git.ui

import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.remote.connection.ServerTools
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.ConnectionGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemotePresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

private const val ROOT = "/srv/çalışma alanı"
private const val HASH = "0123456789abcdef0123456789abcdef01234567"
private const val FILE = "src/main kodu.kt"

/** An implementation that fails every call; the idle manager below never makes one. */
private inline fun <reified T : Any> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Not used: ${method.name}")
    } as T

/** A manager that is never told to connect, as the app's is before the user picks a server. */
private fun idleConnection(scope: CoroutineScope) = ConnectionManager(
    connector = unused<SshConnector>(),
    secrets = unused<SecretStore>(),
    knownHosts = unused<KnownHostsStore>(),
    servers = ServerResolver(unused<SecretStore>(), unused<ServerTools>()),
    clients = OpencodeClientFactory { _, _, _ -> error("Not used") },
    scope = scope,
)

/**
 * `gitModule` builds every presenter from the parameters the graph passes, the destination's route
 * and scope, and binds the git connection to the connection manager. The manager comes from the
 * app's core modules; here it is an idle one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class GitModuleTest {
    private val appScope = CoroutineScope(Job())
    private val screenScope = CoroutineScope(Job())

    private fun koin(): Koin = startKoin {
        modules(
            gitModule,
            module {
                single<CoroutineScope> { appScope }
                single { idleConnection(appScope) }
            },
        )
    }.koin

    @After
    fun stop() {
        stopKoin()
        appScope.cancel()
        screenScope.cancel()
    }

    @Test
    fun `every presenter starts from its route`() {
        val koin = koin()
        val fileDiff = GitRoute.FileDiff(ROOT, FILE, staged = true)
        val commitDiff = GitRoute.CommitFileDiff(ROOT, HASH, FILE)

        assertEquals(
            StatusState(directory = ROOT),
            koin.get<StatusPresenter> { parametersOf(GitRoute.Status(ROOT), screenScope) }.state.value,
        )
        assertEquals(
            DiffState(target = DiffTarget.Staged(FILE)),
            koin.get<DiffPresenter> { parametersOf(fileDiff, screenScope) }.state.value,
        )
        assertEquals(
            DiffState(target = DiffTarget.InCommit(HASH, FILE)),
            koin.get<DiffPresenter> { parametersOf(commitDiff, screenScope) }.state.value,
        )
        assertEquals(LogState(directory = ROOT), koin.get<LogPresenter> { parametersOf(GitRoute.Log(ROOT), screenScope) }.state.value)
        assertEquals(
            CommitDetailState(commit = HASH),
            koin.get<CommitDetailPresenter> { parametersOf(GitRoute.CommitDetail(ROOT, HASH), screenScope) }.state.value,
        )
        assertEquals(
            CommitState(directory = ROOT),
            koin.get<CommitPresenter> { parametersOf(GitRoute.Commit(ROOT), screenScope) }.state.value,
        )
        assertEquals(
            BranchesState(directory = ROOT),
            koin.get<BranchesPresenter> { parametersOf(GitRoute.Branches(ROOT), screenScope) }.state.value,
        )
        assertEquals(
            RemoteState(directory = ROOT),
            koin.get<RemotePresenter> { parametersOf(GitRoute.Remote(ROOT), screenScope) }.state.value,
        )
    }

    @Test
    fun `each destination gets its own presenter`() {
        val koin = koin()
        val route = GitRoute.Status(ROOT)

        assertNotSame(
            koin.get<StatusPresenter> { parametersOf(route, screenScope) },
            koin.get<StatusPresenter> { parametersOf(route, screenScope) },
        )
    }

    @Test
    fun `the git connection is one, on the connection manager`() {
        val koin = koin()

        assertIs<ConnectionGitConnection>(koin.get<GitConnection>())
        assertSame(koin.get<GitConnection>(), koin.get<GitConnection>())
    }

    @Test
    fun `without a connection git fails as not connected and runs nothing`() {
        val koin = koin()
        var ran = false

        val failure = assertFailsWith<GitException> { runBlocking { koin.get<GitConnection>().withExec { ran = true } } }

        assertEquals(GitFailure.NotConnected, failure.failure)
        assertEquals(false, ran)
    }
}
