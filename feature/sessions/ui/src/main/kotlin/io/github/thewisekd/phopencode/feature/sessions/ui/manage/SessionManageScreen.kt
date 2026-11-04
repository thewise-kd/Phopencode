package io.github.thewisekd.phopencode.feature.sessions.ui.manage

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.ManageAction
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import io.github.thewisekd.phopencode.feature.sessions.ui.R

/**
 * Renames, archives and deletes one session (P5.7). Draws [state] and sends [onIntent]; the presenter
 * decides everything, the delete confirmation included: the dialog shows while
 * [SessionManageState.confirmingDelete] is set, and says that deleting cannot be undone and, unless
 * the session is known to have none, that its child sessions go with it.
 *
 * While [SessionManageState.busy] is set every action, the dialog's included, is disabled and a still
 * message says what is under way. Loading and busy are still messages, not core/ui's `Loading`: its
 * spinner never stops, and the app's ActivityScenario tests, which open this route while the stub
 * presenter loads, would never come to rest. The root keeps the `placeholder:<route>` tag the app's
 * navigation test looks for.
 *
 * The title is user data: it is drawn, never logged.
 */
@Composable
fun SessionManageScreen(
    route: SessionsRoute.Manage,
    state: SessionManageState,
    onIntent: (SessionManageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.sessions_manage_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(SessionManageIntent.Close) },
    ) { padding ->
        val spacing = PhopencodeTheme.spacing
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            val loadError = state.loadError
            when {
                loadError != null -> ErrorBanner(
                    message = errorMessage(R.string.sessions_manage_load_failed, loadError),
                    // A session that is gone stays gone: loading it again cannot help.
                    onRetry = if (loadError == SessionsFailure.NotFound) null else ({ onIntent(SessionManageIntent.Retry) }),
                )
                state.loading -> StillProgress(stringResource(R.string.sessions_manage_loading))
                else -> Loaded(state, onIntent)
            }
        }
    }

    if (state.confirmingDelete) {
        DeleteConfirmation(state, onIntent)
    }
}

@Composable
private fun Loaded(state: SessionManageState, onIntent: (SessionManageIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val enabled = state.busy == null
    state.actionError?.let { error ->
        ErrorBanner(
            message = errorMessage(error.action.failedRes(), error.reason),
            onDismiss = { onIntent(SessionManageIntent.DismissError) },
        )
    }
    state.busy?.let { StillProgress(stringResource(it.busyRes())) }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        OutlinedTextField(
            value = state.title,
            onValueChange = { onIntent(SessionManageIntent.TitleChanged(it)) },
            modifier = Modifier.fillMaxWidth().testTag(SESSION_MANAGE_TITLE_FIELD_TAG),
            enabled = enabled,
            label = { Text(stringResource(R.string.sessions_manage_title_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (state.canSaveTitle) onIntent(SessionManageIntent.SaveTitle) },
            ),
        )
        Button(
            onClick = { onIntent(SessionManageIntent.SaveTitle) },
            enabled = state.canSaveTitle,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.touchTarget),
        ) {
            Text(stringResource(R.string.sessions_manage_save))
        }
    }

    state.directory?.let { directory ->
        Text(
            text = stringResource(R.string.sessions_manage_folder, directory),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        if (state.archived) {
            Text(
                text = stringResource(R.string.sessions_manage_archived_note),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(
            onClick = { onIntent(SessionManageIntent.SetArchived(!state.archived)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.touchTarget),
        ) {
            Text(
                stringResource(
                    if (state.archived) R.string.sessions_manage_unarchive else R.string.sessions_manage_archive,
                ),
            )
        }
        TextButton(
            onClick = { onIntent(SessionManageIntent.RequestDelete) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.touchTarget),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.sessions_manage_delete))
        }
    }
}

/**
 * Asks before deleting. Its own dialog rather than core/ui's `ConfirmDialog`, whose buttons cannot be
 * disabled: while an action is under way both buttons are, and back or a tap outside does nothing.
 */
@Composable
private fun DeleteConfirmation(state: SessionManageState, onIntent: (SessionManageIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val enabled = state.busy == null
    AlertDialog(
        onDismissRequest = { if (enabled) onIntent(SessionManageIntent.CancelDelete) },
        title = {
            Text(
                text = stringResource(R.string.sessions_manage_confirm_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(stringResource(R.string.sessions_manage_confirm_irreversible))
                when (state.hasChildSessions) {
                    true -> Text(stringResource(R.string.sessions_manage_confirm_children))
                    null -> Text(stringResource(R.string.sessions_manage_confirm_children_unknown))
                    false -> Unit
                }
                state.busy?.let { StillProgress(stringResource(it.busyRes())) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onIntent(SessionManageIntent.ConfirmDelete) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = spacing.touchTarget),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.sessions_manage_confirm_delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onIntent(SessionManageIntent.CancelDelete) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = spacing.touchTarget),
            ) {
                Text(stringResource(R.string.sessions_manage_cancel))
            }
        },
    )
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

/** What failed, then why, as one message. */
@Composable
private fun errorMessage(@StringRes failedRes: Int, reason: SessionsFailure): String = stringResource(
    R.string.sessions_manage_error,
    stringResource(failedRes),
    stringResource(reason.reasonRes()),
)

@StringRes
private fun ManageAction.busyRes(): Int = when (this) {
    ManageAction.Rename -> R.string.sessions_manage_busy_rename
    ManageAction.Archive -> R.string.sessions_manage_busy_archive
    ManageAction.Unarchive -> R.string.sessions_manage_busy_unarchive
    ManageAction.Delete -> R.string.sessions_manage_busy_delete
}

@StringRes
private fun ManageAction.failedRes(): Int = when (this) {
    ManageAction.Rename -> R.string.sessions_manage_failed_rename
    ManageAction.Archive -> R.string.sessions_manage_failed_archive
    ManageAction.Unarchive -> R.string.sessions_manage_failed_unarchive
    ManageAction.Delete -> R.string.sessions_manage_failed_delete
}

@StringRes
private fun SessionsFailure.reasonRes(): Int = when (this) {
    SessionsFailure.NotConnected -> R.string.sessions_manage_reason_not_connected
    SessionsFailure.Unreachable -> R.string.sessions_manage_reason_unreachable
    SessionsFailure.Unauthorized -> R.string.sessions_manage_reason_unauthorized
    SessionsFailure.NotFound -> R.string.sessions_manage_reason_not_found
    SessionsFailure.Conflict -> R.string.sessions_manage_reason_conflict
    SessionsFailure.Rejected -> R.string.sessions_manage_reason_rejected
    SessionsFailure.ServerFailed -> R.string.sessions_manage_reason_server_failed
    SessionsFailure.Unknown -> R.string.sessions_manage_reason_unknown
}
