package io.github.thewisekd.phopencode.feature.files.ui.tree

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.window.Dialog
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.EmptyStateAction
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.core.ui.theme.StatusColor
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitBadge
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeEntry
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeSort
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
import io.github.thewisekd.phopencode.feature.files.ui.R

/** Between the size and the date of a row; not words, so the same in every language. */
private const val DETAIL_SEPARATOR = " · "

/** Between the folders of the path bar. */
private const val PATH_SEPARATOR = "/"

/**
 * The folder tree of one project folder (P8.5). Draws [state] and sends [onIntent]; it decides
 * nothing itself. The file operations draw over it on the same destination.
 *
 * - **Top bar:** back closes the tree; search, transfers and the folder options.
 * - **Path bar:** an Up button, enabled below the project's folder, and one button per folder from
 *   the project's folder down to the one shown, which is not a button. It scrolls sideways and
 *   starts at its end, the folder shown.
 * - **Status:** a still line while a folder loads or reloads, and the error banner, above the list
 *   so they stay in view however far the list is scrolled.
 * - **List:** a [LazyColumn] keyed by path, so only the rows on screen are composed, and a reload
 *   keeps the rows in view where they are. A row shows the kind of entry, its name, size and date,
 *   its git badge and its `.gitignore` marker; a tap opens it, a long press or its action button
 *   shows its actions. A new folder starts at the top of its list.
 * - **System back** goes up a folder until the project's folder; there the app's navigation has it.
 *
 * Nothing on it runs an endless animation: Robolectric's `idle()` in the app's tests would never
 * return. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun TreeScreen(
    route: FilesRoute.Tree,
    state: TreeState,
    onIntent: (TreeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = !state.isAtRoot) { onIntent(TreeIntent.Up) }
    PhopencodeScaffold(
        title = stringResource(R.string.files_tree_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(TreeIntent.Close) },
        actions = {
            TopAction(TreeIcons.Search, R.string.files_tree_search) { onIntent(TreeIntent.OpenSearch) }
            TopAction(TreeIcons.Transfers, R.string.files_tree_transfers) { onIntent(TreeIntent.OpenTransfer) }
            TopAction(TreeIcons.More, R.string.files_tree_more) { optionsOpen = true }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PathBar(directory = state.directory, folder = state.folder, canGoUp = !state.isAtRoot, onIntent = onIntent)
            HorizontalDivider()
            when {
                state.loading -> StillProgress(R.string.files_tree_loading, TreeTags.LOADING)
                state.refreshing -> StillProgress(R.string.files_tree_refreshing, TreeTags.REFRESHING)
            }
            state.error?.let { failure ->
                ErrorBanner(
                    modifier = Modifier
                        .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm)
                        .testTag(TreeTags.ERROR),
                    message = stringResource(failure.messageRes),
                    onRetry = if (failure.canRetry) ({ onIntent(TreeIntent.Retry) }) else null,
                )
            }
            key(state.folder) {
                EntryList(state = state, onIntent = onIntent, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    if (optionsOpen) {
        OptionsDialog(state = state, onIntent = onIntent, onDismiss = { optionsOpen = false })
    }
}

@Composable
private fun TopAction(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = stringResource(label))
    }
}

/** Listing again cannot help after these; something else has to change first. */
private val FilesFailure.canRetry: Boolean
    get() = when (this) {
        FilesFailure.NotConnected,
        FilesFailure.Unauthorized,
        FilesFailure.NotFound,
        FilesFailure.PathRejected,
        FilesFailure.Unsupported,
        -> false
        else -> true
    }

/** The failures listing a folder can meet have their own words; the others cannot happen here. */
@get:StringRes
private val FilesFailure.messageRes: Int
    get() = when (this) {
        FilesFailure.NotConnected -> R.string.files_tree_error_not_connected
        FilesFailure.Unreachable -> R.string.files_tree_error_unreachable
        FilesFailure.Unauthorized -> R.string.files_tree_error_unauthorized
        FilesFailure.NotFound -> R.string.files_tree_error_not_found
        FilesFailure.PermissionDenied -> R.string.files_tree_error_permission_denied
        FilesFailure.PathRejected -> R.string.files_tree_error_path_rejected
        FilesFailure.ServerFailed -> R.string.files_tree_error_server_failed
        FilesFailure.Unsupported -> R.string.files_tree_error_unsupported
        FilesFailure.AlreadyExists,
        FilesFailure.NotEmpty,
        FilesFailure.TooLarge,
        FilesFailure.ToolMissing,
        FilesFailure.Rejected,
        FilesFailure.Unknown,
        -> R.string.files_tree_error_unknown
    }

/**
 * The Up button and the folders down to the one shown. The row of folders scrolls sideways and
 * starts at its end, so a deep folder shows its last folders, the ones that tell paths apart.
 */
@Composable
private fun PathBar(directory: String, folder: String, canGoUp: Boolean, onIntent: (TreeIntent) -> Unit) {
    val segments = remember(directory, folder) { pathSegments(directory, folder) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TreeTags.PATH_BAR)
            .padding(horizontal = PhopencodeTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onIntent(TreeIntent.Up) }, enabled = canGoUp) {
            Icon(imageVector = TreeIcons.Up, contentDescription = stringResource(R.string.files_tree_up))
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState(), reverseScrolling = true),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            segments.forEachIndexed { index, segment ->
                if (index > 0) {
                    Text(
                        text = PATH_SEPARATOR,
                        modifier = Modifier.clearAndSetSemantics {},
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                val current = index == segments.lastIndex
                TextButton(
                    onClick = { onIntent(TreeIntent.OpenFolder(segment.path)) },
                    enabled = !current,
                    modifier = Modifier.testTag(TreeTags.PATH_SEGMENT),
                    colors = ButtonDefaults.textButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Text(
                        text = segment.name,
                        modifier = Modifier.widthIn(max = PhopencodeTheme.spacing.touchTarget * 4),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * A folder is being listed. A still line rather than a spinner: an endless animation keeps
 * Robolectric's `idle()` from returning. TalkBack hears the message, an indeterminate progress
 * and a polite live region, as for core/ui's `Loading`.
 */
@Composable
private fun StillProgress(@StringRes text: Int, tag: String) {
    val message = stringResource(text)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The rows, or what an empty folder says. Keyed by path: thousands of rows scroll smoothly. */
@Composable
private fun EntryList(state: TreeState, onIntent: (TreeIntent) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = modifier.testTag(TreeTags.LIST)) {
        if (state.isEmpty) {
            item(key = "empty", contentType = "empty") {
                val hint = !state.showHidden
                EmptyState(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.files_tree_empty_title),
                    message = if (hint) stringResource(R.string.files_tree_empty_hidden) else null,
                    icon = TreeIcons.Folder,
                    action = if (hint) {
                        EmptyStateAction(stringResource(R.string.files_tree_empty_show_hidden)) {
                            onIntent(TreeIntent.SetShowHidden(true))
                        }
                    } else {
                        null
                    },
                )
            }
        }
        items(state.entries, key = { it.entry.path }, contentType = { "entry" }) { row ->
            EntryRow(row = row, onIntent = onIntent)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(row: TreeEntry, onIntent: (TreeIntent) -> Unit) {
    val entry = row.entry
    val context = LocalContext.current
    val details = remember(entry, context) { details(context, entry) }
    ListItem(
        modifier = Modifier
            .testTag(TreeTags.ROW)
            .combinedClickable(
                onClickLabel = stringResource(R.string.files_tree_open),
                role = Role.Button,
                onLongClickLabel = stringResource(R.string.files_tree_show_actions),
                onLongClick = { onIntent(TreeIntent.ShowActions(row)) },
                onClick = { onIntent(TreeIntent.Open(row)) },
            ),
        leadingContent = {
            Icon(imageVector = entry.type.icon, contentDescription = stringResource(entry.type.descriptionRes))
        },
        headlineContent = {
            Text(
                text = entry.name,
                color = if (entry.ignored) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        },
        supportingContent = details?.let { { Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.xs),
            ) {
                if (entry.ignored) IgnoredMarker()
                row.badge?.let { Badge(it) }
                IconButton(onClick = { onIntent(TreeIntent.ShowActions(row)) }) {
                    Icon(
                        imageVector = TreeIcons.More,
                        contentDescription = stringResource(R.string.files_tree_actions_for, entry.name),
                    )
                }
            }
        },
    )
}

/** The size of a file and the date it last changed, each when known, in the phone's format. */
private fun details(context: Context, entry: FileEntry): String? {
    val size = entry.sizeBytes?.takeIf { entry.type != FileEntryType.Directory }
        ?.let { Formatter.formatShortFileSize(context, it) }
    val date = entry.modifiedAt?.let {
        DateUtils.formatDateTime(
            context,
            it.toEpochMilliseconds(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
    return listOfNotNull(size, date).joinToString(DETAIL_SEPARATOR).ifEmpty { null }
}

private val FileEntryType.icon
    get() = when (this) {
        FileEntryType.Directory -> TreeIcons.Folder
        FileEntryType.File -> TreeIcons.File
        FileEntryType.Symlink -> TreeIcons.Link
        is FileEntryType.Unknown -> TreeIcons.Other
    }

@get:StringRes
private val FileEntryType.descriptionRes: Int
    get() = when (this) {
        FileEntryType.Directory -> R.string.files_tree_type_folder
        FileEntryType.File -> R.string.files_tree_type_file
        FileEntryType.Symlink -> R.string.files_tree_type_symlink
        is FileEntryType.Unknown -> R.string.files_tree_type_other
    }

/** `.gitignore` covers the entry: a quiet label, beside its dimmed name. */
@Composable
private fun IgnoredMarker() {
    Text(
        text = stringResource(R.string.files_tree_ignored),
        modifier = Modifier.testTag(TreeTags.IGNORED),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** The git status as git prints it, in its status color; a screen reader hears it in words. */
@Composable
private fun Badge(badge: GitBadge) {
    val description = stringResource(badge.descriptionRes)
    val colors = badge.colors
    Box(
        modifier = Modifier
            .testTag(TreeTags.BADGE)
            .clearAndSetSemantics { contentDescription = description }
            .background(colors.container, MaterialTheme.shapes.small)
            .padding(horizontal = PhopencodeTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(badge.markRes),
            color = colors.onContainer,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private val GitBadge.colors: StatusColor
    @Composable get() = when (this) {
        GitBadge.Added, GitBadge.Untracked -> PhopencodeTheme.status.success
        GitBadge.Modified, GitBadge.Renamed, GitBadge.ContainsChanges -> PhopencodeTheme.status.warning
        GitBadge.Deleted -> PhopencodeTheme.status.danger
    }

@get:StringRes
private val GitBadge.descriptionRes: Int
    get() = when (this) {
        GitBadge.Added -> R.string.files_tree_badge_added
        GitBadge.Modified -> R.string.files_tree_badge_modified
        GitBadge.Deleted -> R.string.files_tree_badge_deleted
        GitBadge.Renamed -> R.string.files_tree_badge_renamed
        GitBadge.Untracked -> R.string.files_tree_badge_untracked
        GitBadge.ContainsChanges -> R.string.files_tree_badge_contains_changes
    }

@get:StringRes
private val GitBadge.markRes: Int
    get() = when (this) {
        GitBadge.Added -> R.string.files_tree_badge_mark_added
        GitBadge.Modified -> R.string.files_tree_badge_mark_modified
        GitBadge.Deleted -> R.string.files_tree_badge_mark_deleted
        GitBadge.Renamed -> R.string.files_tree_badge_mark_renamed
        GitBadge.Untracked -> R.string.files_tree_badge_mark_untracked
        GitBadge.ContainsChanges -> R.string.files_tree_badge_mark_contains_changes
    }

/**
 * New folder, refresh, the hidden files switch and the sort order. A dialog, not a bottom sheet:
 * a `ModalBottomSheet` never comes to rest in Robolectric. The two actions close it; the switch
 * and the sort stay open, so their effect shows behind it.
 */
@Composable
private fun OptionsDialog(state: TreeState, onIntent: (TreeIntent) -> Unit, onDismiss: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.testTag(TreeTags.OPTIONS),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = spacing.xs,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = spacing.lg)) {
                Text(
                    text = stringResource(R.string.files_tree_options_title),
                    modifier = Modifier
                        .padding(horizontal = spacing.xl, vertical = spacing.sm)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                OptionAction(R.string.files_tree_new_folder, TreeTags.NEW_FOLDER) {
                    onDismiss()
                    onIntent(TreeIntent.NewFolder)
                }
                OptionAction(
                    R.string.files_tree_refresh,
                    TreeTags.REFRESH,
                    enabled = !state.loading && !state.refreshing,
                ) {
                    onDismiss()
                    onIntent(TreeIntent.Refresh)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                OptionRow(
                    modifier = Modifier
                        .testTag(TreeTags.SHOW_HIDDEN)
                        .toggleable(
                            value = state.showHidden,
                            role = Role.Switch,
                            onValueChange = { onIntent(TreeIntent.SetShowHidden(it)) },
                        ),
                    text = R.string.files_tree_show_hidden,
                ) {
                    Switch(checked = state.showHidden, onCheckedChange = null)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                Text(
                    text = stringResource(R.string.files_tree_sort_heading),
                    modifier = Modifier
                        .padding(horizontal = spacing.xl, vertical = spacing.xs)
                        .semantics { heading() },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    TreeSort.entries.forEach { sort ->
                        val selected = state.sort == sort
                        OptionRow(
                            modifier = Modifier
                                .testTag(sort.tag)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { if (!selected) onIntent(TreeIntent.SortBy(sort)) },
                                ),
                            text = sort.labelRes,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = spacing.lg)
                        .testTag(TreeTags.OPTIONS_CLOSE),
                ) {
                    Text(stringResource(R.string.files_tree_options_close))
                }
            }
        }
    }
}

@Composable
private fun OptionAction(@StringRes text: Int, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhopencodeTheme.spacing.touchTarget)
            .padding(horizontal = PhopencodeTheme.spacing.md)
            .testTag(tag),
    ) {
        Text(text = stringResource(text), modifier = Modifier.fillMaxWidth())
    }
}

/** A labelled control of the options; [modifier] makes the whole row its touch target. */
@Composable
private fun OptionRow(modifier: Modifier, @StringRes text: Int, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhopencodeTheme.spacing.touchTarget)
            .then(modifier)
            .padding(horizontal = PhopencodeTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.md),
    ) {
        Text(text = stringResource(text), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        control()
    }
}

private val TreeSort.tag: String
    get() = when (this) {
        TreeSort.Name -> TreeTags.SORT_NAME
        TreeSort.Modified -> TreeTags.SORT_MODIFIED
        TreeSort.Size -> TreeTags.SORT_SIZE
    }

@get:StringRes
private val TreeSort.labelRes: Int
    get() = when (this) {
        TreeSort.Name -> R.string.files_tree_sort_name
        TreeSort.Modified -> R.string.files_tree_sort_modified
        TreeSort.Size -> R.string.files_tree_sort_size
    }
