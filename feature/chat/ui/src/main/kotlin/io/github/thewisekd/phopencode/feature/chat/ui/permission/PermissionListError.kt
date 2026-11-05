package io.github.thewisekd.phopencode.feature.chat.ui.permission

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionIntent
import io.github.thewisekd.phopencode.feature.chat.ui.R

/**
 * Says that the subagents' permission requests could not be listed, and why (P7.5), with a retry
 * that sends [PermissionIntent.Retry]. This session's own requests still show above it. The chat
 * draws it only while `PermissionsState.error` is set.
 */
@Composable
fun PermissionListError(
    error: ChatFailure,
    onIntent: (PermissionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ErrorBanner(
        modifier = modifier,
        message = stringResource(R.string.chat_permission_list_error, stringResource(error.listReasonRes())),
        onRetry = { onIntent(PermissionIntent.Retry) },
    )
}

/** Why listing failed; a missing session and a refusal read differently than for a reply. */
@StringRes
private fun ChatFailure.listReasonRes(): Int = when (this) {
    ChatFailure.NotFound -> R.string.chat_permission_list_reason_not_found
    ChatFailure.Rejected -> R.string.chat_permission_list_reason_rejected
    else -> replyReasonRes()
}
