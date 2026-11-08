package io.github.thewisekd.phopencode.feature.settings.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState
import io.github.thewisekd.phopencode.feature.settings.ui.licenses.LicensesScreen
import io.github.thewisekd.phopencode.feature.settings.ui.prefs.PreferencesScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each screen keeps, on its root, the `placeholder:<route>` tag the app's navigation test looks for,
 * with the screen's initial state. The screen tasks fill the screens in; this holds for them too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ScreenStubsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the preferences keep their route's tag`() {
        val route = SettingsRoute.Preferences
        compose.setContent { PhopencodeTheme { PreferencesScreen(route, PreferencesState(), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the licenses keep their route's tag`() {
        val route = SettingsRoute.Licenses
        compose.setContent { PhopencodeTheme { LicensesScreen(route, onClose = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }
}
