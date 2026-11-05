package io.github.thewisekd.phopencode.feature.files.logic.contract

import io.github.thewisekd.phopencode.core.model.FileEntry

/**
 * Search in one project folder, `FilesRoute.Search` (P8.14 logic, P8.15 screen).
 *
 * Where it comes from: names from [FileSearch.findNames], content from [FileSearch.searchContent]
 * with [ContentSearchTool.Ripgrep] first and [ContentSearchTool.Grep] when `rg` is missing. A search
 * asks for one result more than the limit, to tell whether [truncated].
 *
 * @property directory The project's folder, from the route.
 * @property query As typed; prints masked.
 * @property searched A search has finished since the query or mode last changed.
 * @property truncated More results exist than [results] holds.
 * @property tool The tool the last content search ran with; null for names.
 * @property error The last search failed.
 */
data class SearchState(
    val directory: String,
    val query: String = "",
    val mode: SearchMode = SearchMode.Names,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val truncated: Boolean = false,
    val tool: ContentSearchTool? = null,
    val error: FilesFailure? = null,
) {
    /** Whether [SearchIntent.Search] would run. */
    val canSearch: Boolean
        get() = query.isNotBlank() && !searching

    /** A finished search that found nothing. */
    val isEmpty: Boolean
        get() = searched && !searching && error == null && results.isEmpty()

    override fun toString(): String =
        "SearchState(directory=***, query=***, mode=$mode, searching=$searching, searched=$searched, " +
            "results=${results.size}, truncated=$truncated, tool=$tool, error=$error)"
}

enum class SearchMode {
    Names,
    Content,
}

sealed interface SearchResult {
    /** A file or folder whose name matched. */
    data class Name(val entry: FileEntry) : SearchResult {
        override fun toString(): String = "Name(type=${entry.type})"
    }

    data class Content(val hit: ContentHit) : SearchResult
}

sealed interface SearchIntent {
    data class QueryChanged(val query: String) : SearchIntent {
        override fun toString(): String = "QueryChanged(***)"
    }

    data class SetMode(val mode: SearchMode) : SearchIntent

    /** Runs [SearchState.query] when [SearchState.canSearch]. */
    data object Search : SearchIntent

    /** A file opens in the viewer, at its line for content; a folder opens in the tree. */
    data class Open(val result: SearchResult) : SearchIntent

    /** Searches again after [SearchState.error]. */
    data object Retry : SearchIntent

    data object Close : SearchIntent
}

sealed interface SearchEvent {
    /** The graph opens `FilesRoute.Viewer`. @property line 1-based; null opens at the top. */
    data class OpenFile(val path: String, val line: Int? = null) : SearchEvent {
        override fun toString(): String = "OpenFile(path=***, line=$line)"
    }

    /** The graph shows the folder in the tree: the one this search was opened from, or a new one. */
    data class OpenFolder(val path: String) : SearchEvent {
        override fun toString(): String = "OpenFolder(***)"
    }

    data object Close : SearchEvent
}

interface SearchPresenter : Presenter<SearchState, SearchIntent, SearchEvent>
