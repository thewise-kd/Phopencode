package io.github.thewisekd.phopencode.feature.preview.ui.page.console

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleKind
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.ui.ports.setThemedAt

/** The tag of the harness's root, which stands for the page the panel lies over. */
internal const val HARNESS_ROOT = "console_harness_root"

/** Made-up sources on the preview origin. */
internal const val APP_SOURCE = "http://127.0.0.1:41234/src/app.js"
internal const val STYLE_SOURCE = "http://127.0.0.1:41234/style.css"

/** One entry of each level, oldest first, with and without a source and a line. */
internal val debugEntry = ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Debug, "render took 4 ms", APP_SOURCE, 7)
internal val logEntry = ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Log, "hello from the page", APP_SOURCE, 12)
internal val infoEntry = ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Info, "hot reload ready", null, 3)
internal val warningEntry = ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Warning, "deprecated call", APP_SOURCE, null)
internal val errorEntry = ConsoleEntry(ConsoleKind.LoadError, ConsoleLevel.Error, "net::ERR_FAILED", STYLE_SOURCE, null)
internal val secondError = ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Error, "TypeError: x is undefined", APP_SOURCE, 40)

internal val allLevels = ConsoleState(entries = listOf(debugEntry, logEntry, infoEntry, warningEntry, errorEntry, secondError))

internal val emptyConsole = ConsoleState()

/**
 * The console as the preview page lays it out: the toolbar button at the top, the panel over the
 * bottom of the page once the button opens it. [state] is what the page logic holds; [onIntent]
 * gets what the panel sends.
 */
@Composable
internal fun ConsoleHarness(state: ConsoleState, onIntent: (PageIntent) -> Unit, startExpanded: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    Surface {
        Box(Modifier.fillMaxSize().testTag(HARNESS_ROOT)) {
            ConsoleButton(
                errorCount = state.errorCount,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                modifier = Modifier.align(Alignment.TopEnd),
            )
            if (expanded) {
                ConsolePanel(
                    console = state,
                    onIntent = onIntent,
                    onHide = { expanded = false },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** Draws [ConsoleHarness] with [state], the panel open unless [expanded] says otherwise. */
internal fun ComposeContentTestRule.setConsole(
    state: ConsoleState,
    expanded: Boolean = true,
    onIntent: (PageIntent) -> Unit = {},
) {
    setContent {
        PhopencodeTheme(darkTheme = false) { ConsoleHarness(state, onIntent, expanded) }
    }
}

/** Like [setConsole], at [fontScale], for the accessibility audit. */
internal fun ComposeContentTestRule.setConsoleAt(fontScale: Float, state: ConsoleState, onIntent: (PageIntent) -> Unit = {}) {
    setThemedAt(fontScale) { ConsoleHarness(state, onIntent, startExpanded = true) }
}

/** Scrolls the panel's list until the shown entry at [index] is in view. */
internal fun ComposeContentTestRule.scrollConsoleTo(index: Int) {
    onNodeWithTag(ConsoleTags.LIST).performScrollToIndex(index)
    waitForIdle()
}

/** Shows one console state after another in a single composition, as a test may set its content only once. */
internal class ConsoleStates(
    private val compose: ComposeContentTestRule,
    private val onIntent: (PageIntent) -> Unit = {},
) {
    private val shown = mutableStateOf(ConsoleState())
    private var started = false

    fun show(state: ConsoleState) {
        shown.value = state
        if (!started) {
            started = true
            compose.setContent {
                PhopencodeTheme(darkTheme = false) { ConsoleHarness(shown.value, onIntent, startExpanded = true) }
            }
        }
        compose.waitForIdle()
    }
}
