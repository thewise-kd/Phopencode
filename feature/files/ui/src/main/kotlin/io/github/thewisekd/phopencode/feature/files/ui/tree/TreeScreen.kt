package io.github.thewisekd.phopencode.feature.files.ui.tree

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState

/**
 * Stub from the contract (P8.2); P8.5 draws the screen: the path bar, the rows with their icons,
 * badges and `.gitignore` marker, and system back going up a folder. Draws [state], sends
 * [onIntent]. The file operations draw over it on the same destination. The root keeps the
 * `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun TreeScreen(
    route: FilesRoute.Tree,
    state: TreeState,
    onIntent: (TreeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
