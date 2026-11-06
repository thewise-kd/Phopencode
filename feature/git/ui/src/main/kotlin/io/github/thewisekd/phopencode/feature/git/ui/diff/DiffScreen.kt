package io.github.thewisekd.phopencode.feature.git.ui.diff

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState

/**
 * Stub from the contract (P9.3); P9.7 draws the screen: line colours, horizontal scrolling and the
 * addition and deletion counts. Both `GitRoute.FileDiff` and `GitRoute.CommitFileDiff` open it, so
 * [route] is one of those. Draws [state], sends
 * [onIntent]. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun DiffScreen(
    route: GitRoute,
    state: DiffState,
    onIntent: (DiffIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
