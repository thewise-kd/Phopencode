package io.github.thewisekd.phopencode.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.thewisekd.phopencode.core.ui.R
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme

/** Marks an [ErrorBanner]'s warning icon, for tests. */
internal const val ERROR_BANNER_ICON_TAG = "ui-common:error-banner-icon"

/**
 * An error shown in place, above the content it concerns: a rounded card in the error
 * container color with a warning icon. A button appears only when its callback is
 * given. The icon is decorative: the merged node says "error" in its state, next to
 * the message.
 */
@Composable
fun ErrorBanner(
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.ui_common_error_generic),
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val spacing = PhopencodeTheme.spacing
    val colors = PhopencodeTheme.status.danger
    val errorLabel = stringResource(R.string.ui_common_status_danger)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = errorLabel
                liveRegion = LiveRegionMode.Polite
            },
        color = colors.container,
        contentColor = colors.onContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(
                start = spacing.lg,
                end = if (onDismiss != null) spacing.xs else spacing.lg,
                top = if (onDismiss != null) spacing.xs else spacing.md,
                bottom = if (onRetry != null) spacing.xs else spacing.md,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Icon(
                    imageVector = PhopencodeIcons.Warning,
                    contentDescription = null,
                    modifier = Modifier.testTag(ERROR_BANNER_ICON_TAG),
                    tint = colors.color,
                )
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = PhopencodeIcons.Close,
                            contentDescription = stringResource(R.string.ui_common_error_dismiss),
                        )
                    }
                }
            }
            if (onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onContainer),
                ) {
                    Icon(
                        imageVector = PhopencodeIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.padding(end = spacing.sm).size(ButtonDefaults.IconSize),
                    )
                    Text(stringResource(R.string.ui_common_retry), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
