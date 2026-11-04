package io.github.thewisekd.phopencode.feature.sessions.ui.manage

import androidx.compose.foundation.layout.height
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
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageState

internal val TEST_ROUTE = SessionsRoute.Manage(sessionId = "ses_test_1")

/** A made-up title, with Turkish letters so a lost character would show. */
internal const val CURRENT_TITLE = "Çalışma oturumu"

internal const val DIRECTORY = "/srv/örnek-proje"

/** A loaded session, its title unchanged, nothing under way. */
internal fun loaded(
    title: String = CURRENT_TITLE,
    archived: Boolean = false,
    hasChildSessions: Boolean? = false,
) = SessionManageState(
    loading = false,
    currentTitle = CURRENT_TITLE,
    title = title,
    archived = archived,
    directory = DIRECTORY,
    hasChildSessions = hasChildSessions,
)

/** The screen in the app theme, [height] tall if given, with [state] changeable by the test and every intent recorded. */
internal class ManageScreenHarness(initial: SessionManageState, private val height: Dp?) {
    val state: MutableState<SessionManageState> = mutableStateOf(initial)
    val intents = mutableListOf<SessionManageIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface(modifier = if (height != null) Modifier.height(height) else Modifier) {
                SessionManageScreen(route = TEST_ROUTE, state = state.value, onIntent = { intents += it })
            }
        }
    }
}

internal fun ComposeContentTestRule.setManageScreen(
    state: SessionManageState,
    height: Dp? = null,
): ManageScreenHarness {
    val harness = ManageScreenHarness(state, height)
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
