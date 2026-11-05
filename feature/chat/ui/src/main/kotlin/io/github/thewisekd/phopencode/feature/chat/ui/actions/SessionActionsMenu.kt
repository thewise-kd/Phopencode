package io.github.thewisekd.phopencode.feature.chat.ui.actions

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState

/**
 * Stub from the contract (P7.1); P7.15 draws the session's menu button with compact and fork, each
 * action's confirmation, and its status. Draws [state], sends [onIntent].
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SessionActionsMenu(
    state: ActionsState,
    onIntent: (ActionsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
