package io.github.thewisekd.phopencode.core.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.thewisekd.phopencode.core.ui.R

/**
 * Asks before an action. The caller removes the dialog: neither callback does, so
 * [onConfirm] can keep it open until the work is done. Back and a tap outside count
 * as [onDismiss].
 *
 * A [destructive] dialog adds a warning icon with a spoken label and a filled confirm
 * button in the error color, so it stands out without relying on color. Its
 * [confirmLabel] should name the action, such as "Delete".
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = stringResource(R.string.ui_common_confirm),
    dismissLabel: String = stringResource(R.string.ui_common_cancel),
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = if (destructive) {
            {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = stringResource(R.string.ui_common_dangerous_action),
                )
            }
        } else {
            null
        },
        iconContentColor = MaterialTheme.colorScheme.error,
        title = { Text(text = title, modifier = Modifier.semantics { heading() }) },
        text = { Text(text = message) },
        confirmButton = {
            if (destructive) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(confirmLabel)
                }
            } else {
                TextButton(onClick = onConfirm) {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        },
    )
}
