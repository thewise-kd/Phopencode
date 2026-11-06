package io.github.thewisekd.phopencode.feature.git.ui.branch

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState

/**
 * Stub from the contract (P9.3); P9.13 draws the screen: local and remote branches apart, the
 * search, and the create, switch and delete dialogs. Draws [state], sends
 * [onIntent]. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun BranchesScreen(
    route: GitRoute.Branches,
    state: BranchesState,
    onIntent: (BranchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
