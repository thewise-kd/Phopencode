package io.github.thewisekd.phopencode.feature.git.ui.commit

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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.component.ConfirmDialog
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.core.ui.theme.StatusColor
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitError
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitMessageProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.ui.R

/** Between the parts of a line; not words, so the same in every language. */
private const val SEPARATOR = " · "

/** Lines the message field shows before it scrolls; it starts at [MESSAGE_MIN_LINES]. */
private const val MESSAGE_MIN_LINES = 3
private const val MESSAGE_MAX_LINES = 8

/** How high the hook's output grows before it scrolls inside its box. */
private val HOOK_OUTPUT_MAX_HEIGHT = 160.dp

/**
 * Writing a commit (P9.11). Draws [state] and sends [onIntent]; it decides nothing itself, and logs
 * nothing: the message, a path and a hook's output never leave the screen.
 *
 * - **Top bar:** back closes the screen; refresh reads the files again, keeping the message.
 * - **Status:** a still line while the files load or an operation runs.
 * - **Errors:** a rejecting hook shows its output in a monospace box that scrolls both ways; any
 *   other failure is worded, falling back to what failed. Both can be dismissed.
 * - **List:** a [LazyColumn] with the multi-line message field, its blank problem, the commit
 *   button (enabled as [CommitState.canCommit] says), then the staged and the unstaged files. A row
 *   opens its diff on a tap; its buttons stage or unstage it and, for an unstaged tracked change
 *   ([CommitState.canDiscard]), ask to discard it. A clean tree says there is nothing to commit.
 * - **Discard:** while [CommitState.confirmDiscard] is set, a dialog names the file and says the
 *   changes are lost for good; it confirms or cancels.
 *
 * Nothing on it runs an endless animation: Robolectric's `idle()` in the app's tests would never
 * return. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun CommitScreen(
    route: GitRoute.Commit,
    state: CommitState,
    onIntent: (CommitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.git_commit_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(CommitIntent.Close) },
        actions = {
            IconButton(
                onClick = { onIntent(CommitIntent.Refresh) },
                enabled = !state.loading && state.busy == null,
                modifier = Modifier.testTag(CommitTags.REFRESH),
            ) {
                Icon(imageVector = CommitIcons.Refresh, contentDescription = stringResource(R.string.git_commit_refresh))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            CommitContent(state, onIntent)
        }
    }
    state.confirmDiscard?.let { change ->
        ConfirmDialog(
            title = stringResource(R.string.git_commit_discard_title),
            message = stringResource(R.string.git_commit_discard_message, change.path),
            onConfirm = { onIntent(CommitIntent.ConfirmDiscard) },
            onDismiss = { onIntent(CommitIntent.CancelDiscard) },
            modifier = Modifier.testTag(CommitTags.DISCARD_DIALOG),
            confirmLabel = stringResource(R.string.git_commit_discard_confirm),
            destructive = true,
        )
    }
}

@Composable
private fun ColumnScope.CommitContent(state: CommitState, onIntent: (CommitIntent) -> Unit) {
    val busy = state.busy
    when {
        state.loading -> StillProgress(R.string.git_commit_loading, CommitTags.LOADING)
        busy != null -> StillProgress(busy.busyRes, CommitTags.BUSY)
    }
    state.error?.let { ErrorSection(it, onIntent) }
    HorizontalDivider()
    val idle = !state.loading && busy == null
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).testTag(CommitTags.LIST)) {
        item(key = "message", contentType = "message") {
            MessageField(state.message, state.messageProblem, enabled = busy != CommitOp.Commit, onIntent)
        }
        item(key = "commit", contentType = "commit") { CommitButton(state.canCommit, onIntent) }
        val hasFiles = state.staged.isNotEmpty() || state.unstaged.isNotEmpty()
        when {
            hasFiles -> {
                files(state.staged, staged = true, idle, onIntent)
                if (state.unstaged.isNotEmpty()) files(state.unstaged, staged = false, idle, onIntent)
            }
            !state.loading -> item(key = "empty", contentType = "empty") {
                EmptyState(
                    modifier = Modifier.fillMaxWidth().testTag(CommitTags.EMPTY),
                    title = stringResource(R.string.git_commit_empty_title),
                    message = stringResource(R.string.git_commit_empty_message),
                    icon = CommitIcons.Done,
                )
            }
        }
    }
}

/**
 * The files are being read or an operation runs. A still line rather than a spinner: an endless
 * animation keeps Robolectric's `idle()` from returning. TalkBack hears the message, an
 * indeterminate progress and a polite live region, as for core/ui's `Loading`.
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

@get:StringRes
private val CommitOp.busyRes: Int
    get() = when (this) {
        CommitOp.Stage -> R.string.git_commit_busy_stage
        CommitOp.Unstage -> R.string.git_commit_busy_unstage
        CommitOp.Discard -> R.string.git_commit_busy_discard
        CommitOp.Commit -> R.string.git_commit_busy_commit
    }

@Composable
private fun ErrorSection(error: CommitError, onIntent: (CommitIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val dismiss = { onIntent(CommitIntent.DismissError) }
    when (error) {
        is CommitError.HookRejected -> HookError(error.output, dismiss)
        CommitError.NothingStaged -> ErrorBanner(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm).testTag(CommitTags.ERROR),
            message = stringResource(R.string.git_commit_error_nothing_staged),
            onDismiss = dismiss,
        )
        is CommitError.Failed -> ErrorBanner(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm).testTag(CommitTags.ERROR),
            message = stringResource(error.failure.messageRes(fallback = error.op.fallbackRes)),
            onDismiss = dismiss,
        )
    }
}

/** The hook refused the commit: what it printed, in a monospace box that scrolls both ways. */
@Composable
private fun HookError(output: String, onDismiss: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CommitTags.HOOK_ERROR)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        ErrorBanner(message = stringResource(R.string.git_commit_hook_rejected), onDismiss = onDismiss)
        Text(
            text = stringResource(R.string.git_commit_hook_output),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Box(
                modifier = Modifier
                    .heightIn(max = HOOK_OUTPUT_MAX_HEIGHT)
                    .testTag(CommitTags.HOOK_OUTPUT)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(spacing.sm),
            ) {
                Text(
                    text = output.ifEmpty { stringResource(R.string.git_commit_hook_no_output) },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * The failures the commit screen can meet have their own words; the others, which belong to pushes,
 * pulls and switches, and a git error whose exit code says nothing to a user, take [fallback], the
 * words for what failed.
 */
@StringRes
private fun GitFailure.messageRes(@StringRes fallback: Int): Int = when (this) {
    GitFailure.NotConnected -> R.string.git_commit_error_not_connected
    GitFailure.Unreachable -> R.string.git_commit_error_unreachable
    GitFailure.TimedOut -> R.string.git_commit_error_timed_out
    GitFailure.Unauthorized -> R.string.git_commit_error_unauthorized
    GitFailure.OutputTooLarge -> R.string.git_commit_error_output_too_large
    GitFailure.NotRepository -> R.string.git_commit_error_not_repository
    GitFailure.GitMissing -> R.string.git_commit_error_git_missing
    GitFailure.Rejected -> R.string.git_commit_error_rejected
    GitFailure.NotFound -> R.string.git_commit_error_not_found
    GitFailure.Conflict -> R.string.git_commit_error_conflict
    GitFailure.CredentialRequired,
    GitFailure.NonFastForward,
    GitFailure.Diverged,
    GitFailure.WouldOverwrite,
    is GitFailure.GitError,
    GitFailure.Unknown,
    -> fallback
}

@get:StringRes
private val CommitOp.fallbackRes: Int
    get() = when (this) {
        CommitOp.Stage -> R.string.git_commit_error_stage
        CommitOp.Unstage -> R.string.git_commit_error_unstage
        CommitOp.Discard -> R.string.git_commit_error_discard
        CommitOp.Commit -> R.string.git_commit_error_commit
    }

@Composable
private fun MessageField(
    message: String,
    problem: CommitMessageProblem?,
    enabled: Boolean,
    onIntent: (CommitIntent) -> Unit,
) {
    val spacing = PhopencodeTheme.spacing
    OutlinedTextField(
        value = message,
        onValueChange = { onIntent(CommitIntent.EditMessage(it)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.lg, end = spacing.lg, top = spacing.sm)
            .testTag(CommitTags.MESSAGE),
        enabled = enabled,
        label = { Text(stringResource(R.string.git_commit_message_label)) },
        supportingText = problem?.let {
            { Text(text = stringResource(it.textRes), modifier = Modifier.testTag(CommitTags.MESSAGE_PROBLEM)) }
        },
        isError = problem != null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        minLines = MESSAGE_MIN_LINES,
        maxLines = MESSAGE_MAX_LINES,
    )
}

@get:StringRes
private val CommitMessageProblem.textRes: Int
    get() = when (this) {
        CommitMessageProblem.Blank -> R.string.git_commit_message_blank
    }

@Composable
private fun CommitButton(enabled: Boolean, onIntent: (CommitIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Button(
        onClick = { onIntent(CommitIntent.Commit) },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm)
            .heightIn(min = spacing.touchTarget)
            .testTag(CommitTags.COMMIT),
    ) {
        Text(stringResource(R.string.git_commit_button))
    }
}

/**
 * The header and rows of the staged or the unstaged files. Staged files always get a header, with a
 * hint when there are none. Keys hold the list: a file can be in both.
 */
private fun LazyListScope.files(
    changes: List<GitFileChange>,
    staged: Boolean,
    idle: Boolean,
    onIntent: (CommitIntent) -> Unit,
) {
    val group = if (staged) "staged" else "unstaged"
    item(key = "header:$group", contentType = "header") {
        GroupHeader(staged, changes.size, enabled = idle && changes.isNotEmpty(), onIntent)
    }
    if (changes.isEmpty()) {
        item(key = "hint:$group", contentType = "hint") { NothingStagedHint() }
        return
    }
    items(changes, key = { "$group:${it.path}" }, contentType = { "row" }) { change ->
        ChangeRow(change, staged, enabled = idle, onIntent)
    }
}

@Composable
private fun GroupHeader(staged: Boolean, count: Int, enabled: Boolean, onIntent: (CommitIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .testTag(CommitTags.header(staged))
            .semantics(mergeDescendants = true) { heading() }
            .padding(start = spacing.lg, end = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(if (staged) R.string.git_commit_group_staged else R.string.git_commit_group_unstaged),
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
        if (count > 0) {
            if (staged) {
                TextButton(
                    onClick = { onIntent(CommitIntent.UnstageAll) },
                    enabled = enabled,
                    modifier = Modifier.testTag(CommitTags.UNSTAGE_ALL),
                ) { Text(stringResource(R.string.git_commit_unstage_all)) }
            } else {
                TextButton(
                    onClick = { onIntent(CommitIntent.StageAll) },
                    enabled = enabled,
                    modifier = Modifier.testTag(CommitTags.STAGE_ALL),
                ) { Text(stringResource(R.string.git_commit_stage_all)) }
            }
        }
    }
}

@Composable
private fun NothingStagedHint() {
    val spacing = PhopencodeTheme.spacing
    Text(
        text = stringResource(R.string.git_commit_nothing_staged_hint),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CommitTags.NOTHING_STAGED)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * One changed file: a tap opens its diff, its buttons stage or unstage it and, where the contract
 * allows it, ask to discard its unstaged changes. While anything runs, all wait.
 */
@Composable
private fun ChangeRow(change: GitFileChange, staged: Boolean, enabled: Boolean, onIntent: (CommitIntent) -> Unit) {
    val name = fileName(change.path)
    val details = listOfNotNull(
        stringResource(change.kind.labelRes),
        folderOf(change.path),
        change.originalPath?.let { stringResource(R.string.git_commit_renamed_from, it) },
    ).joinToString(SEPARATOR)
    val touchTarget = PhopencodeTheme.spacing.touchTarget
    ListItem(
        modifier = Modifier
            .testTag(CommitTags.row(staged))
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.git_commit_open_diff),
                role = Role.Button,
                onClick = { onIntent(CommitIntent.OpenDiff(change, staged = staged)) },
            ),
        leadingContent = { KindMark(change.kind) },
        headlineContent = { Text(text = name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = { Text(text = details, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!staged && CommitState.canDiscard(change)) {
                    IconButton(
                        onClick = { onIntent(CommitIntent.RequestDiscard(change)) },
                        enabled = enabled,
                        modifier = Modifier.size(touchTarget).testTag(CommitTags.DISCARD),
                    ) {
                        Icon(
                            imageVector = CommitIcons.Discard,
                            contentDescription = stringResource(R.string.git_commit_discard, name),
                        )
                    }
                }
                val (icon, description, intent) = if (staged) {
                    Triple(CommitIcons.Unstage, R.string.git_commit_unstage, CommitIntent.Unstage(change))
                } else {
                    Triple(CommitIcons.Stage, R.string.git_commit_stage, CommitIntent.Stage(change))
                }
                IconButton(
                    onClick = { onIntent(intent) },
                    enabled = enabled,
                    modifier = Modifier.size(touchTarget),
                ) {
                    Icon(imageVector = icon, contentDescription = stringResource(description, name))
                }
            }
        },
    )
}

/**
 * The letter git prints for the change, in its status color, in a box that grows with large text.
 * Decorative: the row's text names the kind.
 */
@Composable
private fun KindMark(kind: GitChangeKind) {
    val colors = kind.colors
    Box(
        modifier = Modifier
            .sizeIn(minWidth = PhopencodeTheme.spacing.xl, minHeight = PhopencodeTheme.spacing.xl)
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
        GitChangeKind.Added -> R.string.git_commit_kind_added
        GitChangeKind.Modified -> R.string.git_commit_kind_modified
        GitChangeKind.Deleted -> R.string.git_commit_kind_deleted
        GitChangeKind.Renamed -> R.string.git_commit_kind_renamed
        GitChangeKind.Copied -> R.string.git_commit_kind_copied
        GitChangeKind.TypeChanged -> R.string.git_commit_kind_type_changed
        GitChangeKind.Untracked -> R.string.git_commit_kind_untracked
        GitChangeKind.Unmerged -> R.string.git_commit_kind_unmerged
    }

@get:StringRes
private val GitChangeKind.markRes: Int
    get() = when (this) {
        GitChangeKind.Added -> R.string.git_commit_mark_added
        GitChangeKind.Modified -> R.string.git_commit_mark_modified
        GitChangeKind.Deleted -> R.string.git_commit_mark_deleted
        GitChangeKind.Renamed -> R.string.git_commit_mark_renamed
        GitChangeKind.Copied -> R.string.git_commit_mark_copied
        GitChangeKind.TypeChanged -> R.string.git_commit_mark_type_changed
        GitChangeKind.Untracked -> R.string.git_commit_mark_untracked
        GitChangeKind.Unmerged -> R.string.git_commit_mark_unmerged
    }
