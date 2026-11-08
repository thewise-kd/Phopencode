package io.github.thewisekd.phopencode

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [rememberDarkTheme] maps the stored [ThemePreference] to the theme. The phone's dark mode comes
 * from the `night` / `notnight` qualifier. The levels of `MainActivityTest`: the Pi passes 26 only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ThemePreferenceTest {
    @get:Rule(order = 0)
    val koin = StopKoinRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private lateinit var applied: ColorScheme
    private lateinit var light: ColorScheme
    private lateinit var dark: ColorScheme

    @Composable
    private fun Themes(settings: SettingsRepository) {
        PhopencodeTheme(darkTheme = false) { light = MaterialTheme.colorScheme }
        PhopencodeTheme(darkTheme = true) { dark = MaterialTheme.colorScheme }
        PhopencodeTheme(darkTheme = rememberDarkTheme(settings)) { applied = MaterialTheme.colorScheme }
    }

    private fun show(settings: SettingsRepository) {
        compose.setContent { Themes(settings) }
        compose.waitForIdle()
    }

    @Test
    fun `the mapping forces light and dark and follows the phone for system`() {
        assertTrue(ThemePreference.DARK.isDark(systemDark = false))
        assertTrue(ThemePreference.DARK.isDark(systemDark = true))
        assertFalse(ThemePreference.LIGHT.isDark(systemDark = true))
        assertFalse(ThemePreference.LIGHT.isDark(systemDark = false))
        assertTrue(ThemePreference.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemePreference.SYSTEM.isDark(systemDark = false))
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `stored dark gives the dark scheme on a light phone`() {
        show(ThemeSettingsFake.storing(ThemePreference.DARK))

        compose.runOnIdle {
            assertEquals(dark, applied)
            assertTrue(light != dark)
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun `stored light gives the light scheme on a dark phone`() {
        show(ThemeSettingsFake.storing(ThemePreference.LIGHT))

        compose.runOnIdle { assertEquals(light, applied) }
    }

    @Test
    @Config(qualifiers = "night")
    fun `stored system follows a dark phone`() {
        show(ThemeSettingsFake.storing(ThemePreference.SYSTEM))

        compose.runOnIdle { assertEquals(dark, applied) }
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `stored system follows a light phone`() {
        show(ThemeSettingsFake.storing(ThemePreference.SYSTEM))

        compose.runOnIdle { assertEquals(light, applied) }
    }

    @Test
    @Config(qualifiers = "night")
    fun `before the first value the theme follows the phone`() {
        show(ThemeSettingsFake(initial = null))

        compose.runOnIdle { assertEquals(dark, applied) }
    }

    @Test
    @Config(qualifiers = "night")
    fun `a read failure before any value keeps following the phone`() {
        val settings = ThemeSettingsFake(initial = null)
        show(settings)

        settings.fail()

        compose.runOnIdle { assertEquals(dark, applied) }
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `a read failure after a value keeps the last theme`() {
        val settings = ThemeSettingsFake.storing(ThemePreference.DARK)
        show(settings)
        compose.runOnIdle { assertEquals(dark, applied) }

        settings.fail()

        compose.runOnIdle { assertEquals(dark, applied) }
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `a change while running recomposes in place`() {
        val settings = ThemeSettingsFake.storing(ThemePreference.SYSTEM)
        val markers = mutableListOf<Any>()
        compose.setContent {
            val marker = remember { Any() }
            SideEffect { markers += marker }
            Themes(settings)
        }
        compose.runOnIdle { assertEquals(light, applied) }

        runBlocking { settings.setTheme(ThemePreference.DARK) }
        compose.runOnIdle { assertEquals(dark, applied) }

        runBlocking { settings.setTheme(ThemePreference.LIGHT) }
        compose.runOnIdle { assertEquals(light, applied) }
        // Every composition pass saw the same remembered marker: the content was never rebuilt.
        markers.forEach { assertSame(markers.first(), it) }
    }
}
