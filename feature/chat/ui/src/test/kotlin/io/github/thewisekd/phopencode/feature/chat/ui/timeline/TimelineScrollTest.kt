package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The auto-scroll rule on screen: a growing streamed reply stays pinned to the bottom while the
 * timeline follows the newest item, and the user's place holds while it does not. Older items join
 * at the top without moving what is on screen.
 *
 * Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TimelineScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private val wait = 5_000L

    /** Sets the last item's streamed text to [paragraphs] paragraphs and waits until it is drawn. */
    private fun TimelineHarness.stream(paragraphs: Int) {
        val items = state.value.items
        state.value = state.value.copy(items = items.dropLast(1) + answer(99, streamed(paragraphs), streaming = true))
        compose.waitUntil(wait) {
            compose.onAllNodes(hasText("Paragraph $paragraphs of the reply", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun assertPinned(item: TimelineItem) {
        val list = compose.listBounds()
        assertNear(list.bottom, compose.boundsOf(item.key).bottom, "the newest item's bottom")
    }

    @Test
    fun `a growing reply stays pinned to the bottom while following`() {
        val harness = compose.setTimeline(loaded(prompts(20) + answer(99, streamed(1), streaming = true)), actLikePresenter = true)
        harness.stream(1)

        for (paragraphs in listOf(3, 8, 15, 25)) {
            harness.stream(paragraphs)

            assertPinned(harness.state.value.items.last())
        }
        assertTrue(harness.state.value.followLatest, "growth must not count as the user scrolling away")
        assertFalse(harness.intents.contains(TimelineIntent.Scrolled(atLatest = false)))
    }

    @Test
    fun `a catch-up cut short goes on in the next frames until the end`() {
        val harness = compose.setTimeline(loaded(prompts(5)), actLikePresenter = true, followStepsPerFrame = 1)

        harness.state.value = harness.state.value.copy(items = harness.state.value.items + prompts(40, from = 5))
        compose.waitForIdle()

        assertPinned(harness.state.value.items.last())
        assertTrue(harness.state.value.followLatest)
    }

    @Test
    fun `a catch-up held off by a resting finger runs once the finger lifts`() {
        val harness = compose.setTimeline(loaded(prompts(20)), actLikePresenter = true)
        val list = compose.onNodeWithTag(TIMELINE_LIST_TAG)
        // Pulling past the end moves nothing, but the drag holds the list.
        list.performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop * 4))
        }
        compose.waitForIdle()

        harness.state.value = harness.state.value.copy(items = harness.state.value.items + prompts(5, from = 20))
        compose.waitForIdle()
        list.performTouchInput { up() }
        compose.waitForIdle()

        assertTrue(harness.state.value.followLatest)
        assertPinned(harness.state.value.items.last())
    }

    @Test
    fun `a drag on a history too short to scroll does not stop the following`() {
        val harness = compose.setTimeline(loaded(prompts(2)), actLikePresenter = true)

        compose.onNodeWithTag(TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
        compose.waitForIdle()
        harness.state.value = harness.state.value.copy(items = harness.state.value.items + prompts(20, from = 2))
        compose.waitForIdle()

        assertTrue(harness.state.value.followLatest)
        assertPinned(harness.state.value.items.last())
    }

    @Test
    fun `a short swipe away that ends between frames is not undone`() {
        val harness = compose.setTimeline(loaded(prompts(20)), actLikePresenter = true)
        val last = harness.state.value.items.last()
        assertPinned(last)

        // Down, move and up with no frame between them, and no time for a fling.
        compose.onNodeWithTag(TIMELINE_LIST_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, viewConfiguration.touchSlop + 150f), delayMillis = 0)
            up()
        }
        compose.waitForIdle()

        assertTrue(TimelineIntent.Scrolled(atLatest = false) in harness.intents)
        assertFalse(harness.state.value.followLatest)
        val stillDrawn = compose.onAllNodes(hasTestTag(timelineItemTag(last.key))).fetchSemanticsNodes().isNotEmpty()
        assertTrue(
            !stillDrawn || compose.boundsOf(last.key).bottom > compose.listBounds().bottom + PIXEL,
            "the list stays where the user took it",
        )
    }

    @Test
    fun `an accessibility scroll away is not undone and stops the following`() {
        val harness = compose.setTimeline(loaded(prompts(20)), actLikePresenter = true)
        val last = harness.state.value.items.last()
        assertPinned(last)

        compose.onNodeWithTag(TIMELINE_LIST_TAG).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -300f) }
        compose.waitForIdle()

        assertTrue(TimelineIntent.Scrolled(atLatest = false) in harness.intents)
        assertFalse(harness.state.value.followLatest)
        val stillDrawn = compose.onAllNodes(hasTestTag(timelineItemTag(last.key))).fetchSemanticsNodes().isNotEmpty()
        assertTrue(
            !stillDrawn || compose.boundsOf(last.key).bottom > compose.listBounds().bottom + PIXEL,
            "the list stays where it was taken",
        )
    }

    @Test
    fun `a growing reply keeps the user's place while not following`() {
        val harness = compose.setTimeline(
            loaded(prompts(20) + answer(99, streamed(1), streaming = true), followLatest = false),
            actLikePresenter = true,
        )
        val anchor = harness.state.value.items[10]
        compose.onNodeWithTag(TIMELINE_LIST_TAG).performScrollToKey(anchor.key.value)
        compose.waitForIdle()
        val before = compose.boundsOf(anchor.key).top

        for (paragraphs in listOf(3, 8, 15, 25)) {
            val items = harness.state.value.items
            harness.state.value = harness.state.value.copy(
                items = items.dropLast(1) + answer(99, streamed(paragraphs), streaming = true),
            )
            compose.waitForIdle()

            assertNear(before, compose.boundsOf(anchor.key).top, "the item the user looks at")
        }
        assertFalse(harness.state.value.followLatest)
    }

    @Test
    fun `new items do not move the view while not following`() {
        val harness = compose.setTimeline(loaded(prompts(20), followLatest = false), actLikePresenter = true)
        val anchor = harness.state.value.items[5]
        compose.onNodeWithTag(TIMELINE_LIST_TAG).performScrollToKey(anchor.key.value)
        compose.waitForIdle()
        val before = compose.boundsOf(anchor.key).top

        harness.state.value = harness.state.value.copy(items = harness.state.value.items + prompts(5, from = 20), unseenCount = 5)
        compose.waitForIdle()

        assertNear(before, compose.boundsOf(anchor.key).top, "the item the user looks at")
    }

    @Test
    fun `new items are followed while following`() {
        val harness = compose.setTimeline(loaded(prompts(20)), actLikePresenter = true)

        harness.state.value = harness.state.value.copy(items = harness.state.value.items + prompts(5, from = 20))
        compose.waitForIdle()

        assertPinned(harness.state.value.items.last())
    }

    @Test
    fun `scrolling away from the newest item says so, and coming back follows again`() {
        val harness = compose.setTimeline(loaded(prompts(20)), actLikePresenter = true)
        assertPinned(harness.state.value.items.last())

        compose.onNodeWithTag(TIMELINE_LIST_TAG).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertTrue(TimelineIntent.Scrolled(atLatest = false) in harness.intents)
        assertFalse(harness.state.value.followLatest)

        compose.onNodeWithTag(TIMELINE_LIST_TAG).performScrollToKey(harness.state.value.items.last().key.value)
        compose.waitForIdle()

        assertEquals(TimelineIntent.Scrolled(atLatest = true), harness.intents.last())
        assertTrue(harness.state.value.followLatest)
    }

    @Test
    fun `the jump button shows the unseen count and goes back to the newest item`() {
        val harness = compose.setTimeline(
            loaded(prompts(20), followLatest = false).copy(unseenCount = 3),
            actLikePresenter = true,
        )

        compose.onNodeWithText("3 new messages").performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue(TimelineIntent.JumpToLatest in harness.intents)
        assertPinned(harness.state.value.items.last())
        compose.onNode(hasTestTag(TIMELINE_JUMP_TAG)).assertDoesNotExist()
    }

    @Test
    fun `no jump button while following`() {
        compose.setTimeline(loaded(prompts(20)))

        compose.onNode(hasTestTag(TIMELINE_JUMP_TAG)).assertDoesNotExist()
    }

    @Test
    fun `the top of the list asks for older items, which join without moving the view`() {
        val harness = compose.setTimeline(loaded(prompts(20, from = 10), followLatest = false).copy(hasOlder = true))
        val first = harness.state.value.items.first()
        val before = compose.boundsOf(first.key).top

        assertEquals(listOf<TimelineIntent>(TimelineIntent.LoadOlder), harness.intents)

        harness.state.value = harness.state.value.copy(items = prompts(10) + harness.state.value.items, hasOlder = false)
        compose.waitForIdle()

        assertNear(before, compose.boundsOf(first.key).top, "the item that was first")
        compose.item(harness.state.value.items.first().key).assertDoesNotExist()
    }

    @Test
    fun `following the newest item asks for nothing older`() {
        val harness = compose.setTimeline(loaded(prompts(20)).copy(hasOlder = true))

        assertPinned(harness.state.value.items.last())
        assertFalse(TimelineIntent.LoadOlder in harness.intents)
    }

    @Test
    fun `a short history that fills no screen asks for older items`() {
        val harness = compose.setTimeline(loaded(prompts(2)).copy(hasOlder = true))

        assertTrue(TimelineIntent.LoadOlder in harness.intents)
    }

    @Test
    fun `older items that failed offer a retry`() {
        val harness = compose.setTimeline(
            loaded(prompts(20), followLatest = false).copy(
                hasOlder = true,
                olderError = ChatFailure.Unreachable,
            ),
        )
        assertFalse(TimelineIntent.LoadOlder in harness.intents, "a failure waits for the user")

        compose.onNodeWithText("Try again").performScrollTo().performClick()

        assertEquals(listOf<TimelineIntent>(TimelineIntent.LoadOlder), harness.intents)
    }
}
