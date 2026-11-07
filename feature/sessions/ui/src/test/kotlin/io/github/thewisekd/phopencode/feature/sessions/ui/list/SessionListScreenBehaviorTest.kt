package io.github.thewisekd.phopencode.feature.sessions.ui.list

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionBadge
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the session list draws for each state, and which intents its controls send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SessionListScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = ENGLISH_SESSION_LIST_STRINGS
    private val intents = mutableListOf<SessionListIntent>()

    private fun show(state: SessionListState, route: SessionsRoute.SessionList = SessionsRoute.SessionList(state.directory)) {
        compose.setSessionList(state, route) { intents += it }
    }

    /** The intents sent apart from [SessionListIntent.LoadMore], which a short list sends by itself. */
    private fun sentByUser() = intents.filter { it != SessionListIntent.LoadMore }

    @Test
    fun `the root keeps the placeholder tag the navigation tests look for`() {
        val route = SessionsRoute.SessionList(directory = FOLDER)
        show(listed.copy(directory = FOLDER), route)

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the title is a heading`() {
        show(listed)

        compose.onNodeWithText(strings.title).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `loading without rows shows a still progress message and no empty state`() {
        show(SessionListState(loading = true))

        compose.onNodeWithContentDescription(strings.loading)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText(strings.emptyTitle).assertDoesNotExist()
    }

    @Test
    fun `no sessions shows the empty state whose button creates one`() {
        show(SessionListState(loading = false, endReached = true))

        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.create).assertDoesNotExist()
        compose.onNodeWithText(strings.create).performScrollTo().assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle { assertEquals(listOf<SessionListIntent>(SessionListIntent.Create), sentByUser()) }
    }

    @Test
    fun `a search without a match says so and offers no creation`() {
        show(SessionListState(loading = false, query = "zzz", endReached = true))

        compose.onNodeWithText(strings.noMatchTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.noMatchMessage).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyTitle).assertDoesNotExist()
    }

    @Test
    fun `sessions are listed in the presenter's order`() {
        show(listed)

        compose.onNodeWithText(first.title!!).assertIsDisplayed()
        compose.onNodeWithText(second.title!!).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyTitle).assertDoesNotExist()
        val firstTop = compose.onNodeWithText(first.title!!).fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText(second.title!!).fetchSemanticsNode().boundsInRoot.top
        assertTrue(firstTop < secondTop, "the presenter's order is kept")
    }

    @Test
    fun `a session without a title gets a stand-in name`() {
        show(listed.copy(rows = listOf(untitled)))

        compose.onNodeWithText(strings.untitled).assertIsDisplayed()
    }

    @Test
    fun `tapping a session opens it`() {
        show(listed)

        compose.scrollListTo(hasText(second.title!!))
        compose.onNodeWithText(second.title!!).assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle { assertEquals(listOf<SessionListIntent>(SessionListIntent.Open(second.id)), sentByUser()) }
    }

    @Test
    fun `each session has a labelled manage button of tappable size`() {
        show(listed)

        compose.scrollListTo(hasContentDescription(strings.manage(first.title!!)))
        compose.onNodeWithContentDescription(strings.manage(first.title!!))
            .assertTouchSizeIsAtLeast()
            .performClick()

        compose.runOnIdle { assertEquals(listOf<SessionListIntent>(SessionListIntent.Manage(first.id)), sentByUser()) }
    }

    @Test
    fun `an untitled session's manage button names it by its stand-in`() {
        show(listed.copy(rows = listOf(untitled)))

        compose.onNodeWithContentDescription(strings.manage(strings.untitled)).assertExists()
    }

    @Test
    fun `each badge but idle shows its own label`() {
        val rows = SessionBadge.entries.mapIndexed { index, badge ->
            first.copy(id = SessionId("ses_badge_$index"), title = "Session $index", status = badge, usage = null)
        }
        show(listed.copy(rows = rows))

        strings.badges.values.forEach { label ->
            compose.scrollListTo(hasText(label), useUnmergedTree = true)
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        }
        assertEquals(SessionBadge.entries.toSet() - SessionBadge.Idle, strings.badges.keys)
    }

    @Test
    fun `an idle session has no badge`() {
        show(listed.copy(rows = listOf(second)))

        strings.badges.values.forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertDoesNotExist() }
    }

    @Test
    fun `a row shows its tokens and cost`() {
        show(listed.copy(rows = listOf(first)))

        compose.onNodeWithText("1,234 tokens · $0.12", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a cost some steps did not report is a lower bound`() {
        val usage = TokenUsage(output = BigInteger.ONE, cost = Cost.of(BigDecimal("2.5")), hasMissingCost = true)
        show(listed.copy(rows = listOf(first.copy(usage = usage))))

        compose.onNodeWithText("1 token · at least $2.50", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a cost below a cent is not shown as zero`() {
        val usage = TokenUsage(input = BigInteger.TEN, cost = Cost.of(BigDecimal("0.0004")), hasMissingCost = false)
        show(listed.copy(rows = listOf(first.copy(usage = usage))))

        compose.onNodeWithText("10 tokens · under $0.01", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a lower bound below a cent is not shown as zero`() {
        val usage = TokenUsage(input = BigInteger.TEN, cost = Cost.of(BigDecimal("0.0004")), hasMissingCost = true)
        show(listed.copy(rows = listOf(first.copy(usage = usage))))

        compose.onNodeWithText("10 tokens · more than $0.00", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("at least $0.00", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `no reported cost shows the tokens alone`() {
        val usage = TokenUsage(input = BigInteger.valueOf(5), cost = null, hasMissingCost = true)
        show(listed.copy(rows = listOf(first.copy(usage = usage))))

        compose.onNodeWithText("5 tokens", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a list of every folder shows each session's folder`() {
        show(listed)

        compose.onNodeWithText(FOLDER, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(OTHER_FOLDER, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a list of one folder shows the folder once, above the rows`() {
        val rows = listOf(first, second.copy(directory = FOLDER))
        show(listed.copy(directory = FOLDER, rows = rows))

        compose.onNodeWithText(FOLDER, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `an archived session says so`() {
        show(listed.copy(showArchived = true, rows = listOf(second.copy(archived = true))))

        compose.onNodeWithText(strings.archived, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `typing in the search field sends the query`() {
        show(listed)

        compose.onNode(hasSetTextAction()).performTextInput("login")

        compose.runOnIdle {
            assertEquals(listOf<SessionListIntent>(SessionListIntent.QueryChanged("login")), sentByUser())
        }
    }

    @Test
    fun `the search field shows the state's query`() {
        show(listed.copy(query = "release"))

        compose.onNode(hasSetTextAction() and hasText("release")).assertExists()
    }

    @Test
    fun `the archived switch follows the state and asks to flip it`() {
        show(listed)

        compose.onNodeWithText(strings.showArchived).assertIsOff().assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle {
            assertEquals(listOf<SessionListIntent>(SessionListIntent.ShowArchived(true)), sentByUser())
        }
    }

    @Test
    fun `the archived switch turned on asks to turn it off`() {
        show(listed.copy(showArchived = true))

        compose.onNodeWithText(strings.showArchived).assertIsOn().performClick()

        compose.runOnIdle {
            assertEquals(listOf<SessionListIntent>(SessionListIntent.ShowArchived(false)), sentByUser())
        }
    }

    @Test
    fun `the labelled add button creates a session when sessions are listed`() {
        show(listed)

        compose.onNodeWithContentDescription(strings.create).assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle { assertEquals(listOf<SessionListIntent>(SessionListIntent.Create), sentByUser()) }
    }

    @Test
    fun `a folder's list opens its files and git`() {
        show(listed.copy(directory = FOLDER))

        compose.onNodeWithContentDescription(strings.openFiles).assertIsEnabled().assertTouchSizeIsAtLeast().performClick()
        compose.onNodeWithContentDescription(strings.openGit).assertIsEnabled().assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle {
            assertEquals(listOf(SessionListIntent.OpenFiles, SessionListIntent.OpenGit), sentByUser())
        }
    }

    @Test
    fun `the list of every folder has no files or git button`() {
        show(listed.copy(directory = null))

        compose.onNodeWithContentDescription(strings.refresh).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.openFiles).assertDoesNotExist()
        compose.onNodeWithContentDescription(strings.openGit).assertDoesNotExist()
    }

    @Test
    fun `the refresh button reloads the first page`() {
        show(listed)

        compose.onNodeWithContentDescription(strings.refresh).assertIsEnabled().assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle { assertEquals(listOf<SessionListIntent>(SessionListIntent.Refresh), sentByUser()) }
    }

    @Test
    fun `pulling the list down refreshes it`() {
        show(listed)

        compose.onNodeWithTag(SESSION_LIST_TAG).performTouchInput { swipeDown() }

        compose.runOnIdle { assertEquals(listOf<SessionListIntent>(SessionListIntent.Refresh), sentByUser()) }
    }

    @Test
    fun `pulling the empty state down refreshes it too`() {
        show(SessionListState(loading = false, endReached = true))

        compose.onNodeWithTag(SESSION_LIST_TAG).performTouchInput { swipeDown() }

        compose.runOnIdle { assertEquals(listOf<SessionListIntent>(SessionListIntent.Refresh), sentByUser()) }
    }

    @Test
    fun `a refresh keeps the rows, says so and cannot be started twice`() {
        show(listed.copy(refreshing = true))

        compose.onNodeWithContentDescription(strings.refreshing)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        compose.onNodeWithText(first.title!!).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.refresh).assertIsNotEnabled()
    }

    @Test
    fun `no refresh message when not refreshing`() {
        show(listed)

        compose.onNodeWithContentDescription(strings.refreshing).assertDoesNotExist()
    }

    @Test
    fun `reaching the end of a list with more pages asks for the next one`() {
        show(listed.copy(endReached = false))

        compose.runOnIdle { assertTrue(SessionListIntent.LoadMore in intents) }
    }

    @Test
    fun `nothing more is asked for at the end`() {
        show(listed)

        compose.runOnIdle { assertTrue(SessionListIntent.LoadMore !in intents) }
    }

    @Test
    fun `nothing more is asked for while the first page loads`() {
        show(SessionListState(loading = true))

        compose.runOnIdle { assertTrue(SessionListIntent.LoadMore !in intents) }
    }

    @Test
    fun `a page that loads between two frames still leads to the next one`() {
        var state by mutableStateOf(listed.copy(endReached = false))
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface {
                    SessionListScreen(route = SessionsRoute.SessionList(), state = state, onIntent = { intents += it })
                }
            }
        }
        compose.runOnIdle { assertEquals(1, intents.count { it == SessionListIntent.LoadMore }) }

        // The page starts and ends before the screen is drawn again: the screen never sees loadingMore.
        compose.runOnUiThread {
            state = state.copy(loadingMore = true)
            state = state.copy(loadingMore = false, rows = state.rows + untitled)
        }

        compose.runOnIdle { assertEquals(2, intents.count { it == SessionListIntent.LoadMore }) }
    }

    @Test
    fun `nothing more is asked for while the first page refreshes`() {
        show(listed.copy(endReached = false, refreshing = true))

        compose.runOnIdle { assertTrue(SessionListIntent.LoadMore !in intents) }
    }

    @Test
    fun `loading the next page says so under the rows`() {
        show(listed.copy(endReached = false, loadingMore = true))

        compose.scrollListTo(hasContentDescription(strings.loadingMore))
        compose.onNodeWithContentDescription(strings.loadingMore).assertIsDisplayed()
        compose.runOnIdle { assertTrue(SessionListIntent.LoadMore !in intents) }
    }

    @Test
    fun `a failed next page is retried on request, not by itself`() {
        show(listed.copy(endReached = false, loadMoreError = SessionsFailure.Unreachable))

        compose.scrollListTo(hasText(strings.loadMoreError))
        compose.onNodeWithText(strings.loadMoreError).assertIsDisplayed()
        compose.scrollListTo(hasText(strings.retry))
        compose.onNodeWithText(strings.retry).assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle { assertEquals(listOf<SessionListIntent>(SessionListIntent.Retry), intents) }
    }

    @Test
    fun `an error over the rows can be retried and dismissed`() {
        show(listed.copy(error = SessionsFailure.Unreachable))

        compose.onNodeWithText(strings.errors.getValue(SessionsFailure.Unreachable)).assertIsDisplayed()
        compose.onNodeWithText(first.title!!).assertIsDisplayed()
        compose.onNodeWithText(strings.retry).assertTouchSizeIsAtLeast().performClick()
        compose.onNodeWithContentDescription(strings.dismiss).assertTouchSizeIsAtLeast().performClick()

        compose.runOnIdle {
            assertEquals(listOf(SessionListIntent.Retry, SessionListIntent.DismissError), sentByUser())
        }
    }

    @Test
    fun `an error with no rows shows no empty state`() {
        show(SessionListState(loading = false, error = SessionsFailure.ServerFailed))

        compose.onNodeWithText(strings.errors.getValue(SessionsFailure.ServerFailed)).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyTitle).assertDoesNotExist()
    }

    @Test
    fun `retrying cannot fix a lost connection or refused credentials`() {
        listOf(SessionsFailure.NotConnected, SessionsFailure.Unauthorized).forEach { failure ->
            assertTrue(failure !in RETRYABLE, "$failure is not retryable")
        }
        show(listed.copy(error = SessionsFailure.NotConnected))

        compose.onNodeWithText(strings.retry).assertDoesNotExist()
    }

    @Test
    fun `no error banner without an error`() {
        show(listed)

        compose.onNodeWithContentDescription(strings.dismiss).assertDoesNotExist()
    }
}
