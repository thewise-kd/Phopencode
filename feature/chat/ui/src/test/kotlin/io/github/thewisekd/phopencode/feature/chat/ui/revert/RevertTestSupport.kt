package io.github.thewisekd.phopencode.feature.chat.ui.revert

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.StagedRevert

internal val DIFF_ROUTE = ChatRoute.TurnDiff(sessionId = "ses_1", messageId = "msg_1")

internal val THIS_TURN = MessageId("msg_1")
internal val OTHER_TURN = MessageId("msg_0")

/** A made-up path, with Turkish letters so a lost character would show. */
internal const val EDITED_PATH = "src/çekirdek/Oturum.kt"
internal const val ADDED_PATH = "docs/new.md"
internal const val BINARY_PATH = "assets/logo.png"

internal const val EDITED_PATCH = """--- a/src/çekirdek/Oturum.kt
+++ b/src/çekirdek/Oturum.kt
@@ -1,3 +1,4 @@
 package core
-val old = 1
+val new = 2
+val more = 3"""

internal const val ADDED_PATCH = """--- /dev/null
+++ b/docs/new.md
@@ -0,0 +1 @@
+# New"""

/** A modified file, an added one, and a binary one with neither counts nor patch. */
internal val FILES = listOf(
    FileDiff(VcsFile(EDITED_PATH, VcsFileStatus.Modified, additions = 1_234, deletions = 1), EDITED_PATCH),
    FileDiff(VcsFile(ADDED_PATH, VcsFileStatus.Added, additions = 1, deletions = 0), ADDED_PATCH),
    FileDiff(VcsFile(BINARY_PATH, VcsFileStatus.Unknown("binary"))),
)

/** A loaded, connected turn with nothing staged. */
internal val LOADED = RevertState(loading = false, enabled = true, files = FILES)

/** [LOADED] with this turn's revert staged, waiting for its confirmation. */
internal val STAGED = LOADED.copy(staged = StagedRevert(THIS_TURN, files = FILES.take(2), thisTurn = true))

/** [LOADED] with another turn's revert staged. */
internal val STAGED_ELSEWHERE = LOADED.copy(staged = StagedRevert(OTHER_TURN, files = FILES, thisTurn = false))

/** A patch of [lines] lines, each its own number. */
internal fun longPatch(lines: Int): String = (1..lines).joinToString("\n") { "+line $it" }

/** The page in the app theme, with [state] changeable by the test and every intent recorded. */
internal class TurnDiffHarness(initial: RevertState) {
    val state: MutableState<RevertState> = mutableStateOf(initial)
    val intents = mutableListOf<RevertIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            TurnDiffScreen(route = DIFF_ROUTE, state = state.value, onIntent = { intents += it })
        }
    }
}

internal fun ComposeContentTestRule.setTurnDiff(state: RevertState): TurnDiffHarness =
    TurnDiffHarness(state).also { harness -> setContent { harness.Content() } }

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
