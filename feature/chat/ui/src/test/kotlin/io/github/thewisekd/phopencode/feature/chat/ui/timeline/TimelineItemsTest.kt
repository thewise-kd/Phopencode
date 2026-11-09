package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TurnOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How items look: a prompt and a reply are told apart at a glance, a reply renders its Markdown,
 * and reasoning and tool items draw only what their slots give.
 *
 * Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TimelineItemsTest {
    @get:Rule
    val compose = createComposeRule()

    private val message = MessageId("msg_a1")

    @Test
    fun `a prompt sits in a bubble on the end side, a reply spans the width from the start`() {
        val prompt = user(1, "Run the tests")
        val reply = answer(1, "All tests pass.")
        compose.setTimeline(loaded(listOf(prompt, reply)))
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("All tests pass.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        val list = compose.listBounds()
        val bubble = compose.onNode(hasTestTag(TIMELINE_USER_BUBBLE_TAG), useUnmergedTree = true).pixelBounds()
        val body = compose.onNode(hasTestTag(TIMELINE_ASSISTANT_BODY_TAG), useUnmergedTree = true).pixelBounds()

        assertTrue(bubble.left > list.left + list.width / 8, "the prompt leaves room on the start side")
        assertTrue(list.right - bubble.right < list.width / 10, "the prompt keeps to the end side")
        assertTrue(body.left - list.left < list.width / 10, "the reply starts at the start side")
        assertTrue(body.width > list.width * 0.8f, "the reply spans the width")
        compose.assertNotDrawn("You")
        compose.assertNotDrawn("Assistant")
        assertEquals(listOf("You", "Run the tests"), compose.spokenTexts("Run the tests"))
        assertEquals("Assistant", compose.spokenTexts("All tests pass.").first())
    }

    @Test
    fun `who wrote an item is part of the item, so nothing on screen can hide it`() {
        val reply = answer(1, streamed(40))
        compose.setTimeline(loaded(listOf(user(1, "Run the tests"), reply)))
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Paragraph 40 of the reply", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // One node per item, the author first: no node of its own that the item's text could cover.
        val spoken = compose.spokenTexts("Paragraph 1 of the reply")
        assertEquals("Assistant", spoken.first())
        assertTrue(spoken.any { it.contains("Paragraph 40 of the reply") }, "the whole reply is in the same node")
        compose.onAllNodes(hasContentDescription("Assistant"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a successful end of the work draws nothing`() {
        val end = TimelineItem.TurnEnded(TimelineKey.message(message), message, TurnOutcome.Succeeded)
        compose.setTimeline(loaded(listOf(user(1), end)))

        compose.item(end.key).assertExists()
        assertEquals(0f, compose.item(end.key).pixelBounds().height, "height of a successful end")
        compose.onNode(hasTestTag(TIMELINE_TURN_CHIP_TAG), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a failed, stopped or unknown end is a compact chip`() {
        for (outcome in listOf(TurnOutcome.Failed, TurnOutcome.Interrupted, TurnOutcome.Unknown)) {
            val end = TimelineItem.TurnEnded(TimelineKey.message(message), message, outcome)
            val harness = compose.setTimelineOnce(loaded(listOf(end)))
            harness.state.value = loaded(listOf(end))
            compose.waitForIdle()

            val list = compose.listBounds()
            val chip = compose.onNode(hasTestTag(TIMELINE_TURN_CHIP_TAG), useUnmergedTree = true).pixelBounds()
            assertTrue(chip.width < list.width * 0.9f, "the chip of $outcome is compact")
            assertTrue(chip.height > 0f)
        }
    }

    @Test
    fun `a failed end says why`() {
        val end = TimelineItem.TurnEnded(
            TimelineKey.message(message),
            message,
            TurnOutcome.Failed,
            StructuredError("provider.rate", message = "Rate limited"),
        )
        compose.setTimeline(loaded(listOf(end)))

        compose.onNode(hasTestTag(TIMELINE_TURN_CHIP_TAG), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("The work failed: Rate limited").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an empty session welcomes with hints`() {
        compose.setTimeline(TimelineState(loading = false))

        compose.onNodeWithText("What should we build?").assertIsDisplayed()
        compose.onNodeWithText("Type / for commands and skills, or @ for files and agents.").assertIsDisplayed()
        compose.onNode(hasTestTag(TIMELINE_LIST_TAG)).assertDoesNotExist()
    }

    /** The harness, set once per test: a test that loops shows each case through its state. */
    private var once: TimelineHarness? = null

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setTimelineOnce(state: TimelineState): TimelineHarness =
        once ?: setTimeline(state).also { once = it }

    @Test
    fun `a reply renders its Markdown`() {
        compose.setTimeline(loaded(listOf(answer(1, "Some **bold** words and `code`"))))

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Some bold words and", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasText("**", substring = true), useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue(it.isEmpty(), "the Markdown marks are not shown")
        }
    }

    @Test
    fun `a prompt's text is drawn as typed`() {
        compose.setTimeline(loaded(listOf(user(1, "Use **stars** as is"))))

        compose.onNodeWithText("Use **stars** as is").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `reasoning and tool items draw only their slots`() {
        val thinking = TimelineItem.Reasoning(TimelineKey.reasoning(message, 0), message)
        val call = TimelineItem.Tool(TimelineKey.tool(message, "call_1"), message, callId = "call_1", name = "bash")
        compose.setTimeline(loaded(listOf(thinking, call)))

        compose.onNode(hasTestTag(reasoningSlotTag(thinking.key))).assertExists()
        compose.onNode(hasTestTag(toolSlotTag(call.key))).assertExists()
        compose.onNodeWithText("bash", substring = true).assertDoesNotExist()
        compose.item(thinking.key).assertExists()
        compose.item(call.key).assertExists()
    }

    @Test
    fun `every item carries its key`() {
        val items = listOf(user(1), answer(1), user(2))
        compose.setTimeline(loaded(items, followLatest = false))

        items.forEach { compose.item(it.key).assertExists() }
    }
}

