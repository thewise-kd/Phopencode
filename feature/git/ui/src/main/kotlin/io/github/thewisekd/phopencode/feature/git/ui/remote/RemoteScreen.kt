package io.github.thewisekd.phopencode.feature.git.ui.remote

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.ConfirmDialog
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.PushPlan
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteResult
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import io.github.thewisekd.phopencode.feature.git.ui.R

/** Between the parts of a line; not words, so the same in every language. */
private const val SEPARATOR = " · "

/** Git's short form of a commit hash. */
private const val SHORT_COMMIT = 7

/**
 * Fetch, pull and push (P9.15). Draws [state] and sends [onIntent]; it decides nothing itself, and
 * logs nothing: no remote URL, branch name or git output leaves the screen. URLs are never shown.
 *
 * - **Top bar:** back closes the screen; refresh reads the remotes and the branch again, or retries
 *   after a failed read. It waits while anything loads or runs.
 * - **Status:** a still line while the remotes load. A failed read shows its error and nothing else.
 * - **Running:** the operation that runs with its Cancel button, pinned above the scrolling part so
 *   that Cancel is always in reach.
 * - **Outcome:** how the last operation ended: succeeded, up to date, cancelled or failed, a failure
 *   with a plain explanation of what to do next. On top of the scrolling part, which scrolls up to it
 *   when it appears.
 * - **Branch:** the branch, a detached `HEAD`, or a branch without commits; the upstream with the
 *   commits ahead and behind, or that a push will set one.
 * - **Remote:** the remote operations use, or a choice when there are several, or that there is none.
 * - **Actions:** fetch, pull (fast-forward only) and push. Push only asks: the confirmation names the
 *   branch, the remote and the commits, and only its confirm button pushes. Nothing can force.
 *
 * Nothing on it runs an endless animation: Robolectric's `idle()` in the app's tests would never
 * return. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun RemoteScreen(
    route: GitRoute.Remote,
    state: RemoteState,
    onIntent: (RemoteIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.git_remote_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(RemoteIntent.Close) },
        actions = {
            IconButton(
                onClick = { onIntent(if (state.error != null) RemoteIntent.Retry else RemoteIntent.Refresh) },
                enabled = !state.loading && state.running == null,
                modifier = Modifier.testTag(RemoteTags.REFRESH),
            ) {
                Icon(imageVector = RemoteIcons.Refresh, contentDescription = stringResource(R.string.git_remote_refresh))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            RemoteContent(state, onIntent)
        }
    }
    state.confirmPush?.let { PushConfirmation(it, onIntent) }
}

@Composable
private fun ColumnScope.RemoteContent(state: RemoteState, onIntent: (RemoteIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    if (state.loading) StillProgress(R.string.git_remote_loading, RemoteTags.LOADING)
    val error = state.error
    if (error != null) {
        ErrorBanner(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm).testTag(RemoteTags.ERROR),
            message = stringResource(error.messageRes(fallback = R.string.git_remote_error_read)),
            onRetry = if (error.canRetry) ({ onIntent(RemoteIntent.Retry) }) else null,
        )
        return
    }
    if (state.loading) return
    // Pinned above the scrolling part, so that Cancel is in reach wherever the content is scrolled.
    state.running?.let { RunningPanel(it, onIntent) }
    // A plain scrolling column: the screen holds a few parts, and a lazy list would keep an outcome
    // that appears above the first part out of view.
    val scroll = rememberScrollState()
    val result = state.result
    // A new outcome sits on top: scroll up to it, wherever the content was.
    LaunchedEffect(result) { if (result != null) scroll.animateScrollTo(0) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .testTag(RemoteTags.LIST)
            .verticalScroll(scroll),
    ) {
        result?.let { ResultPanel(it, onIntent) }
        state.branch?.let { BranchHeader(it) }
        HorizontalDivider()
        RemotePart(state, onIntent)
        HorizontalDivider()
        Actions(enabled = state.canStart, onIntent = onIntent)
    }
}

/**
 * Something is being read. A still line rather than a spinner: an endless animation keeps
 * Robolectric's `idle()` from returning. TalkBack hears the message, an indeterminate progress and a
 * polite live region, as for core/ui's `Loading`.
 */
@Composable
private fun StillProgress(@StringRes text: Int, tag: String, modifier: Modifier = Modifier) {
    val message = stringResource(text)
    Box(
        modifier = modifier
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

/** The operation that runs, and the button that cancels it. */
@Composable
private fun RunningPanel(op: RemoteOp, onIntent: (RemoteIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StillProgress(op.runningRes, RemoteTags.RUNNING, Modifier.weight(1f))
            OutlinedButton(
                onClick = { onIntent(RemoteIntent.Cancel) },
                modifier = Modifier.heightIn(min = spacing.touchTarget).testTag(RemoteTags.CANCEL),
            ) {
                Icon(imageVector = RemoteIcons.Close, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.git_remote_cancel))
            }
        }
    }
}

/**
 * How the last operation ended, in its status color with an icon, so that the color is not the only
 * sign; a failure adds its explanation. TalkBack reads it as one polite announcement.
 */
@Composable
private fun ResultPanel(result: RemoteResult, onIntent: (RemoteIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val (colors, icon) = when (result) {
        is RemoteResult.Succeeded -> PhopencodeTheme.status.success to RemoteIcons.Done
        is RemoteResult.Cancelled -> PhopencodeTheme.status.warning to RemoteIcons.Cancelled
        is RemoteResult.Failed -> PhopencodeTheme.status.danger to RemoteIcons.Failed
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm)
            .testTag(RemoteTags.RESULT)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        color = colors.container,
        contentColor = colors.onContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.md, top = spacing.xs, bottom = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.padding(top = spacing.md))
            Column(
                modifier = Modifier.weight(1f).padding(vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(text = stringResource(result.titleRes), style = MaterialTheme.typography.titleSmall)
                if (result is RemoteResult.Failed) {
                    Text(
                        text = stringResource(result.failure.messageRes(fallback = R.string.git_remote_error_other)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            IconButton(
                onClick = { onIntent(RemoteIntent.DismissResult) },
                modifier = Modifier.size(spacing.touchTarget),
            ) {
                Icon(imageVector = RemoteIcons.Close, contentDescription = stringResource(R.string.git_remote_result_dismiss))
            }
        }
    }
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
            modifier = Modifier.testTag(RemoteTags.BRANCH).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            val head = branch.head
            if (head == null) {
                val commit = branch.commit
                Text(
                    text = if (commit != null) {
                        stringResource(R.string.git_remote_detached, commit.take(SHORT_COMMIT))
                    } else {
                        stringResource(R.string.git_remote_detached_unknown)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Text(
                    text = stringResource(R.string.git_remote_branch),
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
                        text = stringResource(R.string.git_remote_no_commits),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        val upstream = branch.upstream
        when {
            upstream != null && branch.upstreamGone -> Text(
                text = stringResource(R.string.git_remote_upstream_gone, upstream),
                modifier = Modifier.testTag(RemoteTags.UPSTREAM_GONE),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            upstream != null -> Text(
                text = upstreamLine(upstream, branch.ahead, branch.behind, branch.aheadBehindKnown),
                modifier = Modifier.testTag(RemoteTags.UPSTREAM),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            !branch.isDetached -> Text(
                text = stringResource(R.string.git_remote_no_upstream),
                modifier = Modifier.testTag(RemoteTags.NO_UPSTREAM),
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
    val parts = mutableListOf(stringResource(R.string.git_remote_tracking, upstream))
    if (!countsKnown) return (parts + stringResource(R.string.git_remote_counts_unknown)).joinToString(SEPARATOR)
    if (ahead > 0) parts += pluralStringResource(R.plurals.git_remote_ahead, ahead, ahead)
    if (behind > 0) parts += pluralStringResource(R.plurals.git_remote_behind, behind, behind)
    if (ahead == 0 && behind == 0) parts += stringResource(R.string.git_remote_up_to_date)
    return parts.joinToString(SEPARATOR)
}

/** The remote in use: named when there is one, a choice when there are several, or that there is none. */
@Composable
private fun RemotePart(state: RemoteState, onIntent: (RemoteIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    when {
        state.remotes.isEmpty() -> EmptyState(
            modifier = Modifier.fillMaxWidth().testTag(RemoteTags.NO_REMOTES),
            title = stringResource(R.string.git_remote_no_remotes_title),
            message = stringResource(R.string.git_remote_no_remotes_message),
            icon = RemoteIcons.Fetch,
        )
        state.remotes.size == 1 -> Text(
            text = stringResource(R.string.git_remote_remote, state.selected ?: state.remotes.first().name),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md)
                .testTag(RemoteTags.REMOTE),
            style = MaterialTheme.typography.bodyLarge,
        )
        else -> Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            Text(
                text = stringResource(R.string.git_remote_choose),
                modifier = Modifier
                    .padding(start = spacing.lg, end = spacing.lg, top = spacing.sm)
                    .semantics { heading() },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
            )
            val enabled = state.running == null
            state.remotes.forEach { remote ->
                val selected = remote.name == state.selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = spacing.touchTarget)
                        .testTag(RemoteTags.remoteOption(remote.name))
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { if (!selected) onIntent(RemoteIntent.SelectRemote(remote.name)) },
                        )
                        .padding(horizontal = spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    RadioButton(selected = selected, onClick = null, enabled = enabled)
                    Text(
                        text = remote.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
    }
}

/** Fetch, pull and push, each with a line on what it does. Push only asks for its confirmation. */
@Composable
private fun Actions(enabled: Boolean, onIntent: (RemoteIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Action(RemoteIcons.Fetch, R.string.git_remote_fetch, R.string.git_remote_fetch_hint, RemoteTags.FETCH, enabled) {
            onIntent(RemoteIntent.Fetch)
        }
        Action(RemoteIcons.Pull, R.string.git_remote_pull, R.string.git_remote_pull_hint, RemoteTags.PULL, enabled) {
            onIntent(RemoteIntent.Pull)
        }
        Action(RemoteIcons.Push, R.string.git_remote_push, R.string.git_remote_push_hint, RemoteTags.PUSH, enabled) {
            onIntent(RemoteIntent.RequestPush)
        }
    }
}

@Composable
private fun Action(
    icon: ImageVector,
    @StringRes label: Int,
    @StringRes hint: Int,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.xxs)) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = PhopencodeTheme.spacing.touchTarget).testTag(tag),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(label))
        }
        Text(
            text = stringResource(hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Asks before a push: which branch goes to which remote, how many commits, whether it sets the
 * upstream, and that it is never forced. Only its confirm button pushes; cancel, back and a tap
 * outside dismiss it.
 */
@Composable
private fun PushConfirmation(plan: PushPlan, onIntent: (RemoteIntent) -> Unit) {
    val lines = buildList {
        add(
            if (plan.ahead > 0) {
                pluralStringResource(R.plurals.git_remote_push_confirm_message, plan.ahead, plan.ahead, plan.branch, plan.remote)
            } else {
                stringResource(R.string.git_remote_push_confirm_message_unknown, plan.branch, plan.remote)
            },
        )
        if (plan.setUpstream) add(stringResource(R.string.git_remote_push_confirm_set_upstream, plan.branch, plan.remote))
        add(stringResource(R.string.git_remote_push_confirm_no_force))
    }
    ConfirmDialog(
        title = stringResource(R.string.git_remote_push_confirm_title, plan.remote),
        message = lines.joinToString("\n\n"),
        onConfirm = { onIntent(RemoteIntent.ConfirmPush) },
        onDismiss = { onIntent(RemoteIntent.DismissPush) },
        modifier = Modifier.testTag(RemoteTags.CONFIRM),
        confirmLabel = stringResource(R.string.git_remote_push),
        dismissLabel = stringResource(R.string.git_remote_cancel),
    )
}
