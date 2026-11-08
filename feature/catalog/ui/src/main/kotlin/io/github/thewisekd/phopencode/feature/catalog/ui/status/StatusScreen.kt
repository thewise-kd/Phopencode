package io.github.thewisekd.phopencode.feature.catalog.ui.status

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.api.McpServer
import io.github.thewisekd.phopencode.core.api.McpStatus
import io.github.thewisekd.phopencode.core.api.Plugin
import io.github.thewisekd.phopencode.core.api.PluginStatus
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.component.StatusChip
import io.github.thewisekd.phopencode.core.ui.component.StatusTone
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.catalog.ui.R

private val ICON_CIRCLE = 40.dp
private val ICON_SIZE = 22.dp

/**
 * The MCP servers and plugins of the connected server, `CatalogRoute.Status`. Draws [state] and sends
 * [onIntent]; it decides nothing itself.
 *
 * View only: each entry is a rounded card with its name, a status badge that says its status in
 * words (the color and icon repeat it, never replace it) and, when the entry carries one, the error
 * in the server's words. Nothing here connects, disables or authorizes anything; the top bar's
 * refresh reads both lists again.
 *
 * The two lists stand apart, as the contract reads them: one that is loading or failed leaves the
 * other on screen. Only when both are read and empty does the screen show its empty state.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun StatusScreen(
    route: CatalogRoute.Status,
    state: StatusState,
    onIntent: (StatusIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstRead = state.mcp.isFirstRead && state.plugins.isFirstRead
    val updating = !firstRead && (state.mcp.isRefreshing || state.plugins.isRefreshing)
    PhopencodeScaffold(
        title = stringResource(R.string.catalog_status_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(StatusIntent.Close) },
        subtitle = if (updating) stringResource(R.string.catalog_status_updating) else null,
        actions = {
            IconButton(onClick = { onIntent(StatusIntent.Refresh) }) {
                Icon(
                    imageVector = PhopencodeIcons.Refresh,
                    contentDescription = stringResource(R.string.catalog_status_refresh),
                )
            }
        },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding)
        when {
            firstRead -> StillLoading(stringResource(R.string.catalog_status_loading), content)
            state.mcp.isEmpty && state.plugins.isEmpty -> EmptyState(
                modifier = content,
                title = stringResource(R.string.catalog_status_empty_title),
                message = stringResource(R.string.catalog_status_empty_message),
                icon = PhopencodeIcons.Server,
            )
            else -> StatusList(state, onIntent, content)
        }
    }
}

/** Read for the first time: loading, nothing to show yet and no error. */
private val CatalogList<*>.isFirstRead: Boolean
    get() = loading && items.isEmpty() && error == null

/** Read again over entries already on screen. */
private val CatalogList<*>.isRefreshing: Boolean
    get() = loading && items.isNotEmpty()

@Composable
private fun StatusList(state: StatusState, onIntent: (StatusIntent) -> Unit, modifier: Modifier) {
    val spacing = PhopencodeTheme.spacing
    val retry = { onIntent(StatusIntent.Refresh) }
    val mcpLoading = stringResource(R.string.catalog_status_mcp_loading)
    val mcpEmpty = stringResource(R.string.catalog_status_mcp_empty)
    val mcpError = state.mcp.error?.let { stringResource(R.string.catalog_status_mcp_error, stringResource(it.messageRes)) }
    val pluginsLoading = stringResource(R.string.catalog_status_plugins_loading)
    val pluginsEmpty = stringResource(R.string.catalog_status_plugins_empty)
    val pluginsError = state.plugins.error?.let {
        stringResource(R.string.catalog_status_plugins_error, stringResource(it.messageRes))
    }
    val mcpHeading = stringResource(R.string.catalog_status_mcp_heading)
    val pluginsHeading = stringResource(R.string.catalog_status_plugins_heading)
    LazyColumn(
        modifier = modifier.testTag(STATUS_LIST_TAG),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        section(
            key = "mcp",
            heading = mcpHeading,
            list = state.mcp,
            loading = mcpLoading,
            empty = mcpEmpty,
            error = mcpError,
            onRetry = retry,
        ) { _, server -> McpRow(server) }
        section(
            key = "plugins",
            heading = pluginsHeading,
            list = state.plugins,
            loading = pluginsLoading,
            empty = pluginsEmpty,
            error = pluginsError,
            onRetry = retry,
        ) { index, plugin -> PluginRow(index, plugin) }
    }
}

/**
 * One list under its heading: the error above whatever was read before it, then the entries, or a
 * line saying the list is loading or empty when there are none.
 */
private fun <T> LazyListScope.section(
    key: String,
    heading: String,
    list: CatalogList<T>,
    loading: String,
    empty: String,
    error: String?,
    onRetry: () -> Unit,
    row: @Composable (Int, T) -> Unit,
) {
    item(key = "$key:heading") { SectionHeading(heading) }
    if (error != null) {
        item(key = "$key:error") { ErrorBanner(message = error, onRetry = onRetry) }
    }
    when {
        list.items.isNotEmpty() -> itemsIndexed(list.items, key = { index, _ -> "$key:$index" }) { index, entry ->
            row(index, entry)
        }
        list.loading -> item(key = "$key:loading") { StillLoading(loading, Modifier.fillMaxWidth()) }
        list.isEmpty -> item(key = "$key:empty") { SectionNote(empty) }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(top = PhopencodeTheme.spacing.sm)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun SectionNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = PhopencodeTheme.spacing.xs),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Loading, as a still message rather than core/ui's `Loading`: its spinner never stops, and
 * Robolectric's `idle()` in the app's ActivityScenario tests, which open this screen while the
 * presenter still reads, would then never return (see `CatalogIdleTest`). TalkBack hears the same as
 * for `Loading`: the message, an indeterminate progress and a polite live region.
 */
@Composable
private fun StillLoading(message: String, modifier: Modifier = Modifier) {
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

@Composable
private fun McpRow(server: McpServer) {
    val badge = server.status.badge()
    EntryCard(
        icon = PhopencodeIcons.Server,
        name = server.name,
        tone = badge.tone,
        label = badge.label,
        badgeTag = mcpBadgeTag(server.name),
        error = server.error,
    )
}

@Composable
private fun PluginRow(index: Int, plugin: Plugin) {
    val badge = plugin.status.badge()
    EntryCard(
        icon = PhopencodeIcons.Sparkle,
        name = plugin.id?.takeIf { it.isNotBlank() } ?: stringResource(R.string.catalog_status_plugin_unnamed),
        tone = badge.tone,
        label = badge.label,
        badgeTag = pluginBadgeTag(index),
        error = plugin.error,
    )
}

/**
 * One entry as a rounded card, in the style of the server and session lists: a tinted icon, the
 * name, the status badge and, when there is one, the error. Not tappable.
 */
@Composable
private fun EntryCard(
    icon: ImageVector,
    name: String,
    tone: StatusTone,
    label: String,
    badgeTag: String,
    error: String?,
) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = spacing.touchTarget)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Surface(
                modifier = Modifier.size(ICON_CIRCLE),
                shape = CircleShape,
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = name,
                    color = colors.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusChip(status = tone, label = label, modifier = Modifier.testTag(badgeTag))
                if (!error.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.catalog_status_entry_error, error),
                        color = PhopencodeTheme.status.danger.color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** A badge's tone and its words. */
private data class Badge(val tone: StatusTone, val label: String)

@Composable
private fun McpStatus.badge(): Badge = when (this) {
    McpStatus.Connected -> Badge(StatusTone.Success, stringResource(R.string.catalog_status_badge_connected))
    McpStatus.Pending -> Badge(StatusTone.Info, stringResource(R.string.catalog_status_badge_pending))
    McpStatus.Disabled -> Badge(StatusTone.Info, stringResource(R.string.catalog_status_badge_disabled))
    McpStatus.Failed -> Badge(StatusTone.Danger, stringResource(R.string.catalog_status_badge_failed))
    McpStatus.NeedsAuth -> Badge(StatusTone.Warning, stringResource(R.string.catalog_status_badge_needs_auth))
    is McpStatus.Unknown -> Badge(StatusTone.Info, stringResource(R.string.catalog_status_badge_unknown, raw))
}

@Composable
private fun PluginStatus.badge(): Badge = when (this) {
    PluginStatus.Active -> Badge(StatusTone.Success, stringResource(R.string.catalog_status_badge_active))
    PluginStatus.Failed -> Badge(StatusTone.Danger, stringResource(R.string.catalog_status_badge_failed))
    is PluginStatus.Unknown -> Badge(StatusTone.Info, stringResource(R.string.catalog_status_badge_unknown, raw))
}

@get:StringRes
private val CatalogFailure.messageRes: Int
    get() = when (this) {
        CatalogFailure.NotConnected -> R.string.catalog_status_failure_not_connected
        CatalogFailure.Unreachable -> R.string.catalog_status_failure_unreachable
        CatalogFailure.Unauthorized -> R.string.catalog_status_failure_unauthorized
        CatalogFailure.NotFound -> R.string.catalog_status_failure_not_found
        CatalogFailure.Rejected -> R.string.catalog_status_failure_rejected
        CatalogFailure.ServerFailed -> R.string.catalog_status_failure_server_failed
        CatalogFailure.Unknown -> R.string.catalog_status_failure_unknown
    }
