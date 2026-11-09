package io.github.thewisekd.phopencode.feature.chat.ui.reasoning

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** What the card draws and sends, in English: the texts of both languages are in `ReasoningLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ReasoningBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    /** Scrolled into view first: at API 35+ the insets push the content down. */
    private fun header(): SemanticsNodeInteraction = compose.onNodeWithTag(REASONING_HEADER_TAG).performScrollTo()

    private fun bodyShows(text: String) = compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    // Collapsing

    @Test
    fun `a collapsed card shows its header and not the thinking`() {
        compose.setReasoning(ReasoningCardState(text = THOUGHT, duration = 4.seconds))

        header().assertIsDisplayed()
        compose.onNodeWithTag(REASONING_BODY_TAG).assertDoesNotExist()
        compose.waitForIdle()
        assertTrue(!bodyShows(THOUGHT), "a collapsed card must not show the thinking")
    }

    @Test
    fun `an expanded card renders the thinking as Markdown`() {
        compose.setReasoning(ReasoningCardState(text = "Plan: **$THOUGHT**\n\n- step one\n", expanded = true))

        // The bold markers are gone: the paragraph reads as plain text.
        compose.awaitText("Plan: $THOUGHT")
        compose.onNodeWithTag(REASONING_BODY_TAG).performScrollTo().assertIsDisplayed()
        compose.awaitText("step one", substring = true)
        assertTrue(!bodyShows("**"), "Markdown markers must not show")
    }

    @Test
    fun `tapping the header asks to toggle the card and leaves the state to the presenter`() {
        val harness = compose.setReasoning(ReasoningCardState(text = THOUGHT))

        header().performClick()

        assertEquals(listOf<ReasoningIntent>(ReasoningIntent.Toggle(KEY)), harness.intents)
        compose.onNodeWithTag(REASONING_BODY_TAG).assertDoesNotExist()
    }

    @Test
    fun `an expanded card asks to toggle the same way`() {
        val harness = compose.setReasoning(ReasoningCardState(text = THOUGHT, expanded = true))

        header().performClick()

        assertEquals(listOf<ReasoningIntent>(ReasoningIntent.Toggle(KEY)), harness.intents)
        compose.onNodeWithTag(REASONING_BODY_TAG).assertExists()
    }

    @Test
    fun `the card opens and closes as the state says`() {
        val harness = compose.setReasoning(ReasoningCardState(text = THOUGHT))

        harness.state.value = harness.state.value.copy(expanded = true)
        compose.awaitText(THOUGHT)

        harness.state.value = harness.state.value.copy(expanded = false)
        compose.waitForIdle()
        compose.onNodeWithTag(REASONING_BODY_TAG).assertDoesNotExist()
        assertTrue(!bodyShows(THOUGHT))
    }

    @Test
    fun `the header is a button big enough to touch`() {
        compose.setReasoning(ReasoningCardState(text = THOUGHT))

        header()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertTouchSizeIsAtLeast(48.dp)
    }

    @Test
    fun `a collapsed card is one slim row, and expanding it grows it`() {
        val harness = compose.setReasoning(ReasoningCardState(text = "$THOUGHT\n\n$THOUGHT\n\n$THOUGHT", duration = 4.seconds))
        compose.waitForIdle()
        val collapsed = cardHeight()
        assertTrue(collapsed <= 56f, "a collapsed card is one row, was $collapsed dp")

        harness.state.value = harness.state.value.copy(expanded = true)
        compose.awaitText(THOUGHT, substring = true)
        compose.waitForIdle()
        assertTrue(cardHeight() > collapsed + 24f, "an expanded card shows the thinking under its row")
    }

    /** The card's height in dp. */
    private fun cardHeight(): Float {
        val node = compose.onNodeWithTag(CARD_TAG).fetchSemanticsNode()
        return with(node.layoutInfo.density) { node.boundsInRoot.height.toDp().value }
    }

    // Streaming

    @Test
    fun `streaming text grows on screen without breaking`() {
        val reply = "First I read the **build** file.\n\n```kotlin\nval x = 1\n```\n\nThen I run the tests and fix them."
        val harness = compose.setReasoning(ReasoningCardState(text = reply.take(1), streaming = true, expanded = true))

        for (end in 2..reply.length step 5) {
            harness.state.value = harness.state.value.copy(text = reply.take(end))
            compose.mainClock.advanceTimeByFrame()
        }
        harness.state.value = harness.state.value.copy(text = reply, streaming = false, duration = 9.seconds)

        compose.awaitText("fix them", substring = true)
        compose.awaitText("val x = 1", substring = true)
        assertTrue(!bodyShows("**"), "Markdown markers must not show")
        compose.onNodeWithTag(CARD_TAG).assertExists()
    }

    @Test
    fun `a streaming card keeps its header while the duration grows`() {
        val harness = compose.setReasoning(ReasoningCardState(text = THOUGHT, streaming = true, duration = 1.seconds))

        compose.onNodeWithText("1 s").performScrollTo().assertIsDisplayed()
        harness.state.value = harness.state.value.copy(text = "$THOUGHT more", duration = 2.seconds)

        compose.onNodeWithText("2 s").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 s").assertDoesNotExist()
    }

    // Hidden

    @Test
    fun `a card with no text draws nothing`() {
        compose.setReasoning(ReasoningCardState(text = "  \n", streaming = true, expanded = true))

        compose.onNodeWithTag(CARD_TAG).assertDoesNotExist()
        compose.onNodeWithTag(REASONING_HEADER_TAG).assertDoesNotExist()
    }

    @Test
    fun `a card that gets text appears`() {
        val harness = compose.setReasoning(ReasoningCardState(text = "", streaming = true))

        harness.state.value = harness.state.value.copy(text = THOUGHT)

        compose.onNodeWithTag(CARD_TAG).assertExists()
        header().assertIsDisplayed()
    }
}
