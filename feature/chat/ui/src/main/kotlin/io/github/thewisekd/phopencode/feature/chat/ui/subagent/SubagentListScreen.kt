package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentNode
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentStatus
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** Deeper subagents are indented no further, so a deep tree still fits a phone. */
private const val MAX_INDENT_DEPTH = 4

private const val TITLE_LINES = 2

/**
 * The subagent list page: a summary, such as "3 subagents · 2 running", then the tree, each
 * subagent above its own, indented by depth. A row shows the subagent's title, its status, its
 * agent, duration and tokens, and its tool calls so far with the one running now, in the number
 * format of the screen's language; it follows [state] as the presenter keeps it live. A tap sends
 * [SubagentIntent.Open], the top bar's back button [SubagentIntent.Close], and a failed load shows
 * its reason with a retry that sends [SubagentIntent.Retry], above what was loaded.
 *
 * Nothing animates: loading is a line of text, not a spinner, and a running subagent's mark is
 * still. Titles and tool names are drawn, never logged. The root keeps the `placeholder:<route>`
 * tag the app's navigation test looks for.
 */
@Composable
fun SubagentListScreen(
    route: ChatRoute.Subagents,
    state: SubagentState,
    onIntent: (SubagentIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    val rows = flatten(state.subagents)
    PhopencodeScaffold(
        title = stringResource(R.string.chat_subagent_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(SubagentIntent.Close) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = spacing.sm),
        ) {
            val error = state.error
            if (error != null) {
                item(contentType = "error") {
                    ErrorBanner(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
                        message = errorText(error),
                        onRetry = { onIntent(SubagentIntent.Retry) },
                    )
                }
            }
            when {
                rows.isNotEmpty() -> {
                    item(contentType = "summary") { Summary(state) }
                    items(rows, contentType = { "row" }) { row ->
                        SubagentRow(row.node, row.depth, onOpen = { onIntent(SubagentIntent.Open(row.node.sessionId)) })
                    }
                }
                state.loading -> item(contentType = "loading") {
                    Text(
                        text = stringResource(R.string.chat_subagent_loading),
                        modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> item(contentType = "empty") {
                    EmptyState(
                        modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                        title = stringResource(R.string.chat_subagent_empty_title),
                        message = stringResource(R.string.chat_subagent_empty_message),
                    )
                }
            }
        }
    }
}

/** A subagent and how deep it sits: 0 for a direct subagent of this session. */
private data class TreeRow(val node: SubagentNode, val depth: Int)

/** The tree in reading order: each subagent, then its own. */
private fun flatten(nodes: List<SubagentNode>): List<TreeRow> = buildList {
    fun add(node: SubagentNode, depth: Int) {
        add(TreeRow(node, depth))
        node.children.forEach { add(it, depth + 1) }
    }
    nodes.forEach { add(it, 0) }
}

@Composable
private fun Summary(state: SubagentState) {
    val locale = subagentLocale()
    val parts = buildList {
        add(totalText(state, locale))
        if (state.runningCount > 0) add(countText(state, locale))
    }
    Text(
        text = joined(parts).orEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SubagentTags.SUMMARY)
            .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One subagent, read as one: its name and status, then its agent, duration and tokens, then its tools. */
@Composable
private fun SubagentRow(node: SubagentNode, depth: Int, onOpen: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val locale = subagentLocale()
    val indent = spacing.lg * (depth.coerceAtMost(MAX_INDENT_DEPTH) + 1)
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TOUCH)
                .clickable(onClickLabel = stringResource(R.string.chat_subagent_open), role = Role.Button, onClick = onOpen)
                .testTag(SubagentTags.row(node.sessionId))
                .semantics(mergeDescendants = true) {}
                .padding(start = indent, end = spacing.lg, top = spacing.sm, bottom = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = nodeName(node.title, node.agent),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = TITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusLabel(node.status)
            }
            val secondary = MaterialTheme.colorScheme.onSurfaceVariant
            metaText(node, locale)?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = secondary)
            }
            toolsText(node, locale)?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = secondary)
            }
        }
        HorizontalDivider(Modifier.padding(start = indent))
    }
}

/** The status in words on a tinted label; the words carry the meaning, the color only repeats it. */
@Composable
private fun StatusLabel(status: SubagentStatus) {
    val (container, content) = statusColors(status)
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text = stringResource(status.labelRes()),
            modifier = Modifier.padding(horizontal = PhopencodeTheme.spacing.sm, vertical = PhopencodeTheme.spacing.xxs),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun statusColors(status: SubagentStatus): Pair<Color, Color> {
    val tones = PhopencodeTheme.status
    val tone = when (status) {
        SubagentStatus.Running -> return MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SubagentStatus.Succeeded -> tones.success
        SubagentStatus.Failed -> tones.danger
        SubagentStatus.Interrupted -> tones.warning
        SubagentStatus.Idle -> return MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    return tone.container to tone.onContainer
}
