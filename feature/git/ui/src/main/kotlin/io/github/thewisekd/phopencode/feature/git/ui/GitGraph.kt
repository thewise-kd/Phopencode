package io.github.thewisekd.phopencode.feature.git.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState

/**
 * The git screens, one destination per [GitRoute]. The app's NavHost calls this; a new screen of
 * the feature is added here only. Until P9.3 wires the screens, every route opens a placeholder.
 *
 * @param navController Moves between screens, once there are screens to move between.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.gitGraph(navController: NavController) {
    composable<GitRoute.Status> { RoutePlaceholder(it.toRoute<GitRoute.Status>()) }
    composable<GitRoute.FileDiff> { RoutePlaceholder(it.toRoute<GitRoute.FileDiff>()) }
    composable<GitRoute.Log> { RoutePlaceholder(it.toRoute<GitRoute.Log>()) }
    composable<GitRoute.CommitDetail> { RoutePlaceholder(it.toRoute<GitRoute.CommitDetail>()) }
    composable<GitRoute.CommitFileDiff> { RoutePlaceholder(it.toRoute<GitRoute.CommitFileDiff>()) }
    composable<GitRoute.Commit> { RoutePlaceholder(it.toRoute<GitRoute.Commit>()) }
    composable<GitRoute.Branches> { RoutePlaceholder(it.toRoute<GitRoute.Branches>()) }
    composable<GitRoute.Remote> { RoutePlaceholder(it.toRoute<GitRoute.Remote>()) }
}

/** Tagged `placeholder:<route>`, the tag the app's navigation test looks for. */
@Composable
private fun RoutePlaceholder(route: GitRoute) {
    EmptyState(Modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
