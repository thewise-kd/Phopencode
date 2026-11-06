package io.github.thewisekd.phopencode.feature.git.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitSummary
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
import io.github.thewisekd.phopencode.feature.git.ui.R

/**
 * The history (P9.9): the commits of the current branch, or of the branch the filter picks, newest
 * first, a page at a time. Draws [state] and sends [onIntent]; it decides nothing itself, and logs
 * nothing: no subject, name or branch leaves the screen.
 *
 * - **Top bar:** back closes the screen; refresh starts over, or retries after a failed read.
 * - **Filter:** the branch shown, or the current one; a menu offers every branch. It stays while a
 *   branch loads or fails, so that another can be picked.
 * - **Status:** a still line while the first page loads; a failed first page shows its error alone.
 * - **List:** a [LazyColumn] of commits: the subject, then the short hash, the author and the date in
 *   the screen's language; a merge carries a marker. A tap opens the commit. Below the last row, a
 *   footer asks for the next page when it comes into view, says a page is loading, shows a failed
 *   page with its retry, or says the history has no more commits. A branch without commits says so.
 *
 * Nothing on it runs an endless animation: Robolectric's `idle()` in the app's tests would never
 * return. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun LogScreen(
    route: GitRoute.Log,
    state: LogState,
    onIntent: (LogIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.git_log_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(LogIntent.Close) },
        actions = {
            IconButton(
                onClick = { onIntent(if (state.error != null) LogIntent.Retry else LogIntent.Refresh) },
                enabled = !state.loading,
                modifier = Modifier.testTag(LogTags.REFRESH),
            ) {
                Icon(imageVector = LogIcons.Refresh, contentDescription = stringResource(R.string.git_log_refresh))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LogContent(state, onIntent)
        }
    }
}

@Composable
private fun ColumnScope.LogContent(state: LogState, onIntent: (LogIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    if (state.branches.isNotEmpty() || state.branch != null) {
        BranchFilter(state.branch, state.branches, onIntent)
        HorizontalDivider()
    }
    if (state.loading) {
        StillProgress(R.string.git_log_loading, LogTags.LOADING)
        return
    }
    val error = state.error
    if (error != null) {
        ErrorBanner(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm).testTag(LogTags.ERROR),
            message = stringResource(error.messageRes(fallback = R.string.git_log_error_read)),
            onRetry = if (error.canRetry) ({ onIntent(LogIntent.Retry) }) else null,
        )
        return
    }
    if (state.isEmpty) {
        EmptyState(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(LogTags.EMPTY),
            title = stringResource(R.string.git_log_empty_title),
            message = stringResource(R.string.git_log_empty_message),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).testTag(LogTags.LIST)) {
        items(state.commits, key = { it.hash }, contentType = { "commit" }) { commit ->
            CommitRow(commit, onIntent)
        }
        item(key = "footer", contentType = "footer") { Footer(state, onIntent) }
    }
}

/** The branch the history shows, and a menu of every branch; the first choice is the current one. */
@Composable
private fun BranchFilter(branch: String?, branches: List<String>, onIntent: (LogIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    var open by rememberSaveable { mutableStateOf(false) }
    val head = stringResource(R.string.git_log_filter_head)
    fun choose(choice: String?) {
        open = false
        if (choice != branch) onIntent(LogIntent.FilterBranch(choice))
    }
    Box(modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs)) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier
                .heightIn(min = spacing.touchTarget)
                .testTag(LogTags.FILTER),
        ) {
            Text(
                text = stringResource(R.string.git_log_filter, branch ?: head),
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Icon(imageVector = LogIcons.DropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FilterOption(head, LogTags.filterOption(null), selected = branch == null) { choose(null) }
            branches.forEach { name ->
                FilterOption(name, LogTags.filterOption(name), selected = name == branch) { choose(name) }
            }
        }
    }
}

@Composable
private fun FilterOption(text: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    val selectedLabel = stringResource(R.string.git_log_filter_selected)
    DropdownMenuItem(
        text = { Text(text = text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        onClick = onClick,
        modifier = Modifier.testTag(tag).semantics { this.selected = selected },
        trailingIcon = if (selected) {
            { Icon(imageVector = LogIcons.Check, contentDescription = selectedLabel) }
        } else {
            null
        },
    )
}

/** One commit: its subject, then its short hash, author and date. A tap opens it. */
@Composable
private fun CommitRow(commit: CommitSummary, onIntent: (LogIntent) -> Unit) {
    val details = listOf(commit.shortHash, commit.authorName, formatDate(commit.authoredAt)).joinToString(SEPARATOR)
    ListItem(
        modifier = Modifier
            .testTag(LogTags.ROW)
            .clickable(
                onClickLabel = stringResource(R.string.git_log_open_commit),
                role = Role.Button,
                onClick = { onIntent(LogIntent.OpenCommit(commit.hash)) },
            ),
        headlineContent = { Text(text = commit.subject, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(text = details, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (commit.isMerge) {
            { MergeMarker() }
        } else {
            null
        },
    )
}

/** A commit with more than one parent. */
@Composable
internal fun MergeMarker(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.git_log_merge),
        modifier = modifier
            .testTag(LogTags.MERGE)
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = PhopencodeTheme.spacing.xs),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.labelMedium,
    )
}

/**
 * Below the last commit. While another page can be read, coming into view asks for it; a failed
 * page waits for its retry instead, so that a footer left in view does not ask again and again.
 */
@Composable
private fun Footer(state: LogState, onIntent: (LogIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val moreError = state.moreError
    when {
        moreError != null -> ErrorBanner(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm).testTag(LogTags.MORE_ERROR),
            message = stringResource(moreError.messageRes(fallback = R.string.git_log_error_more)),
            onRetry = if (moreError.canRetry) ({ onIntent(LogIntent.LoadMore) }) else null,
        )
        state.endReached -> Text(
            text = stringResource(R.string.git_log_end),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LogTags.END)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        else -> {
            StillProgress(R.string.git_log_loading_more, LogTags.LOADING_MORE)
            if (state.canLoadMore) {
                val send by rememberUpdatedState(onIntent)
                LaunchedEffect(state.commits.size) { send(LogIntent.LoadMore) }
            }
        }
    }
}
