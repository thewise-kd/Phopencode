package io.github.thewisekd.phopencode.feature.chat.ui.revert

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState

/**
 * Stub from the contract (P7.1); P7.13 draws the turn diff page: the turn's changed files, and its
 * revert with confirmation and cancel. Draws [state], sends [onIntent]. The root keeps the
 * `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun TurnDiffScreen(
    route: ChatRoute.TurnDiff,
    state: RevertState,
    onIntent: (RevertIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
