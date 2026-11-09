package io.github.thewisekd.phopencode.feature.notify.ui.pending

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState

/**
 * Stub from the contract (P11.7); P11.12 draws the screen: the forms and permission requests of
 * every session in one list, each opening its session when tapped, with the empty, error and
 * not-connected states. `phopencode://pending` opens it (the graph declares the deep link; P11.12
 * adds the manifest's intent filter). Draws [state], sends [onIntent]. The root keeps the
 * `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PendingScreen(
    route: NotifyRoute.Pending,
    state: PendingState,
    onIntent: (PendingIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
