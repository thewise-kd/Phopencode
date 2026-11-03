package io.github.thewisekd.phopencode.feature.notify.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState

/**
 * The notify screens, one destination per [NotifyRoute]. The app's NavHost calls this; a new screen of
 * the feature is added here only. Until P11.7 wires the screens, every route opens a placeholder.
 *
 * @param navController Moves between screens, once there are screens to move between.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.notifyGraph(navController: NavController) {
    composable<NotifyRoute.Setup> { RoutePlaceholder(it.toRoute<NotifyRoute.Setup>()) }
    composable<NotifyRoute.Pending> { RoutePlaceholder(it.toRoute<NotifyRoute.Pending>()) }
}

/** Tagged `placeholder:<route>`, the tag the app's navigation test looks for. */
@Composable
private fun RoutePlaceholder(route: NotifyRoute) {
    EmptyState(Modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
