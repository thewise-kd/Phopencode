package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import kotlin.math.abs

/** Small enough to fit under the status bar that API 35+ pushes content down by. */
internal val TIMELINE_HEIGHT = 360.dp

internal fun reasoningSlotTag(key: TimelineKey) = "test:reasoning:${key.value}"

internal fun toolSlotTag(key: TimelineKey) = "test:tool:${key.value}"

/** A made-up prompt, with Turkish letters so a lost character would show. */
internal fun user(n: Int, text: String = "Soru $n: çalıştır mı?"): TimelineItem.User {
    val id = MessageId("msg_u$n")
    return TimelineItem.User(TimelineKey.message(id), id, text)
}

internal fun answer(n: Int, text: String = "Answer $n", streaming: Boolean = false): TimelineItem.AssistantText {
    val id = MessageId("msg_a$n")
    return TimelineItem.AssistantText(TimelineKey.text(id, 0), id, text, streaming)
}

/** [count] prompts, oldest first, numbered from [from]. */
internal fun prompts(count: Int, from: Int = 0): List<TimelineItem> = (from until from + count).map { user(it) }

/** [paragraphs] Markdown paragraphs, each its own line on screen. */
internal fun streamed(paragraphs: Int): String = (1..paragraphs).joinToString("\n\n") { "Paragraph $it of the reply" }

internal fun loaded(items: List<TimelineItem>, followLatest: Boolean = true) =
    TimelineState(loading = false, items = items, followLatest = followLatest)

/**
 * The timeline in the app theme, [TIMELINE_HEIGHT] tall, with [state] changeable by the test and
 * every intent recorded. With [actLikePresenter], scrolling and jumping change the state the way
 * the presenter does. [followStepsPerFrame] lowers how far one catch-up with the end may go.
 */
internal class TimelineHarness(
    initial: TimelineState,
    private val actLikePresenter: Boolean,
    private val followStepsPerFrame: Int,
) {
    val state: MutableState<TimelineState> = mutableStateOf(initial)
    val intents = mutableListOf<TimelineIntent>()

    private fun onIntent(intent: TimelineIntent) {
        intents += intent
        if (!actLikePresenter) return
        val current = state.value
        state.value = when (intent) {
            is TimelineIntent.Scrolled ->
                if (intent.atLatest) current.copy(followLatest = true, unseenCount = 0) else current.copy(followLatest = false)
            TimelineIntent.JumpToLatest -> current.copy(followLatest = true, unseenCount = 0)
            TimelineIntent.LoadOlder -> current
        }
    }

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                // Scrollable so tests can scroll a node into view first: at API 35+ the insets push content down.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TimelineContent(
                        state = state.value,
                        onIntent = ::onIntent,
                        reasoningCard = { Box(Modifier.fillMaxWidth().height(40.dp).testTag(reasoningSlotTag(it.key))) },
                        toolCard = { Box(Modifier.fillMaxWidth().height(40.dp).testTag(toolSlotTag(it.key))) },
                        modifier = Modifier.fillMaxWidth().height(TIMELINE_HEIGHT),
                        followStepsPerFrame = followStepsPerFrame,
                    )
                }
            }
        }
    }
}

internal fun ComposeContentTestRule.setTimeline(
    state: TimelineState,
    actLikePresenter: Boolean = false,
    followStepsPerFrame: Int = FOLLOW_STEPS_PER_FRAME,
): TimelineHarness {
    val harness = TimelineHarness(state, actLikePresenter, followStepsPerFrame)
    setContent { harness.Content() }
    waitForIdle()
    return harness
}

internal fun ComposeContentTestRule.item(key: TimelineKey): SemanticsNodeInteraction =
    onNode(hasTestTag(timelineItemTag(key)))

internal fun ComposeContentTestRule.listBounds(): Rect = onNode(hasTestTag(TIMELINE_LIST_TAG)).pixelBounds()

internal fun ComposeContentTestRule.boundsOf(key: TimelineKey): Rect = item(key).pixelBounds()

/** Bounds in the root, in pixels and not clipped by the list, for exact comparisons. */
internal fun SemanticsNodeInteraction.pixelBounds(): Rect {
    val density = fetchSemanticsNode().layoutInfo.density
    val bounds = getUnclippedBoundsInRoot()
    return with(density) { Rect(bounds.left.toPx(), bounds.top.toPx(), bounds.right.toPx(), bounds.bottom.toPx()) }
}

/** One pixel, for rounding between dp and pixels. */
internal const val PIXEL = 1.5f

internal fun assertNear(expected: Float, actual: Float, what: String) {
    if (abs(expected - actual) > PIXEL) throw AssertionError("$what: expected $expected, was $actual")
}
