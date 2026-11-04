package io.github.thewisekd.phopencode.feature.connect.ui.hostkey

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.ui.component.ConfirmDialog
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyRequest
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyState
import io.github.thewisekd.phopencode.feature.connect.ui.R

/**
 * Asks the user to decide on the SSH host key of the server being connected to. Draws [state] and
 * sends [onIntent]; the presenter decides everything.
 *
 * - A key seen for the first time shows its type and full SHA-256 fingerprint, selectable so it can
 *   be compared, with what approving means, and offers Approve and Reject.
 * - A changed key shows a warning in the theme's error colours, with a heading that says it in words,
 *   and both the approved and the new fingerprint. Rejecting is the primary action; accepting the new
 *   key asks again in a dialog first, and that dialog closes if the key it was asked for changes.
 *
 * Waiting and saving are still messages, not core/ui's `Loading`: its spinner never stops, and the
 * app's ActivityScenario tests, which open this route while the stub presenter waits, would never
 * come to rest. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun HostKeyScreen(
    route: ConnectRoute.HostKey,
    state: HostKeyState,
    onIntent: (HostKeyIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.connect_hostkey_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
    ) { padding ->
        val spacing = PhopencodeTheme.spacing
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            state.error?.let { error ->
                ErrorBanner(
                    message = stringResource(error.messageRes()),
                    onDismiss = { onIntent(HostKeyIntent.DismissError) },
                )
            }
            if (state.deciding) {
                StillProgress(stringResource(R.string.connect_hostkey_deciding))
            }
            val enabled = !state.deciding
            when (val request = state.request) {
                is HostKeyRequest.FirstSeen -> FirstSeen(request, enabled, onIntent)
                is HostKeyRequest.Changed -> Changed(request, enabled, onIntent)
                null -> when {
                    state.loading -> StillProgress(stringResource(R.string.connect_hostkey_loading))
                    state.error == null -> Text(
                        text = stringResource(R.string.connect_hostkey_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun FirstSeen(request: HostKeyRequest.FirstSeen, enabled: Boolean, onIntent: (HostKeyIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Text(
        text = stringResource(R.string.connect_hostkey_first_title),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        text = stringResource(R.string.connect_hostkey_first_message),
        style = MaterialTheme.typography.bodyMedium,
    )
    KeyType(request.keyType)
    Fingerprint(
        label = stringResource(R.string.connect_hostkey_fingerprint_label),
        fingerprint = request.fingerprint,
        tag = HOSTKEY_NEW_FINGERPRINT_TAG,
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Button(
            onClick = { onIntent(HostKeyIntent.Approve) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.touchTarget),
        ) {
            Text(stringResource(R.string.connect_hostkey_approve))
        }
        OutlinedButton(
            onClick = { onIntent(HostKeyIntent.Reject) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.touchTarget),
        ) {
            Text(stringResource(R.string.connect_hostkey_reject))
        }
    }
}

@Composable
private fun Changed(request: HostKeyRequest.Changed, enabled: Boolean, onIntent: (HostKeyIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    // Keyed by both fingerprints, so a confirmation never carries over to a key the user has not seen.
    // Plain remember, not saveable: a recreation always closes it, and the user confirms again.
    var confirming by remember(request.fingerprint.sha256, request.approved.sha256) { mutableStateOf(false) }

    ChangedWarning()
    KeyType(request.keyType)
    Fingerprint(
        label = stringResource(R.string.connect_hostkey_previous_label),
        fingerprint = request.approved,
        tag = HOSTKEY_APPROVED_FINGERPRINT_TAG,
    )
    Fingerprint(
        label = stringResource(R.string.connect_hostkey_new_label),
        fingerprint = request.fingerprint,
        tag = HOSTKEY_NEW_FINGERPRINT_TAG,
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Button(
            onClick = { onIntent(HostKeyIntent.Reject) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.touchTarget),
        ) {
            Text(stringResource(R.string.connect_hostkey_changed_reject))
        }
        TextButton(
            onClick = { confirming = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.touchTarget),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.connect_hostkey_changed_accept))
        }
    }

    if (confirming && enabled) {
        ConfirmDialog(
            title = stringResource(R.string.connect_hostkey_confirm_title),
            message = stringResource(R.string.connect_hostkey_confirm_message),
            onConfirm = {
                confirming = false
                onIntent(HostKeyIntent.AcceptChangedKey)
            },
            onDismiss = { confirming = false },
            confirmLabel = stringResource(R.string.connect_hostkey_confirm_accept),
            destructive = true,
        )
    }
}

/** The red box: the theme's error roles, a warning icon and a heading that says it in words. */
@Composable
private fun ChangedWarning() {
    val spacing = PhopencodeTheme.spacing
    val container = MaterialTheme.colorScheme.errorContainer
    val content = MaterialTheme.colorScheme.onErrorContainer
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HOSTKEY_WARNING_TAG)
            .semantics {
                this[WarningContainerColor] = container
                this[WarningContentColor] = content
                liveRegion = LiveRegionMode.Polite
            },
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                // Decorative: the heading next to it says "Warning" in words.
                Icon(imageVector = WarningIcon, contentDescription = null)
                Text(
                    text = stringResource(R.string.connect_hostkey_changed_title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = stringResource(R.string.connect_hostkey_changed_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun KeyType(keyType: String) {
    Text(
        text = stringResource(R.string.connect_hostkey_type, keyType),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A fingerprint in full, in monospace, selectable so it can be copied and compared. */
@Composable
private fun Fingerprint(label: String, fingerprint: HostKeyFingerprint, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = fingerprint.sha256,
                modifier = Modifier.testTag(tag),
                style = PhopencodeTheme.codeTypography.body,
            )
        }
    }
}

/**
 * A still progress message. TalkBack hears the message, an indeterminate progress and a polite live
 * region, as for core/ui's `Loading`, but nothing animates.
 */
@Composable
private fun StillProgress(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(PhopencodeTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@StringRes
private fun HostKeyError.messageRes(): Int = when (this) {
    HostKeyError.NoRequest -> R.string.connect_hostkey_error_no_request
    HostKeyError.ServerMissing -> R.string.connect_hostkey_error_server_missing
    HostKeyError.StorageFailed -> R.string.connect_hostkey_error_storage_failed
    HostKeyError.Superseded -> R.string.connect_hostkey_error_superseded
}

/**
 * Material's 24 dp "Warning" symbol. Material 3 brings no icon library and this module does not
 * depend on it, so the path is kept here.
 */
private val WarningIcon: ImageVector = ImageVector.Builder(
    name = "HostKey.Warning",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes("M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z"),
    fill = SolidColor(Color.Black),
).build()
