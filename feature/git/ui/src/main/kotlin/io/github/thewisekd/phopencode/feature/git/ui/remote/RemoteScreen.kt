package io.github.thewisekd.phopencode.feature.git.ui.remote

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState

/**
 * Stub from the contract (P9.3); P9.15 draws the screen: fetch, pull and push, the push confirmation
 * and the explanation of a refused push or pull. Draws [state], sends
 * [onIntent]. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun RemoteScreen(
    route: GitRoute.Remote,
    state: RemoteState,
    onIntent: (RemoteIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
