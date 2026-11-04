package io.github.thewisekd.phopencode.feature.chat.ui.status

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState

/**
 * Stub from the contract (P6.1); P6.14 draws the status. Draws [state], sends [onIntent].
 *
 * Two sizes: a banner above the timeline, or, when [StatusState.blocksChat], the whole chat below
 * the top bar, as loading, not connected, not found, another server, or unavailable; the chat gives
 * it the space through [modifier] then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun StatusBanner(
    state: StatusState,
    onIntent: (StatusIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
