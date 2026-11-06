package io.github.thewisekd.phopencode.feature.git.ui.log

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState

/**
 * Stub from the contract (P9.3); P9.9 draws the screen: the commits, the branch filter and loading
 * more at the end of the list. Draws [state], sends
 * [onIntent]. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun LogScreen(
    route: GitRoute.Log,
    state: LogState,
    onIntent: (LogIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
