package io.github.thewisekd.phopencode.feature.sessions.ui.list

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionBadge
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.time.Duration

/** The failures after which loading the first page again can help. */
internal val RETRYABLE = SessionsFailure.entries.toSet() - setOf(SessionsFailure.NotConnected, SessionsFailure.Unauthorized)

/** The texts of the session list in one language. Formats take a session's name. */
internal data class ExpectedSessionListStrings(
    val title: String,
    val loading: String,
    val refreshing: String,
    val loadingMore: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val noMatchTitle: String,
    val noMatchMessage: String,
    val create: String,
    val refresh: String,
    val openFiles: String,
    val openGit: String,
    val search: String,
    val showArchived: String,
    val archived: String,
    val untitled: String,
    val manage: (String) -> String,
    val retry: String,
    val dismiss: String,
    val loadMoreError: String,
    /** 1 234 567 tokens costing exactly 3.5. */
    val usage: String,
    /** 1 token costing at least 0.0004, some steps reporting no cost. */
    val usageAboveZero: String,
    val badges: Map<SessionBadge, String>,
    /** How long ago a session changed, by age. */
    val ages: Map<Duration, String>,
    val errors: Map<SessionsFailure, String>,
)

/** What the session list shows, the same in each language; a subclass sets the language and its texts. */
abstract class SessionListScreenLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedSessionListStrings

    @Test
    fun `the list shows its title, controls and rows`() {
        compose.setSessionList(listed)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithText(strings.search).assertExists()
        compose.onNodeWithText(strings.showArchived).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.refresh).assertIsDisplayed()
        compose.onNodeWithText(strings.create).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.manage(first.title!!)).assertExists()
    }

    @Test
    fun `the files and git buttons of a folder's list are named`() {
        compose.setSessionList(listed.copy(directory = FOLDER))

        compose.onNodeWithContentDescription(strings.openFiles).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.openGit).assertIsDisplayed()
    }

    @Test
    fun `each row says how long ago it changed`() {
        val rows = strings.ages.keys.mapIndexed { index, age ->
            second.copy(id = SessionId("ses_age_$index"), title = "Session $index", updatedAt = NOW - age)
        }
        compose.setSessionList(listed.copy(rows = rows))

        strings.ages.values.forEach {
            compose.scrollListTo(hasText(it), useUnmergedTree = true)
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `an untitled and archived session is named and marked`() {
        compose.setSessionList(listed.copy(showArchived = true, rows = listOf(untitled.copy(archived = true))))

        compose.onNodeWithText(strings.untitled).assertIsDisplayed()
        compose.onNodeWithText(strings.archived, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `tokens and cost follow the language's number format`() {
        val usage = TokenUsage(input = BigInteger.valueOf(1_234_567), cost = Cost.of(BigDecimal("3.5")), hasMissingCost = false)
        compose.setSessionList(listed.copy(rows = listOf(first.copy(usage = usage))))

        compose.onNodeWithText(strings.usage, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a lower bound below a cent still reads as spent`() {
        val usage = TokenUsage(input = BigInteger.ONE, cost = Cost.of(BigDecimal("0.0004")), hasMissingCost = true)
        compose.setSessionList(listed.copy(rows = listOf(first.copy(usage = usage))))

        compose.onNodeWithText(strings.usageAboveZero, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `every badge but idle has its own label`() {
        assertEquals(SessionBadge.entries.toSet() - SessionBadge.Idle, strings.badges.keys)
        assertEquals(strings.badges.size, strings.badges.values.toSet().size, "labels must differ")
        val rows = strings.badges.keys.mapIndexed { index, badge ->
            first.copy(id = SessionId("ses_badge_$index"), status = badge, usage = null)
        }
        compose.setSessionList(listed.copy(rows = rows))

        strings.badges.values.forEach {
            compose.scrollListTo(hasText(it), useUnmergedTree = true)
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `loading says so`() {
        compose.setSessionList(SessionListState(loading = true))

        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `refreshing and loading more say so`() {
        compose.setSessionList(listed.copy(refreshing = true, endReached = false, loadingMore = true))

        compose.onNodeWithContentDescription(strings.refreshing).assertIsDisplayed()
        compose.scrollListTo(hasContentDescription(strings.loadingMore))
        compose.onNodeWithContentDescription(strings.loadingMore).assertIsDisplayed()
    }

    @Test
    fun `a failed next page says so`() {
        compose.setSessionList(listed.copy(endReached = false, loadMoreError = SessionsFailure.Unreachable))

        compose.scrollListTo(hasText(strings.loadMoreError))
        compose.onNodeWithText(strings.loadMoreError).assertIsDisplayed()
        compose.scrollListTo(hasText(strings.retry))
        compose.onNodeWithText(strings.retry).assertIsDisplayed()
    }

    @Test
    fun `no sessions says so`() {
        compose.setSessionList(SessionListState(loading = false, endReached = true))

        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).assertIsDisplayed()
        compose.onNodeWithText(strings.create).assertIsDisplayed()
    }

    @Test
    fun `no match says so`() {
        compose.setSessionList(SessionListState(loading = false, query = "zzz", endReached = true))

        compose.onNodeWithText(strings.noMatchTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.noMatchMessage).assertIsDisplayed()
    }

    @Test
    fun `every failure has its own message`() {
        assertEquals(SessionsFailure.entries.toSet(), strings.errors.keys)
        assertEquals(SessionsFailure.entries.size, strings.errors.values.toSet().size, "messages must differ")
    }

    @Test
    fun `not connected is explained`() = assertError(SessionsFailure.NotConnected)

    @Test
    fun `unreachable is explained`() = assertError(SessionsFailure.Unreachable)

    @Test
    fun `unauthorized is explained`() = assertError(SessionsFailure.Unauthorized)

    @Test
    fun `not found is explained`() = assertError(SessionsFailure.NotFound)

    @Test
    fun `a conflict is explained`() = assertError(SessionsFailure.Conflict)

    @Test
    fun `a refusal is explained`() = assertError(SessionsFailure.Rejected)

    @Test
    fun `a server failure is explained`() = assertError(SessionsFailure.ServerFailed)

    @Test
    fun `an unknown failure is explained`() = assertError(SessionsFailure.Unknown)

    private fun assertError(failure: SessionsFailure) {
        compose.setSessionList(listed.copy(error = failure))

        compose.onNodeWithText(strings.errors.getValue(failure)).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.dismiss).assertIsDisplayed()
        if (failure in RETRYABLE) {
            compose.onNodeWithText(strings.retry).assertIsDisplayed()
        } else {
            compose.onNodeWithText(strings.retry).assertDoesNotExist()
        }
    }
}
