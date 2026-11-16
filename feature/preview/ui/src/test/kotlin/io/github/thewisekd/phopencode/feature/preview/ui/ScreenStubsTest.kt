package io.github.thewisekd.phopencode.feature.preview.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.ui.page.PageScreen
import io.github.thewisekd.phopencode.feature.preview.ui.ports.PortsScreen
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
    fun `the port picker keeps its route's tag`() {
        val route = PreviewRoute.Ports
        compose.setContent { PhopencodeTheme { PortsScreen(route, PortsState(), onIntent = {}, notice = null) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the page keeps its route's tag`() {
        val route = PreviewRoute.Page(8080)
        compose.setContent {
            PhopencodeTheme {
                PageScreen(route, PageState(port = route.port), onIntent = {}, decide = { NavigationDecision.Refuse })
            }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }
}
