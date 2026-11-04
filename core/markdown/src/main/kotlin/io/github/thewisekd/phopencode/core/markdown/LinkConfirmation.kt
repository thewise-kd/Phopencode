package io.github.thewisekd.phopencode.core.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** What the link dialog shows, if anything. */
internal sealed interface LinkRequest {
    /** An http or https [target] that opens only if the user confirms. */
    data class Confirm(val target: String) : LinkRequest

    /** A target that never opens; [shown] is its escaped, visible form. */
    data class Blocked(val shown: String) : LinkRequest

    /** The user confirmed, but nothing on the device could open the target. */
    data object Failed : LinkRequest
}

/**
 * Holds the link dialog's state. [handler] stands in for the system's [UriHandler] under
 * the Markdown: a tapped link only raises a dialog. Nothing opens until [confirm].
 */
internal class LinkConfirmation(private val opener: UriHandler) {
    var request: LinkRequest? by mutableStateOf(null)
        private set

    val handler: UriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            request = LinkPolicy.openableTarget(uri)?.let(LinkRequest::Confirm)
                ?: LinkRequest.Blocked(LinkPolicy.visibleForm(uri))
        }
    }

    fun confirm(target: String) {
        request = null
        try {
            opener.openUri(target)
        } catch (_: RuntimeException) {
            // No browser, or the system refused. The target is user data: it is not logged.
            request = LinkRequest.Failed
        }
    }

    fun dismiss() {
        request = null
    }
}

@Composable
internal fun rememberLinkConfirmation(opener: UriHandler): LinkConfirmation =
    remember(opener) { LinkConfirmation(opener) }

/** The dialog for the current [LinkConfirmation.request]; shows nothing when there is none. */
@Composable
internal fun LinkDialog(confirmation: LinkConfirmation) {
    when (val request = confirmation.request) {
        null -> Unit
        is LinkRequest.Confirm -> LinkAlert(
            title = stringResource(R.string.markdown_link_confirm_title),
            message = stringResource(R.string.markdown_link_confirm_message),
            target = request.target,
            onDismiss = confirmation::dismiss,
            confirmButton = {
                TextButton(onClick = { confirmation.confirm(request.target) }) {
                    Text(stringResource(R.string.markdown_link_open))
                }
            },
            dismissLabel = stringResource(R.string.markdown_link_cancel),
        )
        is LinkRequest.Blocked -> LinkAlert(
            title = stringResource(R.string.markdown_link_blocked_title),
            message = stringResource(R.string.markdown_link_blocked_message),
            target = request.shown,
            onDismiss = confirmation::dismiss,
            confirmButton = null,
            dismissLabel = stringResource(R.string.markdown_link_close),
        )
        LinkRequest.Failed -> LinkAlert(
            title = stringResource(R.string.markdown_link_failed_title),
            message = stringResource(R.string.markdown_link_failed_message),
            target = null,
            onDismiss = confirmation::dismiss,
            confirmButton = null,
            dismissLabel = stringResource(R.string.markdown_link_close),
        )
    }
}

@Composable
private fun LinkAlert(
    title: String,
    message: String,
    target: String?,
    onDismiss: () -> Unit,
    confirmButton: (@Composable () -> Unit)?,
    dismissLabel: String,
) {
    val dismissButton = @Composable {
        TextButton(onClick = onDismiss) { Text(dismissLabel) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, modifier = Modifier.semantics { heading() }) },
        text = {
            // A long target scrolls instead of being cut: the user must be able to read all of it.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = message)
                if (target != null) {
                    Text(
                        text = target,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = confirmButton ?: dismissButton,
        dismissButton = if (confirmButton != null) dismissButton else null,
    )
}
