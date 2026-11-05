package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState

/**
 * Stub from the contract (P7.1); P7.7 draws the chat's live badge of running subagents, which opens
 * the list with `SubagentIntent.OpenList`. Draws [state], sends [onIntent]; draws nothing without
 * subagents.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SubagentBadge(
    state: SubagentState,
    onIntent: (SubagentIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
