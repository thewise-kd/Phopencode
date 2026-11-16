package io.github.thewisekd.phopencode.feature.preview.ui.ports

import android.view.View
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPort

internal val route = PreviewRoute.Ports

/** Made-up ports: a dev server, a second one and the opencode server's. */
internal const val DEV_PORT = 5173
internal const val OTHER_PORT = 8080
internal const val OPENCODE_PORT = 4096

internal val listed = PortsState(
    loading = false,
    ports = listOf(
        PreviewPort(OPENCODE_PORT, isOpencode = true),
        PreviewPort(DEV_PORT, isOpencode = false),
        PreviewPort(OTHER_PORT, isOpencode = false),
    ),
)

/** Nothing listens but opencode itself. */
internal val onlyOpencode = PortsState(loading = false, ports = listOf(PreviewPort(OPENCODE_PORT, isOpencode = true)))

internal val empty = PortsState(loading = false)

internal val unsupported = PortsState(loading = false, unsupported = true)

/** Draws the port picker with [state]; returns nothing, [onIntent] gets what it sends. */
internal fun ComposeContentTestRule.setPortsScreen(state: PortsState, onIntent: (PortsIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { PortsScreen(route = route, state = state, onIntent = onIntent, notice = null) }
        }
    }
}

/** Like [setPortsScreen], and gives back the view the screen is drawn in, to ask it for its keyboard. */
internal fun ComposeContentTestRule.setPortsScreenInView(state: PortsState, onIntent: (PortsIntent) -> Unit = {}): () -> View {
    var view: View? = null
    setContent {
        view = LocalView.current
        PhopencodeTheme(darkTheme = false) {
            Surface { PortsScreen(route = route, state = state, onIntent = onIntent, notice = null) }
        }
    }
    return { checkNotNull(view) }
}

/** Scrolls the screen's scrolling part until a node matching [matcher] is in view. */
internal fun ComposeContentTestRule.scrollPortsTo(matcher: SemanticsMatcher) {
    onNodeWithTag(PortsTags.CONTENT).performScrollToNode(matcher)
}

/**
 * Shows one state after another in a single composition, as a test may set its content only once.
 * Draws at [fontScale], the system's default unless a test asks for larger text.
 */
internal class PortsStates(
    private val compose: ComposeContentTestRule,
    private val fontScale: Float = 1f,
    private val onIntent: (PortsIntent) -> Unit = {},
) {
    private val shown = mutableStateOf<PortsState?>(null)
    private var started = false

    fun show(state: PortsState) {
        shown.value = state
        if (!started) {
            started = true
            compose.setThemedAt(fontScale) {
                Surface {
                    shown.value?.let { PortsScreen(route = route, state = it, onIntent = onIntent, notice = null) }
                }
            }
        }
        compose.waitForIdle()
    }
}
