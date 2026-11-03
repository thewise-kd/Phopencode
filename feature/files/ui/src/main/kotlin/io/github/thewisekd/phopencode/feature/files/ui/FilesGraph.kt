package io.github.thewisekd.phopencode.feature.files.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState

/**
 * The files screens, one destination per [FilesRoute]. The app's NavHost calls this; a new screen of
 * the feature is added here only. Until P8.2 wires the screens, every route opens a placeholder.
 *
 * @param navController Moves between screens, once there are screens to move between.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.filesGraph(navController: NavController) {
    composable<FilesRoute.Tree> { RoutePlaceholder(it.toRoute<FilesRoute.Tree>()) }
    composable<FilesRoute.Viewer> { RoutePlaceholder(it.toRoute<FilesRoute.Viewer>()) }
    composable<FilesRoute.Editor> { RoutePlaceholder(it.toRoute<FilesRoute.Editor>()) }
    composable<FilesRoute.Search> { RoutePlaceholder(it.toRoute<FilesRoute.Search>()) }
    composable<FilesRoute.Transfer> { RoutePlaceholder(it.toRoute<FilesRoute.Transfer>()) }
}

/** Tagged `placeholder:<route>`, the tag the app's navigation test looks for. */
@Composable
private fun RoutePlaceholder(route: FilesRoute) {
    EmptyState(Modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
