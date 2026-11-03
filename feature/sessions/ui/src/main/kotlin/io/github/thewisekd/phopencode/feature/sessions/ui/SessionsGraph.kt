package io.github.thewisekd.phopencode.feature.sessions.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState

/**
 * The sessions screens, one destination per [SessionsRoute]. The app's NavHost calls this; a new screen of
 * the feature is added here only. Until P5.1 wires the screens, every route opens a placeholder.
 *
 * @param navController Moves between screens, once there are screens to move between.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.sessionsGraph(navController: NavController) {
    composable<SessionsRoute.SessionList> { RoutePlaceholder(it.toRoute<SessionsRoute.SessionList>()) }
    composable<SessionsRoute.Create> { RoutePlaceholder(it.toRoute<SessionsRoute.Create>()) }
    composable<SessionsRoute.Manage> { RoutePlaceholder(it.toRoute<SessionsRoute.Manage>()) }
}

/** Tagged `placeholder:<route>`, the tag the app's navigation test looks for. */
@Composable
private fun RoutePlaceholder(route: SessionsRoute) {
    EmptyState(Modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
