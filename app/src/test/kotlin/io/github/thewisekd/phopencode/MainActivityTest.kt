package io.github.thewisekd.phopencode

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
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
}
