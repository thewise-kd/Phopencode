package io.github.thewisekd.phopencode.feature.chat.ui.reasoning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.markdown.MarkdownView
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.ui.R
import kotlin.time.Duration

/** The tag on the header that toggles the card. */
internal const val REASONING_HEADER_TAG = "reasoning:header"

/** The tag on the Markdown body, drawn only while the card is expanded. */
internal const val REASONING_BODY_TAG = "reasoning:body"

private val MIN_TOUCH = 48.dp

/**
 * The model's thinking for the card [key] (P6.8), drawn from [state]; the chat draws it where the
 * timeline has the [TimelineKey].
 *
 * - **Header:** "Thinking", the running duration and a still "Live" label while the reasoning
 *   streams; "Thought for N s" once it ended, or "Thought" with no duration. Tapping it sends
 *   [ReasoningIntent.Toggle]; whether the card is open comes only from [state].
 * - **Body:** when expanded, the text renders through [MarkdownView], which keeps the last result on
 *   screen while a longer, still unfinished text parses.
 * - A card with blank text draws nothing.
 *
 * Nothing here animates without end, and the text, which is user data, is never logged.
 */
@Composable
fun ReasoningCard(
    key: TimelineKey,
    state: ReasoningCardState,
    onIntent: (ReasoningIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.text.isBlank()) return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column {
            ReasoningHeader(state, onToggle = { onIntent(ReasoningIntent.Toggle(key)) })
            if (state.expanded) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .testTag(REASONING_BODY_TAG),
                ) {
                    MarkdownView(markdown = state.text, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ReasoningHeader(state: ReasoningCardState, onToggle: () -> Unit) {
    val stateLabel = stringResource(
        if (state.expanded) R.string.chat_reasoning_expanded else R.string.chat_reasoning_collapsed,
    )
    val clickLabel = stringResource(if (state.expanded) R.string.chat_reasoning_hide else R.string.chat_reasoning_show)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = stateLabel }
            .testTag(REASONING_HEADER_TAG)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val duration = state.duration?.let { formatDuration(it) }
        if (state.streaming) {
            Text(stringResource(R.string.chat_reasoning_thinking), style = MaterialTheme.typography.titleSmall)
            if (duration != null) {
                Text(duration, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val title = if (duration != null) {
                stringResource(R.string.chat_reasoning_thought_for, duration)
            } else {
                stringResource(R.string.chat_reasoning_thought)
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Box(Modifier.weight(1f))
        if (state.streaming) {
            LiveLabel()
        }
        Icon(
            imageVector = if (state.expanded) ReasoningIcons.Less else ReasoningIcons.More,
            contentDescription = null,
        )
    }
}

/** A still label that says the thinking still streams; deliberately not an animation. */
@Composable
private fun LiveLabel() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = stringResource(R.string.chat_reasoning_live),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Whole seconds, rounded down: "12 s", "3 min", "2 min 5 s", in the current language. */
@Composable
private fun formatDuration(duration: Duration): String {
    val total = duration.inWholeSeconds.coerceAtLeast(0)
    val minutes = (total / SECONDS_PER_MINUTE).toInt()
    val seconds = (total % SECONDS_PER_MINUTE).toInt()
    return when {
        minutes == 0 -> stringResource(R.string.chat_reasoning_seconds, seconds)
        seconds == 0 -> stringResource(R.string.chat_reasoning_minutes, minutes)
        else -> stringResource(R.string.chat_reasoning_minutes_seconds, minutes, seconds)
    }
}

private const val SECONDS_PER_MINUTE = 60

/** Expand and collapse chevrons, drawn here to avoid the large icon library. */
private object ReasoningIcons {
    val More: ImageVector = icon("More", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")
    val Less: ImageVector = icon("Less", "M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z")

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = "Reasoning.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}
