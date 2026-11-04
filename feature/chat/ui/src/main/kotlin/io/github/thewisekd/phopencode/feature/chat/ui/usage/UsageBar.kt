package io.github.thewisekd.phopencode.feature.chat.ui.usage

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState

/**
 * Stub from the contract (P6.1); P6.12 draws the bar at the top of the chat. Draws [state], sends
 * [onIntent]; a tap sends [UsageIntent.OpenDetail].
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun UsageBar(
    state: UsageState,
    onIntent: (UsageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
