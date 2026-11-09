package io.github.thewisekd.phopencode.feature.notify.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import io.github.thewisekd.phopencode.feature.notify.ui.pending.PendingScreen
import io.github.thewisekd.phopencode.feature.notify.ui.setup.SetupScreen
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
    fun `the setup keeps its route's tag`() {
        val route = NotifyRoute.Setup
        compose.setContent { PhopencodeTheme { SetupScreen(route, SetupState(), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the pending list keeps its route's tag`() {
        val route = NotifyRoute.Pending
        compose.setContent { PhopencodeTheme { PendingScreen(route, PendingState(), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }
}
