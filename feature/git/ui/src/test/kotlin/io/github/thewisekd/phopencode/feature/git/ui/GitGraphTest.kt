package io.github.thewisekd.phopencode.feature.git.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
import io.github.thewisekd.phopencode.feature.git.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemotePresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOT = "/srv/çalışma alanı"
private const val FILE = "src/main kodu.kt"
private const val HASH = "0123456789abcdef0123456789abcdef01234567"

/** A presenter that records the intents it gets and sends the events a test puts in [sent]. */
private class Recording<S, I, E>(initial: S) : Presenter<S, I, E> {
    val sent = Channel<E>(Channel.UNLIMITED)
    val intents: MutableList<I> = Collections.synchronizedList(mutableListOf())

    override val state: StateFlow<S> = MutableStateFlow(initial)
    override val events: Flow<E> = sent.receiveAsFlow()

    override fun onIntent(intent: I) {
        intents += intent
    }
}

/**
 * The graph opens every route's screen, turns each presenter's events into navigation between the
 * git routes, and refreshes a screen shown again. The presenters are fakes; one instance per type
 * serves every destination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class GitGraphTest {
    @get:Rule
    val compose = createComposeRule()

    private val status = Recording<StatusState, StatusIntent, StatusEvent>(StatusState(directory = ROOT))
    private val diff = Recording<DiffState, DiffIntent, DiffEvent>(DiffState(target = DiffTarget.Working(FILE)))
    private val log = Recording<LogState, LogIntent, LogEvent>(LogState(directory = ROOT))
    private val detail = Recording<CommitDetailState, CommitDetailIntent, CommitDetailEvent>(CommitDetailState(commit = HASH))
    private val commit = Recording<CommitState, CommitIntent, CommitEvent>(CommitState(directory = ROOT))
    private val branches = Recording<BranchesState, BranchIntent, BranchEvent>(BranchesState(directory = ROOT))
    private val remote = Recording<RemoteState, RemoteIntent, RemoteEvent>(RemoteState(directory = ROOT))

    private lateinit var navController: NavHostController

    @Before
    fun startDi() {
        startKoin {
            modules(
                module {
                    factory<StatusPresenter> {
                        object : StatusPresenter, Presenter<StatusState, StatusIntent, StatusEvent> by status {}
                    }
                    factory<DiffPresenter> { object : DiffPresenter, Presenter<DiffState, DiffIntent, DiffEvent> by diff {} }
                    factory<LogPresenter> { object : LogPresenter, Presenter<LogState, LogIntent, LogEvent> by log {} }
                    factory<CommitDetailPresenter> {
                        object :
                            CommitDetailPresenter,
                            Presenter<CommitDetailState, CommitDetailIntent, CommitDetailEvent> by detail {}
                    }
                    factory<CommitPresenter> {
                        object : CommitPresenter, Presenter<CommitState, CommitIntent, CommitEvent> by commit {}
                    }
                    factory<BranchesPresenter> {
                        object : BranchesPresenter, Presenter<BranchesState, BranchIntent, BranchEvent> by branches {}
                    }
                    factory<RemotePresenter> {
                        object : RemotePresenter, Presenter<RemoteState, RemoteIntent, RemoteEvent> by remote {}
                    }
                },
            )
        }
    }

    @After
    fun stopDi() {
        stopKoin()
    }

    private fun setNavHost() {
        compose.setContent {
            PhopencodeTheme {
                navController = rememberNavController()
                NavHost(navController = navController, startDestination = ConnectRoute.ServerList) {
                    composable<ConnectRoute.ServerList> { Stand("start") }
                    gitGraph(navController)
                }
            }
        }
    }

    @Composable
    private fun Stand(tag: String) {
        Box(Modifier.fillMaxSize().testTag(tag))
    }

    private fun open(vararg routes: Route) {
        routes.forEach { route -> compose.runOnIdle { navController.navigate(route) } }
        compose.waitForIdle()
    }

    private fun <E> send(presenter: Recording<*, *, E>, event: E) {
        presenter.sent.trySend(event)
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle { navController.popBackStack() }
        compose.waitForIdle()
    }

    private fun routeOf(entry: NavBackStackEntry): Route? {
        val destination = entry.destination
        return when {
            destination.hasRoute<ConnectRoute.ServerList>() -> ConnectRoute.ServerList
            destination.hasRoute<GitRoute.Status>() -> entry.toRoute<GitRoute.Status>()
            destination.hasRoute<GitRoute.FileDiff>() -> entry.toRoute<GitRoute.FileDiff>()
            destination.hasRoute<GitRoute.Log>() -> entry.toRoute<GitRoute.Log>()
            destination.hasRoute<GitRoute.CommitDetail>() -> entry.toRoute<GitRoute.CommitDetail>()
            destination.hasRoute<GitRoute.CommitFileDiff>() -> entry.toRoute<GitRoute.CommitFileDiff>()
            destination.hasRoute<GitRoute.Commit>() -> entry.toRoute<GitRoute.Commit>()
            destination.hasRoute<GitRoute.Branches>() -> entry.toRoute<GitRoute.Branches>()
            destination.hasRoute<GitRoute.Remote>() -> entry.toRoute<GitRoute.Remote>()
            else -> null // The graph itself.
        }
    }

    private fun backStack(): List<Route> = compose.runOnIdle {
        navController.currentBackStack.value.mapNotNull(::routeOf)
    }

    private fun <I> intents(presenter: Recording<*, I, *>): List<I> = compose.runOnIdle { presenter.intents.toList() }

    @Test
    fun `every route shows its screen with its tag`() {
        setNavHost()
        val routes = listOf(
            GitRoute.Status(ROOT),
            GitRoute.FileDiff(ROOT, FILE, staged = true),
            GitRoute.Log(ROOT),
            GitRoute.CommitDetail(ROOT, HASH),
            GitRoute.CommitFileDiff(ROOT, HASH, FILE),
            GitRoute.Commit(ROOT),
            GitRoute.Branches(ROOT),
            GitRoute.Remote(ROOT),
        )

        routes.forEach { route ->
            open(route)
            compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
        }
    }

    @Test
    fun `the status opens a file's diff, staged or not`() {
        setNavHost()
        open(GitRoute.Status(ROOT))

        send(status, StatusEvent.OpenDiff(FILE, staged = true))
        assertEquals(
            listOf(ConnectRoute.ServerList, GitRoute.Status(ROOT), GitRoute.FileDiff(ROOT, FILE, staged = true)),
            backStack(),
        )

        back()
        send(status, StatusEvent.OpenDiff(FILE, staged = false))
        assertEquals(listOf(ConnectRoute.ServerList, GitRoute.Status(ROOT), GitRoute.FileDiff(ROOT, FILE)), backStack())
    }

    @Test
    fun `the status opens the commit, history, branch and remote screens`() {
        setNavHost()
        open(GitRoute.Status(ROOT))
        val opened = listOf(
            StatusEvent.OpenCommit to GitRoute.Commit(ROOT),
            StatusEvent.OpenLog to GitRoute.Log(ROOT),
            StatusEvent.OpenBranches to GitRoute.Branches(ROOT),
            StatusEvent.OpenRemote to GitRoute.Remote(ROOT),
        )

        opened.forEach { (event, route) ->
            send(status, event)
            assertEquals(listOf(ConnectRoute.ServerList, GitRoute.Status(ROOT), route), backStack())
            back()
        }
    }

    @Test
    fun `the status's first showing asks for nothing, and it refreshes when shown again`() {
        setNavHost()
        open(GitRoute.Status(ROOT))
        assertTrue(intents(status).isEmpty())

        send(status, StatusEvent.OpenCommit)
        back()

        assertEquals(listOf<StatusIntent>(StatusIntent.Refresh), intents(status))
    }

    @Test
    fun `closing the status goes back`() {
        setNavHost()
        open(GitRoute.Status(ROOT))

        send(status, StatusEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }

    @Test
    fun `the history opens a commit, whose file opens its diff in the commit`() {
        setNavHost()
        open(GitRoute.Status(ROOT), GitRoute.Log(ROOT))

        send(log, LogEvent.OpenCommit(HASH))
        send(detail, CommitDetailEvent.OpenFileDiff(FILE))

        assertEquals(
            listOf(
                ConnectRoute.ServerList,
                GitRoute.Status(ROOT),
                GitRoute.Log(ROOT),
                GitRoute.CommitDetail(ROOT, HASH),
                GitRoute.CommitFileDiff(ROOT, HASH, FILE),
            ),
            backStack(),
        )
    }

    @Test
    fun `closing a diff, the details and the history goes back`() {
        setNavHost()
        open(GitRoute.Log(ROOT), GitRoute.CommitDetail(ROOT, HASH), GitRoute.CommitFileDiff(ROOT, HASH, FILE))

        send(diff, DiffEvent.Close)
        assertEquals(listOf(ConnectRoute.ServerList, GitRoute.Log(ROOT), GitRoute.CommitDetail(ROOT, HASH)), backStack())
        send(detail, CommitDetailEvent.Close)
        assertEquals(listOf(ConnectRoute.ServerList, GitRoute.Log(ROOT)), backStack())
        send(log, LogEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }

    @Test
    fun `a diff shown again refreshes`() {
        setNavHost()
        open(GitRoute.FileDiff(ROOT, FILE), GitRoute.Log(ROOT))
        assertTrue(intents(diff).isEmpty())

        back()

        assertEquals(listOf<DiffIntent>(DiffIntent.Refresh), intents(diff))
    }

    @Test
    fun `the commit screen opens a diff and goes back once committed`() {
        setNavHost()
        open(GitRoute.Status(ROOT), GitRoute.Commit(ROOT))

        send(commit, CommitEvent.OpenDiff(FILE, staged = true))
        assertEquals(
            listOf(ConnectRoute.ServerList, GitRoute.Status(ROOT), GitRoute.Commit(ROOT), GitRoute.FileDiff(ROOT, FILE, staged = true)),
            backStack(),
        )

        back()
        assertEquals(listOf<CommitIntent>(CommitIntent.Refresh), intents(commit))
        send(commit, CommitEvent.Committed(HASH))

        assertEquals(listOf(ConnectRoute.ServerList, GitRoute.Status(ROOT)), backStack())
        assertEquals(listOf<StatusIntent>(StatusIntent.Refresh), intents(status))
    }

    @Test
    fun `a branch switch goes back to the status, which refreshes`() {
        setNavHost()
        open(GitRoute.Status(ROOT), GitRoute.Branches(ROOT))
        assertTrue(intents(branches).isEmpty())

        send(branches, BranchEvent.Switched)

        assertEquals(listOf(ConnectRoute.ServerList, GitRoute.Status(ROOT)), backStack())
        assertEquals(listOf<StatusIntent>(StatusIntent.Refresh), intents(status))
    }

    @Test
    fun `the branches and remote screens refresh when shown again`() {
        setNavHost()
        open(GitRoute.Branches(ROOT), GitRoute.Remote(ROOT), GitRoute.Log(ROOT))
        assertTrue(intents(remote).isEmpty())

        back()
        assertEquals(listOf<RemoteIntent>(RemoteIntent.Refresh), intents(remote))
        back()

        assertEquals(listOf<BranchIntent>(BranchIntent.Refresh), intents(branches))
    }

    @Test
    fun `closing the commit, branch and remote screens goes back`() {
        setNavHost()
        open(GitRoute.Status(ROOT), GitRoute.Commit(ROOT), GitRoute.Branches(ROOT), GitRoute.Remote(ROOT))

        send(remote, RemoteEvent.Close)
        assertEquals(
            listOf(ConnectRoute.ServerList, GitRoute.Status(ROOT), GitRoute.Commit(ROOT), GitRoute.Branches(ROOT)),
            backStack(),
        )
        send(branches, BranchEvent.Close)
        assertEquals(listOf(ConnectRoute.ServerList, GitRoute.Status(ROOT), GitRoute.Commit(ROOT)), backStack())
        send(commit, CommitEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList, GitRoute.Status(ROOT)), backStack())
    }
}
