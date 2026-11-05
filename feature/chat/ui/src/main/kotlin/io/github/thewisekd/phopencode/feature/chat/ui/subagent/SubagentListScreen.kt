package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState

/**
 * Stub from the contract (P7.1); P7.7 draws the subagent list page: the tree with each subagent's
 * status, duration, tokens and tool progress, live; a tap sends `SubagentIntent.Open`. Draws
 * [state], sends [onIntent]. The root keeps the `placeholder:<route>` tag the app's navigation test
 * looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SubagentListScreen(
    route: ChatRoute.Subagents,
    state: SubagentState,
    onIntent: (SubagentIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
