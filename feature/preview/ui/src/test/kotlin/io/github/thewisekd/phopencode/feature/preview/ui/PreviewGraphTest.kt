package io.github.thewisekd.phopencode.feature.preview.ui

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
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ExternalLink
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PagePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsPresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.Presenter
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
 * The graph opens both preview routes and turns the presenters' events into navigation: from the
 * port picker to a page above it, and back. The page's route reaches its presenter. The presenters
 * are fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class PreviewGraphTest {
    @get:Rule
    val compose = createComposeRule()

    private val ports = Recording<PortsState, PortsIntent, PortsEvent>(PortsState())
    private val page = Recording<PageState, PageIntent, PageEvent>(PageState(port = 3000))
    private val pageRoutes: MutableList<PreviewRoute.Page> = Collections.synchronizedList(mutableListOf())

    private lateinit var navController: NavHostController

    @Before
    fun startDi() {
        startKoin {
            modules(
                module {
                    factory<PortsPresenter> { object : PortsPresenter, Presenter<PortsState, PortsIntent, PortsEvent> by ports {} }
                    factory<PagePresenter> { (route: PreviewRoute.Page, _: Any) ->
                        pageRoutes += route
                        object : PagePresenter, Presenter<PageState, PageIntent, PageEvent> by page {
                            override fun decide(url: String): NavigationDecision = NavigationDecision.Refuse
                        }
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
                    previewGraph(navController)
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
            destination.hasRoute<PreviewRoute.Ports>() -> PreviewRoute.Ports
            destination.hasRoute<PreviewRoute.Page>() -> entry.toRoute<PreviewRoute.Page>()
            else -> null // The graph itself.
        }
    }

    private fun backStack(): List<Route> = compose.runOnIdle {
        navController.currentBackStack.value.mapNotNull(::routeOf)
    }

    @Test
    fun `every route shows its screen with its tag`() {
        setNavHost()

        listOf(PreviewRoute.Ports, PreviewRoute.Page(3000)).forEach { route ->
            open(route)
            compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
        }
    }

    @Test
    fun `picking a port opens its page above the picker`() {
        setNavHost()
        open(PreviewRoute.Ports)

        send(ports, PortsEvent.OpenPage(5173))

        assertEquals(listOf(ConnectRoute.ServerList, PreviewRoute.Ports, PreviewRoute.Page(5173)), backStack())
        assertEquals(listOf(PreviewRoute.Page(5173)), compose.runOnIdle { pageRoutes.toList() })
        compose.onNodeWithTag("placeholder:${PreviewRoute.Page(5173)}").assertIsDisplayed()
    }

    @Test
    fun `closing the picker goes back`() {
        setNavHost()
        open(PreviewRoute.Ports)

        send(ports, PortsEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
        assertTrue(compose.runOnIdle { ports.intents.isEmpty() }, "the picker lists the ports itself")
    }

    @Test
    fun `closing the page goes back to the picker`() {
        setNavHost()
        open(PreviewRoute.Ports, PreviewRoute.Page(3000))

        send(page, PageEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList, PreviewRoute.Ports), backStack())
    }

    @Test
    fun `an external address does not navigate inside the app`() {
        setNavHost()
        open(PreviewRoute.Ports, PreviewRoute.Page(3000))

        send(page, PageEvent.OpenExternal(ExternalLink("https://example.com/")))

        assertEquals(listOf(ConnectRoute.ServerList, PreviewRoute.Ports, PreviewRoute.Page(3000)), backStack())
    }
}
