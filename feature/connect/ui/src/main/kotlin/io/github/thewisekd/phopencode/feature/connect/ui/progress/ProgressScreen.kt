package io.github.thewisekd.phopencode.feature.connect.ui.progress

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep
import io.github.thewisekd.phopencode.feature.connect.ui.R

/** The steps the indicator shows, in the order a connect walks them. */
private val INDICATOR_STEPS = listOf(ProgressStep.Ssh, ProgressStep.HostApproval, ProgressStep.Server, ProgressStep.Api)

private val MARKER_SIZE = 32.dp

/** Where one step of the indicator stands. */
internal enum class StepStatus { Done, Current, Failed, Pending }

/**
 * Where [step] stands in [state]: the steps before the running one are done, the running one is
 * current, or failed when the state has an error, and the ones after it wait. Once connected, every
 * step is done.
 */
internal fun stepStatus(step: ProgressStep, state: ProgressState): StepStatus {
    if (state.step == ProgressStep.Connected) return StepStatus.Done
    val running = INDICATOR_STEPS.indexOf(state.step)
    val index = INDICATOR_STEPS.indexOf(step)
    return when {
        index < running -> StepStatus.Done
        index > running -> StepStatus.Pending
        state.error != null -> StepStatus.Failed
        else -> StepStatus.Current
    }
}

/**
 * Connecting to a saved server: its name, a step indicator for SSH, host key approval, the opencode
 * server and its API, and, when the connect stops, why and what can help. Draws [state] and sends
 * [onIntent]; the presenter decides everything. Cancel is always offered.
 *
 * Nothing here animates: the indicator is still, so the screen comes to rest, which Robolectric's
 * idling in the app's ActivityScenario tests needs (an endless spinner never lets it return).
 *
 * The `opencode serve` password is typed into a masked field, held only in plain `remember` while
 * the prompt is open, never in saved state, and leaves the screen only in
 * [ProgressIntent.SubmitServerPassword]. The field empties on submit and on dismiss.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun ProgressScreen(
    route: ConnectRoute.Progress,
    state: ProgressState,
    onIntent: (ProgressIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    PhopencodeScaffold(
        title = stringResource(R.string.connect_progress_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        // Fixed below the scrolling steps, so the actions and Cancel are always in reach.
        bottomBar = { BottomActions(state.actions, onIntent) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            if (state.serverName.isNotEmpty()) {
                Text(
                    text = state.serverName,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Text(
                text = stringResource(statusText(state)),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
            )
            StepIndicator(state)
            state.error?.let { error ->
                ErrorBanner(
                    modifier = Modifier.testTag(PROGRESS_ERROR_TAG),
                    message = errorText(error, state.errorPort),
                )
            }
        }
    }
    if (state.askingServerPassword) {
        ServerPasswordDialog(
            refused = state.error == ProgressError.ServerPasswordRejected,
            onSubmit = { onIntent(ProgressIntent.SubmitServerPassword(it)) },
            onDismiss = { onIntent(ProgressIntent.DismissServerPassword) },
        )
    }
}

@StringRes
private fun statusText(state: ProgressState): Int = when {
    state.error != null -> R.string.connect_progress_status_failed
    state.step == ProgressStep.Connected -> R.string.connect_progress_status_connected
    else -> R.string.connect_progress_status_connecting
}

@Composable
private fun StepIndicator(state: ProgressState) {
    Column(verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm)) {
        INDICATOR_STEPS.forEachIndexed { index, step ->
            StepRow(number = index + 1, step = step, status = stepStatus(step, state))
        }
    }
}

/** One step: a marker, its name and where it stands. TalkBack reads the name, then the standing. */
@Composable
private fun StepRow(number: Int, step: ProgressStep, status: StepStatus) {
    val spacing = PhopencodeTheme.spacing
    val statusLabel = stringResource(statusLabel(status))
    val emphasised = status == StepStatus.Current || status == StepStatus.Failed
    val labelColor = when (status) {
        StepStatus.Failed -> PhopencodeTheme.status.danger.color
        StepStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(stepTag(step))
            .semantics(mergeDescendants = true) { stateDescription = statusLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        StepMarker(number, status)
        Text(
            text = stringResource(stepLabel(step)),
            modifier = Modifier.weight(1f),
            color = labelColor,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            text = statusLabel,
            // Already read out as the row's state.
            modifier = Modifier.clearAndSetSemantics {},
            color = labelColor,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** A still circle: a tick when done, a cross when failed, otherwise the step's number. Decorative. */
@Composable
private fun StepMarker(number: Int, status: StepStatus) {
    val colors = MaterialTheme.colorScheme
    val (fill, content) = when (status) {
        StepStatus.Done -> PhopencodeTheme.status.success.color to PhopencodeTheme.status.success.onColor
        StepStatus.Failed -> PhopencodeTheme.status.danger.color to PhopencodeTheme.status.danger.onColor
        StepStatus.Current -> colors.primary to colors.onPrimary
        StepStatus.Pending -> Color.Transparent to colors.onSurfaceVariant
    }
    val outline = if (status == StepStatus.Pending) colors.outline else fill
    Box(
        modifier = Modifier
            .size(MARKER_SIZE)
            .clearAndSetSemantics {}
            .background(fill, CircleShape)
            .border(2.dp, outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            StepStatus.Done -> Canvas(Modifier.size(MARKER_SIZE / 2)) {
                val stroke = size.minDimension / 6
                fun at(x: Float, y: Float) = Offset(size.width * x, size.height * y)
                drawLine(content, at(0.1f, 0.55f), at(0.4f, 0.85f), stroke, StrokeCap.Round)
                drawLine(content, at(0.4f, 0.85f), at(0.9f, 0.2f), stroke, StrokeCap.Round)
            }
            StepStatus.Failed -> Canvas(Modifier.size(MARKER_SIZE / 2)) {
                val stroke = size.minDimension / 6
                fun at(x: Float, y: Float) = Offset(size.width * x, size.height * y)
                drawLine(content, at(0.15f, 0.15f), at(0.85f, 0.85f), stroke, StrokeCap.Round)
                drawLine(content, at(0.85f, 0.15f), at(0.15f, 0.85f), stroke, StrokeCap.Round)
            }
            StepStatus.Current, StepStatus.Pending -> Text(
                text = number.toString(),
                color = content,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** The state's actions, in a fixed order with the first as the main one, then Cancel, always there. */
@Composable
private fun BottomActions(actions: Set<ProgressAction>, onIntent: (ProgressIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val buttonModifier = Modifier.fillMaxWidth().heightIn(min = spacing.touchTarget)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        ProgressAction.entries.filter { it in actions }.forEachIndexed { index, action ->
            val label = stringResource(actionLabel(action))
            val onClick = { onIntent(actionIntent(action)) }
            if (index == 0) {
                Button(onClick = onClick, modifier = buttonModifier) { Text(label) }
            } else {
                OutlinedButton(onClick = onClick, modifier = buttonModifier) { Text(label) }
            }
        }
        TextButton(onClick = { onIntent(ProgressIntent.Cancel) }, modifier = buttonModifier) {
            Text(stringResource(R.string.connect_progress_cancel))
        }
    }
}

/**
 * Asks for the `opencode serve` password. What is typed stays in this dialog's plain `remember`, is
 * emptied before it is sent or when the prompt is dismissed, and leaves only through [onSubmit].
 */
@Composable
private fun ServerPasswordDialog(refused: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val submit = {
        if (typed.isNotEmpty()) {
            val entered = typed
            typed = ""
            onSubmit(entered)
        }
    }
    val dismiss = {
        typed = ""
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Text(stringResource(R.string.connect_progress_password_title), Modifier.semantics { heading() })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm)) {
                Text(stringResource(R.string.connect_progress_password_message))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxWidth().testTag(SERVER_SECRET_FIELD_TAG),
                    label = { Text(stringResource(R.string.connect_progress_password_label)) },
                    singleLine = true,
                    isError = refused,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                if (refused) {
                    Text(
                        text = stringResource(R.string.connect_progress_error_server_password_rejected),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = submit,
                enabled = typed.isNotEmpty(),
                modifier = Modifier.heightIn(min = PhopencodeTheme.spacing.touchTarget),
            ) {
                Text(stringResource(R.string.connect_progress_password_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = PhopencodeTheme.spacing.touchTarget)) {
                Text(stringResource(R.string.connect_progress_cancel))
            }
        },
    )
}

@StringRes
private fun stepLabel(step: ProgressStep): Int = when (step) {
    ProgressStep.Ssh -> R.string.connect_progress_step_ssh
    ProgressStep.HostApproval -> R.string.connect_progress_step_host_approval
    ProgressStep.Server -> R.string.connect_progress_step_server
    ProgressStep.Api, ProgressStep.Connected -> R.string.connect_progress_step_api
}

@StringRes
private fun statusLabel(status: StepStatus): Int = when (status) {
    StepStatus.Done -> R.string.connect_progress_step_done
    StepStatus.Current -> R.string.connect_progress_step_current
    StepStatus.Failed -> R.string.connect_progress_step_failed
    StepStatus.Pending -> R.string.connect_progress_step_pending
}

@StringRes
private fun actionLabel(action: ProgressAction): Int = when (action) {
    ProgressAction.Retry -> R.string.connect_progress_action_retry
    ProgressAction.EditServer -> R.string.connect_progress_action_edit_server
    ProgressAction.ReviewHostKey -> R.string.connect_progress_action_review_host_key
    ProgressAction.EnterServerPassword -> R.string.connect_progress_action_enter_password
}

private fun actionIntent(action: ProgressAction): ProgressIntent = when (action) {
    ProgressAction.Retry -> ProgressIntent.Retry
    ProgressAction.EditServer -> ProgressIntent.EditServer
    ProgressAction.ReviewHostKey -> ProgressIntent.ReviewHostKey
    ProgressAction.EnterServerPassword -> ProgressIntent.EnterServerPassword
}

/**
 * The message of [error]. [ProgressError.ServerNotOnPort] names [port]; without one, which the
 * presenter never sends, it falls back to the incomplete-settings message rather than a raw template.
 */
@Composable
private fun errorText(error: ProgressError, port: Int?): String = when {
    error != ProgressError.ServerNotOnPort -> stringResource(errorMessage(error))
    port != null -> stringResource(errorMessage(error), port)
    else -> stringResource(R.string.connect_progress_error_invalid_profile)
}

/** A short message per error, saying what to do next. `when` without `else`: a new error fails to compile. */
@StringRes
internal fun errorMessage(error: ProgressError): Int = when (error) {
    ProgressError.InvalidProfile -> R.string.connect_progress_error_invalid_profile
    ProgressError.SecretMissing -> R.string.connect_progress_error_secret_missing
    ProgressError.SecretUnreadable -> R.string.connect_progress_error_secret_unreadable
    ProgressError.StorageUnavailable -> R.string.connect_progress_error_storage_unavailable
    ProgressError.Unreachable -> R.string.connect_progress_error_unreachable
    ProgressError.Timeout -> R.string.connect_progress_error_timeout
    ProgressError.Disconnected -> R.string.connect_progress_error_disconnected
    ProgressError.AuthenticationFailed -> R.string.connect_progress_error_authentication_failed
    ProgressError.HostKeyRejected -> R.string.connect_progress_error_host_key_rejected
    ProgressError.HostKeyChanged -> R.string.connect_progress_error_host_key_changed
    ProgressError.OpencodeMissing -> R.string.connect_progress_error_opencode_missing
    ProgressError.ServerPasswordUnknown -> R.string.connect_progress_error_server_password_unknown
    ProgressError.ServerPasswordRejected -> R.string.connect_progress_error_server_password_rejected
    ProgressError.ServerVersionTooOld -> R.string.connect_progress_error_server_version_too_old
    ProgressError.ServerUnresponsive -> R.string.connect_progress_error_server_unresponsive
    ProgressError.ServerUnusable -> R.string.connect_progress_error_server_unusable
    ProgressError.ServerNotOnPort -> R.string.connect_progress_error_server_not_on_port
    ProgressError.ServerDetectionFailed -> R.string.connect_progress_error_server_detection_failed
    ProgressError.ServerStartFailed -> R.string.connect_progress_error_server_start_failed
    ProgressError.ServerLost -> R.string.connect_progress_error_server_lost
    ProgressError.ApiFailed -> R.string.connect_progress_error_api_failed
    ProgressError.Unexpected -> R.string.connect_progress_error_unexpected
}
