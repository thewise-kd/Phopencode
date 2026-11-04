package io.github.thewisekd.phopencode.feature.sessions.ui.list

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListState

/**
 * Stub from the contract (P5.1); P5.3 draws the screen. Draws [state], sends [onIntent]. The root
 * keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SessionListScreen(
    route: SessionsRoute.SessionList,
    state: SessionListState,
    onIntent: (SessionListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
