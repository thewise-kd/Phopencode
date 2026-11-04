package io.github.thewisekd.phopencode.feature.connect.ui.folder

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState

/**
 * Stub from the contract (P4.3); P4.13 draws the screen. Draws [state], sends [onIntent]. The root
 * keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun FolderScreen(
    route: ConnectRoute.FolderPicker,
    state: FolderState,
    onIntent: (FolderIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
