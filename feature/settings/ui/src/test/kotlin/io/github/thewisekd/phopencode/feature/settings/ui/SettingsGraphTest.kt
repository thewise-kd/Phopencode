package io.github.thewisekd.phopencode.feature.settings.ui

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
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideEvent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuidePresenter
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideState
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesEvent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesPresenter
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState
import io.github.thewisekd.phopencode.feature.settings.logic.contract.Presenter
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
 * The graph opens every settings route, turns the preferences' and the guide's events into
 * navigation, and after all data is deleted leaves only the server list. The presenters are fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SettingsGraphTest {
    @get:Rule
    val compose = createComposeRule()

    private val preferences = Recording<PreferencesState, PreferencesIntent, PreferencesEvent>(PreferencesState())

    private val guide = Recording<GuideState, GuideIntent, GuideEvent>(GuideState())

    private lateinit var navController: NavHostController

    @Before
    fun startDi() {
        startKoin {
            modules(
                module {
                    factory<PreferencesPresenter> {
                        object : PreferencesPresenter, Presenter<PreferencesState, PreferencesIntent, PreferencesEvent> by preferences {}
                    }
                    factory<GuidePresenter> {
                        object : GuidePresenter, Presenter<GuideState, GuideIntent, GuideEvent> by guide {}
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
                    settingsGraph(navController)
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

    private fun send(event: PreferencesEvent) {
        preferences.sent.trySend(event)
        compose.waitForIdle()
    }

    private fun routeOf(entry: NavBackStackEntry): Route? {
        val destination = entry.destination
        return when {
            destination.hasRoute<ConnectRoute.ServerList>() -> ConnectRoute.ServerList
            destination.hasRoute<ConnectRoute.Keys>() -> ConnectRoute.Keys
            destination.hasRoute<SettingsRoute.Preferences>() -> SettingsRoute.Preferences
            destination.hasRoute<SettingsRoute.Licenses>() -> SettingsRoute.Licenses
            destination.hasRoute<SettingsRoute.Guide>() -> SettingsRoute.Guide
            else -> null // The graph itself.
        }
    }

    private fun backStack(): List<Route> = compose.runOnIdle {
        navController.currentBackStack.value.mapNotNull(::routeOf)
    }

    @Test
    fun `every route shows its screen with its tag`() {
        setNavHost()

        listOf(SettingsRoute.Preferences, SettingsRoute.Licenses, SettingsRoute.Guide).forEach { route ->
            open(route)
            compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
        }
    }

    @Test
    fun `the preferences open the licenses`() {
        setNavHost()
        open(SettingsRoute.Preferences)

        send(PreferencesEvent.OpenLicenses)

        assertEquals(listOf(ConnectRoute.ServerList, SettingsRoute.Preferences, SettingsRoute.Licenses), backStack())
        assertTrue(compose.runOnIdle { preferences.intents.isEmpty() }, "the preferences follow the store, they need no refresh")
    }

    @Test
    fun `the preferences open the guide`() {
        setNavHost()
        open(SettingsRoute.Preferences)

        send(PreferencesEvent.OpenGuide)

        assertEquals(listOf(ConnectRoute.ServerList, SettingsRoute.Preferences, SettingsRoute.Guide), backStack())
    }

    @Test
    fun `finishing the guide opened from the preferences returns to them`() {
        setNavHost()
        open(SettingsRoute.Preferences)
        send(PreferencesEvent.OpenGuide)

        finishGuide()

        assertEquals(listOf(ConnectRoute.ServerList, SettingsRoute.Preferences), backStack())
    }

    @Test
    fun `finishing the guide opened on the first launch lands on the server list`() {
        setNavHost()
        open(SettingsRoute.Guide)

        finishGuide()

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
        compose.onNodeWithTag("start").assertIsDisplayed()
    }

    @Test
    fun `finishing a guide alone on the back stack opens the server list`() {
        setNavHost(start = SettingsRoute.Guide)

        finishGuide()

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }

    @Test
    fun `a screen a deep link put above a saving guide stays, and the guide leaves when shown again`() {
        setNavHost()
        open(SettingsRoute.Guide, ConnectRoute.Keys)

        finishGuide()

        assertEquals(listOf(ConnectRoute.ServerList, SettingsRoute.Guide, ConnectRoute.Keys), backStack())
        compose.onNodeWithTag("keys").assertIsDisplayed()

        compose.runOnIdle { navController.popBackStack() }
        compose.waitForIdle()

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
        compose.onNodeWithTag("start").assertIsDisplayed()
    }

    private fun finishGuide() {
        guide.sent.trySend(GuideEvent.Finished)
        compose.waitForIdle()
    }

    @Test
    fun `closing the preferences goes back`() {
        setNavHost()
        open(SettingsRoute.Preferences)

        send(PreferencesEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }

    @Test
    fun `deleted data goes back to the server list, dropping everything above it`() {
        setNavHost()
        open(ConnectRoute.Keys, SettingsRoute.Preferences)

        send(PreferencesEvent.DataDeleted)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }

    @Test
    fun `deleted data opens the server list when it is not on the back stack`() {
        setNavHost(start = ConnectRoute.Keys)
        open(SettingsRoute.Preferences)

        send(PreferencesEvent.DataDeleted)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }
}
