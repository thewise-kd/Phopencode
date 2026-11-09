package io.github.thewisekd.phopencode.feature.notify.ui

import android.net.Uri
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
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.DeepLinks
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingEvent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupEvent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
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
 * The graph opens both notify routes, opens the pending list from `phopencode://pending`, and turns
 * the presenters' events into navigation: back, to a session or a child session, and to the server
 * list. The presenters are fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class NotifyGraphTest {
    @get:Rule
    val compose = createComposeRule()

    private val setup = Recording<SetupState, SetupIntent, SetupEvent>(SetupState())
    private val pending = Recording<PendingState, PendingIntent, PendingEvent>(PendingState())

    private lateinit var navController: NavHostController

    @Before
    fun startDi() {
        startKoin {
            modules(
                module {
                    factory<SetupPresenter> { object : SetupPresenter, Presenter<SetupState, SetupIntent, SetupEvent> by setup {} }
                    factory<PendingPresenter> {
                        object : PendingPresenter, Presenter<PendingState, PendingIntent, PendingEvent> by pending {}
                    }
                },
            )
        }
    }

    @After
    fun stopDi() {
        stopKoin()
    }

    private fun setNavHost(start: Route = ConnectRoute.ServerList) {
        compose.setContent {
            PhopencodeTheme {
                navController = rememberNavController()
                NavHost(navController = navController, startDestination = start) {
                    composable<ConnectRoute.ServerList> { Stand("start") }
                    composable<ConnectRoute.Keys> { Stand("keys") }
                    composable<ChatRoute.Chat> { Stand("chat") }
                    composable<ChatRoute.ChildSession> { Stand("child") }
                    notifyGraph(navController)
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

    private fun routeOf(entry: NavBackStackEntry): Route? {
        val destination = entry.destination
        return when {
            destination.hasRoute<ConnectRoute.ServerList>() -> ConnectRoute.ServerList
            destination.hasRoute<ConnectRoute.Keys>() -> ConnectRoute.Keys
            destination.hasRoute<ChatRoute.Chat>() -> entry.toRoute<ChatRoute.Chat>()
            destination.hasRoute<ChatRoute.ChildSession>() -> entry.toRoute<ChatRoute.ChildSession>()
            destination.hasRoute<NotifyRoute.Setup>() -> NotifyRoute.Setup
            destination.hasRoute<NotifyRoute.Pending>() -> NotifyRoute.Pending
            else -> null // The graph itself.
        }
    }

    private fun backStack(): List<Route> = compose.runOnIdle {
        navController.currentBackStack.value.mapNotNull(::routeOf)
    }

    @Test
    fun `every route shows its screen with its tag`() {
        setNavHost()

        listOf(NotifyRoute.Setup, NotifyRoute.Pending).forEach { route ->
            open(route)
            compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
        }
    }

    @Test
    fun `the deep link opens the pending list`() {
        setNavHost()

        compose.runOnIdle { navController.navigate(Uri.parse(DeepLinks.PENDING)) }
        compose.waitForIdle()

        assertEquals(NotifyRoute.Pending, backStack().last())
        compose.onNodeWithTag("placeholder:${NotifyRoute.Pending}").assertIsDisplayed()
    }

    @Test
    fun `closing the setup goes back`() {
        setNavHost()
        open(NotifyRoute.Setup)

        send(setup, SetupEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
        assertTrue(compose.runOnIdle { setup.intents.isEmpty() }, "the setup reads its status itself")
    }

    @Test
    fun `a pending item of a root session opens its chat above the list`() {
        setNavHost()
        open(NotifyRoute.Pending)

        send(pending, PendingEvent.OpenSession(SessionId("session-1"), isChild = false))

        assertEquals(listOf(ConnectRoute.ServerList, NotifyRoute.Pending, ChatRoute.Chat("session-1")), backStack())
    }

    @Test
    fun `a pending item of a child session opens the child session above the list`() {
        setNavHost()
        open(NotifyRoute.Pending)

        send(pending, PendingEvent.OpenSession(SessionId("child-1"), isChild = true))

        assertEquals(listOf(ConnectRoute.ServerList, NotifyRoute.Pending, ChatRoute.ChildSession("child-1")), backStack())
    }

    @Test
    fun `closing the pending list goes back`() {
        setNavHost()
        open(NotifyRoute.Pending)

        send(pending, PendingEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }

    @Test
    fun `connecting goes back to the server list, dropping everything above it`() {
        setNavHost()
        open(ConnectRoute.Keys, NotifyRoute.Pending)

        send(pending, PendingEvent.OpenServerList)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }

    @Test
    fun `connecting opens the server list when it is not on the back stack`() {
        setNavHost(start = NotifyRoute.Pending)

        send(pending, PendingEvent.OpenServerList)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }
}
