package io.github.thewisekd.phopencode.core.ui.component

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
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons

/**
 * Asks before an action. The caller removes the dialog: neither callback does, so
 * [onConfirm] can keep it open until the work is done. Back and a tap outside count
 * as [onDismiss].
 *
 * The confirm button is the one filled button, so the way forward is clear; cancel is
 * a text button. A [destructive] dialog adds a warning icon with a spoken label and
 * fills its confirm button in the error color, so it stands out without relying on
 * color. Its [confirmLabel] should name the action, such as "Delete".
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
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = if (destructive) {
            {
                Icon(
                    imageVector = PhopencodeIcons.Warning,
                    contentDescription = stringResource(R.string.ui_common_dangerous_action),
                )
            }
        } else {
            null
        },
        iconContentColor = colors.error,
        title = {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)
                } else {
                    ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = colors.surfaceContainerHigh,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
    )
}
