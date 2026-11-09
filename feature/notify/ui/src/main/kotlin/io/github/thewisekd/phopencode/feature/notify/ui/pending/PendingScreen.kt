package io.github.thewisekd.phopencode.feature.notify.ui.pending

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.EmptyStateAction
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingItem
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import io.github.thewisekd.phopencode.feature.notify.ui.R

/**
 * The forms and permission requests waiting for an answer, from every session of the connected
 * server, oldest first. Draws [state] and sends [onIntent]; it decides nothing itself.
 * `phopencode://pending` opens it: the graph declares the deep link, the feature's manifest the
 * intent filter.
 *
 * Each item is a rounded card, like the session list's: its kind, its session's title, and only
 * the form's title or the permission's action, with a mark when a subagent asks. Fields,
 * resources and messages stay in the chat, where the item is answered: they are the server's
 * text and can hold a path or a command. A tap opens the session ([PendingIntent.Open]).
 *
 * A failure with nothing listed takes the screen, with one way out: trying again, or connecting
 * when the connection is gone or was refused. Above kept items it is a banner instead. Progress
 * is shown as still messages, never as a spinner: an endless animation keeps Robolectric's
 * `idle()` in the app's tests from returning.
 *
 * Nothing here is logged. The root keeps the `placeholder:<route>` tag the app's navigation test
 * looks for.
 */
@Composable
fun PendingScreen(
    route: NotifyRoute.Pending,
    state: PendingState,
    onIntent: (PendingIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.notify_pending_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(PendingIntent.Close) },
        actions = {
            IconButton(onClick = { onIntent(PendingIntent.Refresh) }, enabled = !state.loading && !state.refreshing) {
                Icon(
                    imageVector = PhopencodeIcons.Refresh,
                    contentDescription = stringResource(R.string.notify_pending_refresh),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val error = state.error
            if (error != null && state.items.isNotEmpty()) {
                ErrorAboveItems(error, onIntent)
            }
            if (state.refreshing) {
                StillProgress(R.string.notify_pending_refreshing, Modifier.fillMaxWidth())
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.items.isNotEmpty() -> Items(state.items, onIntent)
                    state.loading -> StillProgress(R.string.notify_pending_loading, Modifier.fillMaxSize())
                    // Merged, so that the live region holds the message and TalkBack reads it when it shows;
                    // the way out stays a button of its own.
                    error != null -> EmptyState(
                        modifier = Modifier.fillMaxSize().semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                        },
                        title = stringResource(error.messageRes),
                        icon = PhopencodeIcons.Warning,
                        action = error.wayOut(onIntent),
                    )
                    else -> EmptyState(
                        modifier = Modifier.fillMaxSize().testTag(PENDING_EMPTY_TAG),
                        title = stringResource(R.string.notify_pending_empty),
                        icon = PhopencodeIcons.Check,
                    )
                }
            }
        }
    }
}

/** A banner over items read before the failure: retry or dismiss it, or go and connect. */
@Composable
private fun ErrorAboveItems(error: NotifyFailure, onIntent: (PendingIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Column(
        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
        horizontalAlignment = Alignment.End,
    ) {
        ErrorBanner(
            message = stringResource(error.messageRes),
            onRetry = if (error.needsConnection) null else ({ onIntent(PendingIntent.Refresh) }),
            onDismiss = { onIntent(PendingIntent.DismissError) },
        )
        if (error.needsConnection) {
            TextButton(onClick = { onIntent(PendingIntent.Connect) }) {
                Text(stringResource(R.string.notify_pending_connect))
            }
        }
    }
}

@Composable
private fun Items(items: List<PendingItem>, onIntent: (PendingIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(PENDING_LIST_TAG),
        contentPadding = PaddingValues(vertical = spacing.xs),
    ) {
        items(items, key = { it.key }) { item ->
            ItemCard(
                item = item,
                onOpen = { onIntent(PendingIntent.Open(item)) },
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs),
            )
        }
    }
}

/**
 * One item as a rounded card, at least a touch target tall. The whole card is one button whose
 * click label says it opens the session; TalkBack reads its texts together.
 */
@Composable
private fun ItemCard(item: PendingItem, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceContainer)
            .clickable(onClickLabel = stringResource(R.string.notify_pending_open), role = Role.Button, onClick = onOpen)
            .testTag(pendingItemTag(item))
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = item.icon, contentDescription = null, tint = colors.primary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = stringResource(item.kindRes),
                color = colors.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = item.sessionTitle ?: stringResource(R.string.notify_pending_untitled),
                color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.detail?.let {
                Text(
                    text = it,
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.isChild) SubagentChip()
        }
        Icon(imageVector = PhopencodeIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
    }
}

/** Marks an item a subagent asks for; it opens as a child session. */
@Composable
private fun SubagentChip() {
    val spacing = PhopencodeTheme.spacing
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = stringResource(R.string.notify_pending_subagent),
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** The one line of server text a card shows: the form's title or the permission's action. */
private val PendingItem.detail: String?
    get() = when (this) {
        is PendingItem.FormItem -> form.title
        is PendingItem.PermissionItem -> request.action
    }?.takeIf { it.isNotBlank() }

@get:StringRes
private val PendingItem.kindRes: Int
    get() = when (this) {
        is PendingItem.FormItem -> when (form.kind) {
            FormKind.Question -> R.string.notify_pending_kind_question
            is FormKind.Other -> R.string.notify_pending_kind_form
        }
        is PendingItem.PermissionItem -> R.string.notify_pending_kind_permission
    }

private val PendingItem.icon: ImageVector
    get() = when (this) {
        is PendingItem.FormItem -> when (form.kind) {
            FormKind.Question -> PhopencodeIcons.Chat
            is FormKind.Other -> PhopencodeIcons.Edit
        }
        is PendingItem.PermissionItem -> PhopencodeIcons.Key
    }

/** Reading the list again cannot fix a lost connection or refused credentials: connecting can. */
private val NotifyFailure.needsConnection: Boolean
    get() = this == NotifyFailure.NotConnected || this == NotifyFailure.Unauthorized

/** The way out of a failure that left nothing to show: connecting, or trying again. */
@Composable
private fun NotifyFailure.wayOut(onIntent: (PendingIntent) -> Unit): EmptyStateAction =
    if (needsConnection) {
        EmptyStateAction(stringResource(R.string.notify_pending_connect)) { onIntent(PendingIntent.Connect) }
    } else {
        EmptyStateAction(stringResource(R.string.notify_pending_retry)) { onIntent(PendingIntent.Refresh) }
    }

/** The setup's failures never reach this list; should one come, it reads as not loaded. */
@get:StringRes
private val NotifyFailure.messageRes: Int
    get() = when (this) {
        NotifyFailure.NotConnected -> R.string.notify_pending_error_not_connected
        NotifyFailure.Unreachable -> R.string.notify_pending_error_unreachable
        NotifyFailure.Unauthorized -> R.string.notify_pending_error_unauthorized
        NotifyFailure.NotFound -> R.string.notify_pending_error_not_found
        NotifyFailure.Rejected -> R.string.notify_pending_error_rejected
        NotifyFailure.ServerFailed -> R.string.notify_pending_error_server
        NotifyFailure.PythonMissing, NotifyFailure.PythonTooOld, NotifyFailure.InstallFailed,
        NotifyFailure.StorageFailed, NotifyFailure.NtfyFailed, NotifyFailure.Unknown,
        -> R.string.notify_pending_error_unknown
    }

/**
 * Something loads. A still message rather than core/ui's `Loading`, whose spinner never stops (see
 * `NotifyIdleTest`). TalkBack hears the message, an indeterminate progress and a polite live region.
 */
@Composable
private fun StillProgress(@StringRes text: Int, modifier: Modifier = Modifier) {
    val message = stringResource(text)
    Box(
        modifier = modifier
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
