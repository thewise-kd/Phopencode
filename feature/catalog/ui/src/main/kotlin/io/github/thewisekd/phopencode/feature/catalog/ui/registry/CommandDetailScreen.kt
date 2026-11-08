package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState

/**
 * Stub from the contract (P10.1); P10.5 draws the screen: the command's name and description. Draws [state], sends
 * [onIntent]. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CommandDetailScreen(
    route: CatalogRoute.CommandDetail,
    state: CommandDetailState,
    onIntent: (CommandDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
