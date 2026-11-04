package io.github.thewisekd.phopencode.feature.connect.ui.folder

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState
import io.github.thewisekd.phopencode.feature.connect.ui.R

/**
 * Picking the project folder on the server. Draws [state] and sends [onIntent]; it decides nothing
 * itself. The current path sits on top with an Up button, enabled only when [FolderState.canGoUp];
 * below come the recent folders (a tap selects one) and the current folder's subfolders (a tap opens
 * one). "Select this folder" picks the folder shown.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun FolderScreen(
    route: ConnectRoute.FolderPicker,
    state: FolderState,
    onIntent: (FolderIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.connect_folder_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(FolderIntent.Close) },
        bottomBar = {
            val path = state.path
            SelectBar(enabled = path != null && !state.loading) { path?.let { onIntent(FolderIntent.Select(it)) } }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PathBar(path = state.path, canGoUp = state.canGoUp) { onIntent(FolderIntent.Up) }
            HorizontalDivider()
            state.error?.let { error ->
                ErrorBanner(
                    modifier = Modifier.padding(
                        horizontal = PhopencodeTheme.spacing.lg,
                        vertical = PhopencodeTheme.spacing.sm,
                    ),
                    message = stringResource(error.messageRes),
                    onRetry = if (error.canRetry) ({ onIntent(FolderIntent.Retry) }) else null,
                    onDismiss = { onIntent(FolderIntent.DismissError) },
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                recentFolders(state.recent, onIntent)
                when {
                    state.loading -> item(key = "loading") { LoadingFolders() }
                    state.entries.isNotEmpty() -> folders(state.entries, onIntent)
                    state.error == null && state.path != null -> item(key = "empty") {
                        EmptyState(
                            modifier = Modifier.fillMaxWidth(),
                            title = stringResource(R.string.connect_folder_empty_title),
                            message = stringResource(R.string.connect_folder_empty_message),
                            icon = FolderIcons.Folder,
                        )
                    }
                }
            }
        }
    }
}

/** Listing the folder again can help after these; the others need something else to change first. */
private val FolderError.canRetry: Boolean
    get() = when (this) {
        FolderError.PermissionDenied, FolderError.NotFound, FolderError.Failed -> true
        FolderError.NotConnected, FolderError.StorageFailed -> false
    }

@get:StringRes
private val FolderError.messageRes: Int
    get() = when (this) {
        FolderError.NotConnected -> R.string.connect_folder_error_not_connected
        FolderError.PermissionDenied -> R.string.connect_folder_error_permission_denied
        FolderError.NotFound -> R.string.connect_folder_error_not_found
        FolderError.Failed -> R.string.connect_folder_error_failed
        FolderError.StorageFailed -> R.string.connect_folder_error_storage
    }

/**
 * The Up button and the folder shown. A long path loses its start, not its end: the last folders
 * are the ones that tell paths apart. Accessibility services still read the whole path.
 */
@Composable
private fun PathBar(path: String?, canGoUp: Boolean, onUp: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PhopencodeTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUp, enabled = canGoUp) {
            Icon(imageVector = FolderIcons.Up, contentDescription = stringResource(R.string.connect_folder_up))
        }
        Text(
            text = path.orEmpty(),
            modifier = Modifier
                .weight(1f)
                .padding(end = PhopencodeTheme.spacing.md),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
        )
    }
}

/** The one way out of the picker with a folder; always in reach under the list. */
@Composable
private fun SelectBar(enabled: Boolean, onSelect: () -> Unit) {
    Surface(tonalElevation = PhopencodeTheme.spacing.xxs) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm),
        ) {
            Button(
                onClick = onSelect,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = PhopencodeTheme.spacing.touchTarget),
            ) {
                Text(stringResource(R.string.connect_folder_select))
            }
        }
    }
}

@Composable
private fun SectionHeading(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PhopencodeTheme.spacing.lg,
                end = PhopencodeTheme.spacing.lg,
                top = PhopencodeTheme.spacing.md,
                bottom = PhopencodeTheme.spacing.xs,
            )
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** The active server's recent folders, newest first; nothing at all when there are none. */
private fun LazyListScope.recentFolders(recent: List<String>, onIntent: (FolderIntent) -> Unit) {
    if (recent.isEmpty()) return
    item(key = "recent-heading") { SectionHeading(R.string.connect_folder_recent) }
    items(recent, key = { "recent:$it" }) { path ->
        ListItem(
            modifier = Modifier.clickable(
                onClickLabel = stringResource(R.string.connect_folder_recent_select),
                role = Role.Button,
            ) { onIntent(FolderIntent.Select(path)) },
            headlineContent = {
                Text(text = path, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            },
            trailingContent = {
                IconButton(onClick = { onIntent(FolderIntent.RemoveRecent(path)) }) {
                    Icon(
                        imageVector = FolderIcons.Remove,
                        contentDescription = stringResource(R.string.connect_folder_recent_remove, path),
                    )
                }
            },
        )
    }
    item(key = "recent-divider") { HorizontalDivider() }
}

/** The subfolders of the folder shown; a symlink to a folder carries a link icon instead of a folder. */
private fun LazyListScope.folders(entries: List<FileEntry>, onIntent: (FolderIntent) -> Unit) {
    item(key = "folders-heading") { SectionHeading(R.string.connect_folder_folders) }
    items(entries, key = { "entry:${it.path}" }) { entry ->
        val symlink = entry.type == FileEntryType.Symlink
        ListItem(
            modifier = Modifier.clickable(
                onClickLabel = stringResource(R.string.connect_folder_open),
                role = Role.Button,
            ) { onIntent(FolderIntent.Open(entry.path)) },
            leadingContent = {
                Icon(
                    imageVector = if (symlink) FolderIcons.Link else FolderIcons.Folder,
                    contentDescription = stringResource(
                        if (symlink) R.string.connect_folder_symlink else R.string.connect_folder_folder,
                    ),
                )
            },
            headlineContent = {
                Text(text = entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
        )
    }
}

/**
 * A folder is being listed. A still message rather than core/ui's `Loading`: its spinner never stops,
 * and Robolectric's `idle()` in the app's ActivityScenario tests, which open this screen while the
 * presenter loads, would never return (found in P4.5). TalkBack hears the same as for `Loading`: the
 * message, an indeterminate progress and a polite live region.
 */
@Composable
private fun LoadingFolders() {
    val message = stringResource(R.string.connect_folder_loading)
    Box(
        modifier = Modifier
            .fillMaxWidth()
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
