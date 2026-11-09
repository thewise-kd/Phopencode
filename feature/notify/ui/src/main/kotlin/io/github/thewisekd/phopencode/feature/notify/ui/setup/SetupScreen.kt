package io.github.thewisekd.phopencode.feature.notify.ui.setup

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
import androidx.compose.ui.text.input.KeyboardType
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.component.ConfirmDialog
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.component.StatusChip
import io.github.thewisekd.phopencode.core.ui.component.StatusTone
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyServiceStatus
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServer
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServerProblem
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupError
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupStep
import io.github.thewisekd.phopencode.feature.notify.ui.R

/** ntfy's page on its phone apps, with the store links: where a phone without the app is sent. */
internal const val NTFY_APP_PAGE: String = "https://docs.ntfy.sh/subscribe/phone/"

/**
 * The notification setup, `NotifyRoute.Setup`. Draws [state] and sends [onIntent]; the presenter
 * decides everything else. Sections, each a rounded card under a heading, in the style of the other
 * screens:
 * - The ntfy app: why it is needed and the subscription link. The link is opened with the system's
 *   handler (`LocalUriHandler`), which the ntfy app answers for `ntfy://`; when nothing answers, a
 *   note says the app is missing and offers ntfy's page on its phone apps. The topic is a secret:
 *   the screen shows the link with the topic replaced by dots, and the link itself reaches only the
 *   handler, never a text, a description, a log or saved state.
 * - The service on the server: its status, read again on demand, and install or remove. Removing
 *   asks first.
 * - A test notification, and whether the ntfy server took the last one.
 * - The ntfy server: the address field with the contract's [NtfyServerProblem] under it, save, and
 *   back to the default.
 *
 * Loading and a running step show a still message rather than core/ui's endless spinner, so the
 * screen comes to rest in the app's ActivityScenario tests (see `NotifyIdleTest`). While anything
 * runs, no step can start. The root keeps the `placeholder:<route>` tag the app's navigation test
 * looks for.
 */
@Composable
fun SetupScreen(
    route: NotifyRoute.Setup,
    state: SetupState,
    onIntent: (SetupIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingRemove by remember { mutableStateOf(false) }
    PhopencodeScaffold(
        title = stringResource(R.string.notify_setup_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(SetupIntent.Close) },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding)
        if (state.loading) {
            StillProgress(
                message = stringResource(R.string.notify_setup_loading),
                modifier = content.testTag(SETUP_LOADING_TAG),
            )
        } else {
            SetupList(state, onIntent, onRemove = { confirmingRemove = true }, modifier = content)
        }
    }
    if (confirmingRemove && !state.busy && state.status.isInstalled) {
        ConfirmDialog(
            title = stringResource(R.string.notify_setup_remove_confirm_title),
            message = stringResource(R.string.notify_setup_remove_confirm_message),
            onConfirm = {
                confirmingRemove = false
                onIntent(SetupIntent.Remove)
            },
            onDismiss = { confirmingRemove = false },
            modifier = Modifier.testTag(SETUP_REMOVE_DIALOG_TAG),
            confirmLabel = stringResource(R.string.notify_setup_remove),
            destructive = true,
        )
    }
}

@Composable
private fun SetupList(
    state: SetupState,
    onIntent: (SetupIntent) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .testTag(SETUP_LIST_TAG)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        state.error?.let { error -> SetupErrorBanner(error, busy = state.busy, onIntent = onIntent) }
        state.operation?.let { step ->
            StillProgress(
                message = stringResource(step.progressRes),
                modifier = Modifier.fillMaxWidth().testTag(SETUP_PROGRESS_TAG),
            )
        }

        Section(stringResource(R.string.notify_setup_app_heading)) { AppCard(state) }
        Section(stringResource(R.string.notify_setup_service_heading)) { ServiceCard(state, onIntent, onRemove) }
        Section(stringResource(R.string.notify_setup_test_heading)) { TestCard(state, onIntent) }
        Section(stringResource(R.string.notify_setup_server_heading)) { ServerCard(state, onIntent) }
    }
}

@Composable
private fun SetupErrorBanner(error: SetupError, busy: Boolean, onIntent: (SetupIntent) -> Unit) {
    val retry = error.retryIntent.takeUnless { busy }
    ErrorBanner(
        modifier = Modifier.testTag(SETUP_ERROR_TAG),
        message = stringResource(
            R.string.notify_setup_error_format,
            stringResource(error.step.failedRes),
            stringResource(error.reason.messageRes),
        ),
        onRetry = retry?.let { intent -> { onIntent(intent) } },
        onDismiss = { onIntent(SetupIntent.DismissError) },
    )
}

/** The step to send again for a failed [SetupError.step]; none when retrying cannot help. */
private val SetupError.retryIntent: SetupIntent?
    get() = if (reason == NotifyFailure.NotConnected) {
        null
    } else {
        when (step) {
            SetupStep.Load -> null
            SetupStep.SaveServer -> SetupIntent.SaveServer
            SetupStep.CheckStatus -> SetupIntent.RefreshStatus
            SetupStep.Install -> SetupIntent.Install
            SetupStep.Remove -> SetupIntent.Remove
            SetupStep.SendTest -> SetupIntent.SendTest
        }
    }

/** A heading over a rounded card, as in the settings and the server list. */
@Composable
private fun Section(heading: String, content: @Composable ColumnScope.() -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Text(
        text = heading,
        modifier = Modifier
            .padding(top = spacing.sm)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            content = content,
        )
    }
}

/**
 * Why ntfy is needed and the link to subscribe with. Whether the app was missing is the screen's
 * own state: it comes from the system, not from the presenter.
 */
@Composable
private fun AppCard(state: SetupState) {
    val spacing = PhopencodeTheme.spacing
    val uriHandler = LocalUriHandler.current
    var appMissing by rememberSaveable { mutableStateOf(false) }

    /*
     * Opens [uri] with the system. Compose's handler wraps ActivityNotFoundException in an
     * IllegalArgumentException whose message holds the URI, so neither is logged or kept.
     */
    fun open(uri: String): Boolean = try {
        uriHandler.openUri(uri)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    BodyText(stringResource(R.string.notify_setup_app_message))
    BodyText(stringResource(R.string.notify_setup_app_privacy))
    val link = state.subscriptionLink
    if (link != null) {
        val description = stringResource(R.string.notify_setup_link_description, state.server.host)
        Text(
            text = stringResource(R.string.notify_setup_link_masked, state.server.host),
            modifier = Modifier
                .testTag(SETUP_LINK_TAG)
                .semantics { contentDescription = description },
            color = MaterialTheme.colorScheme.onSurface,
            style = PhopencodeTheme.codeTypography.body,
        )
    }
    Button(
        onClick = { if (link != null) appMissing = !open(link) },
        enabled = link != null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .testTag(SETUP_OPEN_TAG),
    ) {
        Text(stringResource(R.string.notify_setup_open))
    }
    if (appMissing) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag(SETUP_APP_MISSING_TAG),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            ErrorBanner(
                message = stringResource(R.string.notify_setup_app_missing),
                onDismiss = { appMissing = false },
            )
            OutlinedButton(
                // With no browser either, there is nothing more to offer; the note stays.
                onClick = { open(NTFY_APP_PAGE) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.touchTarget)
                    .testTag(SETUP_GET_APP_TAG),
            ) {
                Text(stringResource(R.string.notify_setup_get_app))
            }
        }
    }
}

@Composable
private fun ServiceCard(state: SetupState, onIntent: (SetupIntent) -> Unit, onRemove: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    val idle = !state.busy
    BodyText(stringResource(R.string.notify_setup_service_message))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            StatusChip(
                status = state.status.tone,
                modifier = Modifier.testTag(SETUP_STATUS_TAG),
                label = stringResource(state.status.labelRes),
            )
        }
        IconButton(
            onClick = { onIntent(SetupIntent.RefreshStatus) },
            enabled = idle,
            modifier = Modifier.testTag(SETUP_REFRESH_TAG),
        ) {
            Icon(
                imageVector = PhopencodeIcons.Refresh,
                contentDescription = stringResource(R.string.notify_setup_refresh),
            )
        }
    }
    Button(
        onClick = { onIntent(SetupIntent.Install) },
        enabled = idle,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .testTag(SETUP_INSTALL_TAG),
    ) {
        Text(stringResource(state.status.installRes))
    }
    if (state.status.isInstalled) {
        OutlinedButton(
            onClick = onRemove,
            enabled = idle,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.touchTarget)
                .testTag(SETUP_REMOVE_TAG),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
        ) {
            Text(stringResource(R.string.notify_setup_remove))
        }
    }
}

@Composable
private fun TestCard(state: SetupState, onIntent: (SetupIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    BodyText(stringResource(R.string.notify_setup_test_message))
    FilledTonalButton(
        onClick = { onIntent(SetupIntent.SendTest) },
        enabled = !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .testTag(SETUP_TEST_TAG),
    ) {
        Text(stringResource(R.string.notify_setup_send_test))
    }
    if (state.testSent) TestSent()
}

/** The ntfy server took the test: a soft success note with a check, read out when it appears. */
@Composable
private fun TestSent() {
    val spacing = PhopencodeTheme.spacing
    val success = PhopencodeTheme.status.success
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SETUP_TEST_SENT_TAG)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        color = success.container,
        contentColor = success.onContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            // Decorative: the text says it.
            Icon(
                imageVector = PhopencodeIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(spacing.lg),
                tint = success.color,
            )
            Text(text = stringResource(R.string.notify_setup_test_sent), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ServerCard(state: SetupState, onIntent: (SetupIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val problem = state.serverProblem
    val canSave = state.serverEdited && problem == null && !state.busy
    val note = when {
        problem != null -> stringResource(problem.messageRes)
        state.status.isInstalled -> stringResource(R.string.notify_setup_server_reinstall_hint)
        else -> null
    }
    BodyText(stringResource(R.string.notify_setup_server_message))
    OutlinedTextField(
        value = state.serverText,
        onValueChange = { onIntent(SetupIntent.EditServer(it)) },
        modifier = Modifier.fillMaxWidth().testTag(SETUP_SERVER_FIELD_TAG),
        singleLine = true,
        label = { Text(stringResource(R.string.notify_setup_server_label)) },
        isError = problem != null,
        supportingText = note?.let { text -> { Text(text) } },
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (canSave) onIntent(SetupIntent.SaveServer) }),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onIntent(SetupIntent.ResetServer) },
            enabled = state.server != NtfyServer.DEFAULT && !state.busy,
            modifier = Modifier.heightIn(min = spacing.touchTarget).testTag(SETUP_SERVER_RESET_TAG),
        ) {
            Text(stringResource(R.string.notify_setup_server_reset))
        }
        Button(
            onClick = { onIntent(SetupIntent.SaveServer) },
            enabled = canSave,
            modifier = Modifier.heightIn(min = spacing.touchTarget).testTag(SETUP_SERVER_SAVE_TAG),
        ) {
            Text(stringResource(R.string.notify_setup_server_save))
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Work under way, as a still message rather than core/ui's `Loading`: its spinner never stops, and
 * Robolectric's `idle()` in the app's ActivityScenario tests would then never return. TalkBack hears
 * the same as for `Loading`: the message, an indeterminate progress and a polite live region.
 */
@Composable
private fun StillProgress(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(PhopencodeTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val NotifyServiceStatus?.isInstalled: Boolean
    get() = this == NotifyServiceStatus.Stopped || this == NotifyServiceStatus.Running

private val NotifyServiceStatus?.tone: StatusTone
    get() = when (this) {
        NotifyServiceStatus.Running -> StatusTone.Success
        NotifyServiceStatus.Stopped -> StatusTone.Warning
        NotifyServiceStatus.NotInstalled, null -> StatusTone.Info
    }

@get:StringRes
private val NotifyServiceStatus?.labelRes: Int
    get() = when (this) {
        NotifyServiceStatus.NotInstalled -> R.string.notify_setup_status_not_installed
        NotifyServiceStatus.Stopped -> R.string.notify_setup_status_stopped
        NotifyServiceStatus.Running -> R.string.notify_setup_status_running
        null -> R.string.notify_setup_status_unknown
    }

/** What installing does now: install, start a stopped service, or install over a running one. */
@get:StringRes
private val NotifyServiceStatus?.installRes: Int
    get() = when (this) {
        NotifyServiceStatus.NotInstalled, null -> R.string.notify_setup_install
        NotifyServiceStatus.Stopped -> R.string.notify_setup_install_start
        NotifyServiceStatus.Running -> R.string.notify_setup_reinstall
    }

@get:StringRes
private val SetupStep.progressRes: Int
    get() = when (this) {
        SetupStep.Load -> R.string.notify_setup_loading
        SetupStep.SaveServer -> R.string.notify_setup_progress_save_server
        SetupStep.CheckStatus -> R.string.notify_setup_progress_check_status
        SetupStep.Install -> R.string.notify_setup_progress_install
        SetupStep.Remove -> R.string.notify_setup_progress_remove
        SetupStep.SendTest -> R.string.notify_setup_progress_send_test
    }

@get:StringRes
private val SetupStep.failedRes: Int
    get() = when (this) {
        SetupStep.Load -> R.string.notify_setup_error_step_load
        SetupStep.SaveServer -> R.string.notify_setup_error_step_save_server
        SetupStep.CheckStatus -> R.string.notify_setup_error_step_check_status
        SetupStep.Install -> R.string.notify_setup_error_step_install
        SetupStep.Remove -> R.string.notify_setup_error_step_remove
        SetupStep.SendTest -> R.string.notify_setup_error_step_send_test
    }

@get:StringRes
private val NotifyFailure.messageRes: Int
    get() = when (this) {
        NotifyFailure.NotConnected -> R.string.notify_setup_error_not_connected
        NotifyFailure.Unreachable -> R.string.notify_setup_error_unreachable
        NotifyFailure.Unauthorized -> R.string.notify_setup_error_unauthorized
        NotifyFailure.NotFound -> R.string.notify_setup_error_not_found
        NotifyFailure.Rejected -> R.string.notify_setup_error_rejected
        NotifyFailure.ServerFailed -> R.string.notify_setup_error_server_failed
        NotifyFailure.PythonMissing -> R.string.notify_setup_error_python_missing
        NotifyFailure.PythonTooOld -> R.string.notify_setup_error_python_too_old
        NotifyFailure.InstallFailed -> R.string.notify_setup_error_install_failed
        NotifyFailure.StorageFailed -> R.string.notify_setup_error_storage_failed
        NotifyFailure.NtfyFailed -> R.string.notify_setup_error_ntfy_failed
        NotifyFailure.Unknown -> R.string.notify_setup_error_unknown
    }

@get:StringRes
private val NtfyServerProblem.messageRes: Int
    get() = when (this) {
        NtfyServerProblem.Blank -> R.string.notify_setup_server_blank
        NtfyServerProblem.NotHttps -> R.string.notify_setup_server_not_https
        NtfyServerProblem.Invalid -> R.string.notify_setup_server_invalid
    }
