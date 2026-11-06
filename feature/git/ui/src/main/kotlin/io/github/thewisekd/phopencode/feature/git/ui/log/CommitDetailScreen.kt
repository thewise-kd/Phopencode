package io.github.thewisekd.phopencode.feature.git.ui.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetail
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitFileChange
import io.github.thewisekd.phopencode.feature.git.ui.R
import kotlin.time.Instant

/**
 * One commit (P9.9): its message, author, committer, dates, parents and the files it changed. Draws
 * [state] and sends [onIntent]; it decides nothing itself, and logs nothing: no path, name, address
 * or message leaves the screen.
 *
 * - **Top bar:** the commit's short hash; back closes the screen.
 * - **Status:** a still line while the commit loads; a failed read shows its error, with a retry
 *   when reading again can help.
 * - **List:** a [LazyColumn] of the subject and body, which can be selected; the full hash and the
 *   refs on it; the author and the committer with their addresses and dates in the screen's
 *   language; the parents by short hash, a merge marked and a root commit said; then the changed
 *   files under their count. A file row shows its name, kind of change, folder and former path, and
 *   the lines it added and removed, or that it is binary; a tap opens its diff in this commit.
 *
 * Nothing on it runs an endless animation: Robolectric's `idle()` in the app's tests would never
 * return. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun CommitDetailScreen(
    route: GitRoute.CommitDetail,
    state: CommitDetailState,
    onIntent: (CommitDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.git_log_detail_title, shortHash(state.commit)),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(CommitDetailIntent.Close) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            DetailContent(state, onIntent)
        }
    }
}

@Composable
private fun ColumnScope.DetailContent(state: CommitDetailState, onIntent: (CommitDetailIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val error = state.error
    val detail = state.detail
    when {
        state.loading -> StillProgress(R.string.git_log_detail_loading, CommitDetailTags.LOADING)
        error != null -> ErrorBanner(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm).testTag(CommitDetailTags.ERROR),
            message = stringResource(error.messageRes(fallback = R.string.git_log_error_detail)),
            onRetry = if (error.canRetry) ({ onIntent(CommitDetailIntent.Retry) }) else null,
        )
        detail != null -> DetailList(detail, onIntent)
    }
}

@Composable
private fun ColumnScope.DetailList(detail: CommitDetail, onIntent: (CommitDetailIntent) -> Unit) {
    val summary = detail.summary
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).testTag(CommitDetailTags.LIST)) {
        item(key = "message", contentType = "message") { Message(summary.subject, detail.body) }
        item(key = "hash", contentType = "hash") { HashSection(summary.hash, summary.refs) }
        item(key = "author", contentType = "person") {
            Person(
                label = R.string.git_log_detail_author,
                name = summary.authorName,
                email = summary.authorEmail,
                at = summary.authoredAt,
                tag = CommitDetailTags.AUTHOR,
            )
        }
        item(key = "committer", contentType = "person") {
            Person(
                label = R.string.git_log_detail_committer,
                name = detail.committerName,
                email = detail.committerEmail,
                at = detail.committedAt,
                tag = CommitDetailTags.COMMITTER,
            )
        }
        item(key = "parents", contentType = "parents") { Parents(summary.parents) }
        item(key = "files-header", contentType = "files-header") { FilesHeader(detail.files.size) }
        if (detail.files.isEmpty()) {
            item(key = "no-files", contentType = "no-files") {
                SectionText(
                    text = stringResource(R.string.git_log_detail_no_files),
                    modifier = Modifier.testTag(CommitDetailTags.NO_FILES),
                )
            }
        }
        itemsIndexed(detail.files, key = { index, file -> "file:$index:${file.path}" }, contentType = { _, _ -> "file" }) { _, file ->
            FileRow(file, onIntent)
        }
    }
}

@Composable
private fun Section(tag: String, content: @Composable ColumnScope.() -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        content = content,
    )
}

@Composable
private fun Label(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun SectionText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** The subject and the body; both can be selected and copied. */
@Composable
private fun Message(subject: String, body: String) {
    Section(CommitDetailTags.MESSAGE) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm)) {
                Text(text = subject, style = MaterialTheme.typography.titleMedium)
                if (body.isNotBlank()) Text(text = body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The full hash, which can be selected, and the branches and tags on the commit. */
@Composable
private fun HashSection(hash: String, refs: List<String>) {
    Section(CommitDetailTags.HASH) {
        SelectionContainer {
            Text(text = hash, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        if (refs.isNotEmpty()) {
            Text(
                text = refs.joinToString(SEPARATOR),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Person(label: Int, name: String, email: String, at: Instant, tag: String) {
    Section(tag) {
        Label(stringResource(label))
        Text(text = name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = listOf(email, formatDate(at, withTime = true)).joinToString(SEPARATOR),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The parents by short hash; a merge is marked, a root commit says it has none. */
@Composable
private fun Parents(parents: List<String>) {
    Section(CommitDetailTags.PARENTS) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm),
        ) {
            Label(stringResource(if (parents.size > 1) R.string.git_log_detail_parents else R.string.git_log_detail_parent))
            if (parents.size > 1) MergeMarker()
        }
        Text(
            text = if (parents.isEmpty()) {
                stringResource(R.string.git_log_detail_root)
            } else {
                parents.joinToString(SEPARATOR) { shortHash(it) }
            },
            fontFamily = if (parents.isEmpty()) null else FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FilesHeader(count: Int) {
    val spacing = PhopencodeTheme.spacing
    Column {
        HorizontalDivider()
        Text(
            text = pluralStringResource(R.plurals.git_log_detail_files, count, count),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.touchTarget)
                .testTag(CommitDetailTags.FILES_HEADER)
                .semantics { heading() }
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** One changed file: its name, kind, folder and former path, and its line counts. A tap opens its diff. */
@Composable
private fun FileRow(file: CommitFileChange, onIntent: (CommitDetailIntent) -> Unit) {
    val details = listOfNotNull(
        stringResource(file.kind.labelRes),
        folderOf(file.path),
        file.oldPath?.let { stringResource(R.string.git_log_detail_renamed_from, it) },
    ).joinToString(SEPARATOR)
    ListItem(
        modifier = Modifier
            .testTag(CommitDetailTags.FILE)
            .clickable(
                onClickLabel = stringResource(R.string.git_log_detail_open_diff),
                role = Role.Button,
                onClick = { onIntent(CommitDetailIntent.OpenFile(file.path)) },
            ),
        headlineContent = { Text(text = fileName(file.path), maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = { Text(text = details, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { LineCounts(file) },
    )
}

/** `+12 −3` as diff tools print it, read out in words; a binary file has no lines to count. */
@Composable
private fun LineCounts(file: CommitFileChange) {
    if (file.isBinary) {
        Text(
            text = stringResource(R.string.git_log_detail_binary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        return
    }
    val added = file.additions ?: 0
    val removed = file.deletions ?: 0
    val description = listOf(
        pluralStringResource(R.plurals.git_log_detail_additions, added, added),
        pluralStringResource(R.plurals.git_log_detail_deletions, removed, removed),
    ).joinToString(", ")
    Row(
        modifier = Modifier.testTag(CommitDetailTags.COUNTS).clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.git_log_detail_additions_mark, added),
            color = PhopencodeTheme.status.success.color,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(R.string.git_log_detail_deletions_mark, removed),
            color = PhopencodeTheme.status.danger.color,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
