package io.github.thewisekd.phopencode.feature.chat.ui.revert

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.StagedRevert
import io.github.thewisekd.phopencode.feature.chat.ui.R
import java.util.Locale


/**
 * The turn diff page: what one turn changed, and undoing it.
 *
 * From the top: a failed load with its retry ([RevertIntent.Retry]), a failed step with its
 * dismiss ([RevertIntent.DismissError]), the revert, a summary such as "3 files changed · +12 −4",
 * then the files as a lazy list. A file shows its path, status and line counts; a tap opens its
 * diff in monospace, scrolled sideways rather than wrapped, and cut after [DIFF_MAX_LINES] lines or
 * [DIFF_MAX_CHARS] characters with a note of what is left, so no item grows without bound.
 *
 * Reverting never happens on one tap. The revert button sends [RevertIntent.Revert], which only
 * stages it; once [RevertState.staged] is this turn's, the page asks for confirmation, whose
 * buttons send [RevertIntent.Confirm] and [RevertIntent.Cancel]. A revert staged for another turn
 * can only be cancelled here. Each button follows the state's `can…` flags, and a step on its way
 * is said in words.
 *
 * Nothing animates: loading and a step on its way are lines of text, not spinners. Paths and diffs
 * are drawn, never logged. The root keeps the `placeholder:<route>` tag the app's navigation test
 * looks for.
 */
@Composable
fun TurnDiffScreen(
    route: ChatRoute.TurnDiff,
    state: RevertState,
    onIntent: (RevertIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    // Which files show their diff, by place in the list; kept across rotation.
    var open by rememberSaveable { mutableStateOf(listOf<Int>()) }
    PhopencodeScaffold(
        title = stringResource(R.string.chat_revert_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(RevertIntent.Close) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag(RevertTags.LIST),
            contentPadding = PaddingValues(vertical = spacing.sm),
        ) {
            val loadError = state.loadError
            if (loadError != null) {
                item(contentType = "error") {
                    ErrorBanner(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
                        message = stringResource(R.string.chat_revert_load_error, stringResource(loadError.reasonRes())),
                        onRetry = { onIntent(RevertIntent.Retry) },
                    )
                }
            }
            val error = state.error
            if (error != null) {
                item(contentType = "error") {
                    ErrorBanner(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
                        message = stringResource(error.action.errorRes(), stringResource(error.reason.reasonRes())),
                        onDismiss = { onIntent(RevertIntent.DismissError) },
                    )
                }
            }
            item(contentType = "revert") {
                RevertPanel(state, onIntent, Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm))
            }
            when {
                state.files.isNotEmpty() -> {
                    item(contentType = "summary") { Summary(state.files) }
                    itemsIndexed(state.files, contentType = { _, _ -> "file" }) { index, file ->
                        FileItem(
                            index = index,
                            file = file,
                            expanded = index in open,
                            onToggle = { open = if (index in open) open - index else open + index },
                        )
                    }
                }
                state.loading -> item(contentType = "loading") {
                    Text(
                        text = stringResource(R.string.chat_revert_loading),
                        modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // A failed load says so above; claiming no changes would be wrong.
                loadError != null -> Unit
                else -> item(contentType = "empty") {
                    EmptyState(
                        modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                        title = stringResource(R.string.chat_revert_empty_title),
                        message = stringResource(R.string.chat_revert_empty_message),
                    )
                }
            }
        }
    }
}

/** The revert: its button before anything is staged, else the confirmation or the other turn's notice. */
@Composable
private fun RevertPanel(state: RevertState, onIntent: (RevertIntent) -> Unit, modifier: Modifier) {
    val spacing = PhopencodeTheme.spacing
    val staged = state.staged
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        when {
            staged == null -> {
                Text(
                    text = stringResource(R.string.chat_revert_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { onIntent(RevertIntent.Revert) },
                    enabled = state.canRevert,
                    modifier = Modifier.heightIn(min = PhopencodeTheme.spacing.touchTarget).testTag(RevertTags.REVERT),
                ) {
                    Text(stringResource(R.string.chat_revert_revert))
                }
            }
            staged.thisTurn -> Confirmation(staged, state, onIntent)
            else -> OtherTurn(state, onIntent)
        }
        val pending = state.pending
        if (pending != null) {
            Text(
                text = stringResource(pending.pendingRes()),
                modifier = Modifier.testTag(RevertTags.PENDING),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Asks before this turn's staged revert is made final: what it removes, then revert and cancel. */
@Composable
private fun Confirmation(staged: StagedRevert, state: RevertState, onIntent: (RevertIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val tone = PhopencodeTheme.status.warning
    val locale = revertLocale()
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(RevertTags.CONFIRMATION),
        color = tone.container,
        contentColor = tone.onContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = stringResource(R.string.chat_revert_confirm_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            val count = staged.files.size
            Text(
                text = if (count == 0) {
                    stringResource(R.string.chat_revert_confirm_message_none)
                } else {
                    pluralStringResource(R.plurals.chat_revert_confirm_message, count, formatCount(count.toLong(), locale))
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                TextButton(
                    onClick = { onIntent(RevertIntent.Cancel) },
                    enabled = state.canCancel,
                    modifier = Modifier.heightIn(min = PhopencodeTheme.spacing.touchTarget).testTag(RevertTags.CANCEL),
                    colors = ButtonDefaults.textButtonColors(contentColor = tone.onContainer),
                ) {
                    Text(stringResource(R.string.chat_revert_cancel))
                }
                Button(
                    onClick = { onIntent(RevertIntent.Confirm) },
                    enabled = state.canConfirm,
                    modifier = Modifier.heightIn(min = PhopencodeTheme.spacing.touchTarget).testTag(RevertTags.CONFIRM),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.chat_revert_revert))
                }
            }
        }
    }
}

/** Another turn's revert is staged: this one cannot be staged until that is cancelled. */
@Composable
private fun OtherTurn(state: RevertState, onIntent: (RevertIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().testTag(RevertTags.OTHER_TURN),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.chat_revert_other_turn),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { onIntent(RevertIntent.Cancel) },
            enabled = state.canCancel,
            modifier = Modifier.heightIn(min = PhopencodeTheme.spacing.touchTarget).testTag(RevertTags.CANCEL),
        ) {
            Text(stringResource(R.string.chat_revert_cancel))
        }
    }
}

/** The number of files and, when known, the lines they add and remove. */
@Composable
private fun Summary(files: List<FileDiff>) {
    val locale = revertLocale()
    val count = files.size
    val changed = pluralStringResource(R.plurals.chat_revert_files_changed, count, formatCount(count.toLong(), locale))
    val lines = countsText(totalAdditions(files), totalDeletions(files), locale)
    Text(
        text = if (lines == null) changed else stringResource(R.string.chat_revert_join, changed, lines),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RevertTags.SUMMARY)
            .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A file: its row, which opens and closes its diff, then the diff when open. */
@Composable
private fun FileItem(index: Int, file: FileDiff, expanded: Boolean, onToggle: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val locale = revertLocale()
    val stateText = stringResource(if (expanded) R.string.chat_revert_expanded else R.string.chat_revert_collapsed)
    val clickLabel = stringResource(if (expanded) R.string.chat_revert_hide_diff else R.string.chat_revert_show_diff)
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PhopencodeTheme.spacing.touchTarget)
                .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onToggle)
                .testTag(RevertTags.file(index))
                .semantics(mergeDescendants = true) { stateDescription = stateText }
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(
                text = file.file.path,
                style = PhopencodeTheme.codeTypography.body,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = statusText(file.file.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Counts(file, locale)
            }
        }
        if (expanded) Diff(index, file.patch)
        HorizontalDivider()
    }
}

/** A file's added and removed lines, each in its tone; left out when the server gave none. */
@Composable
private fun Counts(file: FileDiff, locale: Locale) {
    val additions = file.file.additions
    val deletions = file.file.deletions
    if (additions == null && deletions == null) return
    val tones = PhopencodeTheme.status
    if (additions != null) {
        Text(
            text = stringResource(R.string.chat_revert_additions, formatCount(additions, locale)),
            style = MaterialTheme.typography.labelMedium,
            color = tones.success.color,
        )
    }
    if (deletions != null) {
        Text(
            text = stringResource(R.string.chat_revert_deletions, formatCount(deletions, locale)),
            style = MaterialTheme.typography.labelMedium,
            color = tones.danger.color,
        )
    }
}

/**
 * A file's diff, cut to a bounded size, in monospace and scrolled sideways so its columns keep;
 * or a note when the server gave none.
 */
@Composable
private fun Diff(index: Int, patch: String?) {
    val spacing = PhopencodeTheme.spacing
    val modifier = Modifier.fillMaxWidth().padding(start = spacing.lg, end = spacing.lg, bottom = spacing.sm)
    if (patch == null) {
        Text(
            text = stringResource(R.string.chat_revert_no_patch),
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val shown = shortenDiff(patch)
    val colors = diffColors()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.small,
        ) {
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(spacing.sm)) {
                Text(
                    text = highlightDiff(shown.text, colors),
                    modifier = Modifier.testTag(RevertTags.diff(index)),
                    style = PhopencodeTheme.codeTypography.small,
                    softWrap = false,
                )
            }
        }
        moreText(shown, revertLocale())?.let {
            Text(
                text = it,
                modifier = Modifier.testTag(RevertTags.more(index)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
