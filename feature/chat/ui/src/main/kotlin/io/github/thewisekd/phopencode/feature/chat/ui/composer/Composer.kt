package io.github.thewisekd.phopencode.feature.chat.ui.composer

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.input.KeyboardCapitalization
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerAction
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.ui.R

/**
 * Writes and sends a prompt, and stops the running work (P6.6). Draws [state], sends [onIntent]; the
 * presenter decides everything.
 *
 * One button sends or stops, in the same place: while the work runs and nothing is typed it is Stop,
 * enabled by [ComposerState.canStop]; otherwise it is Send, enabled by [ComposerState.canSend], so a
 * prompt typed while running is sent with the chosen delivery rather than stopping the work. The
 * delivery choice, steer or queue, shows only while running, the only time it goes along. Sending and
 * stopping are still messages, not core/ui's `Loading`: its spinner never stops, and the app's
 * ActivityScenario tests would never come to rest. An error shows above the field with a dismiss
 * button; a failed send keeps the draft, as the presenter keeps it.
 *
 * The field grows with the draft up to [COMPOSER_MAX_LINES] lines, then scrolls inside, so the
 * timeline keeps its room. The chat places the composer under the timeline inside the screen's
 * insets, keyboard included (`ChatScreen` applies `safeDrawingPadding`), so the composer applies no
 * insets of its own: it would pad twice.
 *
 * The draft is user data: it is drawn, never logged.
 */
@Composable
fun Composer(
    state: ComposerState,
    onIntent: (ComposerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    Column(
        modifier = modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        state.error?.let { error ->
            ErrorBanner(
                message = stringResource(
                    R.string.chat_composer_error,
                    stringResource(error.action.failedRes()),
                    stringResource(error.reason.reasonRes()),
                ),
                onDismiss = { onIntent(ComposerIntent.DismissError) },
            )
        }
        if (state.running) {
            DeliveryChoice(state, onIntent)
        }
        when {
            state.sending -> StillProgress(stringResource(R.string.chat_composer_sending))
            state.stopping -> StillProgress(stringResource(R.string.chat_composer_stopping))
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = { onIntent(ComposerIntent.DraftChanged(it)) },
                modifier = Modifier.weight(1f).testTag(COMPOSER_FIELD_TAG),
                enabled = state.enabled && !state.sending,
                label = { Text(stringResource(R.string.chat_composer_field_label)) },
                minLines = 1,
                maxLines = COMPOSER_MAX_LINES,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            SendOrStop(state, onIntent)
        }
    }
}

/** The one button: Stop while the work runs and nothing is typed, Send otherwise. */
@Composable
private fun SendOrStop(state: ComposerState, onIntent: (ComposerIntent) -> Unit) {
    val stops = state.running && state.draft.isBlank()
    Button(
        onClick = { onIntent(if (stops) ComposerIntent.Stop else ComposerIntent.Send) },
        enabled = if (stops) state.canStop else state.canSend,
        modifier = Modifier.heightIn(min = PhopencodeTheme.spacing.touchTarget).testTag(COMPOSER_ACTION_TAG),
    ) {
        Text(stringResource(if (stops) R.string.chat_composer_stop else R.string.chat_composer_send))
    }
}

/** Steer or queue, for a prompt sent while the work runs. Locked while a prompt is on its way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliveryChoice(state: ComposerState, onIntent: (ComposerIntent) -> Unit) {
    val options = PromptDelivery.entries
    Column(verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.xs)) {
        Text(
            text = stringResource(R.string.chat_composer_delivery_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, delivery ->
                SegmentedButton(
                    selected = state.delivery == delivery,
                    onClick = { onIntent(ComposerIntent.DeliveryChanged(delivery)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    enabled = state.enabled && !state.sending,
                    label = { Text(stringResource(delivery.labelRes())) },
                )
            }
        }
    }
}

/**
 * A still progress message. TalkBack hears the message, an indeterminate progress and a polite live
 * region, as for core/ui's `Loading`, but nothing animates.
 */
@Composable
private fun StillProgress(message: String) {
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@StringRes
private fun PromptDelivery.labelRes(): Int = when (this) {
    PromptDelivery.Steer -> R.string.chat_composer_delivery_steer
    PromptDelivery.Queue -> R.string.chat_composer_delivery_queue
}

@StringRes
private fun ComposerAction.failedRes(): Int = when (this) {
    ComposerAction.Send -> R.string.chat_composer_failed_send
    ComposerAction.Stop -> R.string.chat_composer_failed_stop
}

@StringRes
private fun ChatFailure.reasonRes(): Int = when (this) {
    ChatFailure.NotConnected -> R.string.chat_composer_reason_not_connected
    ChatFailure.Unreachable -> R.string.chat_composer_reason_unreachable
    ChatFailure.Unauthorized -> R.string.chat_composer_reason_unauthorized
    ChatFailure.NotFound -> R.string.chat_composer_reason_not_found
    ChatFailure.Conflict -> R.string.chat_composer_reason_conflict
    ChatFailure.Rejected -> R.string.chat_composer_reason_rejected
    ChatFailure.ServerFailed -> R.string.chat_composer_reason_server_failed
    ChatFailure.Unknown -> R.string.chat_composer_reason_unknown
}
