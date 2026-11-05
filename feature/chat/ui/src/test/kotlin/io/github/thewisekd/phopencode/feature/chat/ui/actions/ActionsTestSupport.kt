package io.github.thewisekd.phopencode.feature.chat.ui.actions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState

/** The tags the harness puts on the two composables' modifiers, as the chat puts its slot tags there. */
internal const val MENU_HOST_TAG = "test:session_actions"
internal const val MESSAGE_HOST_TAG = "test:message_actions"

/** A made-up prompt the message actions belong to. */
internal val PROMPT = MessageId("msg_prompt_1")

/** A loaded session where an action could start. */
internal val READY = ActionsState(enabled = true)

/**
 * The session menu and, when [withMessage], a prompt's actions, in the app theme, with [state]
 * changeable by the test and every intent and dismissal recorded.
 */
internal class ActionsHarness(initial: ActionsState, private val withMessage: Boolean) {
    val state: MutableState<ActionsState> = mutableStateOf(initial)
    val intents = mutableListOf<ActionsIntent>()
    var dismissed = 0

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                // Scrollable so tests can scroll a part into view, as in the other slots' harnesses.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SessionActionsMenu(
                        state = state.value,
                        onIntent = { intents += it },
                        modifier = Modifier.testTag(MENU_HOST_TAG),
                    )
                    if (withMessage) {
                        MessageActions(
                            messageId = PROMPT,
                            state = state.value,
                            onIntent = { intents += it },
                            onDismiss = { dismissed++ },
                            modifier = Modifier.fillMaxWidth().testTag(MESSAGE_HOST_TAG),
                        )
                    }
                }
            }
        }
    }
}

internal fun ComposeContentTestRule.setActions(state: ActionsState, withMessage: Boolean = false): ActionsHarness {
    val harness = ActionsHarness(state, withMessage)
    setContent { harness.Content() }
    waitForIdle()
    return harness
}

/** The text a node shows, its parts joined. */
internal fun SemanticsNodeInteraction.text(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

/** What a screen reader says for a node instead of its text. */
internal fun SemanticsNodeInteraction.description(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")

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
