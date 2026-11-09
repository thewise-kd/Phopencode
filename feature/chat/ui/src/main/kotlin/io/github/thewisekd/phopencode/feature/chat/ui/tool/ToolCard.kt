package io.github.thewisekd.phopencode.feature.chat.ui.tool

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSection
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSummary
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** The tag on the row with the status icon and the summary line. */
internal const val TOOL_HEADER_TAG = "tool:header"

/** The tag on the summary line's text. */
internal const val TOOL_SUMMARY_TAG = "tool:summary"

/** The tag on an edit's "+added −removed" counts, drawn only when the tool reported any. */
internal const val TOOL_CHANGES_TAG = "tool:changes"

/** The tag on the status icon. */
internal const val TOOL_STATUS_TAG = "tool:status"

/** The tag on the error text of a failed call. */
internal const val TOOL_ERROR_TAG = "tool:error"

/** The tag on the header that toggles [section]. */
internal fun toolSectionTag(section: ToolSection): String = "tool:section:${section.name}"

/** The tag on the drawn text of [section], present only while it is expanded. */
internal fun toolTextTag(section: ToolSection): String = "tool:text:${section.name}"

/** The tag on the "N more lines" note of [section], present only when its text was shortened. */
internal fun toolMoreTag(section: ToolSection): String = "tool:more:${section.name}"

private val MIN_TOUCH = 48.dp

/** Lines of the server's error text the card shows before it ends in an ellipsis. */
private const val ERROR_MAX_LINES = 6

/**
 * One tool call for the card [key] (P6.10), drawn from [state]; the chat draws it where the
 * timeline has the [TimelineKey].
 *
 * - **Row:** one slim line: an icon for the kind of call, the summary per [ToolSummary] kind (the
 *   kind's name, then its target, such as the file or the command, in a quieter monospace), an
 *   edit's "+added −removed" counts when known, a still status icon named for screen readers, and a
 *   chevron when there are details. Tapping the row shows or hides the details; that is the row's
 *   own state, kept here and not sent. The details open by themselves when the call failed with an
 *   error, or when [state] has a section open.
 * - **Error:** the error's message, or its type when it has none.
 * - **Sections:** input and output, each drawn only when there is text. Tapping a section's header
 *   sends [ToolIntent.Toggle]; whether it is open comes only from [state]. An open section shows at
 *   most [TOOL_MAX_LINES] lines and [TOOL_MAX_CHARS] characters, then says how much is left.
 *
 * The card never scrolls vertically: it sits in the timeline's lazy list, whose follow logic breaks
 * if a nested card takes vertical scroll. Long lines scroll sideways. Nothing animates without end,
 * and the input and output, which can hold secrets, are never logged.
 */
@Composable
fun ToolCard(
    key: TimelineKey,
    state: ToolCardState,
    onIntent: (ToolIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasDetails = state.error != null || !state.input.isNullOrEmpty() || !state.output.isNullOrEmpty()
    val failed = state.error != null
    val sectionOpen = state.expanded.isNotEmpty()
    var open by rememberSaveable(key.value) { mutableStateOf(failed || sectionOpen) }
    // An error that arrives, or a first section the presenter opens, shows: the row opens with it.
    // What the row last saw is saved with it, so only a real change opens it: not the row coming
    // back on screen, which would undo the user's own close. Each is tracked on its own, so one
    // already true does not hide the other's arrival.
    var seenFailed by rememberSaveable(key.value) { mutableStateOf(failed) }
    var seenSectionOpen by rememberSaveable(key.value) { mutableStateOf(sectionOpen) }
    LaunchedEffect(failed) {
        if (failed && !seenFailed) open = true
        seenFailed = failed
    }
    LaunchedEffect(sectionOpen) {
        if (sectionOpen && !seenSectionOpen) open = true
        seenSectionOpen = sectionOpen
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = BORDER_ALPHA)),
    ) {
        Column {
            ToolHeader(state, open = open && hasDetails, onToggle = if (hasDetails) ({ open = !open }) else null)
            if (open && hasDetails) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    state.error?.let { ToolError(it) }
                    for (section in ToolSection.entries) {
                        val text = when (section) {
                            ToolSection.Input -> state.input
                            ToolSection.Output -> state.output
                        }
                        if (!text.isNullOrEmpty()) {
                            ToolSectionView(
                                section = section,
                                text = text,
                                expanded = section in state.expanded,
                                onToggle = { onIntent(ToolIntent.Toggle(key, section)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How strongly the row's hairline border shows: enough to hold it together, not to frame it. */
private const val BORDER_ALPHA = 0.6f

/** The row: kind, summary, counts, status and, when there are details, whether they show. */
@Composable
private fun ToolHeader(state: ToolCardState, open: Boolean, onToggle: (() -> Unit)?) {
    val toggle = if (onToggle != null) {
        val stateLabel = stringResource(if (open) R.string.chat_tool_expanded else R.string.chat_tool_collapsed)
        val clickLabel = stringResource(if (open) R.string.chat_tool_hide_details else R.string.chat_tool_show_details)
        Modifier
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = stateLabel }
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH)
            .then(toggle)
            .testTag(TOOL_HEADER_TAG)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = kindIcon(state.summary),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = summaryText(state.summary),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(TOOL_SUMMARY_TAG),
        )
        (state.summary as? ToolSummary.Edit)?.let { edit -> EditChanges(edit) }
        Icon(
            imageVector = toolStatusIcon(state.status),
            contentDescription = stringResource(state.status.labelRes()),
            tint = statusTint(state.status),
            modifier = Modifier.size(20.dp).testTag(TOOL_STATUS_TAG),
        )
        if (onToggle != null) {
            Icon(
                imageVector = if (open) ToolIcons.Less else ToolIcons.More,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** What kind of call it is, at a glance; the summary says it in words. */
private fun kindIcon(summary: ToolSummary): ImageVector = when (summary) {
    is ToolSummary.Shell -> PhopencodeIcons.Terminal
    is ToolSummary.Edit -> PhopencodeIcons.Edit
    is ToolSummary.Read -> PhopencodeIcons.File
    is ToolSummary.Search -> PhopencodeIcons.Search
    is ToolSummary.Generic -> PhopencodeIcons.Settings
}

/**
 * The summary line: the kind's name as the strings put it, with its targets (the command, path or
 * pattern) set in a quieter monospace. The text is the same as unstyled; only its look changes.
 */
@Composable
private fun summaryText(summary: ToolSummary): AnnotatedString {
    val (text, targets) = when (summary) {
        is ToolSummary.Shell -> oneLine(summary.command).let { stringResource(R.string.chat_tool_summary_shell, it) to listOf(it) }
        is ToolSummary.Edit -> stringResource(R.string.chat_tool_summary_edit, summary.path) to listOf(summary.path)
        is ToolSummary.Read -> stringResource(R.string.chat_tool_summary_read, summary.path) to listOf(summary.path)
        is ToolSummary.Search -> {
            val pattern = oneLine(summary.pattern)
            val path = summary.path
            if (path.isNullOrBlank()) {
                stringResource(R.string.chat_tool_summary_search, pattern) to listOf(pattern)
            } else {
                stringResource(R.string.chat_tool_summary_search_in, pattern, path) to listOf(pattern, path)
            }
        }
        is ToolSummary.Generic -> stringResource(R.string.chat_tool_summary_generic, summary.name) to listOf(summary.name)
    }
    val target = SpanStyle(
        fontFamily = PhopencodeTheme.codeTypography.small.fontFamily,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val name = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    return remember(text, targets, target, name) {
        buildAnnotatedString {
            append(text)
            addStyle(name, 0, text.length)
            var from = 0
            for (part in targets) {
                if (part.isEmpty()) continue
                val at = text.indexOf(part, from)
                if (at < 0) continue
                addStyle(target, at, at + part.length)
                from = at + part.length
            }
        }
    }
}

/** A multi-line command or pattern on one line, its line breaks and runs of spaces collapsed. */
private fun oneLine(text: String): String = text.trim().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("\\s+")

@Composable
private fun EditChanges(edit: ToolSummary.Edit) {
    val parts = listOfNotNull(
        edit.additions?.let { stringResource(R.string.chat_tool_additions, it) },
        edit.deletions?.let { stringResource(R.string.chat_tool_deletions, it) },
    )
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" "),
        style = PhopencodeTheme.codeTypography.small,
        maxLines = 1,
        modifier = Modifier.testTag(TOOL_CHANGES_TAG),
    )
}

@Composable
private fun ToolError(error: StructuredError) {
    val description = error.message?.takeIf { it.isNotBlank() } ?: error.type
    Text(
        text = stringResource(R.string.chat_tool_error, description),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        maxLines = ERROR_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(TOOL_ERROR_TAG),
    )
}

@Composable
private fun ToolSectionView(section: ToolSection, text: String, expanded: Boolean, onToggle: () -> Unit) {
    val stateLabel = stringResource(if (expanded) R.string.chat_tool_expanded else R.string.chat_tool_collapsed)
    val clickLabel = stringResource(section.toggleLabelRes(expanded))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = stateLabel }
            .testTag(toolSectionTag(section))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(section.titleRes()),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(imageVector = if (expanded) ToolIcons.Less else ToolIcons.More, contentDescription = null)
    }
    if (expanded) {
        ToolSectionBody(section, text)
    }
}

/**
 * The shortened text of an open section. Lines do not wrap and scroll sideways together; the
 * height is bounded by the shortening, so nothing here scrolls vertically.
 */
@Composable
private fun ToolSectionBody(section: ToolSection, text: String) {
    val shortened = remember(text) { shortenToolText(text) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                Text(
                    text = shortened.text,
                    style = PhopencodeTheme.codeTypography.small,
                    softWrap = false,
                    modifier = Modifier.testTag(toolTextTag(section)),
                )
            }
        }
        if (shortened.truncated) {
            val note = if (shortened.hiddenLines > 0) {
                pluralStringResource(R.plurals.chat_tool_more_lines, shortened.hiddenLines, shortened.hiddenLines)
            } else {
                pluralStringResource(R.plurals.chat_tool_more_characters, shortened.hiddenChars, shortened.hiddenChars)
            }
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag(toolMoreTag(section)),
            )
        }
    }
}

@Composable
private fun statusTint(status: ToolCardStatus): Color = when (status) {
    ToolCardStatus.Completed -> MaterialTheme.colorScheme.outline
    ToolCardStatus.Running -> MaterialTheme.colorScheme.primary
    ToolCardStatus.Failed -> MaterialTheme.colorScheme.error
    ToolCardStatus.Preparing, ToolCardStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
}

@StringRes
private fun ToolCardStatus.labelRes(): Int = when (this) {
    ToolCardStatus.Preparing -> R.string.chat_tool_status_pending
    ToolCardStatus.Running -> R.string.chat_tool_status_running
    ToolCardStatus.Completed -> R.string.chat_tool_status_done
    ToolCardStatus.Failed -> R.string.chat_tool_status_failed
    ToolCardStatus.Unknown -> R.string.chat_tool_status_unknown
}

@StringRes
private fun ToolSection.titleRes(): Int = when (this) {
    ToolSection.Input -> R.string.chat_tool_input
    ToolSection.Output -> R.string.chat_tool_output
}

@StringRes
private fun ToolSection.toggleLabelRes(expanded: Boolean): Int = when (this) {
    ToolSection.Input -> if (expanded) R.string.chat_tool_hide_input else R.string.chat_tool_show_input
    ToolSection.Output -> if (expanded) R.string.chat_tool_hide_output else R.string.chat_tool_show_output
}

/** The still icon for [status]; a running call gets a play mark, not a spinner. */
internal fun toolStatusIcon(status: ToolCardStatus): ImageVector = when (status) {
    ToolCardStatus.Preparing -> ToolIcons.Pending
    ToolCardStatus.Running -> ToolIcons.Running
    ToolCardStatus.Completed -> ToolIcons.Done
    ToolCardStatus.Failed -> ToolIcons.Failed
    ToolCardStatus.Unknown -> ToolIcons.Unknown
}

/** Status marks and chevrons, drawn here to avoid the large icon library. */
private object ToolIcons {
    private const val CIRCLE = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z"
    private const val RING = "${CIRCLE}M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z"

    val Pending: ImageVector = icon("Pending", "${RING}M12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z")
    val Running: ImageVector = icon("Running", "${RING}M10,16.5l6,-4.5 -6,-4.5v9z")
    val Done: ImageVector = icon("Done", "${CIRCLE}M10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z")
    val Failed: ImageVector = icon("Failed", "${CIRCLE}M13,17h-2v-2h2v2zM13,13h-2L11,7h2v6z")
    val Unknown: ImageVector = icon(
        "Unknown",
        "${CIRCLE}M13,19h-2v-2h2v2zM15.07,11.25l-0.9,0.92C13.45,12.9 13,13.5 13,15h-2v-0.5c0,-1.1 0.45,-2.1 " +
            "1.17,-2.83l1.24,-1.26c0.37,-0.36 0.59,-0.86 0.59,-1.41 0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2L8,9c0,-2.21 " +
            "1.79,-4 4,-4s4,1.79 4,4c0,0.88 -0.36,1.68 -0.93,2.25z",
    )
    val More: ImageVector = icon("More", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")
    val Less: ImageVector = icon("Less", "M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z")

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = "Tool.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}
