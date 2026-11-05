package io.github.thewisekd.phopencode.feature.chat.ui.switcher

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState

/**
 * Stub from the contract (P7.1); P7.11 draws the current agent, model and variant above the
 * composer, and the bottom sheet to pick others while `SwitcherState.open`. Draws [state], sends
 * [onIntent].
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AgentModelSwitcher(
    state: SwitcherState,
    onIntent: (SwitcherIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
