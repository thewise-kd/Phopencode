package io.github.thewisekd.phopencode.feature.chat.ui.reasoning

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningCardState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The texts of the reasoning card in one language. [seconds] and [minutes] format a duration;
 * [thoughtFor] wraps a formatted duration.
 */
internal data class ExpectedReasoningStrings(
    val thinking: String,
    val thought: String,
    val live: String,
    val thoughtFor: (String) -> String,
    val seconds: (Int) -> String,
    val minutes: (Int) -> String,
    val minutesSeconds: (Int, Int) -> String,
    val expanded: String,
    val collapsed: String,
    val show: String,
    val hide: String,
)

/** What the card's header says, the same in each language; a subclass sets the language and its texts. */
abstract class ReasoningLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedReasoningStrings

    private fun header(): SemanticsNodeInteraction =
        compose.onNodeWithTag(REASONING_HEADER_TAG).performScrollTo().assertIsDisplayed()

    /** The texts of the header, in order. */
    private fun headerTexts(): List<String> =
        header().fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /** The card a test draws once and then changes; content can be set only once per test. */
    private var shown: ReasoningHarness? = null

    private fun assertHeader(state: ReasoningCardState, vararg expected: String) {
        val current = shown
        if (current == null) shown = compose.setReasoning(state) else current.state.value = state
        compose.waitForIdle()
        assertEquals(expected.toList(), headerTexts(), "header of $state")
    }

    @Test
    fun `a streaming card says it is thinking, how long, and that it is live`() {
        assertHeader(
            ReasoningCardState(text = THOUGHT, streaming = true, duration = 12.seconds),
            strings.thinking, strings.seconds(12), strings.live,
        )
        assertHeader(ReasoningCardState(text = THOUGHT, streaming = true), strings.thinking, strings.live)
        assertHeader(
            ReasoningCardState(text = THOUGHT, streaming = true, duration = 2.minutes + 5.seconds),
            strings.thinking, strings.minutesSeconds(2, 5), strings.live,
        )
    }

    @Test
    fun `a finished card says how long it thought`() {
        assertHeader(ReasoningCardState(text = THOUGHT, duration = 12.seconds), strings.thoughtFor(strings.seconds(12)))
        assertHeader(
            ReasoningCardState(text = THOUGHT, duration = 12_900.milliseconds),
            strings.thoughtFor(strings.seconds(12)),
        )
        assertHeader(ReasoningCardState(text = THOUGHT, duration = Duration.ZERO), strings.thoughtFor(strings.seconds(0)))
        assertHeader(ReasoningCardState(text = THOUGHT, duration = 3.minutes), strings.thoughtFor(strings.minutes(3)))
        assertHeader(
            ReasoningCardState(text = THOUGHT, duration = 61.seconds),
            strings.thoughtFor(strings.minutesSeconds(1, 1)),
        )
        assertHeader(ReasoningCardState(text = THOUGHT), strings.thought)
    }

    @Test
    fun `the header says whether the card is open and what a tap does`() {
        val harness = compose.setReasoning(ReasoningCardState(text = THOUGHT))

        var node = header().fetchSemanticsNode()
        assertEquals(strings.collapsed, node.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(strings.show, node.config.getOrNull(SemanticsActions.OnClick)?.label)

        harness.state.value = harness.state.value.copy(expanded = true)
        compose.waitForIdle()
        node = header().fetchSemanticsNode()
        assertEquals(strings.expanded, node.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(strings.hide, node.config.getOrNull(SemanticsActions.OnClick)?.label)
    }
}
