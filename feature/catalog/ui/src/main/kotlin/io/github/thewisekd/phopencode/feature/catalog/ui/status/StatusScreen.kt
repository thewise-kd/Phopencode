package io.github.thewisekd.phopencode.feature.catalog.ui.status

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusState

/**
 * Stub from the contract (P10.1); P10.7 draws the screen: the MCP servers and plugins with
 * their status badges and error text. Draws [state], sends
 * [onIntent]. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun StatusScreen(
    route: CatalogRoute.Status,
    state: StatusState,
    onIntent: (StatusIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
