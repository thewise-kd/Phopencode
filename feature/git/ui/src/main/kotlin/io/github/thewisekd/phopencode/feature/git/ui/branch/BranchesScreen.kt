package io.github.thewisekd.phopencode.feature.git.ui.branch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranch
import io.github.thewisekd.phopencode.feature.git.logic.contract.SHORT_HASH_LENGTH
import io.github.thewisekd.phopencode.feature.git.ui.R

/** Between the parts of a line; not words, so the same in every language. */
private const val SEPARATOR = " · "

/**
 * The branches (P9.13): local and remote apart, a search, and create, switch and delete. Draws
 * [state] and sends [onIntent]; it decides nothing itself, and logs nothing: no branch name leaves
 * the screen.
 *
 * - **Top bar:** back closes the screen; new branch opens the create dialog; refresh lists again,
 *   or retries after a failed list.
 * - **Status:** a still line while the list loads or an operation runs; the rows wait meanwhile. A
 *   failed list shows its error alone; a failed create, switch or delete shows above the rows.
 * - **Search:** filters both sections through the contract's shown lists; a match-nothing search
 *   says so.
 * - **List:** a [LazyColumn] of the local section, then the remote one, each under a header with its
 *   count; an empty section has no header. A local row marks the current branch, and shows the
 *   upstream with the commits ahead and behind. A tap switches; a non-current local branch has a
 *   delete button; no remote branch is deleted here.
 * - **Dialogs:** the dirty-tree warning, the delete confirmations and the create dialog, each as the
 *   contract's [BranchesState.dialog] says.
 *
 * Nothing on it runs an endless animation: Robolectric's `idle()` in the app's tests would never
 * return. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun BranchesScreen(
    route: GitRoute.Branches,
    state: BranchesState,
    onIntent: (BranchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = !state.loading && state.busy == null
    PhopencodeScaffold(
        title = stringResource(R.string.git_branch_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(BranchIntent.Close) },
        actions = {
            IconButton(
                onClick = { onIntent(BranchIntent.StartCreate()) },
                enabled = idle && state.error == null,
                modifier = Modifier.testTag(BranchTags.CREATE),
            ) {
                Icon(imageVector = BranchIcons.Add, contentDescription = stringResource(R.string.git_branch_create))
            }
            IconButton(
                onClick = { onIntent(if (state.error != null) BranchIntent.Retry else BranchIntent.Refresh) },
                enabled = idle,
                modifier = Modifier.testTag(BranchTags.REFRESH),
            ) {
                Icon(imageVector = BranchIcons.Refresh, contentDescription = stringResource(R.string.git_branch_refresh))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            BranchesContent(state, onIntent)
        }
    }
    BranchDialogs(state, onIntent)
}

@Composable
private fun ColumnScope.BranchesContent(state: BranchesState, onIntent: (BranchIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val hasBranches = state.local.isNotEmpty() || state.remote.isNotEmpty()
    if (state.loading) StillProgress(R.string.git_branch_loading, BranchTags.LOADING)
    val error = state.error
    if (error != null) {
        ErrorBanner(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm).testTag(BranchTags.ERROR),
            message = stringResource(error.messageRes(fallback = R.string.git_branch_error_read)),
            onRetry = if (error.canRetry) ({ onIntent(BranchIntent.Retry) }) else null,
        )
        return
    }
    if (state.loading && !hasBranches) return
    state.busy?.let { StillProgress(it.busyRes, BranchTags.BUSY) }
    state.actionError?.let { actionError ->
        ErrorBanner(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm).testTag(BranchTags.ACTION_ERROR),
            message = stringResource(actionError.failure.messageRes(fallback = actionError.op.failureRes)),
            onDismiss = { onIntent(BranchIntent.DismissError) },
        )
    }
    if (!hasBranches) {
        EmptyState(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(BranchTags.EMPTY),
            title = stringResource(R.string.git_branch_empty_title),
            message = stringResource(R.string.git_branch_empty_message),
        )
        return
    }
    SearchField(state.query, onIntent)
    HorizontalDivider()
    val shownLocal = state.shownLocal
    val shownRemote = state.shownRemote
    if (shownLocal.isEmpty() && shownRemote.isEmpty()) {
        EmptyState(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(BranchTags.NO_MATCH),
            title = stringResource(R.string.git_branch_no_match_title),
            message = stringResource(R.string.git_branch_no_match_message),
            icon = BranchIcons.Search,
        )
        return
    }
    val enabled = state.busy == null && !state.loading
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).testTag(BranchTags.LIST)) {
        section(BranchSection.Local, shownLocal, enabled, onIntent)
        section(BranchSection.Remote, shownRemote, enabled, onIntent)
    }
}

/** What the search holds; every change goes to the contract, which filters both sections. */
@Composable
private fun SearchField(query: String, onIntent: (BranchIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    OutlinedTextField(
        value = query,
        onValueChange = { onIntent(BranchIntent.Search(it)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.xs)
            .testTag(BranchTags.SEARCH),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.git_branch_search)) },
        leadingIcon = { Icon(imageVector = BranchIcons.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(
                    onClick = { onIntent(BranchIntent.Search("")) },
                    modifier = Modifier.testTag(BranchTags.SEARCH_CLEAR),
                ) {
                    Icon(imageVector = BranchIcons.Clear, contentDescription = stringResource(R.string.git_branch_search_clear))
                }
            }
        } else {
            null
        },
    )
}

private enum class BranchSection { Local, Remote }

/** [section]'s header and rows, or nothing when it shows no rows. Keys hold the section. */
private fun LazyListScope.section(
    section: BranchSection,
    branches: List<GitBranch>,
    enabled: Boolean,
    onIntent: (BranchIntent) -> Unit,
) {
    if (branches.isEmpty()) return
    item(key = "header:${section.name}", contentType = "header") { SectionHeader(section, branches.size) }
    items(branches, key = { "${section.name}:${it.name}" }, contentType = { "branch" }) { branch ->
        BranchRow(branch, enabled, onIntent)
    }
}

@Composable
private fun SectionHeader(section: BranchSection, count: Int) {
    val spacing = PhopencodeTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .testTag(if (section == BranchSection.Local) BranchTags.HEADER_LOCAL else BranchTags.HEADER_REMOTE)
            .semantics(mergeDescendants = true) { heading() }
            .padding(horizontal = spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(
                if (section == BranchSection.Local) R.string.git_branch_section_local else R.string.git_branch_section_remote,
            ),
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
    }
}

/**
 * One branch. A tap switches to it, except on the current one; a non-current local branch has a
 * delete button. While an operation runs, both wait.
 */
@Composable
private fun BranchRow(branch: GitBranch, enabled: Boolean, onIntent: (BranchIntent) -> Unit) {
    val switchLabel = stringResource(if (branch.isRemote) R.string.git_branch_switch_remote else R.string.git_branch_switch)
    val tap = if (branch.isCurrent) {
        Modifier
    } else {
        Modifier.clickable(
            enabled = enabled,
            onClickLabel = switchLabel,
            role = Role.Button,
            onClick = { onIntent(BranchIntent.Switch(branch)) },
        )
    }
    ListItem(
        modifier = Modifier
            .testTag(if (branch.isRemote) BranchTags.ROW_REMOTE else BranchTags.ROW_LOCAL)
            .then(tap)
            .semantics(mergeDescendants = true) { selected = branch.isCurrent },
        leadingContent = { CurrentMark(branch.isCurrent) },
        headlineContent = {
            Text(
                text = branch.name,
                fontWeight = if (branch.isCurrent) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        },
        supportingContent = { BranchDetails(branch) },
        trailingContent = if (!branch.isRemote && !branch.isCurrent) {
            {
                IconButton(
                    onClick = { onIntent(BranchIntent.Delete(branch)) },
                    enabled = enabled,
                    modifier = Modifier.size(PhopencodeTheme.spacing.touchTarget).testTag(BranchTags.DELETE),
                ) {
                    Icon(
                        imageVector = BranchIcons.Delete,
                        contentDescription = stringResource(R.string.git_branch_delete, branch.name),
                    )
                }
            }
        } else {
            null
        },
    )
}

/** A check on the current branch, with a spoken label; an empty slot of the same size elsewhere, so names line up. */
@Composable
private fun CurrentMark(current: Boolean) {
    Box(modifier = Modifier.size(PhopencodeTheme.spacing.xl), contentAlignment = Alignment.Center) {
        if (current) {
            Icon(
                imageVector = BranchIcons.Check,
                contentDescription = stringResource(R.string.git_branch_current),
                modifier = Modifier.testTag(BranchTags.CURRENT),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A local branch's upstream line, then the tip's short hash and subject when known. */
@Composable
private fun BranchDetails(branch: GitBranch) {
    Column {
        if (!branch.isRemote) {
            val upstream = branch.upstream
            Text(
                text = if (upstream != null) {
                    upstreamLine(upstream, branch.ahead, branch.behind)
                } else {
                    stringResource(R.string.git_branch_no_upstream)
                },
                modifier = Modifier.testTag(BranchTags.UPSTREAM),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val tip = listOfNotNull(branch.commit?.take(SHORT_HASH_LENGTH), branch.subject).joinToString(SEPARATOR)
        if (tip.isNotEmpty()) {
            Text(text = tip, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The upstream, then the commits ahead and behind it, or that the branch is even with it. */
@Composable
private fun upstreamLine(upstream: String, ahead: Int, behind: Int): String {
    val parts = mutableListOf(stringResource(R.string.git_branch_tracking, upstream))
    if (ahead > 0) parts += pluralStringResource(R.plurals.git_branch_ahead, ahead, ahead)
    if (behind > 0) parts += pluralStringResource(R.plurals.git_branch_behind, behind, behind)
    if (ahead == 0 && behind == 0) parts += stringResource(R.string.git_branch_up_to_date)
    return parts.joinToString(SEPARATOR)
}
