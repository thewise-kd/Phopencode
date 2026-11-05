package io.github.thewisekd.phopencode.feature.chat.ui.tool

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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSummary

/** The card's key in the tests. */
internal val KEY: TimelineKey = TimelineKey.tool(MessageId("msg_1"), "call_1")

/** The tag the harness puts on the card's modifier, as the chat puts its slot tag there. */
internal const val CARD_TAG = "test:tool"

/** A made-up command, with Turkish letters so a lost character would show. */
internal const val COMMAND = "ls -la çıktı/"

/** A shell call in [status] with [input] and [output]. */
internal fun shellCard(
    status: ToolCardStatus = ToolCardStatus.Running,
    input: String? = null,
    output: String? = null,
) = ToolCardState(
    callId = "call_1",
    name = "bash",
    status = status,
    summary = ToolSummary.Shell(COMMAND),
    input = input,
    output = output,
)

/** [count] numbered lines, "line 1" to "line [count]". */
internal fun numberedLines(count: Int): String = (1..count).joinToString("\n") { "line $it" }

/** The card in the app theme, with [state] changeable by the test and every intent recorded. */
internal class ToolHarness(initial: ToolCardState) {
    val state: MutableState<ToolCardState> = mutableStateOf(initial)
    val intents = mutableListOf<ToolIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                // Scrollable so tests can scroll a node into view first: at API 35+ the insets push content down.
                // The no-vertical-scroll test checks the card's own subtree, not this wrapper.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ToolCard(
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

internal fun ComposeContentTestRule.setTool(state: ToolCardState): ToolHarness {
    val harness = ToolHarness(state)
    setContent { harness.Content() }
    return harness
}

/** The text a node shows, its parts joined. */
internal fun SemanticsNodeInteraction.text(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

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
