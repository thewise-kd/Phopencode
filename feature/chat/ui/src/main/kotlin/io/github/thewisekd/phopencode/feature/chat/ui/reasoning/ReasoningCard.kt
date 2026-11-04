package io.github.thewisekd.phopencode.feature.chat.ui.reasoning

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey

/**
 * Stub from the contract (P6.1); P6.8 draws the card. Draws [state], sends [onIntent] for the card
 * [key]. The chat draws it where the timeline has the [TimelineKey].
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ReasoningCard(
    key: TimelineKey,
    state: ReasoningCardState,
    onIntent: (ReasoningIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
