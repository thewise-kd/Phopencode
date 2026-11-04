package io.github.thewisekd.phopencode.feature.chat.ui.tool

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolIntent

/**
 * Stub from the contract (P6.1); P6.10 draws the card. Draws [state], sends [onIntent] for the card
 * [key]. The chat draws it where the timeline has the [TimelineKey].
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ToolCard(
    key: TimelineKey,
    state: ToolCardState,
    onIntent: (ToolIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
