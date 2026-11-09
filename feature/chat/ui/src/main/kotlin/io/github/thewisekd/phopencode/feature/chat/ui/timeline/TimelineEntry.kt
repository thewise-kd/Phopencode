package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.markdown.MarkdownView
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
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
        is TimelineItem.Unknown -> CenteredNote(
            stringResource(R.string.chat_timeline_unknown, item.rawType),
            PhopencodeIcons.Info,
        )
    }
}

/**
 * A prompt: a filled bubble on the end side, drawn as typed. No caption shows who wrote it, the
 * bubble's side does; screen readers hear "You" first, through [spokenAs].
 */
@Composable
private fun UserEntry(item: TimelineItem.User) {
    val spacing = PhopencodeTheme.spacing
    val corner = MaterialTheme.shapes.large.topStart
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = USER_INSET),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Surface(
            modifier = Modifier
                .spokenAs(stringResource(R.string.chat_timeline_you))
                .testTag(TIMELINE_USER_BUBBLE_TAG),
            // The corner on the writer's side stays tight, like a speech bubble's tail.
            shape = RoundedCornerShape(
                topStart = corner,
                topEnd = corner,
                bottomStart = corner,
                bottomEnd = CornerSize(spacing.xs),
            ),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
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

/**
 * Makes an item one node for screen readers that says who wrote it, [author], before its own texts.
 * Nothing is drawn. The author is part of the item's own semantics, so no node of its own can be
 * covered by the item's content and dropped. A text rather than a content description: a node's
 * content description would be read instead of the texts it holds, not before them.
 */
private fun Modifier.spokenAs(author: String): Modifier = semantics(mergeDescendants = true) {
    text = AnnotatedString(author)
}

/** What goes with a prompt, one line per kind; nothing when empty. */
@Composable
private fun Attached(@StringRes label: Int, names: List<String>) {
    if (names.isEmpty()) return
    Text(text = stringResource(label, names.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
}

/**
 * A reply: no bubble, the full width from the start side, its Markdown rendered. Screen readers hear
 * "Assistant" first, through [spokenAs]; nothing on screen says it.
 */
@Composable
private fun AssistantEntry(item: TimelineItem.AssistantText) {
    Box(Modifier.fillMaxWidth().spokenAs(stringResource(R.string.chat_timeline_assistant))) {
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
                PhopencodeIcons.Warning,
                MaterialTheme.colorScheme.error,
            )
            else -> CenteredNote(stringResource(R.string.chat_timeline_compaction_done), PhopencodeIcons.History)
        }
        val summary = item.summary
        if (!summary.isNullOrBlank() && item.status != COMPACTION_RUNNING) {
            MarkdownView(summary, Modifier.fillMaxWidth())
        }
    }
}

private const val COMPACTION_RUNNING = "running"
private const val COMPACTION_FAILED = "failed"

/** A one-line change in the session, small, muted and centered, with an icon for its kind. */
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
    val icon = when (notice) {
        is TimelineNotice.AgentSwitched -> PhopencodeIcons.Agent
        is TimelineNotice.ModelSwitched -> PhopencodeIcons.Refresh
        is TimelineNotice.LocationSwitched -> PhopencodeIcons.Folder
        is TimelineNotice.SkillInvoked -> PhopencodeIcons.Sparkle
        is TimelineNotice.Synthetic, is TimelineNotice.System -> PhopencodeIcons.Info
    }
    CenteredNote(text, icon)
}

/**
 * Where an execution ended. A success draws nothing: the reply above says it. Any other end is a
 * compact tinted chip: the error's tone and description for a failure, a neutral tone otherwise.
 */
@Composable
private fun TurnEndedEntry(item: TimelineItem.TurnEnded) {
    val error = item.error
    val text = when (item.outcome) {
        TurnOutcome.Succeeded -> return
        TurnOutcome.Failed -> if (error != null) {
            stringResource(R.string.chat_timeline_turn_failed_detail, error.description)
        } else {
            stringResource(R.string.chat_timeline_turn_failed)
        }
        TurnOutcome.Interrupted -> stringResource(R.string.chat_timeline_turn_interrupted)
        TurnOutcome.Unknown -> stringResource(R.string.chat_timeline_turn_unknown)
    }
    val failed = item.outcome == TurnOutcome.Failed
    val colors = MaterialTheme.colorScheme
    val spacing = PhopencodeTheme.spacing
    Box(Modifier.fillMaxWidth().padding(vertical = spacing.xs), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.testTag(TIMELINE_TURN_CHIP_TAG),
            shape = MaterialTheme.shapes.small,
            color = if (failed) colors.errorContainer else colors.surfaceContainerHigh,
            contentColor = if (failed) colors.onErrorContainer else colors.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(start = spacing.sm, end = spacing.md, top = spacing.xs, bottom = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Icon(
                    imageVector = when (item.outcome) {
                        TurnOutcome.Failed -> PhopencodeIcons.Warning
                        TurnOutcome.Interrupted -> PhopencodeIcons.Stop
                        else -> PhopencodeIcons.Info
                    },
                    contentDescription = null,
                    modifier = Modifier.size(NOTE_ICON),
                    tint = if (failed) colors.error else colors.onSurfaceVariant,
                )
                Text(text = text, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
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

/**
 * A small, muted centered line, for what is not a turn of the conversation, after an [icon] that
 * only decorates: the text says the same.
 */
@Composable
private fun CenteredNote(text: String, icon: ImageVector? = null, color: Color = Color.Unspecified) {
    val spacing = PhopencodeTheme.spacing
    val tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(NOTE_ICON), tint = tint)
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f, fill = false),
            color = tint,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The size of a note's or a chip's icon, next to small text. */
private val NOTE_ICON = 16.dp

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
