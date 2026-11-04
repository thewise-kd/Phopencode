package io.github.thewisekd.phopencode.feature.sessions.ui.manage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageState

/**
 * Stub from the contract (P5.1); P5.7 draws the screen. Draws [state], sends [onIntent]. The root
 * keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SessionManageScreen(
    route: SessionsRoute.Manage,
    state: SessionManageState,
    onIntent: (SessionManageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
