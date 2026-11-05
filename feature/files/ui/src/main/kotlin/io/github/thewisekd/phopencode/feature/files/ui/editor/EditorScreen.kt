package io.github.thewisekd.phopencode.feature.files.ui.editor

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import io.github.thewisekd.phopencode.core.codeview.CodeLanguage
import io.github.thewisekd.phopencode.core.codeview.CodeView
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorBlock
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.ui.R
import io.github.thewisekd.phopencode.feature.files.ui.viewer.fileName

/** Tags on the editor's parts, for tests. */
internal object EditorTags {
    const val LOADING = "files:editor:loading"
    const val CODE = "files:editor:code"
    const val SAVE = "files:editor:save"
    const val SAVING = "files:editor:saving"
    const val UNSAVED = "files:editor:unsaved"
    const val BLOCKED = "files:editor:blocked"
    const val LOAD_ERROR = "files:editor:load-error"
    const val SAVE_ERROR = "files:editor:save-error"
    const val CONFLICT = "files:editor:conflict"
    const val CONFLICT_OVERWRITE = "files:editor:conflict:overwrite"
    const val CONFLICT_RELOAD = "files:editor:conflict:reload"
    const val CONFLICT_KEEP = "files:editor:conflict:keep"
    const val LEAVE = "files:editor:leave"
    const val LEAVE_DISCARD = "files:editor:leave:discard"
    const val LEAVE_KEEP = "files:editor:leave:keep"
}

/**
 * One text file, editable. Draws [state] and sends [onIntent]; it decides nothing: what may be
 * saved, and whether leaving asks first, are the presenter's.
 *
 * - The top bar names the file. Its back button and system back both send [EditorIntent.Back]; the
 *   presenter closes, or sets [EditorState.confirmingLeave] when the text is dirty. Save shows
 *   while the file is open for editing and is enabled by [EditorState.canSave].
 * - The text shows in core/codeview's code view in edit mode, with the language from the path's
 *   extension. Every change goes out as [EditorIntent.TextChanged] with the whole text, and the
 *   state's text is passed back, so a reload replaces what the editor holds.
 * - Under the top bar a still line says "Saving…" during a save, or that there are unsaved
 *   changes. A failed save shows its reason above the text, with retry while the text can be saved
 *   and a dismiss button; the text stays.
 * - A file that cannot be edited ([EditorState.blocked]) says why, with nothing to save or retry;
 *   a failed load says why and offers [EditorIntent.Retry].
 * - Two dialogs: the conflict, with overwrite, reload and keep editing; and the question before
 *   leaving with unsaved changes, which goes over a conflict. Plain dialogs, not bottom sheets: a
 *   `ModalBottomSheet` never comes to rest in Robolectric.
 *
 * Nothing animates without end: there is no spinner, and the code view's cursor does not blink.
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun EditorScreen(
    route: FilesRoute.Editor,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onIntent(EditorIntent.Back) }
    val editing = !state.loading && state.blocked == null && state.loadError == null
    PhopencodeScaffold(
        title = fileName(state.path),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(EditorIntent.Back) },
        actions = {
            if (editing) {
                TextButton(
                    onClick = { onIntent(EditorIntent.Save) },
                    enabled = state.canSave,
                    modifier = Modifier.testTag(EditorTags.SAVE),
                ) {
                    Text(stringResource(R.string.files_editor_save))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Body(state, editing, onIntent)
        }
    }
    when {
        state.confirmingLeave -> LeaveDialog(state.path, onIntent)
        state.conflict && !state.saving -> ConflictDialog(onIntent)
    }
}

@Composable
private fun ColumnScope.Body(state: EditorState, editing: Boolean, onIntent: (EditorIntent) -> Unit) {
    val blocked = state.blocked
    val loadError = state.loadError
    when {
        state.loading -> StillLine(R.string.files_editor_loading, EditorTags.LOADING)
        blocked != null -> Scrolling {
            EmptyState(
                modifier = Modifier.fillMaxWidth().testTag(EditorTags.BLOCKED),
                title = stringResource(R.string.files_editor_blocked_title),
                message = stringResource(blocked.messageRes()),
            )
        }
        loadError != null -> Scrolling {
            ErrorBanner(
                modifier = Modifier.testTag(EditorTags.LOAD_ERROR),
                message = stringResource(R.string.files_editor_load_error, stringResource(loadError.reasonRes())),
                onRetry = { onIntent(EditorIntent.Retry) },
            )
        }
        editing -> Editing(state, onIntent)
    }
}

@Composable
private fun ColumnScope.Editing(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    when {
        state.saving -> StillLine(R.string.files_editor_saving, EditorTags.SAVING)
        state.dirty -> StillLine(R.string.files_editor_unsaved, EditorTags.UNSAVED)
    }
    state.saveError?.let { failure ->
        ErrorBanner(
            modifier = Modifier
                .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm)
                .testTag(EditorTags.SAVE_ERROR),
            message = stringResource(R.string.files_editor_save_error, stringResource(failure.reasonRes())),
            onRetry = if (state.canSave) ({ onIntent(EditorIntent.Save) }) else null,
            onDismiss = { onIntent(EditorIntent.DismissError) },
        )
    }
    CodeView(
        text = state.text,
        language = CodeLanguage.fromFileName(state.path),
        modifier = Modifier.weight(1f).fillMaxWidth().testTag(EditorTags.CODE),
        readOnly = false,
        onTextChange = { onIntent(EditorIntent.TextChanged(it)) },
    )
}

/** A line of text that stands for progress; it never moves. */
@Composable
private fun StillLine(@StringRes text: Int, tag: String) {
    val spacing = PhopencodeTheme.spacing
    Text(
        text = stringResource(text),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(tag),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** The file changed on the server: overwrite it, take the server's version, or go on editing. */
@Composable
private fun ConflictDialog(onIntent: (EditorIntent) -> Unit) {
    ChoiceDialog(
        tag = EditorTags.CONFLICT,
        title = stringResource(R.string.files_editor_conflict_title),
        message = stringResource(R.string.files_editor_conflict_message),
        onDismiss = { onIntent(EditorIntent.DismissConflict) },
        choices = listOf(
            Choice(R.string.files_editor_overwrite, EditorTags.CONFLICT_OVERWRITE) { onIntent(EditorIntent.Overwrite) },
            Choice(R.string.files_editor_reload, EditorTags.CONFLICT_RELOAD) { onIntent(EditorIntent.Reload) },
            Choice(R.string.files_editor_keep_editing, EditorTags.CONFLICT_KEEP) { onIntent(EditorIntent.DismissConflict) },
        ),
    )
}

/** Leaving with unsaved changes: discard them and leave, or stay. Back and a tap outside stay. */
@Composable
private fun LeaveDialog(path: String, onIntent: (EditorIntent) -> Unit) {
    ChoiceDialog(
        tag = EditorTags.LEAVE,
        title = stringResource(R.string.files_editor_leave_title),
        message = stringResource(R.string.files_editor_leave_message, fileName(path)),
        onDismiss = { onIntent(EditorIntent.CancelLeave) },
        choices = listOf(
            Choice(R.string.files_editor_discard, EditorTags.LEAVE_DISCARD) { onIntent(EditorIntent.ConfirmLeave) },
            Choice(R.string.files_editor_keep_editing, EditorTags.LEAVE_KEEP) { onIntent(EditorIntent.CancelLeave) },
        ),
    )
}

private class Choice(@StringRes val label: Int, val tag: String, val onClick: () -> Unit)

/**
 * A question with its answers stacked at the end, since three do not fit side by side in every
 * language. It scrolls when the screen is short; back and a tap outside are [onDismiss].
 */
@Composable
private fun ChoiceDialog(tag: String, title: String, message: String, onDismiss: () -> Unit, choices: List<Choice>) {
    val spacing = PhopencodeTheme.spacing
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.testTag(tag),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = spacing.xs,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(modifier = Modifier.align(Alignment.End), horizontalAlignment = Alignment.End) {
                    choices.forEach { choice ->
                        TextButton(onClick = choice.onClick, modifier = Modifier.testTag(choice.tag)) {
                            Text(stringResource(choice.label))
                        }
                    }
                }
            }
        }
    }
}

/** A message that scrolls if the screen is too short for it, as on a small phone in landscape. */
@Composable
private fun ColumnScope.Scrolling(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PhopencodeTheme.spacing.lg),
        content = content,
    )
}

@StringRes
private fun EditorBlock.messageRes(): Int = when (this) {
    EditorBlock.Binary -> R.string.files_editor_blocked_binary
    EditorBlock.TooLarge -> R.string.files_editor_blocked_too_large
    EditorBlock.Malformed -> R.string.files_editor_blocked_malformed
}

/** Why the file could not be read or saved, as the end of a sentence that says which. */
@StringRes
private fun FilesFailure.reasonRes(): Int = when (this) {
    FilesFailure.NotConnected -> R.string.files_editor_reason_not_connected
    FilesFailure.Unreachable -> R.string.files_editor_reason_unreachable
    FilesFailure.Unauthorized -> R.string.files_editor_reason_unauthorized
    FilesFailure.NotFound -> R.string.files_editor_reason_not_found
    FilesFailure.PermissionDenied -> R.string.files_editor_reason_permission_denied
    FilesFailure.AlreadyExists -> R.string.files_editor_reason_already_exists
    FilesFailure.NotEmpty -> R.string.files_editor_reason_not_empty
    FilesFailure.PathRejected -> R.string.files_editor_reason_path_rejected
    FilesFailure.TooLarge -> R.string.files_editor_reason_too_large
    FilesFailure.ToolMissing -> R.string.files_editor_reason_tool_missing
    FilesFailure.Rejected -> R.string.files_editor_reason_rejected
    FilesFailure.ServerFailed -> R.string.files_editor_reason_server_failed
    FilesFailure.Unsupported -> R.string.files_editor_reason_unsupported
    FilesFailure.Unknown -> R.string.files_editor_reason_unknown
}
