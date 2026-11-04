package io.github.thewisekd.phopencode.feature.connect.ui.keys

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysState

/**
 * Stub from the contract (P4.3); P4.9 draws the screen. Draws [state], sends [onIntent]; the file
 * picker for an import lives here and sends the file as [KeysIntent.Import]. The root keeps the
 * `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun KeysScreen(
    route: ConnectRoute.Keys,
    state: KeysState,
    onIntent: (KeysIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
