package io.github.thewisekd.phopencode.feature.sessions.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import io.github.thewisekd.phopencode.feature.sessions.ui.create.SessionCreateScreen
import io.github.thewisekd.phopencode.feature.sessions.ui.create.createRoute
import io.github.thewisekd.phopencode.feature.sessions.ui.create.ready
import io.github.thewisekd.phopencode.feature.sessions.ui.list.FixedClock
import io.github.thewisekd.phopencode.feature.sessions.ui.list.LocalSessionListClock
import io.github.thewisekd.phopencode.feature.sessions.ui.list.SessionListScreen
import io.github.thewisekd.phopencode.feature.sessions.ui.list.listed
import io.github.thewisekd.phopencode.feature.sessions.ui.list.untitled
import io.github.thewisekd.phopencode.feature.sessions.ui.manage.SessionManageScreen
import io.github.thewisekd.phopencode.feature.sessions.ui.manage.TEST_ROUTE
import io.github.thewisekd.phopencode.feature.sessions.ui.manage.loaded
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every sessions screen, loaded, empty, failed and loading, at the largest font: every control is
 * named and big enough to tap, and no text is cut off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class AccessibilityAuditTest {
    @get:Rule
    val compose = createComposeRule()

    private val failure = SessionsFailure.Unreachable

    private fun audit(content: @Composable () -> Unit) {
        compose.setThemedAt { Surface(content = content) }
        compose.assertAccessible()
    }

    private fun list(state: SessionListState) = audit {
        CompositionLocalProvider(LocalSessionListClock provides FixedClock) {
            SessionListScreen(
                route = SessionsRoute.SessionList(directory = state.directory),
                state = state,
                onIntent = {},
                onOpenCatalog = {},
                onOpenNotify = {},
            )
        }
    }

    @Test
    fun `the session list`() = list(listed.copy(rows = listed.rows + untitled))

    @Test
    fun `the session list of one folder`() = list(listed.copy(directory = "/work/sample"))

    @Test
    fun `the session list while loading`() = list(SessionListState())

    @Test
    fun `the session list empty`() = list(SessionListState(loading = false, endReached = true))

    @Test
    fun `the session list failed`() {
        list(SessionListState(loading = false, error = failure))
        compose.assertErrorAnnounced(R.string.sessions_list_error_unreachable)
    }

    @Test
    fun `the session list failing to load more`() {
        // One row, so the failure under it is in view.
        list(listed.copy(rows = listed.rows.take(1), endReached = false, loadMoreError = failure))
        compose.assertErrorAnnounced(R.string.sessions_list_load_more_error)
    }

    @Test
    fun `a new session, ready`() = audit { SessionCreateScreen(createRoute, ready, {}) }

    @Test
    fun `a new session while the catalog loads`() = audit { SessionCreateScreen(createRoute, SessionCreateState(), {}) }

    @Test
    fun `a new session failing`() {
        audit {
            SessionCreateScreen(createRoute, ready.copy(catalogError = failure, error = failure), {})
        }
        compose.assertErrorAnnounced(R.string.sessions_create_reason_unreachable)
    }

    @Test
    fun `managing a session`() = audit { SessionManageScreen(TEST_ROUTE, loaded(), {}) }

    @Test
    fun `managing a session while it loads`() = audit { SessionManageScreen(TEST_ROUTE, SessionManageState(), {}) }

    @Test
    fun `managing a session that failed to load`() {
        audit {
            SessionManageScreen(TEST_ROUTE, SessionManageState(loading = false, loadError = failure), {})
        }
        compose.assertErrorAnnounced(R.string.sessions_manage_reason_unreachable)
    }
}
