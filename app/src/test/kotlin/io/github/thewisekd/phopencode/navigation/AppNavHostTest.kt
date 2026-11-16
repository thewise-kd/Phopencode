package io.github.thewisekd.phopencode.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.test.junit4.StateRestorationTester
import io.github.thewisekd.phopencode.StopKoinRule
import io.github.thewisekd.phopencode.ThemeSettingsFake
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import kotlinx.coroutines.runBlocking
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The levels of `MainActivityTest`: level 36 has no arm64 Linux runtime, so the Pi passes 26 only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AppNavHostTest {
    @get:Rule(order = 0)
    val koin = StopKoinRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private lateinit var navController: NavHostController

    private fun setNavHost() {
        compose.setContent {
            PhopencodeTheme {
                navController = rememberNavController()
                AppNavHost(navController = navController)
            }
        }
    }

    private fun concreteClasses(type: Class<*>): Set<Class<*>> =
        if (type.isSealed) type.permittedSubclasses.orEmpty().flatMap(::concreteClasses).toSet() else setOf(type)

    @Test
    fun `every route has a sample`() {
        assertEquals(concreteClasses(Route::class.java), allRoutes.map { it.javaClass }.toSet())
    }

    @Test
    fun `placeholder tags tell every route apart`() {
        assertEquals(allRoutes.size, allRoutes.map(::placeholderTag).toSet().size)
    }

    @Test
    fun `the start is the server list`() {
        setNavHost()

        compose.onNodeWithTag(placeholderTag(ConnectRoute.ServerList)).assertIsDisplayed()
        val destination = assertNotNull(navController.currentDestination)
        assertTrue(destination.hasRoute(ConnectRoute.ServerList::class))
    }

    /** The routes on the back stack, bottom first, by their class's simple name. */
    private fun stack(): List<String?> = compose.runOnIdle {
        navController.currentBackStack.value.map { it.destination }.filter { it.parent != null }
            .map { destination -> allRoutes.firstOrNull { destination.hasRoute(it::class) }?.javaClass?.simpleName }
    }

    /** The app's NavHost with the first-launch check, as `MainActivity` composes them. */
    private fun setApp(settings: SettingsRepository, restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            PhopencodeTheme {
                navController = rememberNavController()
                AppNavHost(navController = navController)
                OpenGuideOnFirstLaunch(navController, settings)
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
        compose.waitForIdle()
    }

    @Test
    fun `the first launch opens the guide above the server list`() {
        setApp(ThemeSettingsFake(AppSettings(guideSeen = false)))

        assertEquals(listOf("ServerList", "Guide"), stack())
        compose.onNodeWithTag(placeholderTag(SettingsRoute.Guide)).assertIsDisplayed()
    }

    @Test
    fun `a guide already seen does not open`() {
        setApp(ThemeSettingsFake(AppSettings(guideSeen = true)))

        assertEquals(listOf("ServerList"), stack())
        compose.onNodeWithTag(placeholderTag(ConnectRoute.ServerList)).assertIsDisplayed()
    }

    @Test
    fun `nothing opens before the settings are read, so a seen guide never flashes`() {
        val settings = ThemeSettingsFake(initial = null)
        setApp(settings)
        assertEquals(listOf("ServerList"), stack())

        runBlocking { settings.markGuideSeen() }
        compose.waitForIdle()

        assertEquals(listOf("ServerList"), stack())
    }

    @Test
    fun `the guide opens once the settings are read late`() {
        val settings = ThemeSettingsFake(initial = null)
        setApp(settings)

        runBlocking { settings.setTheme(ThemePreference.DARK) }
        compose.waitForIdle()

        assertEquals(listOf("ServerList", "Guide"), stack())
    }

    @Test
    fun `settings that cannot be read open no guide`() {
        val settings = ThemeSettingsFake(initial = null).apply { fail() }
        setApp(settings)

        assertEquals(listOf("ServerList"), stack())
    }

    @Test
    fun `the guide opens only once, even after the state is restored`() {
        val restoration = StateRestorationTester(compose)
        val settings = ThemeSettingsFake(AppSettings(guideSeen = false))
        setApp(settings, restoration)
        assertEquals(listOf("ServerList", "Guide"), stack())
        // Left without being recorded as seen, as when storing failed.
        compose.runOnIdle { navController.popBackStack() }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(listOf("ServerList"), stack())
    }

    @Test
    fun `the guide does not cover a screen opened before the settings were read`() {
        val settings = ThemeSettingsFake(initial = null)
        setApp(settings)
        compose.runOnIdle { navController.navigate(NotifyRoute.Pending) }

        runBlocking { settings.setTheme(ThemePreference.DARK) }
        compose.waitForIdle()

        assertEquals(listOf("ServerList", "Pending"), stack())
    }

    @Test
    fun `every route opens its placeholder`() {
        setNavHost()

        allRoutes.forEach { route ->
            compose.runOnIdle { navController.navigate(route) }

            compose.onNodeWithTag(placeholderTag(route)).assertIsDisplayed()
            val destination = assertNotNull(navController.currentDestination)
            assertTrue(destination.hasRoute(route::class), "$route opened ${destination.route}")
        }
    }
}
