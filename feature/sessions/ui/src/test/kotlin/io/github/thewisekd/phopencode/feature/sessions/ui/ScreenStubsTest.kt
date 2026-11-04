package io.github.thewisekd.phopencode.feature.sessions.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageState
import io.github.thewisekd.phopencode.feature.sessions.ui.create.SessionCreateScreen
import io.github.thewisekd.phopencode.feature.sessions.ui.list.SessionListScreen
import io.github.thewisekd.phopencode.feature.sessions.ui.manage.SessionManageScreen
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
    fun `the session list keeps its route's tag`() {
        val route = SessionsRoute.SessionList(directory = "/srv/çalışma alanı")
        compose.setContent {
            PhopencodeTheme { SessionListScreen(route, SessionListState(directory = route.directory), onIntent = {}) }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `creation keeps its route's tag`() {
        val route = SessionsRoute.Create(directory = "/srv/app", skillId = "skill-1")
        compose.setContent {
            PhopencodeTheme {
                SessionCreateScreen(route, SessionCreateState(directory = "/srv/app", skillId = "skill-1"), onIntent = {})
            }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `management keeps its route's tag`() {
        val route = SessionsRoute.Manage(sessionId = "ses_1")
        compose.setContent {
            PhopencodeTheme { SessionManageScreen(route, SessionManageState(), onIntent = {}) }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }
}
