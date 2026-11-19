package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A long press on a delivered prompt opens its actions slot, which the chat fills with the prompt's
 * actions (P7.15); a prompt still waiting in the inbox has none. Nothing else of the timeline
 * changes. A reply's text is selectable instead (P15.12): its long press selects text and opens no
 * prompt actions, and its links and the list's scrolling work as before.
 *
 * Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TimelineUserActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<TimelineItem.User>()

    private fun actionsTag(item: TimelineItem.User) = "test:actions:${item.key.value}"

    private fun setTimeline(state: TimelineState) {
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface {
                    Timeline(
                        state = state,
                        onIntent = {},
                        reasoningCard = {},
                        toolCard = {},
                        modifier = Modifier.fillMaxWidth().height(TIMELINE_HEIGHT),
                        userActions = { item, dismiss ->
                            opened += item
                            Box(Modifier.testTag(actionsTag(item))) {
                                TextButton(onClick = dismiss, modifier = Modifier.testTag("test:dismiss")) { Text("Close") }
                            }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bubble() = compose.onNode(hasTestTag(TIMELINE_USER_BUBBLE_TAG), useUnmergedTree = true)

    private fun row() = compose.onNode(hasTestTag(TIMELINE_USER_ROW_TAG), useUnmergedTree = true)

    @Test
    fun `a long press on a prompt opens its actions, and they can be dismissed`() {
        val prompt = user(1, "Fix the build")
        setTimeline(loaded(listOf(prompt)))
        compose.onNodeWithTag(actionsTag(prompt)).assertDoesNotExist()

        bubble().performScrollTo().performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(actionsTag(prompt)).assertExists()
        assertEquals(prompt, opened.last())

        compose.onNodeWithTag("test:dismiss").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(actionsTag(prompt)).assertDoesNotExist()
    }

    @Test
    fun `the actions are offered to accessibility services as a long click`() {
        setTimeline(loaded(listOf(user(1))))

        row().assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun `a prompt still waiting in the inbox has no actions`() {
        val waiting = user(2).copy(pending = PromptDelivery.Queue)
        setTimeline(loaded(listOf(waiting)))

        bubble().performScrollTo().performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(actionsTag(waiting)).assertDoesNotExist()
        row().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    private fun body() = compose.onNode(hasTestTag(TIMELINE_ASSISTANT_BODY_TAG), useUnmergedTree = true)

    /** The drawn text that shows [text], once the Markdown, parsed off the main thread, is on screen. */
    private fun drawnText(text: String): SemanticsNodeInteraction {
        val matcher = hasText(text, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        compose.waitUntil(5_000) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher, useUnmergedTree = true).onFirst()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a long press on a reply selects part of it to copy and opens no prompt actions`() {
        val prompt = user(1, "Fix the build")
        val reply = "Selectable words here"
        setTimeline(loaded(listOf(prompt, answer(1, text = reply, whole = reply))))

        drawnText(reply).performTouchInput { longClick(centerLeft + Offset(width * 0.1f, 0f)) }
        compose.waitForIdle()
        assertTrue(opened.isEmpty(), "a reply's long press opened prompt actions")

        // The long press selected a word of this block; the copy key copies that selection, the
        // same copy the system toolbar offers.
        drawnText(reply).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        compose.waitForIdle()

        val copied = clipboardText().orEmpty()
        assertTrue(copied.isNotBlank() && copied in reply && copied != reply, "copied part: '$copied'")
    }

    @Test
    fun `a link in a selectable reply still asks before it opens`() {
        setTimeline(loaded(listOf(answer(1, text = "See [the docs](https://example.org/guide) first."))))

        tapLink("the docs")
        compose.waitForIdle()

        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasText("https://example.org/guide", substring = true) and hasAnyAncestor(isDialog())).assertExists()
    }

    @Test
    fun `a swipe over a reply still scrolls the list and stops the following`() {
        val reply = answer(30, text = streamed(30), whole = streamed(30))
        val harness = compose.setTimeline(loaded(prompts(10) + reply), actLikePresenter = true)
        assertTrue(harness.state.value.followLatest)
        drawnText("Paragraph 30 of the reply")
        compose.waitForIdle()

        body().performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertTrue(TimelineIntent.Scrolled(atLatest = false) in harness.intents, "intents: ${harness.intents}")
        assertFalse(harness.state.value.followLatest)
    }

    /** Taps the middle of [label] inside the text that shows it, the way a finger would. */
    private fun tapLink(label: String) {
        val node = drawnText(label)
        node.performScrollTo()
        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        val start = text.indexOf(label)
        check(start >= 0) { "link text not found" }
        val layouts = mutableListOf<TextLayoutResult>()
        checkNotNull(node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
        val box = layouts.single().getBoundingBox(start + label.length / 2)
        node.performTouchInput { click(box.center) }
    }
}
