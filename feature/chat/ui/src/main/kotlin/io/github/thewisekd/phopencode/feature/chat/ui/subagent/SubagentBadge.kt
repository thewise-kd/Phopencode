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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** The smallest touch target the platform asks for. */
internal val MIN_TOUCH = 48.dp

private val LIVE_DOT_SIZE = 8.dp

/** The compact badge's agent icon, its live dot and its side padding. */
private val COMPACT_ICON_SIZE = 16.dp
private val COMPACT_DOT_SIZE = 6.dp
private val COMPACT_PADDING = 6.dp

/**
 * The chat's badge of its subagents: how many run now, such as "2 running", with a still dot and a
 * "Live" state while any do, else how many there are, such as "3 subagents". A tap sends
 * [SubagentIntent.OpenList]. Draws nothing while the session has no subagents, loaded or not: only
 * an empty box for [modifier], so the chat's slot stays in place.
 *
 * [compact], as in the chat's top bar, it is a small pill with an agent icon and only the count,
 * such as "● 1"; a screen reader still hears the full text, such as "1 running".
 *
 * The live mark is deliberately still: a pulsing one would keep the main thread busy forever.
 */
@Composable
fun SubagentBadge(
    state: SubagentState,
    onIntent: (SubagentIntent) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (state.totalCount == 0) {
        // An empty box keeps the chat's slot in place, taking no room and offering nothing to tap.
        Box(modifier)
        return
    }
    if (compact) {
        CompactCountChip(state, onClick = { onIntent(SubagentIntent.OpenList) }, modifier = modifier)
    } else {
        SubagentCountChip(state, onClick = { onIntent(SubagentIntent.OpenList) }, modifier = modifier)
    }
}

/**
 * The count as a small pill: an agent icon, with a still dot on it while any run, and the number. Its layout is
 * only as wide as the pill, so the top bar's title keeps its room; Compose still gives the tap a
 * full 48 dp target, and a screen reader hears the full count text.
 */
@Composable
private fun CompactCountChip(state: SubagentState, onClick: () -> Unit, modifier: Modifier) {
    val spacing = PhopencodeTheme.spacing
    val live = state.runningCount > 0
    val locale = subagentLocale()
    val full = countText(state, locale)
    val liveLabel = stringResource(R.string.chat_subagent_live)
    val openLabel = stringResource(R.string.chat_subagent_open_list)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .heightIn(min = MIN_TOUCH)
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = full
                if (live) stateDescription = liveLabel
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = if (live) colors.primaryContainer else colors.surfaceContainerHigh,
            contentColor = if (live) colors.onPrimaryContainer else colors.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = COMPACT_PADDING, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                // While any run, a still dot sits on the icon's corner, taking no width of its own.
                Box {
                    Icon(PhopencodeIcons.Agent, contentDescription = null, modifier = Modifier.size(COMPACT_ICON_SIZE))
                    if (live) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(COMPACT_DOT_SIZE)
                                .background(colors.primary, CircleShape),
                        )
                    }
                }
                Text(
                    text = formatCount((if (live) state.runningCount else state.totalCount).toLong(), locale),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
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
