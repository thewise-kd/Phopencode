package io.github.thewisekd.phopencode.feature.connect.ui.progress

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep

/** A made-up server name; no real server is named in the tests. */
internal const val SERVER_NAME = "Test box"

internal val ROUTE = ConnectRoute.Progress("profile-1")

/** The steps the indicator shows, in order. */
internal val SHOWN_STEPS = listOf(ProgressStep.Ssh, ProgressStep.HostApproval, ProgressStep.Server, ProgressStep.Api)

/** The screen in the app theme, with [state] changeable by the test and every intent recorded. */
internal class ProgressScreenHarness(initial: ProgressState) {
    val state: MutableState<ProgressState> = mutableStateOf(initial)
    val intents = mutableListOf<ProgressIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                ProgressScreen(route = ROUTE, state = state.value, onIntent = { intents += it })
            }
        }
    }
}

internal fun ComposeContentTestRule.setProgressScreen(state: ProgressState): ProgressScreenHarness {
    val harness = ProgressScreenHarness(state)
    setContent { harness.Content() }
    return harness
}

/** The node says [description] as its state, as TalkBack reads it after the label. */
internal fun hasStateDescription(description: String) =
    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

internal val isPassword = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

/** What a node draws or reads out: its text and the field's shown (transformed) text, not its raw input. */
internal fun showsText(text: String) = SemanticsMatcher("shows '$text'") { node ->
    val shown = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text) +
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    shown.any { text in it }
}

/** The field's shown text is empty: nothing typed is left in it. */
internal val isEmptyField = SemanticsMatcher("the field is empty") { node ->
    node.config.getOrNull(SemanticsProperties.EditableText)?.text.isNullOrEmpty()
}

/** The smallest size of anything tappable, as `PhopencodeSpacing.touchTarget`. */
internal val TOUCH_TARGET = 48.dp

private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** The node's touch bounds are at least [minimum] in both directions. */
internal fun SemanticsNodeInteraction.assertTouchSizeIsAtLeast(minimum: Dp = TOUCH_TARGET): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Touch size is $width x $height, expected at least $minimum")
    }
    return this
}
