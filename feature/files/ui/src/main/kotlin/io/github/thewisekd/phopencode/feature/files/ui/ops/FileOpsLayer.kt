package io.github.thewisekd.phopencode.feature.files.ui.ops

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileAction
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOp
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpError
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsDialog
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsRequest
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.NameProblem
import io.github.thewisekd.phopencode.feature.files.ui.R

/**
 * The file operations, drawn over the tree. Draws [state] and sends [onIntent]; it decides nothing:
 * which actions an entry offers, whether a name is acceptable and when an operation may run are
 * the presenter's.
 *
 * - The menu of an entry ([FileOpsState.menu]) names it and lists [FileOpsState.menuActions] in
 *   their order; each sends [FileOpsIntent.Choose].
 * - Rename and new folder ask for a name. Each change goes out as [FileOpsIntent.NameChanged] and
 *   the state's name is drawn back; a [NameProblem] shows under the field. The submit button, and
 *   the keyboard's done key, follow [FileOpsState.canSubmit].
 * - Deleting asks first, naming the entry; for a folder the question says that everything in it
 *   goes too. The confirm button is in the error color.
 * - While an operation runs ([FileOpsState.busy]) its dialog says so in a still line and takes no
 *   input; cancel stays, as the contract allows closing the dialog without stopping the operation.
 * - A failure shows in the open dialog or menu, or, with nothing open, in a dialog of its own;
 *   each is dismissed with [FileOpsIntent.DismissError].
 *
 * Plain dialogs, not bottom sheets: a `ModalBottomSheet` never comes to rest in Robolectric. A
 * dropdown would need an anchor in the tree's row, which this layer, drawn over the whole tree,
 * does not have. Nothing animates without end: there is no spinner, and the name field is not
 * focused on its own, so its cursor does not blink until the user taps it. Back and a tap outside
 * send [FileOpsIntent.Cancel], or [FileOpsIntent.DismissError] for the error on its own.
 */
@Composable
fun FileOpsLayer(
    state: FileOpsState,
    onIntent: (FileOpsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
    val menu = state.menu
    val error = state.error
    when (val dialog = state.dialog) {
        is FileOpsDialog.Rename -> NameDialog(
            title = R.string.files_ops_rename_title,
            label = R.string.files_ops_rename_label,
            submit = R.string.files_ops_rename,
            name = dialog.name,
            problem = dialog.problem,
            state = state,
            onIntent = onIntent,
        )
        is FileOpsDialog.NewFolder -> NameDialog(
            title = R.string.files_ops_new_folder_title,
            label = R.string.files_ops_new_folder_label,
            submit = R.string.files_ops_create,
            name = dialog.name,
            problem = dialog.problem,
            state = state,
            onIntent = onIntent,
        )
        is FileOpsDialog.ConfirmDelete -> DeleteDialog(dialog.entry, state, onIntent)
        null -> when {
            menu != null -> MenuDialog(menu, state, onIntent)
            error != null -> ErrorDialog(error, onIntent)
        }
    }
}

/** The actions for one entry, under its name. */
@Composable
private fun MenuDialog(menu: FileOpsRequest.Actions, state: FileOpsState, onIntent: (FileOpsIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    OpsDialog(tag = FileOpsTags.MENU, onDismiss = { onIntent(FileOpsIntent.Cancel) }) {
        Text(
            text = menu.entry.name,
            modifier = Modifier
                .padding(horizontal = spacing.md)
                .semantics { heading() }
                .testTag(FileOpsTags.MENU_TITLE),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
        )
        ErrorPart(state.error, onIntent)
        Column {
            state.menuActions.forEach { action ->
                TextButton(
                    onClick = { onIntent(FileOpsIntent.Choose(action)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = spacing.touchTarget)
                        .testTag(action.tag),
                    colors = if (action == FileAction.Delete) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text(text = stringResource(action.labelRes), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Buttons {
            TextButton(onClick = { onIntent(FileOpsIntent.Cancel) }, modifier = Modifier.testTag(FileOpsTags.MENU_CANCEL)) {
                Text(stringResource(R.string.files_ops_cancel))
            }
        }
    }
}

/** Rename or new folder: one name field with its problem, cancel and submit. */
@Composable
private fun NameDialog(
    @StringRes title: Int,
    @StringRes label: Int,
    @StringRes submit: Int,
    name: String,
    problem: NameProblem?,
    state: FileOpsState,
    onIntent: (FileOpsIntent) -> Unit,
) {
    val idle = state.busy == null
    OpsDialog(tag = FileOpsTags.NAME_DIALOG, onDismiss = { onIntent(FileOpsIntent.Cancel) }) {
        Title(stringResource(title))
        OutlinedTextField(
            value = name,
            onValueChange = { onIntent(FileOpsIntent.NameChanged(it)) },
            modifier = Modifier.fillMaxWidth().testTag(FileOpsTags.NAME_FIELD),
            enabled = idle,
            label = { Text(stringResource(label)) },
            isError = problem != null,
            supportingText = problem?.let {
                { Text(text = stringResource(it.messageRes), modifier = Modifier.testTag(FileOpsTags.NAME_PROBLEM)) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) onIntent(FileOpsIntent.Submit) }),
        )
        BusyLine(state.busy)
        ErrorPart(state.error, onIntent)
        Buttons {
            CancelButton(onIntent)
            TextButton(
                onClick = { onIntent(FileOpsIntent.Submit) },
                enabled = state.canSubmit,
                modifier = Modifier.testTag(FileOpsTags.SUBMIT),
            ) {
                Text(stringResource(submit))
            }
        }
    }
}

/** The question before deleting; a folder's says its content goes with it. */
@Composable
private fun DeleteDialog(entry: FileEntry, state: FileOpsState, onIntent: (FileOpsIntent) -> Unit) {
    val folder = entry.type == FileEntryType.Directory
    OpsDialog(tag = FileOpsTags.DELETE_DIALOG, onDismiss = { onIntent(FileOpsIntent.Cancel) }) {
        Title(stringResource(if (folder) R.string.files_ops_delete_folder_title else R.string.files_ops_delete_file_title))
        Text(
            text = stringResource(
                if (folder) R.string.files_ops_delete_folder_message else R.string.files_ops_delete_file_message,
                entry.name,
            ),
            modifier = Modifier.testTag(FileOpsTags.DELETE_MESSAGE),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        BusyLine(state.busy)
        ErrorPart(state.error, onIntent)
        Buttons {
            CancelButton(onIntent)
            Button(
                onClick = { onIntent(FileOpsIntent.ConfirmDelete) },
                enabled = state.canSubmit,
                modifier = Modifier.testTag(FileOpsTags.DELETE_CONFIRM),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.files_ops_delete))
            }
        }
    }
}

/** A failure when nothing else is open, as after the dialog was closed while the operation ran. */
@Composable
private fun ErrorDialog(error: FileOpError, onIntent: (FileOpsIntent) -> Unit) {
    OpsDialog(tag = FileOpsTags.ERROR_DIALOG, onDismiss = { onIntent(FileOpsIntent.DismissError) }) {
        Text(
            text = errorMessage(error),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
        )
        Buttons {
            TextButton(
                onClick = { onIntent(FileOpsIntent.DismissError) },
                modifier = Modifier.testTag(FileOpsTags.ERROR_OK),
            ) {
                Text(stringResource(R.string.files_ops_ok))
            }
        }
    }
}

/** A dialog whose content scrolls when the screen is short, as on a small phone in landscape. */
@Composable
private fun OpsDialog(tag: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
                content = content,
            )
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() }.testTag(FileOpsTags.TITLE),
        style = MaterialTheme.typography.headlineSmall,
    )
}

@Composable
private fun ColumnScope.Buttons(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.align(Alignment.End),
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun CancelButton(onIntent: (FileOpsIntent) -> Unit) {
    TextButton(onClick = { onIntent(FileOpsIntent.Cancel) }, modifier = Modifier.testTag(FileOpsTags.CANCEL)) {
        Text(stringResource(R.string.files_ops_cancel))
    }
}

/** A line of text that stands for progress; it never moves. */
@Composable
private fun BusyLine(busy: FileOp?) {
    if (busy == null) return
    Text(
        text = stringResource(busy.busyRes),
        modifier = Modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(FileOpsTags.BUSY),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ErrorPart(error: FileOpError?, onIntent: (FileOpsIntent) -> Unit) {
    if (error == null) return
    ErrorBanner(
        modifier = Modifier.testTag(FileOpsTags.ERROR),
        message = errorMessage(error),
        onDismiss = { onIntent(FileOpsIntent.DismissError) },
    )
}

@Composable
private fun errorMessage(error: FileOpError): String {
    val reason = stringResource(error.reason.reasonRes)
    return when (error.op) {
        FileOp.Rename -> stringResource(R.string.files_ops_rename_error, reason)
        FileOp.Delete -> stringResource(R.string.files_ops_delete_error, reason)
        FileOp.CreateFolder -> stringResource(R.string.files_ops_create_error, reason)
    }
}

@get:StringRes
private val FileAction.labelRes: Int
    get() = when (this) {
        FileAction.Edit -> R.string.files_ops_edit
        FileAction.Download -> R.string.files_ops_download
        FileAction.Rename -> R.string.files_ops_rename
        FileAction.Delete -> R.string.files_ops_delete
    }

@get:StringRes
private val FileOp.busyRes: Int
    get() = when (this) {
        FileOp.Rename -> R.string.files_ops_renaming
        FileOp.Delete -> R.string.files_ops_deleting
        FileOp.CreateFolder -> R.string.files_ops_creating
    }

@get:StringRes
private val NameProblem.messageRes: Int
    get() = when (this) {
        NameProblem.Blank -> R.string.files_ops_problem_blank
        NameProblem.Separator -> R.string.files_ops_problem_separator
        NameProblem.Reserved -> R.string.files_ops_problem_reserved
        NameProblem.TooLong -> R.string.files_ops_problem_too_long
        NameProblem.ControlCharacter -> R.string.files_ops_problem_control
        NameProblem.Taken -> R.string.files_ops_name_taken
    }

/** Why an operation failed, as the end of a sentence that says which. A taken name reads as one. */
@get:StringRes
private val FilesFailure.reasonRes: Int
    get() = when (this) {
        FilesFailure.NotConnected -> R.string.files_ops_reason_not_connected
        FilesFailure.Unreachable -> R.string.files_ops_reason_unreachable
        FilesFailure.Unauthorized -> R.string.files_ops_reason_unauthorized
        FilesFailure.NotFound -> R.string.files_ops_reason_not_found
        FilesFailure.PermissionDenied -> R.string.files_ops_reason_permission_denied
        FilesFailure.AlreadyExists -> R.string.files_ops_name_taken
        FilesFailure.NotEmpty -> R.string.files_ops_reason_not_empty
        FilesFailure.PathRejected -> R.string.files_ops_reason_path_rejected
        FilesFailure.TooLarge -> R.string.files_ops_reason_too_large
        FilesFailure.ToolMissing -> R.string.files_ops_reason_tool_missing
        FilesFailure.Rejected -> R.string.files_ops_reason_rejected
        FilesFailure.ServerFailed -> R.string.files_ops_reason_server_failed
        FilesFailure.Unsupported -> R.string.files_ops_reason_unsupported
        FilesFailure.Unknown -> R.string.files_ops_reason_unknown
    }
