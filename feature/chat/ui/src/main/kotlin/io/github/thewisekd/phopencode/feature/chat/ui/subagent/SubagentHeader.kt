package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.ui.R

private const val TITLE_LINES = 2

/**
 * The top of a subagent's timeline: the way back to its parent, which sends
 * [SubagentIntent.OpenParent] and shows once the parent is known; the session's title as a heading,
 * "Loading…" before it is known, and its agent; and, when it started subagents of its own, their
 * count, which opens them with [SubagentIntent.OpenList]. A failed load shows its reason with a
 * retry that sends [SubagentIntent.Retry], below what was loaded.
 *
 * The state carries no status of this session itself, so none is drawn; the timeline's status
 * banner below says whether it runs.
 */
@Composable
fun SubagentHeader(
    state: SubagentState,
    onIntent: (SubagentIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.parentId != null) {
                IconButton(
                    onClick = { onIntent(SubagentIntent.OpenParent) },
                    modifier = Modifier.testTag(SubagentTags.HEADER_BACK),
                ) {
                    Icon(BackArrow, contentDescription = stringResource(R.string.chat_subagent_back))
                }
            } else {
                Spacer(Modifier.width(spacing.lg))
            }
            Column(Modifier.weight(1f).padding(vertical = spacing.xs)) {
                val own = state.title
                val title = when {
                    !own.isNullOrBlank() -> own
                    state.loading -> stringResource(R.string.chat_subagent_header_loading)
                    else -> nodeName(null, null)
                }
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = TITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                val agent = state.agent
                if (!agent.isNullOrBlank()) {
                    Text(
                        text = agent,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.totalCount > 0) {
                SubagentCountChip(
                    state = state,
                    onClick = { onIntent(SubagentIntent.OpenList) },
                    modifier = Modifier.testTag(SubagentTags.HEADER_LIST),
                )
            }
        }
        val error = state.error
        if (error != null) {
            ErrorBanner(
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.sm),
                message = errorText(error),
                onRetry = { onIntent(SubagentIntent.Retry) },
            )
        }
        HorizontalDivider()
    }
}

/** A back arrow that turns with right-to-left text, drawn here to avoid the large icon library. */
private val BackArrow: ImageVector = ImageVector.Builder(
    name = "Subagent.Back",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = true,
).addPath(
    pathData = addPathNodes("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"),
    fill = SolidColor(Color.Black),
).build()
