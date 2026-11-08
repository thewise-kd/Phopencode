package io.github.thewisekd.phopencode.feature.chat.ui.composer

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerAction
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** The delivery chips' visible height; their touch target is the full 48dp. */
private val CHIP_HEIGHT = 32.dp

/** The check mark on the chosen delivery chip. */
private val CHECK_SIZE = 18.dp

/** How faint a delivery chip is while it cannot be tapped. */
private const val DISABLED_ALPHA = 0.38f

/**
 * Writes and sends a prompt, and stops the running work (P6.6). Draws [state], sends [onIntent]; the
 * presenter decides everything.
 *
 * One rounded card holds it all: while running, the delivery choice, steer or queue, as two small
 * chips, the only time it goes along; a still note while a prompt is sent or the work stops; the
 * field, with a hint for `/` and `@` while empty; and under it [controls], as the chat's agent and
 * model chips, with the round button at the end.
 *
 * The button sends or stops, in the same place: while the work runs with nothing typed and no skill
 * attached it is Stop, in the error tone, enabled by [ComposerState.canStop]; otherwise it is Send,
 * enabled by [ComposerState.canSend], so a prompt typed while running is sent with the chosen
 * delivery rather than stopping the work. It is an icon, named for a screen reader. Sending and
 * stopping are still messages, not core/ui's `Loading`: its spinner never stops, and the app's
 * ActivityScenario tests would never come to rest. An error shows above the card with a dismiss
 * button; a failed send keeps the draft, as the presenter keeps it.
 *
 * The field grows with the draft up to [COMPOSER_MAX_LINES] lines, then scrolls inside, so the
 * timeline keeps its room; when even that does not fit, the field gives way first. The chat places the composer under the timeline inside the screen's
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
    controls: @Composable RowScope.() -> Unit = {},
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
        Surface(
            modifier = Modifier.fillMaxWidth().testTag(COMPOSER_CARD_TAG),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            BoxWithConstraints {
                // Under a bounded height, as in the chat, the field is measured last: on a short
                // window it gives way, so the button stays whole. Unbounded, it takes its own height.
                val bounded = constraints.hasBoundedHeight
                Column(Modifier.padding(vertical = spacing.xs)) {
                    if (state.running) {
                        DeliveryChoice(state, onIntent)
                    }
                    when {
                        state.sending -> StillProgress(stringResource(R.string.chat_composer_sending))
                        state.stopping -> StillProgress(stringResource(R.string.chat_composer_stopping))
                    }
                    Field(state, onIntent, if (bounded) Modifier.weight(1f, fill = false) else Modifier)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = spacing.sm, end = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { controls() }
                        SendOrStop(state, onIntent)
                    }
                }
            }
        }
    }
}

/** The field: filled, on the card's color, with no label and a hint while empty. */
@Composable
private fun Field(state: ComposerState, onIntent: (ComposerIntent) -> Unit, modifier: Modifier) {
    val clear = Color.Transparent
    TextField(
        value = state.draft,
        onValueChange = { onIntent(ComposerIntent.DraftChanged(it)) },
        modifier = modifier.fillMaxWidth().testTag(COMPOSER_FIELD_TAG),
        enabled = state.enabled && !state.sending,
        placeholder = { Text(stringResource(R.string.chat_composer_field_hint)) },
        minLines = 1,
        maxLines = COMPOSER_MAX_LINES,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = clear,
            unfocusedContainerColor = clear,
            disabledContainerColor = clear,
            errorContainerColor = clear,
            focusedIndicatorColor = clear,
            unfocusedIndicatorColor = clear,
            disabledIndicatorColor = clear,
            errorIndicatorColor = clear,
        ),
    )
}

/**
 * The round button: Stop while the work runs and there is nothing to send, neither text nor a
 * skill; Send otherwise.
 */
@Composable
private fun SendOrStop(state: ComposerState, onIntent: (ComposerIntent) -> Unit) {
    val stops = state.running && !state.hasContent
    val colors = MaterialTheme.colorScheme
    FilledIconButton(
        onClick = { onIntent(if (stops) ComposerIntent.Stop else ComposerIntent.Send) },
        enabled = if (stops) state.canStop else state.canSend,
        modifier = Modifier.testTag(COMPOSER_ACTION_TAG),
        colors = if (stops) {
            IconButtonDefaults.filledIconButtonColors(containerColor = colors.error, contentColor = colors.onError)
        } else {
            IconButtonDefaults.filledIconButtonColors()
        },
    ) {
        Icon(
            imageVector = if (stops) PhopencodeIcons.Stop else PhopencodeIcons.Send,
            contentDescription = stringResource(if (stops) R.string.chat_composer_stop else R.string.chat_composer_send),
        )
    }
}

/**
 * Steer or queue, for a prompt sent while the work runs: two small chips, one choice, read as a
 * group of radio buttons. Locked while a prompt is on its way.
 */
@Composable
private fun DeliveryChoice(state: ComposerState, onIntent: (ComposerIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Row(
        modifier = Modifier.padding(horizontal = spacing.md).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PromptDelivery.entries.forEach { delivery ->
            DeliveryChip(
                label = stringResource(delivery.labelRes()),
                selected = state.delivery == delivery,
                enabled = state.enabled && !state.sending,
                onClick = { onIntent(ComposerIntent.DeliveryChanged(delivery)) },
            )
        }
    }
}

@Composable
private fun DeliveryChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Surface(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .then(if (selected) Modifier else Modifier.border(BorderStroke(1.dp, colors.outlineVariant), shape))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        shape = shape,
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.heightIn(min = CHIP_HEIGHT).padding(horizontal = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (selected) {
                Icon(PhopencodeIcons.Check, contentDescription = null, modifier = Modifier.size(CHECK_SIZE))
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A still progress message, small. TalkBack hears the message, an indeterminate progress and a
 * polite live region, as for core/ui's `Loading`, but nothing animates.
 */
@Composable
private fun StillProgress(message: String) {
    val spacing = PhopencodeTheme.spacing
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.xs)
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
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
