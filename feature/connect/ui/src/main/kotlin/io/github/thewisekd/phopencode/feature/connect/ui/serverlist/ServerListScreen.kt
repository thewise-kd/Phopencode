package io.github.thewisekd.phopencode.feature.connect.ui.serverlist

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.ui.component.ConfirmDialog
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.EmptyStateAction
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListState
import io.github.thewisekd.phopencode.feature.connect.ui.R
import kotlinx.coroutines.launch

/** Room under the last server, so the add button never covers it. */
private val FAB_CLEARANCE = 88.dp

/**
 * The saved servers, the app's start screen. Draws [state] and sends [onIntent]; it decides nothing
 * itself. A swipe toward the start only asks to delete ([ServerListIntent.RequestDelete]): the
 * confirmation dialog is drawn from [ServerListState.pendingDelete], and the presenter deletes.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun ServerListScreen(
    route: ConnectRoute.ServerList,
    state: ServerListState,
    onIntent: (ServerListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val addLabel = stringResource(R.string.connect_serverlist_add)
    PhopencodeScaffold(
        title = stringResource(R.string.connect_serverlist_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        actions = {
            TextButton(onClick = { onIntent(ServerListIntent.OpenKeys) }) {
                Text(stringResource(R.string.connect_serverlist_keys))
            }
            TextButton(onClick = { onIntent(ServerListIntent.OpenSettings) }) {
                Text(stringResource(R.string.connect_serverlist_settings))
            }
        },
        floatingActionButton = {
            // The empty state has its own add button; one is enough. Not the extended button: in
            // Material 3 1.4 it hides its text from accessibility services, leaving no label.
            if (state.servers.isNotEmpty()) {
                FloatingActionButton(onClick = { onIntent(ServerListIntent.Add) }) {
                    Icon(imageVector = ServerListIcons.Add, contentDescription = addLabel)
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { error ->
                ErrorBanner(
                    modifier = Modifier.padding(
                        horizontal = PhopencodeTheme.spacing.lg,
                        vertical = PhopencodeTheme.spacing.sm,
                    ),
                    message = stringResource(error.messageRes),
                    onDismiss = { onIntent(ServerListIntent.DismissError) },
                )
            }
            val content = Modifier.fillMaxWidth().weight(1f)
            when {
                state.servers.isNotEmpty() -> ServerList(state.servers, onIntent, content)
                state.loading -> LoadingServers(content)
                else -> EmptyState(
                    modifier = content,
                    title = stringResource(R.string.connect_serverlist_empty_title),
                    message = stringResource(R.string.connect_serverlist_empty_message),
                    action = EmptyStateAction(addLabel) { onIntent(ServerListIntent.Add) },
                )
            }
        }
    }
    state.pendingDelete?.let { server ->
        ConfirmDialog(
            title = stringResource(R.string.connect_serverlist_delete_title),
            message = stringResource(R.string.connect_serverlist_delete_message, server.name),
            onConfirm = { onIntent(ServerListIntent.ConfirmDelete) },
            onDismiss = { onIntent(ServerListIntent.CancelDelete) },
            confirmLabel = stringResource(R.string.connect_serverlist_delete),
            destructive = true,
        )
    }
}

/**
 * The servers are being read. A still message rather than core/ui's `Loading`: its spinner never
 * stops, and Robolectric's `idle()` in the app's ActivityScenario tests, which open this screen
 * while the presenter still loads, would then never return. Reading the saved servers is a local
 * read that ends quickly, so a spinner would barely show anyway. TalkBack hears the same as for
 * `Loading`: the message, an indeterminate progress and a polite live region.
 */
@Composable
private fun LoadingServers(modifier: Modifier = Modifier) {
    val message = stringResource(R.string.connect_serverlist_loading)
    Box(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(PhopencodeTheme.spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@get:StringRes
private val ServerListError.messageRes: Int
    get() = when (this) {
        ServerListError.LoadFailed -> R.string.connect_serverlist_error_load
        ServerListError.SaveFailed -> R.string.connect_serverlist_error_save
        ServerListError.DeleteFailed -> R.string.connect_serverlist_error_delete
    }

@Composable
private fun ServerList(
    servers: List<ServerProfile>,
    onIntent: (ServerListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = FAB_CLEARANCE)) {
        items(servers, key = { it.id.value }) { server ->
            ServerRow(server, onIntent)
            HorizontalDivider()
        }
    }
}

/**
 * One server: a tap connects, the pencil edits, a swipe toward the start asks to delete. The swipe
 * snaps back at once; the dialog that follows comes from the state. Accessibility services, which
 * cannot swipe, get the same request as a custom action.
 */
@Composable
private fun ServerRow(server: ServerProfile, onIntent: (ServerListIntent) -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val requestDelete = { onIntent(ServerListIntent.RequestDelete(server.id)) }
    val deleteLabel = stringResource(R.string.connect_serverlist_delete)
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                requestDelete()
                scope.launch { swipe.reset() }
            }
        },
        backgroundContent = { DeleteBackground() },
    ) {
        ListItem(
            modifier = Modifier
                .clickable(
                    onClickLabel = stringResource(R.string.connect_serverlist_connect),
                    role = Role.Button,
                ) { onIntent(ServerListIntent.Select(server.id)) }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(deleteLabel) {
                            requestDelete()
                            true
                        },
                    )
                },
            headlineContent = { Text(text = server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(text = server.address(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                IconButton(onClick = { onIntent(ServerListIntent.Edit(server.id)) }) {
                    Icon(
                        imageVector = ServerListIcons.Edit,
                        contentDescription = stringResource(R.string.connect_serverlist_edit, server.name),
                    )
                }
            },
        )
    }
}

/** What a swipe uncovers. Decorative: the dialog that follows says what happens. */
@Composable
private fun DeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = PhopencodeTheme.spacing.lg),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = ServerListIcons.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** `user@host:port`; an IPv6 host goes in brackets so its colons stay apart from the port. */
@Composable
private fun ServerProfile.address(): String {
    val shownHost = if (':' in host) "[$host]" else host
    return stringResource(R.string.connect_serverlist_address, username, shownHost, port)
}
