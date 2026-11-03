package io.github.thewisekd.phopencode.feature.connect.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState

/**
 * The connect screens, one destination per [ConnectRoute]. The app's NavHost calls this; a new screen of
 * the feature is added here only. Until P4.3 wires the screens, every route opens a placeholder.
 *
 * @param navController Moves between screens, once there are screens to move between.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.connectGraph(navController: NavController) {
    composable<ConnectRoute.ServerList> { RoutePlaceholder(it.toRoute<ConnectRoute.ServerList>()) }
    composable<ConnectRoute.ServerForm> { RoutePlaceholder(it.toRoute<ConnectRoute.ServerForm>()) }
    composable<ConnectRoute.Keys> { RoutePlaceholder(it.toRoute<ConnectRoute.Keys>()) }
    composable<ConnectRoute.HostKey> { RoutePlaceholder(it.toRoute<ConnectRoute.HostKey>()) }
    composable<ConnectRoute.FolderPicker> { RoutePlaceholder(it.toRoute<ConnectRoute.FolderPicker>()) }
    composable<ConnectRoute.Progress> { RoutePlaceholder(it.toRoute<ConnectRoute.Progress>()) }
}

/** Tagged `placeholder:<route>`, the tag the app's navigation test looks for. */
@Composable
private fun RoutePlaceholder(route: ConnectRoute) {
    EmptyState(Modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
