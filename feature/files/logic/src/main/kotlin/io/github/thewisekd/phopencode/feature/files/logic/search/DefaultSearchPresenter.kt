package io.github.thewisekd.phopencode.feature.files.logic.search

import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentSearchTool
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileSearch
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchMode
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchResult
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Name and content search in one project folder (P8.14); the rules are on [SearchState].
 *
 * - [SearchIntent.Search] runs [SearchState.query] in [SearchState.mode] when
 *   [SearchState.canSearch]. Names come from [FileSearch.findNames]; content from
 *   [FileSearch.searchContent] with [ContentSearchTool.Ripgrep], then once with
 *   [ContentSearchTool.Grep] when that fails with [FilesFailure.ToolMissing]. A second
 *   [FilesFailure.ToolMissing] is the search's error; there is no third try.
 * - Every search asks for one result more than [limit]: it shows at most [limit] and marks
 *   [SearchState.truncated] when more came.
 * - A content query that is not one line (a line break or a NUL in it) fails with
 *   [FilesFailure.Rejected] before anything is sent. The query is sent as typed, never trimmed.
 * - Changing the query or the mode drops the search in flight and clears what was found, so the
 *   results shown always belong to the query and mode shown. Only the latest search lands: each
 *   one takes a generation, and a result whose generation is not the latest is dropped.
 * - A new search of the same query keeps the old results shown until its own land.
 * - [SearchIntent.Retry] searches again only after [SearchState.error], from `rg` again.
 * - A failure of the port shows as [SearchState.error] with no results; any other exception as
 *   [FilesFailure.Unknown]. Cancellation stops the work and is never shown.
 * - Intents, results and events are handled under one lock; each state is published whole, and an
 *   event is sent in the locked step that decides it.
 *
 * Known limit: the wired adapter clamps the limits it is given (names to 1..1000, content to
 * 1..5000), so a [limit] at or above those clamps cannot tell that more results exist.
 *
 * Nothing runs in the constructor: work starts in [scope] after a `yield()`. Nothing here logs; the
 * query and the paths are user data.
 *
 * @param directory The project's folder, from the route.
 * @param search Searches names and content.
 * @param limit The most results shown; at least 1.
 */
class DefaultSearchPresenter(
    private val directory: String,
    private val search: FileSearch,
    private val scope: CoroutineScope,
    private val limit: Int = DEFAULT_LIMIT,
) : SearchPresenter {
    init {
        require(limit >= 1) { "The search limit must be at least 1" }
    }

    private val lock = Any()

    private var ui = SearchState(directory = directory)

    /** Raised by every search started and every query or mode change; only the latest lands. */
    private var generation = 0L

    private var running: Job? = null

    private val mutableState = MutableStateFlow(ui)

    override val state: StateFlow<SearchState> = mutableState.asStateFlow()

    /** Unlimited, so [send] never drops an event; it fails only once closed, which nothing does. */
    private val channel = Channel<SearchEvent>(Channel.UNLIMITED)

    override val events: Flow<SearchEvent> = channel.receiveAsFlow()

    override fun onIntent(intent: SearchIntent) {
        synchronized(lock) {
            when (intent) {
                is SearchIntent.QueryChanged ->
                    if (intent.query != ui.query) reset(ui.copy(query = intent.query))
                is SearchIntent.SetMode ->
                    if (intent.mode != ui.mode) reset(ui.copy(mode = intent.mode))
                SearchIntent.Search -> if (ui.canSearch) start()
                SearchIntent.Retry -> if (ui.error != null && ui.canSearch) start()
                is SearchIntent.Open -> open(intent.result)
                SearchIntent.Close -> send(SearchEvent.Close)
            }
        }
    }

    // Every function from here to the searching section holds [lock].

    /** Publishes [next] with nothing searched, dropping the search in flight. */
    private fun reset(next: SearchState) {
        drop()
        publish(
            next.copy(
                searching = false,
                searched = false,
                results = emptyList(),
                truncated = false,
                tool = null,
                error = null,
            ),
        )
    }

    private fun open(result: SearchResult) {
        when (result) {
            is SearchResult.Name ->
                if (result.entry.type == FileEntryType.Directory) {
                    send(SearchEvent.OpenFolder(result.entry.path))
                } else {
                    send(SearchEvent.OpenFile(result.entry.path))
                }
            is SearchResult.Content ->
                send(SearchEvent.OpenFile(result.hit.path, result.hit.line.takeIf { it >= 1 }))
        }
    }

    /** Invalidates and cancels the search in flight, if any. */
    private fun drop() {
        ++generation
        running?.cancel()
        running = null
    }

    private fun start() {
        val query = ui.query
        val mode = ui.mode
        if (mode == SearchMode.Content && !isOneLine(query)) {
            drop()
            publish(failed(ui, FilesFailure.Rejected))
            return
        }
        drop()
        val mine = generation
        publish(ui.copy(searching = true, error = null))
        running = scope.launch {
            yield()
            val outcome = run(query, mode)
            synchronized(lock) {
                if (generation != mine || !isActive) return@launch
                running = null
                publish(land(ui, outcome))
            }
        }
    }

    // Searching

    private sealed interface Outcome {
        class Found(val results: List<SearchResult>, val tool: ContentSearchTool?) : Outcome {
            override fun toString(): String = "Found(results=${results.size}, tool=$tool)"
        }

        data class Failed(val failure: FilesFailure) : Outcome
    }

    private suspend fun run(query: String, mode: SearchMode): Outcome = try {
        val asked = if (limit == Int.MAX_VALUE) limit else limit + 1
        when (mode) {
            SearchMode.Names ->
                Outcome.Found(search.findNames(directory, query, asked).map(SearchResult::Name), tool = null)
            SearchMode.Content -> content(query, asked)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: FilesException) {
        Outcome.Failed(e.failure)
    } catch (_: Exception) {
        Outcome.Failed(FilesFailure.Unknown)
    }

    /** `rg` first; `grep` once when `rg` is missing. */
    private suspend fun content(query: String, asked: Int): Outcome {
        val ripgrep = try {
            search.searchContent(directory, query, ContentSearchTool.Ripgrep, asked)
        } catch (e: FilesException) {
            if (e.failure != FilesFailure.ToolMissing) throw e
            null
        }
        if (ripgrep != null) return Outcome.Found(ripgrep.map(SearchResult::Content), ContentSearchTool.Ripgrep)
        val grep = search.searchContent(directory, query, ContentSearchTool.Grep, asked)
        return Outcome.Found(grep.map(SearchResult::Content), ContentSearchTool.Grep)
    }

    private fun land(current: SearchState, outcome: Outcome): SearchState = when (outcome) {
        is Outcome.Found -> current.copy(
            searching = false,
            searched = true,
            results = outcome.results.take(limit),
            truncated = outcome.results.size > limit,
            tool = outcome.tool,
            error = null,
        )
        is Outcome.Failed -> failed(current, outcome.failure)
    }

    private fun failed(current: SearchState, failure: FilesFailure): SearchState = current.copy(
        searching = false,
        searched = true,
        results = emptyList(),
        truncated = false,
        tool = null,
        error = failure,
    )

    /** What the adapter takes as a content query: no line break and no NUL. */
    private fun isOneLine(query: String): Boolean = query.none { it == '\n' || it == '\r' || it == '\u0000' }

    // State

    /** Holds [lock]. Never suspends: the channel is unlimited. */
    private fun send(event: SearchEvent) {
        channel.trySend(event)
    }

    /** Holds [lock]. */
    private fun publish(next: SearchState) {
        ui = next
        mutableState.value = next
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 200
    }
}
