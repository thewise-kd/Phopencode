package io.github.thewisekd.phopencode.feature.files.ui.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorState

/**
 * Stub from the contract (P8.2); P8.9 draws the screen: the code view in edit mode, save, the
 * conflict choice, and the confirmation when leaving without saving (system back sends
 * `EditorIntent.Back`). Draws [state], sends [onIntent]. The root keeps the `placeholder:<route>`
 * tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun EditorScreen(
    route: FilesRoute.Editor,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
