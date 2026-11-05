package io.github.thewisekd.phopencode.feature.chat.ui.permission

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionIntent
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** Lines the rejection message field grows to before it scrolls its own text. */
private const val REJECT_MESSAGE_MAX_LINES = 4

/**
 * One pending permission request (P7.5), drawn from [card]; every tap sends a [PermissionIntent]
 * for it to [onIntent], and what shows next comes only from the state.
 *
 * - **Who asks:** this session, or a subagent, named by its session's ID.
 * - **What is asked:** the action as the server names it, in words too when the app knows it, and
 *   every resource, command or path, in full: nothing is cut, so nothing dangerous hides behind an
 *   ellipsis. The server's own wording follows when it has some.
 * - **Choices:** allow once sends at once; always allow only asks for its confirmation, which then
 *   takes the choices' place and lists what the rule would save, with a warning when a pattern
 *   matches everything; reject opens a field for a message to the agent.
 * - **On its way:** while a decision is sent, every control is disabled and a still note says so.
 * - **Failed:** the decision that failed and why, dismissable; the choices stay to try again.
 *
 * The card sits in the chat's interaction area, which scrolls and is capped at half the screen, so
 * it never scrolls vertically itself and never fills the height. Nothing animates without end, and
 * the server's text, which can hold secrets, is drawn but never logged.
 */
@Composable
fun PermissionCard(
    card: PermissionCardState,
    onIntent: (PermissionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val warning = PhopencodeTheme.status.warning
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = warning.container,
        contentColor = warning.onContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Header(card)
            Request(card)
            card.error?.let { error ->
                ErrorBanner(
                    modifier = Modifier.testTag(PERMISSION_ERROR_TAG),
                    message = errorText(error),
                    onDismiss = { onIntent(PermissionIntent.DismissError(card.requestId)) },
                )
            }
            card.replying?.let { decision ->
                Text(
                    text = stringResource(decision.sendingRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .testTag(PERMISSION_SENDING_TAG)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            when {
                card.confirmingAlways -> ConfirmAlways(card, onIntent)
                card.rejecting -> RejectMessage(card, onIntent)
                else -> Choices(card, onIntent)
            }
        }
    }
}

@Composable
private fun Header(card: PermissionCardState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.chat_permission_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag(PERMISSION_TITLE_TAG).semantics { heading() },
        )
        val asker = if (card.fromSubagent) {
            stringResource(R.string.chat_permission_asker_subagent, card.sessionId.value)
        } else {
            stringResource(R.string.chat_permission_asker_self)
        }
        Text(
            text = asker,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (card.fromSubagent) FontWeight.SemiBold else null,
            modifier = Modifier.testTag(PERMISSION_ASKER_TAG),
        )
    }
}

@Composable
private fun Request(card: PermissionCardState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actionMeaningRes(card.action)?.let { meaning ->
            Text(
                text = stringResource(meaning),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(PERMISSION_ACTION_MEANING_TAG),
            )
        }
        Text(
            text = stringResource(R.string.chat_permission_action, card.action),
            style = PhopencodeTheme.codeTypography.body,
            modifier = Modifier.testTag(PERMISSION_ACTION_TAG),
        )
        if (card.resources.isEmpty()) {
            Text(
                text = stringResource(R.string.chat_permission_no_resources),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(PERMISSION_NO_RESOURCES_TAG),
            )
        } else {
            Text(text = stringResource(R.string.chat_permission_resources), style = MaterialTheme.typography.labelMedium)
            card.resources.forEachIndexed { index, resource -> Code(resource, permissionResourceTag(index)) }
        }
        card.message?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(PERMISSION_MESSAGE_TAG),
            )
        }
    }
}

/** Server text drawn in full and as is, line breaks kept, wrapped to the card's width. */
@Composable
private fun Code(text: String, tag: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = PhopencodeTheme.codeTypography.small,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag(tag),
        )
    }
}

@Composable
private fun Choices(card: PermissionCardState, onIntent: (PermissionIntent) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onIntent(PermissionIntent.AllowOnce(card.requestId)) },
            enabled = card.canReply,
            modifier = Modifier.testTag(PERMISSION_ONCE_TAG),
        ) {
            Text(stringResource(R.string.chat_permission_allow_once))
        }
        OutlinedButton(
            onClick = { onIntent(PermissionIntent.AllowAlways(card.requestId)) },
            enabled = card.canReply,
            modifier = Modifier.testTag(PERMISSION_ALWAYS_TAG),
        ) {
            Text(stringResource(R.string.chat_permission_allow_always))
        }
        OutlinedButton(
            onClick = { onIntent(PermissionIntent.StartReject(card.requestId)) },
            enabled = card.canReply,
            modifier = Modifier.testTag(PERMISSION_REJECT_TAG),
        ) {
            Text(stringResource(R.string.chat_permission_reject))
        }
    }
}

@Composable
private fun ConfirmAlways(card: PermissionCardState, onIntent: (PermissionIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.chat_permission_confirm_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag(PERMISSION_CONFIRM_TITLE_TAG).semantics { heading() },
        )
        val explanation = if (card.save.isEmpty()) R.string.chat_permission_confirm_no_save else R.string.chat_permission_confirm_save
        Text(
            text = stringResource(explanation, card.action),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(PERMISSION_CONFIRM_TAG),
        )
        card.save.forEachIndexed { index, pattern -> Code(pattern, permissionSaveTag(index)) }
        if (card.save.any(::matchesEverything)) {
            Text(
                text = stringResource(R.string.chat_permission_wildcard, card.action),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(PERMISSION_WILDCARD_TAG),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onIntent(PermissionIntent.ConfirmAlways(card.requestId)) },
                enabled = card.canReply,
                modifier = Modifier.testTag(PERMISSION_CONFIRM_ALWAYS_TAG),
            ) {
                Text(stringResource(R.string.chat_permission_confirm_always))
            }
            TextButton(
                onClick = { onIntent(PermissionIntent.CancelAlways(card.requestId)) },
                enabled = card.canReply,
                modifier = Modifier.testTag(PERMISSION_CANCEL_ALWAYS_TAG),
            ) {
                Text(stringResource(R.string.chat_permission_cancel))
            }
        }
    }
}

/** A pattern made of `*` alone, such as `*` or `**`, saves a rule for every request of the action. */
private fun matchesEverything(pattern: String): Boolean {
    val trimmed = pattern.trim()
    return trimmed.isNotEmpty() && trimmed.all { it == '*' }
}

@Composable
private fun RejectMessage(card: PermissionCardState, onIntent: (PermissionIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = card.rejectMessage,
            onValueChange = { onIntent(PermissionIntent.RejectMessageChanged(card.requestId, it)) },
            enabled = card.canReply,
            label = { Text(stringResource(R.string.chat_permission_reject_message)) },
            maxLines = REJECT_MESSAGE_MAX_LINES,
            modifier = Modifier.fillMaxWidth().testTag(PERMISSION_REJECT_FIELD_TAG),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onIntent(PermissionIntent.Reject(card.requestId)) },
                enabled = card.canReply,
                modifier = Modifier.testTag(PERMISSION_SEND_REJECT_TAG),
            ) {
                Text(stringResource(R.string.chat_permission_reject))
            }
            TextButton(
                onClick = { onIntent(PermissionIntent.CancelReject(card.requestId)) },
                enabled = card.canReply,
                modifier = Modifier.testTag(PERMISSION_CANCEL_REJECT_TAG),
            ) {
                Text(stringResource(R.string.chat_permission_cancel))
            }
        }
    }
}

@Composable
private fun errorText(error: PermissionError): String {
    val reason = stringResource(error.reason.replyReasonRes())
    val res = when (error.decision) {
        PermissionDecision.Once -> R.string.chat_permission_error_once
        PermissionDecision.Always -> R.string.chat_permission_error_always
        PermissionDecision.Reject -> R.string.chat_permission_error_reject
    }
    return stringResource(res, reason)
}

@StringRes
private fun PermissionDecision.sendingRes(): Int = when (this) {
    PermissionDecision.Once -> R.string.chat_permission_sending_once
    PermissionDecision.Always -> R.string.chat_permission_sending_always
    PermissionDecision.Reject -> R.string.chat_permission_sending_reject
}

/** The actions the app says in words; any other is named only as the server names it. */
@StringRes
private fun actionMeaningRes(action: String): Int? = when (action) {
    "bash" -> R.string.chat_permission_action_bash
    "edit" -> R.string.chat_permission_action_edit
    "read" -> R.string.chat_permission_action_read
    "webfetch" -> R.string.chat_permission_action_webfetch
    "external_directory" -> R.string.chat_permission_action_external_directory
    else -> null
}

/** Why a reply failed. */
@StringRes
internal fun ChatFailure.replyReasonRes(): Int = when (this) {
    ChatFailure.NotConnected -> R.string.chat_permission_reason_not_connected
    ChatFailure.Unreachable -> R.string.chat_permission_reason_unreachable
    ChatFailure.Unauthorized -> R.string.chat_permission_reason_unauthorized
    ChatFailure.NotFound -> R.string.chat_permission_reason_not_found
    ChatFailure.Conflict -> R.string.chat_permission_reason_conflict
    ChatFailure.Rejected -> R.string.chat_permission_reason_rejected
    ChatFailure.ServerFailed -> R.string.chat_permission_reason_server_failed
    ChatFailure.Unknown -> R.string.chat_permission_reason_unknown
}
