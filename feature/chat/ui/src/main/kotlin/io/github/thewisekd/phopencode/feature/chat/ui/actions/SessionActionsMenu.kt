package io.github.thewisekd.phopencode.feature.chat.ui.actions

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SessionAction
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** The smallest touch target the platform asks for. */
private val MIN_TOUCH = 48.dp

/** The most width the status next to the button takes, so the usage bar keeps its room. */
private val STATUS_MAX_WIDTH = 120.dp

/** How faint a menu action's text is while it cannot be tapped. */
private const val DISABLED_ALPHA = 0.38f

/**
 * The session's action menu (P7.15), next to the usage bar. Draws [state] and sends every tap to
 * [onIntent]; what shows next comes only from the state.
 *
 * - **The button:** a "more" icon button, named for a screen reader, that sends
 *   [ActionsIntent.OpenMenu]; it cannot be tapped until [ActionsState.enabled], but always keeps
 *   its place in the chat.
 * - **The status:** while a compaction is on its way or on the server, or a fork or a new session
 *   (the `/new` built-in) is on its way, a short still text before the button says so, on the same
 *   line, so the row grows no taller.
 * - **The menu:** while [ActionsState.menuOpen], a dialog with compact and fork, the whole session,
 *   each with a line on what it does; each sends [ActionsIntent.Request], and both are disabled
 *   while an action runs, with a note saying so. Its button, back or a tap outside sends
 *   [ActionsIntent.CloseMenu]. The last failure, if any, shows at its top.
 * - **The confirmation:** while [ActionsState.confirming], for the menu's actions and a prompt's
 *   "fork from here" alike, a dialog that says what the action does; confirming sends
 *   [ActionsIntent.Confirm], and its cancel button, back or a tap outside [ActionsIntent.Cancel].
 * - **A failure:** what failed and why, in a dialog of its own when the menu is closed; dismissing
 *   it sends [ActionsIntent.DismissError].
 *
 * At most one dialog shows: the confirmation, else the menu, else the failure. These are plain
 * dialogs, not Material's dropdown menu or bottom sheet, and nothing animates, so they come to rest
 * at once under Robolectric as on a device.
 */
@Composable
fun SessionActionsMenu(
    state: ActionsState,
    onIntent: (ActionsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        state.statusRes()?.let { Status(it) }
        IconButton(
            onClick = { onIntent(ActionsIntent.OpenMenu) },
            enabled = state.enabled,
            modifier = Modifier.testTag(ActionsTags.MENU_BUTTON),
        ) {
            Icon(imageVector = ActionsIcons.More, contentDescription = stringResource(R.string.chat_actions_menu_open))
        }
        val confirming = state.confirming
        val error = state.error
        when {
            // A new session is never asked for; the presenter never sets it here.
            confirming != null && confirming != SessionAction.NewSession -> Confirmation(confirming, state.canAct, onIntent)
            state.menuOpen -> Menu(state, onIntent)
            error != null -> FailureDialog(error, onIntent)
        }
    }
}

/** What runs now, in words, or null when nothing does. */
@StringRes
private fun ActionsState.statusRes(): Int? = when {
    running == SessionAction.Compact || compacting -> R.string.chat_actions_status_compacting
    running is SessionAction.Fork -> R.string.chat_actions_status_forking
    running == SessionAction.NewSession -> R.string.chat_actions_status_creating
    else -> null
}

@Composable
private fun Status(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(start = PhopencodeTheme.spacing.sm)
            .widthIn(max = STATUS_MAX_WIDTH)
            .testTag(ActionsTags.STATUS)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** A dialog's card, its content scrolling when the screen is short. */
@Composable
private fun DialogCard(onDismissRequest: () -> Unit, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            // The padding sits outside the scroll, so nothing is padded after it.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(tag)
                    .padding(PhopencodeTheme.spacing.lg)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm),
                content = content,
            )
        }
    }
}

@Composable
private fun Menu(state: ActionsState, onIntent: (ActionsIntent) -> Unit) {
    DialogCard(onDismissRequest = { onIntent(ActionsIntent.CloseMenu) }, tag = ActionsTags.MENU) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_actions_menu_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).testTag(ActionsTags.MENU_TITLE).semantics { heading() },
            )
            TextButton(onClick = { onIntent(ActionsIntent.CloseMenu) }, modifier = Modifier.testTag(ActionsTags.MENU_CLOSE)) {
                Text(stringResource(R.string.chat_actions_close))
            }
        }
        state.error?.let { Failure(it, onIntent) }
        if (state.running != null) {
            Text(
                text = stringResource(R.string.chat_actions_busy),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(ActionsTags.BUSY).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        MenuAction(
            title = R.string.chat_actions_compact,
            detail = R.string.chat_actions_compact_detail,
            enabled = state.canAct,
            tag = ActionsTags.COMPACT,
        ) { onIntent(ActionsIntent.Request(SessionAction.Compact)) }
        MenuAction(
            title = R.string.chat_actions_fork,
            detail = R.string.chat_actions_fork_detail,
            enabled = state.canAct,
            tag = ActionsTags.FORK,
        ) { onIntent(ActionsIntent.Request(SessionAction.Fork(null))) }
    }
}

/** One of the menu's actions: its name above a line on what it does, read as one button. */
@Composable
private fun MenuAction(@StringRes title: Int, @StringRes detail: Int, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH)
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = PhopencodeTheme.spacing.sm),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface.copy(alpha = alpha),
        )
        Text(
            text = stringResource(detail),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

@Composable
private fun Confirmation(action: SessionAction, canConfirm: Boolean, onIntent: (ActionsIntent) -> Unit) {
    val (title, message, confirm) = when (action) {
        SessionAction.NewSession -> return
        SessionAction.Compact -> Triple(
            R.string.chat_actions_confirm_compact_title,
            R.string.chat_actions_confirm_compact_message,
            R.string.chat_actions_confirm_compact,
        )
        is SessionAction.Fork -> if (action.messageId == null) {
            Triple(R.string.chat_actions_confirm_fork_title, R.string.chat_actions_confirm_fork_message, R.string.chat_actions_confirm_fork)
        } else {
            Triple(
                R.string.chat_actions_confirm_fork_here_title,
                R.string.chat_actions_confirm_fork_here_message,
                R.string.chat_actions_confirm_fork,
            )
        }
    }
    DialogCard(onDismissRequest = { onIntent(ActionsIntent.Cancel) }, tag = ActionsTags.CONFIRM_DIALOG) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(ActionsTags.CONFIRM_TITLE).semantics { heading() },
        )
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(ActionsTags.CONFIRM_MESSAGE),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm, Alignment.End),
        ) {
            TextButton(onClick = { onIntent(ActionsIntent.Cancel) }, modifier = Modifier.testTag(ActionsTags.CANCEL)) {
                Text(stringResource(R.string.chat_actions_cancel))
            }
            TextButton(
                onClick = { onIntent(ActionsIntent.Confirm) },
                enabled = canConfirm,
                modifier = Modifier.testTag(ActionsTags.CONFIRM),
            ) {
                Text(stringResource(confirm))
            }
        }
    }
}

/** The failure on its own, while the menu is closed; it scrolls when a large font makes it taller than the screen. */
@Composable
private fun FailureDialog(error: ActionError, onIntent: (ActionsIntent) -> Unit) {
    Dialog(onDismissRequest = { onIntent(ActionsIntent.DismissError) }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Failure(error, onIntent)
        }
    }
}

@Composable
private fun Failure(error: ActionError, onIntent: (ActionsIntent) -> Unit) {
    val what = when (error.action) {
        SessionAction.Compact -> R.string.chat_actions_error_compact
        is SessionAction.Fork -> R.string.chat_actions_error_fork
        SessionAction.NewSession -> R.string.chat_actions_error_new_session
    }
    ErrorBanner(
        modifier = Modifier.testTag(ActionsTags.ERROR),
        message = stringResource(what, stringResource(error.reason.reasonRes())),
        onDismiss = { onIntent(ActionsIntent.DismissError) },
    )
}

@StringRes
private fun ChatFailure.reasonRes(): Int = when (this) {
    ChatFailure.NotConnected -> R.string.chat_actions_reason_not_connected
    ChatFailure.Unreachable -> R.string.chat_actions_reason_unreachable
    ChatFailure.Unauthorized -> R.string.chat_actions_reason_unauthorized
    ChatFailure.NotFound -> R.string.chat_actions_reason_not_found
    ChatFailure.Conflict -> R.string.chat_actions_reason_conflict
    ChatFailure.Rejected -> R.string.chat_actions_reason_rejected
    ChatFailure.ServerFailed -> R.string.chat_actions_reason_server
    ChatFailure.Unknown -> R.string.chat_actions_reason_unknown
}
