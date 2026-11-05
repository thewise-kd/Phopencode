package io.github.thewisekd.phopencode.feature.chat.ui.reasoning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey

/** The card's key in the tests. */
internal val KEY: TimelineKey = TimelineKey.reasoning(MessageId("msg_1"), 0)

/** The tag the harness puts on the card's modifier, as the chat puts its slot tag there. */
internal const val CARD_TAG = "test:reasoning"

/** Made-up thinking, with Turkish letters so a lost character would show. */
internal const val THOUGHT = "Önce testleri çalıştırıp hatayı bulmalıyım."

/** Parsing runs off the main thread, so a Markdown result needs a wait. */
private const val WAIT_MS = 5_000L

/** The card in the app theme, with [state] changeable by the test and every intent recorded. */
internal class ReasoningHarness(initial: ReasoningCardState) {
    val state: MutableState<ReasoningCardState> = mutableStateOf(initial)
    val intents = mutableListOf<ReasoningIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                // Scrollable so tests can scroll a node into view first: at API 35+ the insets push content down.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReasoningCard(
                        key = KEY,
                        state = state.value,
                        onIntent = { intents += it },
                        modifier = Modifier.fillMaxWidth().testTag(CARD_TAG),
                    )
                }
            }
        }
    }
}

internal fun ComposeContentTestRule.setReasoning(state: ReasoningCardState): ReasoningHarness {
    val harness = ReasoningHarness(state)
    setContent { harness.Content() }
    return harness
}

/** Waits until some node shows [text] (a part of its text if [substring]). */
internal fun ComposeContentTestRule.awaitText(text: String, substring: Boolean = false) {
    waitUntil(WAIT_MS) {
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
}

private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** The node's touch bounds are at least [minimum] in both directions. */
internal fun SemanticsNodeInteraction.assertTouchSizeIsAtLeast(minimum: Dp): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Touch size is $width x $height, expected at least $minimum")
    }
    return this
}
