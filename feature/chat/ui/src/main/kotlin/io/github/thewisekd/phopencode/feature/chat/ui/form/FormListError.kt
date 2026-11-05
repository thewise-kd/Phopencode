package io.github.thewisekd.phopencode.feature.chat.ui.form

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent
import io.github.thewisekd.phopencode.feature.chat.ui.R

/**
 * Says that the subagents' forms could not be listed, and why, with a retry that sends
 * [FormIntent.Retry]. The chat draws it only while `FormsState.error` is set; this session's own
 * forms still show above it.
 */
@Composable
fun FormListError(
    error: ChatFailure,
    onIntent: (FormIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    Box(modifier.padding(horizontal = spacing.md, vertical = spacing.xs)) {
        ErrorBanner(
            modifier = Modifier.testTag(FormTags.LIST_ERROR),
            message = stringResource(R.string.chat_form_list_error, stringResource(error.reasonRes())),
            onRetry = { onIntent(FormIntent.Retry) },
        )
    }
}

/** Why a form's call failed, as the user reads it. */
@StringRes
internal fun ChatFailure.reasonRes(): Int = when (this) {
    ChatFailure.NotConnected -> R.string.chat_form_reason_not_connected
    ChatFailure.Unreachable -> R.string.chat_form_reason_unreachable
    ChatFailure.Unauthorized -> R.string.chat_form_reason_unauthorized
    ChatFailure.NotFound -> R.string.chat_form_reason_not_found
    ChatFailure.Conflict -> R.string.chat_form_reason_conflict
    ChatFailure.Rejected -> R.string.chat_form_reason_rejected
    ChatFailure.ServerFailed -> R.string.chat_form_reason_server_failed
    ChatFailure.Unknown -> R.string.chat_form_reason_unknown
}
