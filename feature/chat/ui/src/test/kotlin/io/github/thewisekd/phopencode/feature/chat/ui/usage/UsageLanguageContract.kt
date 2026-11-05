package io.github.thewisekd.phopencode.feature.chat.ui.usage

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the usage bar and page in one language; numbers are written as the language does. */
internal data class ExpectedUsageStrings(
    /** The bar for [LOADED]. */
    val bar: String,
    /** The bar for [LOADED] without a context limit or cost. */
    val barTokensOnly: String,
    val barOneToken: String,
    val barLoading: String,
    val barEmpty: String,
    val barOpen: String,
    val title: String,
    /** Label to value for each row of [LOADED]. */
    val rows: Map<String, Pair<String, String>>,
    /** A cost as the server gives it, and whether it is a lower bound, to the text shown. */
    val costs: Map<Pair<String, Boolean>, String>,
    val costUnknown: String,
    val loading: String,
    val emptyTitle: String,
    val errors: Map<ChatFailure, String>,
    val retry: String,
)

/** What the bar and page say, the same in each language; a subclass sets the language and its texts. */
abstract class UsageLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedUsageStrings

    @Test
    fun `the bar shows tokens, cost and context in the language's number format`() {
        val harness = compose.setUsageBar(LOADED)
        compose.onNodeWithTag(BAR_TAG).performScrollTo().assertTextEquals(strings.bar)
        val label = compose.onNodeWithTag(BAR_TAG).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
        assertEquals(strings.barOpen, label)

        harness.state.value = LOADED.copy(contextPercent = null, contextLimit = null, usage = USAGE.copy(cost = null))
        compose.onNodeWithTag(BAR_TAG).performScrollTo().assertTextEquals(strings.barTokensOnly)

        harness.state.value = UsageState(loading = false, usage = TokenUsage(input = count(1)))
        compose.onNodeWithTag(BAR_TAG).performScrollTo().assertTextEquals(strings.barOneToken)

        harness.state.value = UsageState()
        compose.onNodeWithTag(BAR_TAG).performScrollTo().assertTextEquals(strings.barLoading)

        harness.state.value = UsageState(loading = false)
        compose.onNodeWithTag(BAR_TAG).performScrollTo().assertTextEquals(strings.barEmpty)
    }

    @Test
    fun `the page lists every count in the language's number format`() {
        compose.setUsageDetail(LOADED)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        strings.rows.forEach { (tag, row) ->
            compose.onNodeWithTag(tag).performScrollTo().assertTextEquals(row.first, row.second)
        }
    }

    @Test
    fun `a cost is written in the language's format and says when it is a bound`() {
        val harness = compose.setUsageDetail(LOADED)
        strings.costs.forEach { (given, shown) ->
            harness.state.value = costing(given.first, given.second)
            compose.onNodeWithTag(UsageTags.COST).performScrollTo()
                .assertTextEquals(strings.rows.getValue(UsageTags.COST).first, shown)
        }
        harness.state.value = costing(null, missing = true)
        compose.onNodeWithTag(UsageTags.COST).performScrollTo()
            .assertTextEquals(strings.rows.getValue(UsageTags.COST).first, strings.costUnknown)
    }

    @Test
    fun `the page says when it is loading or has nothing yet`() {
        val harness = compose.setUsageDetail(UsageState())
        compose.onNodeWithText(strings.loading).performScrollTo().assertIsDisplayed()

        harness.state.value = UsageState(loading = false)
        compose.onNodeWithText(strings.emptyTitle).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every failure has its own message and a retry`() {
        val harness = compose.setUsageDetail(LOADED.copy(error = ChatFailure.Unknown))
        strings.errors.forEach { (failure, message) ->
            harness.state.value = LOADED.copy(error = failure)
            compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(strings.retry).performScrollTo().assertIsDisplayed()
        assertEquals(ChatFailure.entries.toSet(), strings.errors.keys)
    }
}
