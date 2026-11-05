package io.github.thewisekd.phopencode.feature.files.ui.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState

/**
 * Stub from the contract (P8.2); P8.15 draws the screen: the query, name or content mode, the
 * results with their lines, and the limit notice. Draws [state], sends [onIntent]. The root keeps
 * the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SearchScreen(
    route: FilesRoute.Search,
    state: SearchState,
    onIntent: (SearchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
