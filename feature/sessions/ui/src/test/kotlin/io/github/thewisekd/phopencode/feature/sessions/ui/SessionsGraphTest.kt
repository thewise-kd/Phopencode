package io.github.thewisekd.phopencode.feature.sessions.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListPresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManagePresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
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

private const val DELETED = "ses_deleted"
private const val OTHER = "ses_other"
private const val CHILD = "ses_child"
private const val TURN = "msg_turn"
private const val FOLDER = "/srv/çalışma alanı"

/**
 * Where the graph goes once a session is deleted: never back to one of its screens. The chat
 * destinations stand in for the chat feature's graph, which this module cannot see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SessionsGraphTest {
    @get:Rule
    val compose = createComposeRule()

    private val manageEvents = Channel<SessionManageEvent>(Channel.BUFFERED)
    private lateinit var navController: NavHostController

    private val listPresenter = object : SessionListPresenter {
        override val state: StateFlow<SessionListState> = MutableStateFlow(SessionListState())
        override val events: Flow<SessionListEvent> = emptyFlow()
        override fun onIntent(intent: SessionListIntent) = Unit
    }

    private val managePresenter = object : SessionManagePresenter {
        override val state: StateFlow<SessionManageState> = MutableStateFlow(SessionManageState())
        override val events: Flow<SessionManageEvent> = manageEvents.receiveAsFlow()
        override fun onIntent(intent: SessionManageIntent) = Unit
    }

    @Before
    fun startDi() {
        startKoin {
            modules(
                module {
                    factory<SessionListPresenter> { listPresenter }
                    factory<SessionManagePresenter> { managePresenter }
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
                    composable<NotifyRoute.Pending> { Stand("pending") }
                    composable<ChatRoute.Chat> { Stand("chat") }
                    composable<ChatRoute.Subagents> { Stand("subagents") }
                    composable<ChatRoute.ChildSession> { Stand("child") }
                    composable<ChatRoute.TurnDiff> { Stand("diff") }
                    composable<ChatRoute.TokenUsage> { Stand("usage") }
                    sessionsGraph(navController)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Stand(tag: String) {
        Box(Modifier.fillMaxSize().testTag(tag))
    }

    private fun open(vararg routes: Route) {
        routes.forEach { route -> compose.runOnIdle { navController.navigate(route) } }
        compose.waitForIdle()
    }

    private fun delete(directory: String?) {
        manageEvents.trySend(SessionManageEvent.Deleted(directory))
        compose.waitForIdle()
    }

    private fun routeOf(entry: NavBackStackEntry): Route? {
        val destination = entry.destination
        return when {
            destination.hasRoute<ConnectRoute.ServerList>() -> ConnectRoute.ServerList
            destination.hasRoute<NotifyRoute.Pending>() -> NotifyRoute.Pending
            destination.hasRoute<ChatRoute.Chat>() -> entry.toRoute<ChatRoute.Chat>()
            destination.hasRoute<ChatRoute.Subagents>() -> entry.toRoute<ChatRoute.Subagents>()
            destination.hasRoute<ChatRoute.ChildSession>() -> entry.toRoute<ChatRoute.ChildSession>()
            destination.hasRoute<ChatRoute.TurnDiff>() -> entry.toRoute<ChatRoute.TurnDiff>()
            destination.hasRoute<ChatRoute.TokenUsage>() -> entry.toRoute<ChatRoute.TokenUsage>()
            destination.hasRoute<SessionsRoute.SessionList>() -> entry.toRoute<SessionsRoute.SessionList>()
            destination.hasRoute<SessionsRoute.Create>() -> entry.toRoute<SessionsRoute.Create>()
            destination.hasRoute<SessionsRoute.Manage>() -> entry.toRoute<SessionsRoute.Manage>()
            else -> null // The graph itself.
        }
    }

    private fun backStack(): List<Route> = compose.runOnIdle {
        navController.currentBackStack.value.mapNotNull(::routeOf)
    }

    @Test
    fun `with a list on the back stack, a deletion goes back to it past the session's chat`() {
        setNavHost()
        open(SessionsRoute.SessionList(directory = FOLDER), ChatRoute.Chat(DELETED), SessionsRoute.Manage(DELETED))

        delete(FOLDER)

        assertEquals(listOf(ConnectRoute.ServerList, SessionsRoute.SessionList(directory = FOLDER)), backStack())
    }

    @Test
    fun `a list under another screen is reached past the session's screens above it`() {
        setNavHost()
        open(
            SessionsRoute.SessionList(),
            NotifyRoute.Pending,
            ChatRoute.Chat(DELETED),
            SessionsRoute.Manage(DELETED),
        )

        delete(FOLDER)

        assertEquals(listOf(ConnectRoute.ServerList, SessionsRoute.SessionList()), backStack())
    }

    @Test
    fun `without a list, a deletion leaves every screen of the session for a list of its folder`() {
        setNavHost()
        open(NotifyRoute.Pending, ChatRoute.Chat(OTHER), ChatRoute.Chat(DELETED), SessionsRoute.Manage(DELETED))

        delete(FOLDER)

        assertEquals(
            listOf(
                ConnectRoute.ServerList,
                NotifyRoute.Pending,
                ChatRoute.Chat(OTHER),
                SessionsRoute.SessionList(directory = FOLDER),
            ),
            backStack(),
        )
    }

    @Test
    fun `without a list or a folder, a deletion opens the list of every folder`() {
        setNavHost()
        open(NotifyRoute.Pending, SessionsRoute.Manage(DELETED))

        delete(directory = null)

        assertEquals(listOf(ConnectRoute.ServerList, NotifyRoute.Pending, SessionsRoute.SessionList()), backStack())
    }

    @Test
    fun `a deletion from the management of a session leaves its subagents, child, diff and usage screens too`() {
        setNavHost()
        open(
            SessionsRoute.SessionList(directory = FOLDER),
            ChatRoute.Chat(DELETED),
            ChatRoute.Subagents(DELETED),
            ChatRoute.ChildSession(CHILD),
            ChatRoute.TurnDiff(DELETED, TURN),
            ChatRoute.TokenUsage(DELETED),
            SessionsRoute.Manage(DELETED),
        )

        delete(FOLDER)

        assertEquals(listOf(ConnectRoute.ServerList, SessionsRoute.SessionList(directory = FOLDER)), backStack())
    }

    @Test
    fun `screens of the session are left from the lowest one, even with other screens between`() {
        setNavHost()
        open(
            NotifyRoute.Pending,
            ChatRoute.TokenUsage(DELETED),
            ChatRoute.Chat(OTHER),
            ChatRoute.Subagents(DELETED),
            SessionsRoute.Manage(DELETED),
        )

        delete(FOLDER)

        assertEquals(
            listOf(ConnectRoute.ServerList, NotifyRoute.Pending, SessionsRoute.SessionList(directory = FOLDER)),
            backStack(),
        )
    }

    @Test
    fun `a list above the session's lowest screen goes with it, and the list below is reached`() {
        setNavHost()
        open(
            SessionsRoute.SessionList(),
            ChatRoute.Chat(DELETED),
            SessionsRoute.SessionList(directory = FOLDER),
            ChatRoute.Chat(OTHER),
            SessionsRoute.Manage(DELETED),
        )

        delete(FOLDER)

        assertEquals(listOf(ConnectRoute.ServerList, SessionsRoute.SessionList()), backStack())
    }

    @Test
    fun `a deletion from a diff's management, with no chat behind, goes back to the list past other screens`() {
        setNavHost()
        open(
            SessionsRoute.SessionList(),
            NotifyRoute.Pending,
            ChatRoute.TurnDiff(DELETED, TURN),
            SessionsRoute.Manage(DELETED),
        )

        delete(FOLDER)

        assertEquals(listOf(ConnectRoute.ServerList, SessionsRoute.SessionList()), backStack())
    }

    @Test
    fun `a list between another session's chat and the deleted session's screens is reached`() {
        setNavHost()
        open(
            ChatRoute.Chat(OTHER),
            SessionsRoute.SessionList(directory = FOLDER),
            ChatRoute.Subagents(DELETED),
            ChatRoute.ChildSession(CHILD),
            SessionsRoute.Manage(DELETED),
        )

        delete(directory = null)

        assertEquals(
            listOf(ConnectRoute.ServerList, ChatRoute.Chat(OTHER), SessionsRoute.SessionList(directory = FOLDER)),
            backStack(),
        )
    }
}
