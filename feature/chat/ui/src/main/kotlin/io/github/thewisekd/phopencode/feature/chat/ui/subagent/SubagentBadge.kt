package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** The smallest touch target the platform asks for. */
internal val MIN_TOUCH = 48.dp

private val LIVE_DOT_SIZE = 8.dp

/**
 * The chat's badge of its subagents: how many run now, such as "2 running", with a still dot and a
 * "Live" state while any do, else how many there are, such as "3 subagents". A tap sends
 * [SubagentIntent.OpenList]. Draws nothing while the session has no subagents, loaded or not: only
 * an empty box for [modifier], so the chat's slot stays in place.
 *
 * The live mark is deliberately still: a pulsing one would keep the main thread busy forever.
 */
@Composable
fun SubagentBadge(
    state: SubagentState,
    onIntent: (SubagentIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.totalCount == 0) {
        // An empty box keeps the chat's slot in place, taking no room and offering nothing to tap.
        Box(modifier)
        return
    }
    SubagentCountChip(state, onClick = { onIntent(SubagentIntent.OpenList) }, modifier = modifier)
}

/** The count of [state]'s subagents as a chip that opens their list; shared by the badge and the header. */
@Composable
internal fun SubagentCountChip(state: SubagentState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = PhopencodeTheme.spacing
    val live = state.runningCount > 0
    val liveLabel = stringResource(R.string.chat_subagent_live)
    val openLabel = stringResource(R.string.chat_subagent_open_list)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .heightIn(min = MIN_TOUCH)
            .widthIn(min = MIN_TOUCH)
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (live) stateDescription = liveLabel }
            .padding(horizontal = spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (live) colors.primaryContainer else colors.surfaceContainerHigh,
            contentColor = if (live) colors.onPrimaryContainer else colors.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (live) {
                    Box(
                        Modifier
                            .size(LIVE_DOT_SIZE)
                            .background(colors.primary, CircleShape)
                            .testTag(SubagentTags.LIVE_DOT),
                    )
                }
                Text(
                    text = countText(state, subagentLocale()),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
