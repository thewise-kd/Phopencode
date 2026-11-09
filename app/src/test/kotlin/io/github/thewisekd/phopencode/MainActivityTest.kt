package io.github.thewisekd.phopencode

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.core.view.WindowCompat
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.DeepLinks
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.navigation.placeholderTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The lowest level the app supports and the highest level Robolectric 4.17 can run:
 * the manifest's target of 37 is newer, so the level cannot be left to its default.
 * Level 36 needs Robolectric's native runtime, which has no arm64 Linux build: on the
 * Pi only level 26 passes, and x86-64 CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class MainActivityTest {
    @get:Rule(order = 0)
    val koin = StopKoinRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the launcher opens the server list`() {
        val launcher = assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        ActivityScenario.launch<MainActivity>(launcher).use { scenario ->
            scenario.onActivity { assertIs<MainActivity>(it) }
            compose.onNodeWithTag(placeholderTag(ConnectRoute.ServerList)).assertIsDisplayed()
        }
    }

    @Test
    fun `the application starts Koin with its context`() {
        val application = assertIs<PhopencodeApplication>(context)

        assertSame(application, GlobalContext.get().get<Application>())
    }

    @Test
    fun `the merged manifest declares API 26 as the minimum`() {
        assertEquals(26, context.applicationInfo.minSdkVersion)
    }

    private fun storeTheme(theme: ThemePreference): ThemeSettingsFake {
        val settings = ThemeSettingsFake.storing(theme)
        loadKoinModules(module { single<SettingsRepository> { settings } })
        return settings
    }

    /** Light status bar icons mean a light theme behind them, dark icons a dark one. */
    private fun MainActivity.lightBars(): Pair<Boolean, Boolean> {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        return controller.isAppearanceLightStatusBars to controller.isAppearanceLightNavigationBars
    }

    private fun launch(block: (ActivityScenario<MainActivity>) -> Unit) {
        val launcher = assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        ActivityScenario.launch<MainActivity>(launcher).use { scenario ->
            compose.waitForIdle()
            block(scenario)
        }
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `stored dark darkens the system bars on a light phone`() {
        storeTheme(ThemePreference.DARK)

        launch { scenario -> scenario.onActivity { assertEquals(false to false, it.lightBars()) } }
    }

    @Test
    @Config(qualifiers = "night")
    fun `stored light lightens the system bars on a dark phone`() {
        storeTheme(ThemePreference.LIGHT)

        launch { scenario -> scenario.onActivity { assertEquals(true to true, it.lightBars()) } }
    }

    @Test
    @Config(qualifiers = "night")
    fun `stored system follows the phone`() {
        storeTheme(ThemePreference.SYSTEM)

        launch { scenario -> scenario.onActivity { assertEquals(false to false, it.lightBars()) } }
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `a theme change applies without recreating the activity`() {
        val settings = storeTheme(ThemePreference.SYSTEM)

        launch { scenario ->
            lateinit var before: MainActivity
            scenario.onActivity {
                before = it
                assertTrue(it.lightBars().first)
            }

            runBlocking { settings.setTheme(ThemePreference.DARK) }
            compose.waitForIdle()

            scenario.onActivity {
                assertSame(before, it)
                assertFalse(it.lightBars().first)
            }
        }
    }

    /** The routes on the back stack, bottom first; the NavHost's own graph entry is left out. */
    private fun NavHostController.stack(): List<String?> =
        currentBackStack.value.map { it.destination }.filter { it.parent != null }.map { it.route }

    private fun view(uri: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        .setClass(context, MainActivity::class.java)
        // As ntfy sends it: with a new task flag, which must not make Navigation restart the task.
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Opens [ConnectRoute.Keys] above the server list, delivers [intent] to the running activity as
     * a new intent, and hands [check] the back stack before and after.
     */
    private fun deliverOverKeys(
        intent: Intent,
        check: (before: List<String?>, after: List<String?>, navController: NavHostController) -> Unit,
    ) {
        launch { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val navController = assertNotNull(activity.navController)
            compose.runOnIdle { navController.navigate(ConnectRoute.Keys) }
            compose.waitForIdle()
            val before = navController.stack()
            val launchIntent = activity.intent

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync { instrumentation.callActivityOnNewIntent(activity, intent) }
            compose.waitForIdle()

            try {
                scenario.onActivity {
                    assertSame(activity, it)
                    assertFalse(it.isFinishing)
                    assertSame(intent, it.intent)
                    assertSame(navController, it.navController)
                }
                check(before, navController.stack(), navController)
            } finally {
                // ActivityScenario recognises its activity by the intent it launched it with; without
                // that intent back, closing the scenario waits forever for the activity's end.
                scenario.onActivity { it.intent = launchIntent }
            }
        }
    }

    @Test
    fun `the pending link opens the pending list above the current screen`() {
        deliverOverKeys(view(DeepLinks.PENDING)) { before, after, navController ->
            assertEquals(3, after.size, "back stack: $after")
            assertEquals(before, after.dropLast(1))
            val destination = assertNotNull(navController.currentDestination)
            assertTrue(destination.hasRoute(NotifyRoute.Pending::class), "opened ${destination.route}")
            val previous = assertNotNull(navController.previousBackStackEntry)
            assertTrue(previous.destination.hasRoute(ConnectRoute.Keys::class))
        }
    }

    @Test
    fun `an unknown link changes nothing`() {
        deliverOverKeys(view("phopencode://unknown")) { before, after, _ -> assertEquals(before, after) }
    }

    @Test
    fun `a launcher intent changes nothing`() {
        val launcher = assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        deliverOverKeys(launcher) { before, after, _ -> assertEquals(before, after) }
    }

    @Test
    fun `a cold start from the pending link opens the pending list above the server list`() {
        ActivityScenario.launch<MainActivity>(view(DeepLinks.PENDING)).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity {
                assertFalse(it.isFinishing)
                val navController = assertNotNull(it.navController)
                val destination = assertNotNull(navController.currentDestination)
                assertTrue(destination.hasRoute(NotifyRoute.Pending::class), "opened ${destination.route}")
                val previous = assertNotNull(navController.previousBackStackEntry)
                assertTrue(previous.destination.hasRoute(ConnectRoute.ServerList::class))
                assertEquals(2, navController.stack().size, "back stack: ${navController.stack()}")
            }
        }
    }
}
