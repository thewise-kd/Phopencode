package io.github.thewisekd.phopencode.feature.git.ui.branch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.thewisekd.phopencode.core.ui.component.ConfirmDialog
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchDialog
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.ui.R

/**
 * The dialog [BranchesState.dialog] asks for. Confirming sends [BranchIntent.Confirm], cancelling,
 * back or a tap outside [BranchIntent.DismissDialog]; the contract closes the dialog.
 */
@Composable
internal fun BranchDialogs(state: BranchesState, onIntent: (BranchIntent) -> Unit) {
    val confirm = { onIntent(BranchIntent.Confirm) }
    val dismiss = { onIntent(BranchIntent.DismissDialog) }
    when (val dialog = state.dialog) {
        is BranchDialog.Create -> CreateDialog(dialog, busy = state.busy != null, onIntent = onIntent)
        is BranchDialog.ConfirmSwitchDirty -> ConfirmDialog(
            title = stringResource(R.string.git_branch_dirty_title),
            message = stringResource(R.string.git_branch_dirty_message, dialog.branch.name),
            onConfirm = confirm,
            onDismiss = dismiss,
            modifier = Modifier.testTag(BranchTags.DIALOG_DIRTY),
            confirmLabel = stringResource(R.string.git_branch_dirty_confirm),
        )
        is BranchDialog.ConfirmDelete -> ConfirmDialog(
            title = stringResource(R.string.git_branch_delete_title),
            message = stringResource(R.string.git_branch_delete_message, dialog.branch.name),
            onConfirm = confirm,
            onDismiss = dismiss,
            modifier = Modifier.testTag(BranchTags.DIALOG_DELETE),
            confirmLabel = stringResource(R.string.git_branch_delete_confirm),
            destructive = true,
        )
        is BranchDialog.ConfirmDeleteUnmerged -> ConfirmDialog(
            title = stringResource(R.string.git_branch_unmerged_title),
            message = stringResource(R.string.git_branch_unmerged_message, dialog.branch.name),
            onConfirm = confirm,
            onDismiss = dismiss,
            modifier = Modifier.testTag(BranchTags.DIALOG_UNMERGED),
            confirmLabel = stringResource(R.string.git_branch_unmerged_confirm),
            destructive = true,
        )
        null -> Unit
    }
}

/**
 * A new branch: its name, checked by the contract as it is typed, where it starts, and whether to
 * switch to it. Create waits for a usable name and for no other operation to run.
 */
@Composable
private fun CreateDialog(dialog: BranchDialog.Create, busy: Boolean, onIntent: (BranchIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val canSubmit = dialog.canSubmit && !busy
    AlertDialog(
        onDismissRequest = { onIntent(BranchIntent.DismissDialog) },
        modifier = Modifier.testTag(BranchTags.DIALOG_CREATE),
        title = {
            Text(text = stringResource(R.string.git_branch_create_title), modifier = Modifier.semantics { heading() })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                val problem = dialog.problem
                OutlinedTextField(
                    value = dialog.name,
                    onValueChange = { onIntent(BranchIntent.EditName(it)) },
                    modifier = Modifier.fillMaxWidth().testTag(BranchTags.CREATE_NAME),
                    label = { Text(stringResource(R.string.git_branch_create_name)) },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(stringResource(it.messageRes)) } },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSubmit) onIntent(BranchIntent.SubmitCreate) }),
                )
                val startPoint = dialog.startPoint
                Text(
                    text = if (startPoint != null) {
                        stringResource(R.string.git_branch_create_from, startPoint)
                    } else {
                        stringResource(R.string.git_branch_create_from_head)
                    },
                    modifier = Modifier.testTag(BranchTags.CREATE_FROM),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = spacing.touchTarget)
                        .testTag(BranchTags.CREATE_SWITCH_AFTER)
                        .toggleable(
                            value = dialog.switchAfter,
                            role = Role.Checkbox,
                            onValueChange = { onIntent(BranchIntent.SetSwitchAfter(it)) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Checkbox(checked = dialog.switchAfter, onCheckedChange = null)
                    Text(text = stringResource(R.string.git_branch_create_switch_after))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onIntent(BranchIntent.SubmitCreate) },
                enabled = canSubmit,
                modifier = Modifier.testTag(BranchTags.CREATE_SUBMIT),
            ) { Text(stringResource(R.string.git_branch_create_submit)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onIntent(BranchIntent.DismissDialog) },
                modifier = Modifier.testTag(BranchTags.CREATE_CANCEL),
            ) { Text(stringResource(R.string.git_branch_create_cancel)) }
        },
    )
}
