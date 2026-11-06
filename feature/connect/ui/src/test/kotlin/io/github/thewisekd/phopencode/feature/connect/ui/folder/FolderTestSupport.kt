package io.github.thewisekd.phopencode.feature.connect.ui.folder

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState

/** Made-up paths: no real server layout or user name. */
internal const val CURRENT = "/work/projects"
internal val alpha = FileEntry(path = "$CURRENT/alpha", type = FileEntryType.Directory)
internal val beta = FileEntry(path = "$CURRENT/beta", type = FileEntryType.Directory)
internal val linked = FileEntry(path = "$CURRENT/linked", type = FileEntryType.Symlink)
internal const val RECENT_ONE = "/work/projects/alpha"
internal const val RECENT_TWO = "/data/sample"

/** A failure's detail as the presenter builds it. */
internal const val DETAIL = "SshException.Sftp(Failed) <- java.io.EOFException"

internal val browsing = FolderState(
    loading = false,
    path = CURRENT,
    entries = listOf(alpha, beta, linked),
)

internal fun ComposeContentTestRule.setFolderScreen(
    state: FolderState,
    onIntent: (FolderIntent) -> Unit = {},
) {
    setContent { FolderScreenUnderTest(state, onIntent) }
}

/** The folder picker as the tests draw it, for a test that changes [state] after the first frame. */
@Composable
internal fun FolderScreenUnderTest(state: FolderState, onIntent: (FolderIntent) -> Unit = {}) {
    PhopencodeTheme(darkTheme = false) {
        Surface {
            FolderScreen(route = ConnectRoute.FolderPicker, state = state, onIntent = onIntent)
        }
    }
}

/** The smallest size of anything tappable, as `PhopencodeSpacing.touchTarget`. */
internal val FOLDER_TOUCH_TARGET = 48.dp

/** Float rounding slack, the same as Compose's own `assertTouchHeightIsEqualTo`. */
private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** Asserts the node's touch bounds are at least [minimum] in both directions. */
internal fun SemanticsNodeInteraction.assertTouchAtLeast(minimum: Dp = FOLDER_TOUCH_TARGET): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Actual touch size is $width x $height, expected at least $minimum")
    }
    return this
}
