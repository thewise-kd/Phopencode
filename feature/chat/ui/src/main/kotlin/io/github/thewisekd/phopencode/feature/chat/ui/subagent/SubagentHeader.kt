package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState

/**
 * Stub from the contract (P7.1); P7.7 draws the top of a subagent's timeline: its title, agent and
 * status, the way back to the parent (`SubagentIntent.OpenParent`), and its own subagents
 * (`SubagentIntent.OpenList`). Draws [state], sends [onIntent].
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SubagentHeader(
    state: SubagentState,
    onIntent: (SubagentIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
