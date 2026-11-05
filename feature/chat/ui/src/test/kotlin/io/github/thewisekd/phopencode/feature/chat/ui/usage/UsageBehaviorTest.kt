package io.github.thewisekd.phopencode.feature.chat.ui.usage

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private val COUNT_ROWS = listOf(
    UsageTags.INPUT, UsageTags.OUTPUT, UsageTags.REASONING, UsageTags.CACHE_READ, UsageTags.CACHE_WRITE,
    UsageTags.TOTAL, UsageTags.COST,
)

/** What the bar and page send and show, in English: the texts of both languages are in `UsageLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class UsageBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val placeholder = "placeholder:$DETAIL_ROUTE"

    // The bar

    @Test
    fun `tapping the bar sends OpenDetail and nothing else`() {
        val harness = compose.setUsageBar(LOADED)

        compose.onNodeWithTag(BAR_TAG).performScrollTo().performClick()

        assertEquals(listOf<UsageIntent>(UsageIntent.OpenDetail), harness.intents)
    }

    @Test
    fun `the bar opens the page while loading too`() {
        val harness = compose.setUsageBar(UsageState())

        compose.onNodeWithTag(BAR_TAG).performScrollTo().performClick()

        assertEquals(listOf<UsageIntent>(UsageIntent.OpenDetail), harness.intents)
    }

    @Test
    fun `the bar is big enough to tap`() {
        compose.setUsageBar(LOADED)

        compose.onNodeWithTag(BAR_TAG).performScrollTo().assertTouchSizeIsAtLeast(48.dp)
    }

    @Test
    fun `the bar leaves the context out without a limit`() {
        compose.setUsageBar(LOADED.copy(contextLimit = null, contextPercent = null))

        compose.onNodeWithTag(BAR_TAG).performScrollTo().assertTextEquals("14,190 tokens · $0.12")
    }

    @Test
    fun `the bar leaves the cost out when none was reported`() {
        compose.setUsageBar(costing(null, missing = true))

        compose.onNodeWithTag(BAR_TAG).performScrollTo().assertTextEquals("14,190 tokens · Context 7%")
    }

    @Test
    fun `the bar keeps the totals when the model list failed`() {
        compose.setUsageBar(LOADED.copy(contextLimit = null, contextPercent = null, error = ChatFailure.Unreachable))

        compose.onNodeWithTag(BAR_TAG).performScrollTo().assertTextEquals("14,190 tokens · $0.12")
    }

    // The page

    @Test
    fun `the page keeps its route's tag in every state`() {
        val harness = compose.setUsageDetail(UsageState())
        compose.onNodeWithTag(placeholder).assertIsDisplayed()

        listOf(LOADED, UsageState(loading = false), LOADED.copy(error = ChatFailure.Unreachable)).forEach {
            harness.state.value = it
            compose.onNodeWithTag(placeholder).assertIsDisplayed()
        }
    }

    @Test
    fun `the page title is a heading`() {
        compose.setUsageDetail(LOADED)

        val node = compose.onNodeWithText("Token usage").fetchSemanticsNode()
        assertNotNull(node.config.getOrNull(SemanticsProperties.Heading))
    }

    @Test
    fun `going back sends Close and nothing else`() {
        val harness = compose.setUsageDetail(LOADED)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<UsageIntent>(UsageIntent.Close), harness.intents)
    }

    @Test
    fun `retry sends Retry and the totals still show`() {
        val harness = compose.setUsageDetail(
            LOADED.copy(contextLimit = null, contextPercent = null, error = ChatFailure.Unreachable),
        )

        COUNT_ROWS.forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("Try again").performScrollTo().performClick()

        assertEquals(listOf<UsageIntent>(UsageIntent.Retry), harness.intents)
    }

    @Test
    fun `no error shows without a failure`() {
        compose.setUsageDetail(LOADED)

        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `the context shows its use, limit and a bar when the limit is known`() {
        compose.setUsageDetail(LOADED)

        compose.onNodeWithTag(UsageTags.CONTEXT_USED).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UsageTags.CONTEXT_LIMIT).performScrollTo().assertIsDisplayed()
        val progress = compose.onNodeWithTag(UsageTags.CONTEXT_PROGRESS).performScrollTo().fetchSemanticsNode()
        assertEquals(0.07f, progress.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current)
    }

    @Test
    fun `without a limit only the context in use shows`() {
        compose.setUsageDetail(LOADED.copy(contextLimit = null, contextPercent = null))

        compose.onNodeWithTag(UsageTags.CONTEXT_USED).performScrollTo().assertTextEquals("Context in use", "13,534")
        compose.onNodeWithTag(UsageTags.CONTEXT_LIMIT).assertDoesNotExist()
        compose.onNodeWithTag(UsageTags.CONTEXT_PROGRESS).assertDoesNotExist()
    }

    @Test
    fun `without any context the context rows are left out`() {
        compose.setUsageDetail(LOADED.copy(contextLimit = null, contextPercent = null, contextTokens = null))

        compose.onNodeWithTag(UsageTags.CONTEXT_USED).assertDoesNotExist()
        compose.onNodeWithTag(UsageTags.CONTEXT_LIMIT).assertDoesNotExist()
    }

    @Test
    fun `the model row shows only when the model is known`() {
        compose.setUsageDetail(LOADED.copy(model = null))

        compose.onNodeWithTag(UsageTags.MODEL).assertDoesNotExist()
        compose.onNodeWithTag(UsageTags.INPUT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `no rows show before the counts are known`() {
        val harness = compose.setUsageDetail(UsageState())
        COUNT_ROWS.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }

        harness.state.value = UsageState(loading = false)
        COUNT_ROWS.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }
}
