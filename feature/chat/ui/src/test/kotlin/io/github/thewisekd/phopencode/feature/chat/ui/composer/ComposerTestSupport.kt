package io.github.thewisekd.phopencode.feature.chat.ui.composer

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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState

/** A made-up prompt, with Turkish letters so a lost character would show. */
internal const val DRAFT = "Testleri çalıştır ve düzelt"

/** A connected, idle session with [draft] typed. */
internal fun ready(draft: String = "") = ComposerState(draft = draft, enabled = true)

/** A connected session whose work runs, with [draft] typed. */
internal fun running(draft: String = "") = ComposerState(draft = draft, enabled = true, running = true)

/** The composer in the app theme, [height] tall if given, with [state] changeable by the test and every intent recorded. */
internal class ComposerHarness(initial: ComposerState, private val height: Dp?) {
    val state: MutableState<ComposerState> = mutableStateOf(initial)
    val intents = mutableListOf<ComposerIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface(modifier = if (height != null) Modifier.height(height) else Modifier) {
                // Scrollable so tests can scroll a node into view first: at API 35+ the insets push content down.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Composer(state = state.value, onIntent = { intents += it }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

internal fun ComposeContentTestRule.setComposer(state: ComposerState, height: Dp? = null): ComposerHarness {
    val harness = ComposerHarness(state, height)
    setContent { harness.Content() }
    return harness
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
