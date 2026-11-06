package io.github.thewisekd.phopencode.feature.git.ui.status

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.core.ui.theme.StatusColor
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.git.ui.R

/** Between the parts of a line; not words, so the same in every language. */
private const val SEPARATOR = " · "

/**
 * The state of the working tree (P9.5), the hub of the git feature. Draws [state] and sends
 * [onIntent]; it decides nothing itself, and logs nothing: a path never leaves the screen.
 *
 * - **Top bar:** back closes the screen; refresh reads the status again, or retries after a failed
 *   read.
 * - **Status:** a still line while the status loads or reloads. A failed read shows its error and
 *   nothing else; a failed stage or unstage shows above the rows, which stay.
 * - **Branch:** the branch, a detached `HEAD` at its short commit, or a branch without commits; the
 *   upstream it tracks with the commits it is ahead and behind, or that it tracks none.
 * - **Hub:** commit, history, branches and remote, in a row that scrolls sideways when narrow.
 * - **List:** a [LazyColumn] of the conflicted, staged, unstaged and untracked groups, each under a
 *   header with its count; an empty group has no header. A row shows the file's name, its kind of
 *   change and its folder; a tap opens its diff, staged for a staged row, of the working tree
 *   otherwise. Its button stages, unstages or marks the conflict resolved. A pending row waits. A
 *   clean tree says there is nothing to commit.
 *
 * Nothing on it runs an endless animation: Robolectric's `idle()` in the app's tests would never
 * return. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun StatusScreen(
    route: GitRoute.Status,
    state: StatusState,
    onIntent: (StatusIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.git_status_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(StatusIntent.Close) },
        actions = {
            IconButton(
                onClick = { onIntent(if (state.error != null) StatusIntent.Retry else StatusIntent.Refresh) },
                enabled = !state.loading && !state.refreshing,
                modifier = Modifier.testTag(StatusTags.REFRESH),
            ) {
                Icon(imageVector = StatusIcons.Refresh, contentDescription = stringResource(R.string.git_status_refresh))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            StatusContent(state, onIntent)
        }
    }
}

@Composable
private fun ColumnScope.StatusContent(state: StatusState, onIntent: (StatusIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    when {
        state.loading -> StillProgress(R.string.git_status_loading, StatusTags.LOADING)
        state.refreshing -> StillProgress(R.string.git_status_refreshing, StatusTags.REFRESHING)
    }
    val error = state.error
    if (error != null) {
        ErrorBanner(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm).testTag(StatusTags.ERROR),
            message = stringResource(error.messageRes(fallback = R.string.git_status_error_read)),
            onRetry = if (error.canRetry) ({ onIntent(StatusIntent.Retry) }) else null,
        )
        return
    }
    if (state.loading) return
    state.branch?.let { BranchHeader(it) }
    HubActions(onIntent)
    state.actionError?.let { failure ->
        ErrorBanner(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm).testTag(StatusTags.ACTION_ERROR),
            message = stringResource(failure.messageRes(fallback = R.string.git_status_error_action)),
            onDismiss = { onIntent(StatusIntent.DismissError) },
        )
    }
    HorizontalDivider()
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).testTag(StatusTags.LIST)) {
        if (state.isClean) {
            item(key = "clean", contentType = "clean") {
                EmptyState(
                    modifier = Modifier.fillMaxWidth().testTag(StatusTags.CLEAN),
                    title = stringResource(R.string.git_status_clean_title),
                    message = stringResource(R.string.git_status_clean_message),
                    icon = StatusIcons.Resolve,
                )
            }
        }
        StatusGroup.entries.forEach { group -> group(state, group, onIntent) }
    }
}

/**
 * The status is being read. A still line rather than a spinner: an endless animation keeps
 * Robolectric's `idle()` from returning. TalkBack hears the message, an indeterminate progress and a
 * polite live region, as for core/ui's `Loading`.
 */
@Composable
private fun StillProgress(@StringRes text: Int, tag: String) {
    val message = stringResource(text)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Reading again cannot help after these; the connection has to be made again first. */
private val GitFailure.canRetry: Boolean
    get() = this != GitFailure.NotConnected && this != GitFailure.Unauthorized

/**
 * The failures the status can meet have their own words; the others, which belong to pushes, pulls
 * and switches, and a git error whose exit code says nothing to a user, take [fallback].
 */
@StringRes
private fun GitFailure.messageRes(@StringRes fallback: Int): Int = when (this) {
    GitFailure.NotConnected -> R.string.git_status_error_not_connected
    GitFailure.Unreachable -> R.string.git_status_error_unreachable
    GitFailure.TimedOut -> R.string.git_status_error_timed_out
    GitFailure.Unauthorized -> R.string.git_status_error_unauthorized
    GitFailure.OutputTooLarge -> R.string.git_status_error_output_too_large
    GitFailure.NotRepository -> R.string.git_status_error_not_repository
    GitFailure.GitMissing -> R.string.git_status_error_git_missing
    GitFailure.Rejected -> R.string.git_status_error_rejected
    GitFailure.NotFound -> R.string.git_status_error_not_found
    GitFailure.Conflict -> R.string.git_status_error_conflict
    GitFailure.CredentialRequired,
    GitFailure.NonFastForward,
    GitFailure.Diverged,
    GitFailure.WouldOverwrite,
    is GitFailure.GitError,
    GitFailure.Unknown,
    -> fallback
}

/** Where `HEAD` is, and how the branch stands against its upstream. */
@Composable
private fun BranchHeader(branch: GitBranchStatus) {
    val spacing = PhopencodeTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Row(
            modifier = Modifier.testTag(StatusTags.BRANCH).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            val head = branch.head
            if (head == null) {
                val commit = branch.commit
                Text(
                    text = if (commit != null) {
                        stringResource(R.string.git_status_detached, shortCommit(commit))
                    } else {
                        stringResource(R.string.git_status_detached_unknown)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Text(
                    text = stringResource(R.string.git_status_branch),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = head,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (branch.commit == null) {
                    Text(
                        text = stringResource(R.string.git_status_no_commits),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        val upstream = branch.upstream
        when {
            upstream != null && branch.upstreamGone -> Text(
                text = stringResource(R.string.git_status_upstream_gone, upstream),
                modifier = Modifier.testTag(StatusTags.UPSTREAM_GONE),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            upstream != null -> Text(
                text = upstreamLine(upstream, branch.ahead, branch.behind, branch.aheadBehindKnown),
                modifier = Modifier.testTag(StatusTags.UPSTREAM),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            !branch.isDetached -> Text(
                text = stringResource(R.string.git_status_no_upstream),
                modifier = Modifier.testTag(StatusTags.NO_UPSTREAM),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The upstream, then the commits ahead and behind it, or that the branch is even with it; when git
 * did not count, that the counts are unknown rather than up to date.
 */
@Composable
private fun upstreamLine(upstream: String, ahead: Int, behind: Int, countsKnown: Boolean): String {
    val parts = mutableListOf(stringResource(R.string.git_status_tracking, upstream))
    if (!countsKnown) return (parts + stringResource(R.string.git_status_counts_unknown)).joinToString(SEPARATOR)
    if (ahead > 0) parts += pluralStringResource(R.plurals.git_status_ahead, ahead, ahead)
    if (behind > 0) parts += pluralStringResource(R.plurals.git_status_behind, behind, behind)
    if (ahead == 0 && behind == 0) parts += stringResource(R.string.git_status_up_to_date)
    return parts.joinToString(SEPARATOR)
}

/** The screens the status leads to; they scroll sideways when the screen is narrow. */
@Composable
private fun HubActions(onIntent: (StatusIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        FilledTonalButton(onClick = { onIntent(StatusIntent.OpenCommit) }, modifier = Modifier.testTag(StatusTags.COMMIT)) {
            Text(stringResource(R.string.git_status_commit))
        }
        HubButton(R.string.git_status_log, StatusTags.LOG) { onIntent(StatusIntent.OpenLog) }
        HubButton(R.string.git_status_branches, StatusTags.BRANCHES) { onIntent(StatusIntent.OpenBranches) }
        HubButton(R.string.git_status_remote, StatusTags.REMOTE) { onIntent(StatusIntent.OpenRemote) }
    }
}

@Composable
private fun HubButton(@StringRes text: Int, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Text(stringResource(text))
    }
}

/** [group]'s header and rows, or nothing when it has no rows. Keys hold the group: a file can be in two. */
private fun LazyListScope.group(state: StatusState, group: StatusGroup, onIntent: (StatusIntent) -> Unit) {
    val changes = state.changes(group)
    if (changes.isEmpty()) return
    item(key = "header:${group.name}", contentType = "header") {
        GroupHeader(group, changes.size, state.bulkAction(group), enabled = state.pending.isEmpty(), onIntent)
    }
    items(changes, key = { "${group.name}:${it.path}" }, contentType = { "row" }) { change ->
        ChangeRow(change, group, pending = change.path in state.pending, onIntent = onIntent)
    }
}

@Composable
private fun GroupHeader(
    group: StatusGroup,
    count: Int,
    bulk: BulkAction?,
    enabled: Boolean,
    onIntent: (StatusIntent) -> Unit,
) {
    val spacing = PhopencodeTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .testTag(StatusTags.header(group))
            .semantics(mergeDescendants = true) { heading() }
            .padding(start = spacing.lg, end = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(group.titleRes),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = count.toString(),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                .padding(horizontal = spacing.xs),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(modifier = Modifier.weight(1f))
        when (bulk) {
            BulkAction.StageAll -> TextButton(
                onClick = { onIntent(StatusIntent.StageAll) },
                enabled = enabled,
                modifier = Modifier.testTag(StatusTags.STAGE_ALL),
            ) { Text(stringResource(R.string.git_status_stage_all)) }
            BulkAction.UnstageAll -> TextButton(
                onClick = { onIntent(StatusIntent.UnstageAll) },
                enabled = enabled,
                modifier = Modifier.testTag(StatusTags.UNSTAGE_ALL),
            ) { Text(stringResource(R.string.git_status_unstage_all)) }
            null -> Unit
        }
    }
}

@get:StringRes
private val StatusGroup.titleRes: Int
    get() = when (this) {
        StatusGroup.Conflicted -> R.string.git_status_group_conflicted
        StatusGroup.Staged -> R.string.git_status_group_staged
        StatusGroup.Unstaged -> R.string.git_status_group_unstaged
        StatusGroup.Untracked -> R.string.git_status_group_untracked
    }

/**
 * One changed file: a tap opens its diff, its button stages or unstages it. While its stage or
 * unstage runs, both wait and the row says it is working.
 */
@Composable
private fun ChangeRow(change: GitFileChange, group: StatusGroup, pending: Boolean, onIntent: (StatusIntent) -> Unit) {
    val name = fileName(change.path)
    val pendingText = stringResource(R.string.git_status_pending)
    val details = listOfNotNull(
        stringResource(change.kind.labelRes),
        folderOf(change.path),
        change.originalPath?.let { stringResource(R.string.git_status_renamed_from, it) },
    ).joinToString(SEPARATOR)
    ListItem(
        modifier = Modifier
            .testTag(StatusTags.row(group))
            .clickable(
                enabled = !pending,
                onClickLabel = stringResource(R.string.git_status_open_diff),
                role = Role.Button,
                onClick = { onIntent(StatusIntent.OpenDiff(change, staged = group == StatusGroup.Staged)) },
            )
            .semantics { if (pending) stateDescription = pendingText },
        leadingContent = { KindMark(change.kind) },
        headlineContent = { Text(text = name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = { Text(text = details, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            val (icon, description, intent) = when (group) {
                StatusGroup.Conflicted -> Triple(StatusIcons.Resolve, R.string.git_status_resolve, StatusIntent.Stage(change))
                StatusGroup.Staged -> Triple(StatusIcons.Unstage, R.string.git_status_unstage, StatusIntent.Unstage(change))
                StatusGroup.Unstaged, StatusGroup.Untracked ->
                    Triple(StatusIcons.Stage, R.string.git_status_stage, StatusIntent.Stage(change))
            }
            IconButton(
                onClick = { onIntent(intent) },
                enabled = !pending,
                modifier = Modifier.size(PhopencodeTheme.spacing.touchTarget),
            ) {
                Icon(imageVector = icon, contentDescription = stringResource(description, name))
            }
        },
    )
}

/** The letter git prints for the change, in its status color. Decorative: the row's text names the kind. */
@Composable
private fun KindMark(kind: GitChangeKind) {
    val colors = kind.colors
    Box(
        modifier = Modifier
            .size(PhopencodeTheme.spacing.xl)
            .clearAndSetSemantics {}
            .background(colors.container, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = stringResource(kind.markRes), color = colors.onContainer, style = MaterialTheme.typography.labelLarge)
    }
}

private val GitChangeKind.colors: StatusColor
    @Composable get() = when (this) {
        GitChangeKind.Added, GitChangeKind.Untracked -> PhopencodeTheme.status.success
        GitChangeKind.Modified, GitChangeKind.Renamed, GitChangeKind.Copied, GitChangeKind.TypeChanged ->
            PhopencodeTheme.status.warning
        GitChangeKind.Deleted, GitChangeKind.Unmerged -> PhopencodeTheme.status.danger
    }

@get:StringRes
private val GitChangeKind.labelRes: Int
    get() = when (this) {
        GitChangeKind.Added -> R.string.git_status_kind_added
        GitChangeKind.Modified -> R.string.git_status_kind_modified
        GitChangeKind.Deleted -> R.string.git_status_kind_deleted
        GitChangeKind.Renamed -> R.string.git_status_kind_renamed
        GitChangeKind.Copied -> R.string.git_status_kind_copied
        GitChangeKind.TypeChanged -> R.string.git_status_kind_type_changed
        GitChangeKind.Untracked -> R.string.git_status_kind_untracked
        GitChangeKind.Unmerged -> R.string.git_status_kind_unmerged
    }

@get:StringRes
private val GitChangeKind.markRes: Int
    get() = when (this) {
        GitChangeKind.Added -> R.string.git_status_mark_added
        GitChangeKind.Modified -> R.string.git_status_mark_modified
        GitChangeKind.Deleted -> R.string.git_status_mark_deleted
        GitChangeKind.Renamed -> R.string.git_status_mark_renamed
        GitChangeKind.Copied -> R.string.git_status_mark_copied
        GitChangeKind.TypeChanged -> R.string.git_status_mark_type_changed
        GitChangeKind.Untracked -> R.string.git_status_mark_untracked
        GitChangeKind.Unmerged -> R.string.git_status_mark_unmerged
    }
