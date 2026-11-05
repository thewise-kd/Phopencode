package io.github.thewisekd.phopencode.feature.files.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState

/**
 * Stub from the contract (P8.2); P8.7 draws the screen: highlighted code at the route's line,
 * Markdown rendered or raw, the image preview, and the size warning. Draws [state], sends
 * [onIntent]. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ViewerScreen(
    route: FilesRoute.Viewer,
    state: ViewerState,
    onIntent: (ViewerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
