package io.github.thewisekd.phopencode.feature.files.ui.search

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentSearchTool
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchMode
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchResult
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState
import io.github.thewisekd.phopencode.feature.files.ui.R
import io.github.thewisekd.phopencode.feature.files.ui.tree.TreeIcons
import java.io.Serializable

/** Between a name hit's type and its path; not words, so the same in every language. */
private const val DETAIL_SEPARATOR = " · "

/** The most lines a content hit's text takes; a longer line is cut with an ellipsis. */
private const val HIT_TEXT_LINES = 2

/**
 * Name and content search in one project folder (P8.15). Draws [state] and sends [onIntent]; it
 * decides nothing itself.
 *
 * - **Top bar:** back sends [SearchIntent.Close].
 * - **Query:** the field sends each change as typed, and the keyboard's search action and the
 *   Search button send [SearchIntent.Search] when [SearchState.canSearch]. Below it, a Names or
 *   Content switch sends [SearchIntent.SetMode].
 * - **List:** a [LazyColumn] with stable keys. Above the hits it shows, as each applies: a still
 *   "Searching…" line, the error with retry when another try can help, the tool the content search
 *   ran with, and the note that more results exist than shown. Before a search it shows a hint, and
 *   after one that found nothing, an empty state.
 * - **Hits:** a name hit shows its name, its type and its path in the project; a content hit its
 *   path, line number and the matching line, at most [HIT_TEXT_LINES] lines. A tap sends
 *   [SearchIntent.Open]; the presenter turns a content hit into a file opened at its line.
 *
 * Nothing on it runs an endless animation: Robolectric's `idle()` in the app's tests would never
 * return. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun SearchScreen(
    route: FilesRoute.Search,
    state: SearchState,
    onIntent: (SearchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.files_search_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(SearchIntent.Close) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            QueryRow(state, onIntent)
            ModeSwitch(state.mode, onIntent)
            HorizontalDivider()
            Results(state, onIntent, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun QueryRow(state: SearchState, onIntent: (SearchIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val search = { if (state.canSearch) onIntent(SearchIntent.Search) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { onIntent(SearchIntent.QueryChanged(it)) },
            modifier = Modifier.weight(1f).testTag(SearchTags.QUERY),
            label = { Text(stringResource(R.string.files_search_query_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        Button(
            onClick = search,
            enabled = state.canSearch,
            modifier = Modifier.testTag(SearchTags.RUN),
        ) {
            Text(stringResource(R.string.files_search_run))
        }
    }
}

/** Names or content; tapping the mode already shown sends nothing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: SearchMode, onIntent: (SearchIntent) -> Unit) {
    val options = listOf(
        Triple(SearchMode.Names, R.string.files_search_mode_names, SearchTags.MODE_NAMES),
        Triple(SearchMode.Content, R.string.files_search_mode_content, SearchTags.MODE_CONTENT),
    )
    val spacing = PhopencodeTheme.spacing
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        options.forEachIndexed { index, (option, label, tag) ->
            SegmentedButton(
                selected = mode == option,
                onClick = { if (mode != option) onIntent(SearchIntent.SetMode(option)) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.testTag(tag),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

/** The notes on the search, then the hits. Everything scrolls, so a short screen reaches it all. */
@Composable
private fun Results(state: SearchState, onIntent: (SearchIntent) -> Unit, modifier: Modifier) {
    val keys = remember(state.results) { stableKeys(state.results) }
    LazyColumn(modifier = modifier.testTag(SearchTags.LIST)) {
        notes(state, onIntent)
        items(keys, key = { it.first }, contentType = { (_, result) -> result.contentType }) { (_, result) ->
            when (result) {
                is SearchResult.Name -> NameRow(state.directory, result, onIntent)
                is SearchResult.Content -> ContentRow(state.directory, result, onIntent)
            }
        }
    }
}

private fun LazyListScope.notes(state: SearchState, onIntent: (SearchIntent) -> Unit) {
    val error = state.error
    when {
        state.searching -> item(key = "searching", contentType = "note") { Searching() }
        !state.searched -> item(key = "hint", contentType = "note") { Hint(state.mode) }
    }
    if (error != null) {
        item(key = "error", contentType = "error") {
            ErrorBanner(
                modifier = Modifier
                    .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm)
                    .testTag(SearchTags.ERROR),
                message = stringResource(R.string.files_search_error, stringResource(error.reasonRes)),
                onRetry = if (error.canRetry && state.canSearch) ({ onIntent(SearchIntent.Retry) }) else null,
            )
        }
    }
    state.tool?.let { tool ->
        item(key = "tool", contentType = "note") { Note(tool.labelRes, SearchTags.TOOL) }
    }
    if (state.truncated) {
        item(key = "truncated", contentType = "note") { Note(R.string.files_search_truncated, SearchTags.TRUNCATED) }
    }
    if (state.isEmpty) {
        item(key = "empty", contentType = "empty") {
            EmptyState(
                modifier = Modifier.fillMaxWidth().testTag(SearchTags.EMPTY),
                title = stringResource(R.string.files_search_empty_title),
                message = stringResource(R.string.files_search_empty_message),
                icon = TreeIcons.Search,
            )
        }
    }
}

/**
 * A search is running. A still line rather than a spinner: an endless animation keeps Robolectric's
 * `idle()` from returning. TalkBack hears the message, an indeterminate progress and a polite live
 * region, as for core/ui's `Loading`.
 */
@Composable
private fun Searching() {
    val message = stringResource(R.string.files_search_searching)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SearchTags.SEARCHING)
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(mode: SearchMode) {
    val text = when (mode) {
        SearchMode.Names -> R.string.files_search_hint_names
        SearchMode.Content -> R.string.files_search_hint_content
    }
    Note(text, SearchTags.HINT)
}

@Composable
private fun Note(@StringRes text: Int, tag: String) {
    Text(
        text = stringResource(text),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm)
            .testTag(tag),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun NameRow(directory: String, result: SearchResult.Name, onIntent: (SearchIntent) -> Unit) {
    val entry = result.entry
    val type = stringResource(entry.type.labelRes)
    ListItem(
        modifier = Modifier
            .testTag(SearchTags.NAME_ROW)
            .clickable(
                onClickLabel = stringResource(R.string.files_search_open),
                role = Role.Button,
                onClick = { onIntent(SearchIntent.Open(result)) },
            ),
        leadingContent = { Icon(imageVector = entry.type.icon, contentDescription = null) },
        headlineContent = { Text(text = entry.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = {
            Text(
                text = type + DETAIL_SEPARATOR + relativePath(directory, entry.path),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        },
    )
}

@Composable
private fun ContentRow(directory: String, result: SearchResult.Content, onIntent: (SearchIntent) -> Unit) {
    val hit = result.hit
    ListItem(
        modifier = Modifier
            .testTag(SearchTags.CONTENT_ROW)
            .clickable(
                onClickLabel = stringResource(R.string.files_search_open),
                role = Role.Button,
                onClick = { onIntent(SearchIntent.Open(result)) },
            ),
        headlineContent = {
            Text(text = relativePath(directory, hit.path), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        },
        overlineContent = { Text(text = stringResource(R.string.files_search_line, hit.line)) },
        supportingContent = {
            Text(
                text = hit.text.trim(),
                fontFamily = FontFamily.Monospace,
                maxLines = HIT_TEXT_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** [path] below [directory] without the folder's prefix; any other path whole. */
internal fun relativePath(directory: String, path: String): String {
    val prefix = directory.trimEnd('/') + "/"
    return if (path.startsWith(prefix) && path.length > prefix.length) path.removePrefix(prefix) else path
}

/**
 * A hit's key in the lazy list: its kind, path and line (0 for a name), and which repeat of that
 * same hit it is. A structure, not a joined string, so no character in a path can make two keys
 * equal; equal keys crash a lazy list. Serializable, as the list saves its keys in a Bundle.
 */
internal data class SearchKey(
    val content: Boolean,
    val path: String,
    val line: Int,
    val occurrence: Int,
) : Serializable {
    override fun toString(): String = "SearchKey(content=$content, path=***, line=$line, occurrence=$occurrence)"

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Each result with a key unique in the list and stable while it stays. A hit the server listed
 * more than once counts its repeats, so each copy keeps its own key.
 */
internal fun stableKeys(results: List<SearchResult>): List<Pair<SearchKey, SearchResult>> {
    val seen = HashMap<SearchKey, Int>()
    return results.map { result ->
        val first = when (result) {
            is SearchResult.Name -> SearchKey(content = false, path = result.entry.path, line = 0, occurrence = 0)
            is SearchResult.Content -> SearchKey(content = true, path = result.hit.path, line = result.hit.line, occurrence = 0)
        }
        val repeats = seen.getOrDefault(first, 0)
        seen[first] = repeats + 1
        first.copy(occurrence = repeats) to result
    }
}

private val SearchResult.contentType: String
    get() = when (this) {
        is SearchResult.Name -> "name"
        is SearchResult.Content -> "content"
    }

/** Another try can help after these; the others need the query, the server or the app to change. */
private val FilesFailure.canRetry: Boolean
    get() = when (this) {
        FilesFailure.Unauthorized,
        FilesFailure.NotFound,
        FilesFailure.PathRejected,
        FilesFailure.ToolMissing,
        FilesFailure.Rejected,
        FilesFailure.Unsupported,
        -> false
        else -> true
    }

/** Why the search failed, as the end of a sentence that says it did. */
@get:StringRes
private val FilesFailure.reasonRes: Int
    get() = when (this) {
        FilesFailure.NotConnected -> R.string.files_search_reason_not_connected
        FilesFailure.Unreachable -> R.string.files_search_reason_unreachable
        FilesFailure.Unauthorized -> R.string.files_search_reason_unauthorized
        FilesFailure.NotFound -> R.string.files_search_reason_not_found
        FilesFailure.PermissionDenied -> R.string.files_search_reason_permission_denied
        FilesFailure.AlreadyExists -> R.string.files_search_reason_already_exists
        FilesFailure.NotEmpty -> R.string.files_search_reason_not_empty
        FilesFailure.PathRejected -> R.string.files_search_reason_path_rejected
        FilesFailure.TooLarge -> R.string.files_search_reason_too_large
        FilesFailure.ToolMissing -> R.string.files_search_reason_tool_missing
        FilesFailure.Rejected -> R.string.files_search_reason_rejected
        FilesFailure.ServerFailed -> R.string.files_search_reason_server_failed
        FilesFailure.Unsupported -> R.string.files_search_reason_unsupported
        FilesFailure.Unknown -> R.string.files_search_reason_unknown
    }

@get:StringRes
private val ContentSearchTool.labelRes: Int
    get() = when (this) {
        ContentSearchTool.Ripgrep -> R.string.files_search_tool_ripgrep
        ContentSearchTool.Grep -> R.string.files_search_tool_grep
    }

private val FileEntryType.icon: ImageVector
    get() = when (this) {
        FileEntryType.Directory -> TreeIcons.Folder
        FileEntryType.File -> TreeIcons.File
        FileEntryType.Symlink -> TreeIcons.Link
        is FileEntryType.Unknown -> TreeIcons.Other
    }

@get:StringRes
private val FileEntryType.labelRes: Int
    get() = when (this) {
        FileEntryType.Directory -> R.string.files_search_type_folder
        FileEntryType.File -> R.string.files_search_type_file
        FileEntryType.Symlink -> R.string.files_search_type_symlink
        is FileEntryType.Unknown -> R.string.files_search_type_other
    }
