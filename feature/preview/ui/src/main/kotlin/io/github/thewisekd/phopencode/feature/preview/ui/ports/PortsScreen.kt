package io.github.thewisekd.phopencode.feature.preview.ui.ports

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.EmptyStateAction
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPort
import io.github.thewisekd.phopencode.feature.preview.ui.R

/** How much a disabled row's text fades, as Material fades disabled content. */
private const val DISABLED_ALPHA = 0.38f

/**
 * The port picker (P13.9): which port of the server to preview. Draws [state] and sends [onIntent];
 * it decides nothing itself, and logs nothing: neither the ports nor what the user types.
 *
 * - **Top bar:** back closes the screen; refresh lists the ports again. It waits while a listing runs.
 * - **Status:** a still line while the ports are listed for the first time or again.
 * - **Not connected:** says to go back and connect again, with no retry and no list: listing again
 *   cannot help.
 * - **Failed listing:** its error with retry and dismiss, above the ports it had, if any.
 * - **Ports:** each port the server listens on, a row of at least 48 dp that opens it. The opencode
 *   server's own port is listed disabled, with why.
 * - **Empty:** no port but opencode's says to start the dev server, or to ask opencode to, then
 *   refresh. A server that cannot list its ports says to enter the port by hand.
 * - **Manual entry:** pinned below the scrolling part, above the keyboard, so it is always in reach:
 *   a field with a numeric keyboard whose Go key opens the port, an Open button, and in place of its
 *   hint why the entry cannot be opened.
 *
 * Nothing on it runs an endless animation: Robolectric's `idle()` in the app's tests would never
 * return. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun PortsScreen(
    route: PreviewRoute.Ports,
    state: PortsState,
    onIntent: (PortsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.preview_ports_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(PortsIntent.Close) },
        actions = {
            IconButton(
                onClick = { onIntent(PortsIntent.Refresh) },
                enabled = !state.busy,
                modifier = Modifier.testTag(PortsTags.REFRESH),
            ) {
                Icon(
                    imageVector = PhopencodeIcons.Refresh,
                    contentDescription = stringResource(R.string.preview_ports_refresh),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(PortsTags.CONTENT)
                    .verticalScroll(rememberScrollState()),
            ) {
                PortsContent(state, onIntent)
            }
            HorizontalDivider()
            ManualEntry(state, onIntent)
        }
    }
}

@Composable
private fun PortsContent(state: PortsState, onIntent: (PortsIntent) -> Unit) {
    if (state.loading) {
        StillProgress(R.string.preview_ports_loading, PortsTags.LOADING)
        return
    }
    if (state.refreshing) StillProgress(R.string.preview_ports_refreshing, PortsTags.REFRESHING)
    val error = state.error
    if (error != null) {
        ListingError(error, onIntent)
        // Without a connection the list is stale and an empty state would mislead.
        if (error == PreviewFailure.NotConnected) return
    }
    if (state.ports.isNotEmpty()) PortList(state.ports, onIntent)
    when {
        state.unsupported -> EmptyState(
            modifier = Modifier.fillMaxWidth().testTag(PortsTags.UNSUPPORTED),
            title = stringResource(R.string.preview_ports_unsupported_title),
            message = stringResource(R.string.preview_ports_unsupported_message),
            icon = PhopencodeIcons.Info,
        )
        // A failed listing explains itself; a list with only opencode's port has nothing to open.
        error == null && state.ports.all { it.isOpencode } -> {
            val refreshLabel = stringResource(R.string.preview_ports_empty_refresh)
            EmptyState(
                modifier = Modifier.fillMaxWidth().testTag(PortsTags.EMPTY),
                title = stringResource(R.string.preview_ports_empty_title),
                message = stringResource(R.string.preview_ports_empty_message),
                icon = PhopencodeIcons.Server,
                action = EmptyStateAction(refreshLabel) { onIntent(PortsIntent.Refresh) },
            )
        }
    }
}

/** The failed listing: not connected says so alone; any other failure can be retried or dismissed. */
@Composable
private fun ListingError(error: PreviewFailure, onIntent: (PortsIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val retryable = error.canRetry
    ErrorBanner(
        modifier = Modifier
            .padding(horizontal = spacing.lg, vertical = spacing.sm)
            .testTag(if (retryable) PortsTags.ERROR else PortsTags.NOT_CONNECTED),
        message = stringResource(error.listingMessageRes()),
        onRetry = if (retryable) ({ onIntent(PortsIntent.Refresh) }) else null,
        onDismiss = if (retryable) ({ onIntent(PortsIntent.DismissError) }) else null,
    )
}

/**
 * Something is being listed. A still line rather than a spinner: an endless animation keeps
 * Robolectric's `idle()` from returning. TalkBack hears the message, an indeterminate progress and a
 * polite live region, as for core/ui's `Loading`.
 */
@Composable
private fun StillProgress(@StringRes text: Int, tag: String) {
    val message = stringResource(text)
    val spacing = PhopencodeTheme.spacing
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PortList(ports: List<PreviewPort>, onIntent: (PortsIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Text(
        text = stringResource(R.string.preview_ports_list_heading),
        modifier = Modifier
            .padding(start = spacing.lg, end = spacing.lg, top = spacing.md, bottom = spacing.xs)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
    ports.forEach { PortRow(it, onIntent) }
}

/**
 * One port: a row that opens it, or, for the opencode server's own port, a disabled row that says
 * why it cannot be opened. TalkBack reads the row as one button, disabled for opencode's.
 */
@Composable
private fun PortRow(port: PreviewPort, onIntent: (PortsIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    val enabled = !port.isOpencode
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .testTag(PortsTags.port(port.port))
            .clickable(enabled = enabled, role = Role.Button) { onIntent(PortsIntent.Open(port.port)) }
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        val content = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = DISABLED_ALPHA)
        Icon(
            imageVector = if (enabled) PhopencodeIcons.Server else PhopencodeIcons.Terminal,
            contentDescription = null,
            tint = if (enabled) colors.primary else content,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                text = stringResource(R.string.preview_ports_port, port.port),
                color = content,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!enabled) {
                Text(
                    text = stringResource(R.string.preview_ports_opencode),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (enabled) {
            Icon(imageVector = PhopencodeIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

/**
 * The port typed by hand. The keyboard is numeric and its Go key opens the port, as the button does;
 * the logic checks the entry, and its problem shows in place of the hint, announced when it appears.
 */
@Composable
private fun ManualEntry(state: PortsState, onIntent: (PortsIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val problem = state.manualProblem
    val open = { onIntent(PortsIntent.OpenManualPort) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.preview_ports_manual_heading),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OutlinedTextField(
                value = state.manualText,
                onValueChange = { onIntent(PortsIntent.EditManualPort(it)) },
                modifier = Modifier.weight(1f).testTag(PortsTags.MANUAL_FIELD),
                label = { Text(stringResource(R.string.preview_ports_manual_label)) },
                supportingText = {
                    if (problem != null) {
                        Text(
                            text = stringResource(problem.messageRes),
                            modifier = Modifier
                                .testTag(PortsTags.MANUAL_PROBLEM)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    } else {
                        Text(stringResource(R.string.preview_ports_manual_hint))
                    }
                },
                isError = problem != null,
                keyboardOptions = PORT_KEYBOARD,
                keyboardActions = KeyboardActions(onGo = { open() }),
                singleLine = true,
            )
            Button(
                onClick = open,
                // Level with the field's box, below the room its floating label takes.
                modifier = Modifier
                    .padding(top = spacing.sm)
                    .heightIn(min = spacing.touchTarget)
                    .testTag(PortsTags.MANUAL_OPEN),
            ) {
                Text(stringResource(R.string.preview_ports_manual_open))
            }
        }
    }
}

/** Digits only, no suggestions; Go opens the port. */
private val PORT_KEYBOARD = KeyboardOptions(
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Number,
    imeAction = ImeAction.Go,
)
