package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState

/**
 * Stub from the contract (P6.1); P6.4 draws the timeline. Draws [state], sends [onIntent].
 *
 * Items keep their [TimelineItem.key] as the list key. A [TimelineItem.Reasoning] and a
 * [TimelineItem.Tool] are drawn by [reasoningCard] and [toolCard], which the chat fills from their
 * own presenters; the timeline only places them.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Timeline(
    state: TimelineState,
    onIntent: (TimelineIntent) -> Unit,
    reasoningCard: @Composable (TimelineItem.Reasoning) -> Unit,
    toolCard: @Composable (TimelineItem.Tool) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        items(state.items, key = { it.key.value }) { item ->
            when (item) {
                is TimelineItem.Reasoning -> reasoningCard(item)
                is TimelineItem.Tool -> toolCard(item)
                else -> Unit
            }
        }
    }
}
