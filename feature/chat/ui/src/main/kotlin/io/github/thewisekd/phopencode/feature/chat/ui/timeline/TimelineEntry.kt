package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.markdown.MarkdownView
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineNotice
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TurnOutcome
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** A prompt's bubble leaves at least this much room on the start side, so it never passes for a reply. */
private val USER_INSET = 48.dp

/** A shell command's output shows this many lines at most; the rest is cut. */
private const val SHELL_OUTPUT_MAX_LINES = 20

/** One item of the timeline, by its kind. Reasoning and tool items are their slot's card, nothing more. */
@Composable
internal fun TimelineEntry(
    item: TimelineItem,
    reasoningCard: @Composable (TimelineItem.Reasoning) -> Unit,
    toolCard: @Composable (TimelineItem.Tool) -> Unit,
) {
    when (item) {
        is TimelineItem.User -> UserEntry(item)
        is TimelineItem.AssistantText -> AssistantEntry(item)
        is TimelineItem.Reasoning -> reasoningCard(item)
        is TimelineItem.Tool -> toolCard(item)
        is TimelineItem.AssistantError -> ErrorBanner(
            modifier = Modifier.fillMaxWidth(),
            message = stringResource(R.string.chat_timeline_answer_failed, item.error.description),
        )
        is TimelineItem.Shell -> ShellEntry(item)
        is TimelineItem.Compaction -> CompactionEntry(item)
        is TimelineItem.Notice -> NoticeEntry(item.notice)
        is TimelineItem.TurnEnded -> TurnEndedEntry(item)
        is TimelineItem.Unknown -> CenteredNote(stringResource(R.string.chat_timeline_unknown, item.rawType))
    }
}

/** A prompt: a filled bubble on the end side, under a "You" caption, drawn as typed. */
@Composable
private fun UserEntry(item: TimelineItem.User) {
    val spacing = PhopencodeTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = USER_INSET),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Caption(stringResource(R.string.chat_timeline_you))
        Surface(
            modifier = Modifier.testTag(TIMELINE_USER_BUBBLE_TAG),
            shape = RoundedCornerShape(topStart = spacing.lg, topEnd = spacing.lg, bottomStart = spacing.lg, bottomEnd = spacing.xs),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(text = item.text, style = MaterialTheme.typography.bodyLarge)
                Attached(R.string.chat_timeline_files, item.files)
                Attached(R.string.chat_timeline_agents, item.agents)
                Attached(R.string.chat_timeline_skills, item.skills)
            }
        }
        item.pending?.let { delivery -> Caption(stringResource(delivery.pendingRes())) }
    }
}

/** What goes with a prompt, one line per kind; nothing when empty. */
@Composable
private fun Attached(@StringRes label: Int, names: List<String>) {
    if (names.isEmpty()) return
    Text(text = stringResource(label, names.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
}

/** A reply: no bubble, the full width from the start side, its Markdown rendered. */
@Composable
private fun AssistantEntry(item: TimelineItem.AssistantText) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.xxs),
    ) {
        Caption(stringResource(R.string.chat_timeline_assistant))
        if (item.text.isBlank() && item.streaming) {
            StillProgress(stringResource(R.string.chat_timeline_writing), center = false)
        } else {
            Box(Modifier.fillMaxWidth().testTag(TIMELINE_ASSISTANT_BODY_TAG)) {
                MarkdownView(item.text, Modifier.fillMaxWidth())
            }
        }
    }
}

/** A shell command the user ran: its command and output in monospace, or a still message while it runs. */
@Composable
private fun ShellEntry(item: TimelineItem.Shell) {
    val spacing = PhopencodeTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Caption(stringResource(R.string.chat_timeline_shell_label))
            Text(text = item.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            val output = item.output
            if (output == null) {
                StillProgress(stringResource(R.string.chat_timeline_shell_running), center = false)
            } else if (output.isNotEmpty()) {
                Text(
                    text = output,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = SHELL_OUTPUT_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A compaction: how it went, then its summary once there is one. */
@Composable
private fun CompactionEntry(item: TimelineItem.Compaction) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.xs),
    ) {
        when (item.status) {
            COMPACTION_RUNNING -> StillProgress(stringResource(R.string.chat_timeline_compaction_running))
            COMPACTION_FAILED -> CenteredNote(
                stringResource(R.string.chat_timeline_compaction_failed),
                MaterialTheme.colorScheme.error,
            )
            else -> CenteredNote(stringResource(R.string.chat_timeline_compaction_done))
        }
        val summary = item.summary
        if (!summary.isNullOrBlank() && item.status != COMPACTION_RUNNING) {
            MarkdownView(summary, Modifier.fillMaxWidth())
        }
    }
}

private const val COMPACTION_RUNNING = "running"
private const val COMPACTION_FAILED = "failed"

/** A one-line change in the session, small and centered. */
@Composable
private fun NoticeEntry(notice: TimelineNotice) {
    val text = when (notice) {
        is TimelineNotice.AgentSwitched -> notice.previous?.let { previous ->
            stringResource(R.string.chat_timeline_agent_switched_from, previous, notice.agent)
        } ?: stringResource(R.string.chat_timeline_agent_switched, notice.agent)
        is TimelineNotice.ModelSwitched -> notice.previous?.let { previous ->
            stringResource(R.string.chat_timeline_model_switched_from, previous.label, notice.model.label)
        } ?: stringResource(R.string.chat_timeline_model_switched, notice.model.label)
        is TimelineNotice.LocationSwitched -> stringResource(R.string.chat_timeline_location_switched, notice.directory)
        is TimelineNotice.SkillInvoked -> stringResource(R.string.chat_timeline_skill_invoked, notice.name)
        is TimelineNotice.Synthetic -> notice.text
        is TimelineNotice.System -> notice.text
    }
    CenteredNote(text)
}

/** Where an execution ended, with the error's description when it failed with one. */
@Composable
private fun TurnEndedEntry(item: TimelineItem.TurnEnded) {
    val error = item.error
    val text = when (item.outcome) {
        TurnOutcome.Succeeded -> stringResource(R.string.chat_timeline_turn_succeeded)
        TurnOutcome.Failed -> if (error != null) {
            stringResource(R.string.chat_timeline_turn_failed_detail, error.description)
        } else {
            stringResource(R.string.chat_timeline_turn_failed)
        }
        TurnOutcome.Interrupted -> stringResource(R.string.chat_timeline_turn_interrupted)
        TurnOutcome.Unknown -> stringResource(R.string.chat_timeline_turn_unknown)
    }
    val color = if (item.outcome == TurnOutcome.Failed) MaterialTheme.colorScheme.error else Color.Unspecified
    CenteredNote(text, color)
}

/** Who wrote an item, or what a block is. */
@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

/** A small centered line, for what is not a turn of the conversation. */
@Composable
private fun CenteredNote(text: String, color: Color = Color.Unspecified) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = PhopencodeTheme.spacing.xs),
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Something runs. A still message rather than core/ui's `Loading`: its spinner never stops, and the
 * app's ActivityScenario tests would never come to rest. TalkBack hears the same as for `Loading`:
 * the message, an indeterminate progress and a polite live region.
 */
@Composable
internal fun StillProgress(message: String, modifier: Modifier = Modifier, center: Boolean = true) {
    Box(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(PhopencodeTheme.spacing.sm),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** What the user reads of an error: the server's message, or its type when it gave none. */
private val StructuredError.description: String
    get() = message?.takeIf { it.isNotBlank() } ?: type

/** A model as `provider/model`, the way opencode names it. */
private val ModelRef.label: String
    get() = listOf(providerId, modelId).joinToString("/")

@StringRes
private fun PromptDelivery.pendingRes(): Int = when (this) {
    PromptDelivery.Steer -> R.string.chat_timeline_pending_steer
    PromptDelivery.Queue -> R.string.chat_timeline_pending_queue
}
