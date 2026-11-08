package io.github.thewisekd.phopencode.feature.sessions.ui.list

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.EmptyStateAction
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.component.StatusChip
import io.github.thewisekd.phopencode.core.ui.component.StatusTone
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionBadge
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionRow
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import io.github.thewisekd.phopencode.feature.sessions.ui.R
import kotlin.time.Instant
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Room under the last session, so the add button never covers it. */
private val FAB_CLEARANCE = 96.dp

private val RUNNING_MARK_WIDTH = 4.dp

private val CHIP_ICON_SIZE = 14.dp

/** About what the top bar's subtitle shows on a phone beside three icon buttons. */
private const val FOLDER_SUBTITLE_MAX = 32

/** What stands for the folders a compacted path leaves out. Not a word: a mark. */
private const val ELLIPSIS_PREFIX = "…/"

/** The next page is asked for this many rows before the end comes into view. */
private const val LOAD_AHEAD = 3

/**
 * The root sessions of the connected server, newest first. Draws [state] and sends [onIntent]; it
 * decides nothing itself.
 *
 * When the list is limited to a folder ([SessionListState.hasFolder]), the top bar names that folder
 * under its title and has buttons that open the folder's files and git, before the refresh button;
 * the list of every folder has none.
 *
 * Above the rows sit the search field and the archived switch; each row is a rounded card with the
 * session's title, how long ago it changed, its tokens and cost, and its status badge. Pulling the list down, or
 * the refresh button for those who cannot pull, reloads the first page; the end of the list coming
 * into view asks for the next one. Progress is shown as still messages, never as a spinner: an
 * endless animation keeps Robolectric's `idle()` in the app's tests from returning.
 *
 * Session titles are user data: they are drawn, never logged.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    route: SessionsRoute.SessionList,
    state: SessionListState,
    onIntent: (SessionListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val createLabel = stringResource(R.string.sessions_list_create)
    val canRefresh = !state.loading && !state.refreshing
    val now = rememberListNow()
    PhopencodeScaffold(
        title = stringResource(R.string.sessions_list_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        subtitle = state.directory?.takeIf { state.hasFolder }?.let(::compactFolder),
        actions = {
            if (state.hasFolder) {
                IconButton(onClick = { onIntent(SessionListIntent.OpenFiles) }) {
                    Icon(
                        imageVector = PhopencodeIcons.Folder,
                        contentDescription = stringResource(R.string.sessions_list_open_files),
                    )
                }
                IconButton(onClick = { onIntent(SessionListIntent.OpenGit) }) {
                    Icon(
                        imageVector = PhopencodeIcons.Git,
                        contentDescription = stringResource(R.string.sessions_list_open_git),
                    )
                }
            }
            IconButton(onClick = { onIntent(SessionListIntent.Refresh) }, enabled = canRefresh) {
                Icon(
                    imageVector = PhopencodeIcons.Refresh,
                    contentDescription = stringResource(R.string.sessions_list_refresh),
                )
            }
        },
        floatingActionButton = {
            // The empty state has its own button; one is enough. The content overload, so the
            // label is plain text that TalkBack reads: the icon and text overload of Material 3 1.4
            // hides its text from accessibility services.
            if (!(state.isEmpty && !state.isSearching)) {
                ExtendedFloatingActionButton(onClick = { onIntent(SessionListIntent.Create) }) {
                    Icon(imageVector = PhopencodeIcons.Add, contentDescription = null)
                    Spacer(Modifier.width(PhopencodeTheme.spacing.sm))
                    Text(createLabel)
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
                    onRetry = if (error.canRetry) ({ onIntent(SessionListIntent.Retry) }) else null,
                    onDismiss = { onIntent(SessionListIntent.DismissError) },
                )
            }
            if (state.refreshing) {
                StillProgress(R.string.sessions_list_refreshing, Modifier.fillMaxWidth())
            }
            val pull = rememberPullToRefreshState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pullToRefresh(
                        isRefreshing = state.refreshing,
                        state = pull,
                        enabled = !state.loading,
                        onRefresh = { onIntent(SessionListIntent.Refresh) },
                    ),
            ) {
                val listState = rememberLazyListState()
                LoadMoreWhenNearTheEnd(listState, state, onIntent)
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(SESSION_LIST_TAG),
                    state = listState,
                    contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
                ) {
                    controls(state, onIntent)
                    when {
                        state.rows.isNotEmpty() -> sessions(state, now, onIntent)
                        state.loading -> item(key = "loading") {
                            StillProgress(R.string.sessions_list_loading, Modifier.fillParentMaxSize())
                        }
                        state.isEmpty && state.isSearching -> item(key = "no-match") {
                            EmptyState(
                                modifier = Modifier.fillParentMaxSize(),
                                title = stringResource(R.string.sessions_list_no_match_title),
                                message = stringResource(R.string.sessions_list_no_match_message),
                                icon = PhopencodeIcons.Search,
                            )
                        }
                        state.isEmpty -> item(key = "empty") {
                            EmptyState(
                                modifier = Modifier.fillParentMaxSize(),
                                title = stringResource(R.string.sessions_list_empty_title),
                                message = stringResource(R.string.sessions_list_empty_message),
                                icon = PhopencodeIcons.Chat,
                                action = EmptyStateAction(createLabel) { onIntent(SessionListIntent.Create) },
                            )
                        }
                    }
                }
                PullIndicator(pull = pull, refreshing = state.refreshing, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

/**
 * The pull indicator: a ring that fills as the list is pulled, and a still refresh icon while the
 * refresh runs, in place of Material's spinner, which never stops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullIndicator(
    pull: PullToRefreshState,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = refreshing, modifier = modifier) {
        if (refreshing) {
            Icon(
                imageVector = PhopencodeIcons.Refresh,
                contentDescription = null,
                tint = PullToRefreshDefaults.indicatorColor,
            )
        } else {
            CircularProgressIndicator(
                progress = { pull.distanceFraction.coerceIn(0f, 1f) },
                modifier = Modifier.size(PhopencodeTheme.spacing.xl),
                color = PullToRefreshDefaults.indicatorColor,
            )
        }
    }
}

/**
 * Sends [SessionListIntent.LoadMore] when the last rows come into view, while a next page may follow
 * and nothing loads, refreshes or failed: a refresh replaces the rows, so their next page waits for
 * it. A failed next page waits for the user's retry.
 *
 * Each loaded page restarts the check, keyed on the row count: a page can start and end between two
 * frames, so the screen may never see [SessionListState.loadingMore] change, and the end may still be
 * in view after it. Without the restart, the end staying in view would never ask again.
 */
@Composable
private fun LoadMoreWhenNearTheEnd(
    listState: LazyListState,
    state: SessionListState,
    onIntent: (SessionListIntent) -> Unit,
) {
    val send by rememberUpdatedState(onIntent)
    val canLoadMore = state.rows.isNotEmpty() && !state.loading && !state.refreshing && !state.endReached &&
        !state.loadingMore && state.loadMoreError == null
    LaunchedEffect(listState, canLoadMore, state.rows.size) {
        if (!canLoadMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
            last >= info.totalItemsCount - 1 - LOAD_AHEAD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { send(SessionListIntent.LoadMore) }
    }
}

/** The search field and the archived switch. The folder the list is limited to is in the top bar. */
private fun LazyListScope.controls(state: SessionListState, onIntent: (SessionListIntent) -> Unit) {
    item(key = "search") {
        OutlinedTextField(
            value = state.query,
            onValueChange = { onIntent(SessionListIntent.QueryChanged(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.xs),
            label = { Text(stringResource(R.string.sessions_list_search)) },
            leadingIcon = { Icon(imageVector = PhopencodeIcons.Search, contentDescription = null) },
            shape = MaterialTheme.shapes.large,
            singleLine = true,
        )
    }
    item(key = "archived") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PhopencodeTheme.spacing.touchTarget)
                .toggleable(
                    value = state.showArchived,
                    role = Role.Switch,
                    onValueChange = { onIntent(SessionListIntent.ShowArchived(it)) },
                )
                .padding(horizontal = PhopencodeTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sessions_list_show_archived),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = state.showArchived, onCheckedChange = null)
        }
    }
}

/** The rows, then what follows them: the next page loading, or why it did not load. */
private fun LazyListScope.sessions(state: SessionListState, now: Instant, onIntent: (SessionListIntent) -> Unit) {
    val showDirectory = state.directory == null
    items(state.rows, key = { "session:${it.id.value}" }) { row ->
        SessionCard(
            row = row,
            showDirectory = showDirectory,
            now = now,
            onIntent = onIntent,
            modifier = Modifier.padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.xs),
        )
    }
    when {
        state.loadingMore -> item(key = "loading-more") {
            StillProgress(R.string.sessions_list_loading_more, Modifier.fillMaxWidth())
        }
        state.loadMoreError != null -> item(key = "load-more-error") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.sessions_list_load_more_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { onIntent(SessionListIntent.Retry) }) {
                    Text(stringResource(R.string.sessions_list_retry))
                }
            }
        }
    }
}

/**
 * One session as a rounded card: a tap opens its chat, the trailing button manages it. Under the
 * title sit how long ago it changed and its tokens and cost, then its status, its folder when the list
 * spans every folder, and whether it is archived. A running session has an accent bar along its start
 * edge as well as its badge, so it stands out while scrolling.
 */
@Composable
private fun SessionCard(
    row: SessionRow,
    showDirectory: Boolean,
    now: Instant,
    onIntent: (SessionListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = row.title ?: stringResource(R.string.sessions_list_untitled)
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val running = row.status == SessionBadge.Running
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(colors.surfaceContainer)
            .clickable(
                onClickLabel = stringResource(R.string.sessions_list_open),
                role = Role.Button,
            ) { onIntent(SessionListIntent.Open(row.id)) },
    ) {
        if (running) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(RUNNING_MARK_WIDTH)
                    .background(colors.primary)
                    .testTag(sessionRunningMarkTag(row.id)),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = if (running) spacing.lg - RUNNING_MARK_WIDTH else spacing.lg,
                    top = spacing.md,
                    bottom = spacing.md,
                ),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = name,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(row, now)
            Chips(row, showDirectory)
        }
        IconButton(
            onClick = { onIntent(SessionListIntent.Manage(row.id)) },
            modifier = Modifier.padding(top = spacing.xs, end = spacing.xs),
        ) {
            Icon(
                imageVector = PhopencodeIcons.MoreVert,
                contentDescription = stringResource(R.string.sessions_list_manage, name),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

/** How long ago the session changed, and its tokens and cost, muted, on one line when they fit. */
@Composable
private fun MetaLine(row: SessionRow, now: Instant) {
    val time = row.updatedAt?.let { relativeTime(it, now) }
    val usage = row.usage?.let { usageText(it) }
    if (time == null && usage == null) return
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        time?.let { Text(text = it, color = color, style = style, maxLines = 1) }
        if (time != null && usage != null) {
            // A separator mark, not a word; TalkBack skips it.
            Text(text = "·", color = color, style = style, modifier = Modifier.clearAndSetSemantics {})
        }
        usage?.let {
            Text(text = it, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The status badge, the folder when the list spans folders, and the archived mark; nothing when none applies. */
@Composable
private fun Chips(row: SessionRow, showDirectory: Boolean) {
    val badge = row.status.badge
    val directory = row.directory?.takeIf { showDirectory }
    if (badge == null && directory == null && !row.archived) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badge?.let { (tone, label) -> StatusChip(status = tone, label = stringResource(label)) }
        if (row.archived) SmallChip(text = stringResource(R.string.sessions_list_archived))
        directory?.let {
            SmallChip(
                text = it,
                icon = PhopencodeIcons.Folder,
                modifier = Modifier.weight(1f, fill = false).testTag(SESSION_FOLDER_CHIP_TAG),
            )
        }
    }
}

/** A quiet tinted chip. A long path loses its start, not its end: the last folders tell paths apart. */
@Composable
private fun SmallChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val spacing = PhopencodeTheme.spacing
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(imageVector = it, contentDescription = null, modifier = Modifier.size(CHIP_ICON_SIZE)) }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

/**
 * [path] short enough for the top bar's subtitle: whole when it fits in [max] characters, otherwise
 * "…/" and as many of its last folders as fit, at least the last one.
 */
internal fun compactFolder(path: String, max: Int = FOLDER_SUBTITLE_MAX): String {
    if (path.length <= max) return path
    val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return path
    var tail = parts.last()
    for (part in parts.dropLast(1).asReversed()) {
        val longer = "$part/$tail"
        if (ELLIPSIS_PREFIX.length + longer.length > max) break
        tail = longer
    }
    return ELLIPSIS_PREFIX + tail
}

/** What a badge shows: its tone and label. An idle session shows none; most sessions are idle. */
private val SessionBadge.badge: Pair<StatusTone, Int>?
    get() = when (this) {
        SessionBadge.WaitingForInput -> StatusTone.Warning to R.string.sessions_list_badge_waiting
        SessionBadge.Running -> StatusTone.Info to R.string.sessions_list_badge_running
        SessionBadge.Failed -> StatusTone.Danger to R.string.sessions_list_badge_failed
        SessionBadge.Interrupted -> StatusTone.Warning to R.string.sessions_list_badge_interrupted
        SessionBadge.Idle -> null
    }

/** Loading the first page again cannot fix a lost connection or refused credentials. */
private val SessionsFailure.canRetry: Boolean
    get() = when (this) {
        SessionsFailure.NotConnected, SessionsFailure.Unauthorized -> false
        SessionsFailure.Unreachable, SessionsFailure.NotFound, SessionsFailure.Conflict,
        SessionsFailure.Rejected, SessionsFailure.ServerFailed, SessionsFailure.Unknown,
        -> true
    }

@get:StringRes
private val SessionsFailure.messageRes: Int
    get() = when (this) {
        SessionsFailure.NotConnected -> R.string.sessions_list_error_not_connected
        SessionsFailure.Unreachable -> R.string.sessions_list_error_unreachable
        SessionsFailure.Unauthorized -> R.string.sessions_list_error_unauthorized
        SessionsFailure.NotFound -> R.string.sessions_list_error_not_found
        SessionsFailure.Conflict -> R.string.sessions_list_error_conflict
        SessionsFailure.Rejected -> R.string.sessions_list_error_rejected
        SessionsFailure.ServerFailed -> R.string.sessions_list_error_server
        SessionsFailure.Unknown -> R.string.sessions_list_error_unknown
    }

/**
 * Something loads. A still message rather than core/ui's `Loading`: its spinner never stops, and
 * Robolectric's `idle()` in the app's ActivityScenario tests, which open this screen while the
 * presenter loads, would never return. TalkBack hears the same as for `Loading`: the message, an
 * indeterminate progress and a polite live region.
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
