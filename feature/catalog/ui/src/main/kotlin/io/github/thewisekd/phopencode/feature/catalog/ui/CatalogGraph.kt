package io.github.thewisekd.phopencode.feature.catalog.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState

/**
 * The catalog screens, one destination per [CatalogRoute]. The app's NavHost calls this; a new screen of
 * the feature is added here only. Until P10.1 wires the screens, every route opens a placeholder.
 *
 * @param navController Moves between screens, once there are screens to move between.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.catalogGraph(navController: NavController) {
    composable<CatalogRoute.Skills> { RoutePlaceholder(it.toRoute<CatalogRoute.Skills>()) }
    composable<CatalogRoute.SkillDetail> { RoutePlaceholder(it.toRoute<CatalogRoute.SkillDetail>()) }
    composable<CatalogRoute.Registry> { RoutePlaceholder(it.toRoute<CatalogRoute.Registry>()) }
    composable<CatalogRoute.CommandDetail> { RoutePlaceholder(it.toRoute<CatalogRoute.CommandDetail>()) }
    composable<CatalogRoute.AgentDetail> { RoutePlaceholder(it.toRoute<CatalogRoute.AgentDetail>()) }
    composable<CatalogRoute.ModelDetail> { RoutePlaceholder(it.toRoute<CatalogRoute.ModelDetail>()) }
    composable<CatalogRoute.Status> { RoutePlaceholder(it.toRoute<CatalogRoute.Status>()) }
}

/** Tagged `placeholder:<route>`, the tag the app's navigation test looks for. */
@Composable
private fun RoutePlaceholder(route: CatalogRoute) {
    EmptyState(Modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
