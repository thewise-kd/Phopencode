package io.github.thewisekd.phopencode.feature.preview.ui.ports

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState

/**
 * Stub from the contract (P13.5); P13.9 draws the screen: the server's listening ports with the
 * opencode one disabled, a manual port field with a numeric keyboard and inline validation,
 * refresh, and the empty, loading, not connected and error states. Draws [state], sends [onIntent].
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PortsScreen(
    route: PreviewRoute.Ports,
    state: PortsState,
    onIntent: (PortsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
