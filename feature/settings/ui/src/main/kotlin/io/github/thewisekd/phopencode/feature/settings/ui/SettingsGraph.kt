package io.github.thewisekd.phopencode.feature.settings.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState

/**
 * The settings screens, one destination per [SettingsRoute]. The app's NavHost calls this; a new screen of
 * the feature is added here only. Until P10.1 wires the screens, every route opens a placeholder.
 *
 * @param navController Moves between screens, once there are screens to move between.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.settingsGraph(navController: NavController) {
    composable<SettingsRoute.Preferences> { RoutePlaceholder(it.toRoute<SettingsRoute.Preferences>()) }
    composable<SettingsRoute.Licenses> { RoutePlaceholder(it.toRoute<SettingsRoute.Licenses>()) }
}

/** Tagged `placeholder:<route>`, the tag the app's navigation test looks for. */
@Composable
private fun RoutePlaceholder(route: SettingsRoute) {
    EmptyState(Modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
