package io.github.thewisekd.phopencode.feature.files.logic.search

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentHit
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentSearchTool
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileSearch
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchMode
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchResult
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val ROOT = "/srv/project"
private const val LIMIT = 3

private fun file(name: String) = FileEntry(path = "$ROOT/$name", type = FileEntryType.File)

private fun folder(name: String) = FileEntry(path = "$ROOT/$name", type = FileEntryType.Directory)

private fun hit(name: String, line: Int = 1) = ContentHit(path = "$ROOT/$name", line = line, text = "x")

/** Answers from lists the test sets; every call is recorded, and a gate per query can hold a call. */
private class FakeSearch : FileSearch {
    var names: List<FileEntry> = emptyList()
    var hits: List<ContentHit> = emptyList()

    /** Tools that fail with [FilesFailure.ToolMissing]. */
    var missing: Set<ContentSearchTool> = emptySet()
    var failure: FilesFailure? = null
    var error: Throwable? = null

    /** When a query is here, the call waits for its gate. */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Per query, overrides what is answered. */
    val namesFor = mutableMapOf<String, List<FileEntry>>()

    val calls = mutableListOf<String>()

    override suspend fun findNames(directory: String, query: String, limit: Int): List<FileEntry> {
        calls += "names $directory $query $limit"
        gates[query]?.await()
        error?.let { throw it }
        failure?.let { throw FilesException(it) }
        return (namesFor[query] ?: names).take(limit)
    }

    override suspend fun searchContent(
        directory: String,
        query: String,
        tool: ContentSearchTool,
        limit: Int,
    ): List<ContentHit> {
        calls += "content $directory $query $tool $limit"
        gates[query]?.await()
        if (tool in missing) throw FilesException(FilesFailure.ToolMissing)
        error?.let { throw it }
        failure?.let { throw FilesException(it) }
        return hits.take(limit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSearchPresenterTest {
    private val search = FakeSearch()

    private fun TestScope.presenter(limit: Int = LIMIT) = DefaultSearchPresenter(
        directory = ROOT,
        search = search,
        scope = backgroundScope,
        limit = limit,
    )

    private fun TestScope.events(presenter: DefaultSearchPresenter): List<SearchEvent> {
        val seen = mutableListOf<SearchEvent>()
        backgroundScope.launch { presenter.events.toList(seen) }
        runCurrent()
        return seen
    }

    private fun DefaultSearchPresenter.query(query: String, mode: SearchMode = SearchMode.Names) {
        onIntent(SearchIntent.SetMode(mode))
        onIntent(SearchIntent.QueryChanged(query))
        onIntent(SearchIntent.Search)
    }

    @Test
    fun `nothing is called before a search`() = runTest {
        val presenter = presenter()
        runCurrent()

        assertEquals(SearchState(directory = ROOT), presenter.state.value)
        assertTrue(search.calls.isEmpty())
    }

    @Test
    fun `the limit must be positive`() = runTest {
        assertFailsWith<IllegalArgumentException> { presenter(limit = 0) }
    }

    @Test
    fun `a name search asks one more than the limit and shows what it found`() = runTest {
        search.names = listOf(file("a.kt"), folder("src"))
        val presenter = presenter()

        presenter.query("a")
        assertTrue(presenter.state.value.searching)
        assertTrue(search.calls.isEmpty(), "work starts in the scope, after a yield")
        runCurrent()

        assertEquals(listOf("names $ROOT a ${LIMIT + 1}"), search.calls)
        assertEquals(
            SearchState(
                directory = ROOT,
                query = "a",
                searched = true,
                results = listOf(SearchResult.Name(file("a.kt")), SearchResult.Name(folder("src"))),
            ),
            presenter.state.value,
        )
        assertFalse(presenter.state.value.truncated)
        assertNull(presenter.state.value.tool)
    }

    @Test
    fun `more results than the limit are cut and marked truncated`() = runTest {
        search.names = (1..10).map { file("f$it") }
        val presenter = presenter()

        presenter.query("f")
        runCurrent()

        assertEquals((1..LIMIT).map { SearchResult.Name(file("f$it")) }, presenter.state.value.results)
        assertTrue(presenter.state.value.truncated)
    }

    @Test
    fun `exactly the limit is not truncated`() = runTest {
        search.names = (1..LIMIT).map { file("f$it") }
        val presenter = presenter()

        presenter.query("f")
        runCurrent()

        assertEquals(LIMIT, presenter.state.value.results.size)
        assertFalse(presenter.state.value.truncated)
    }

    @Test
    fun `the largest limit does not overflow`() = runTest {
        val presenter = presenter(limit = Int.MAX_VALUE)

        presenter.query("f")
        runCurrent()

        assertEquals(listOf("names $ROOT f ${Int.MAX_VALUE}"), search.calls)
    }

    @Test
    fun `nothing found is empty`() = runTest {
        val presenter = presenter()

        presenter.query("zzz")
        runCurrent()

        assertTrue(presenter.state.value.isEmpty)
    }

    @Test
    fun `a content search runs with ripgrep`() = runTest {
        search.hits = listOf(hit("a.kt", 4), hit("b.kt", 9))
        val presenter = presenter()

        presenter.query("fun main", SearchMode.Content)
        runCurrent()

        assertEquals(listOf("content $ROOT fun main Ripgrep ${LIMIT + 1}"), search.calls)
        val state = presenter.state.value
        assertEquals(listOf(SearchResult.Content(hit("a.kt", 4)), SearchResult.Content(hit("b.kt", 9))), state.results)
        assertEquals(ContentSearchTool.Ripgrep, state.tool)
        assertTrue(state.searched)
        assertFalse(state.truncated)
        assertNull(state.error)
    }

    @Test
    fun `a content search falls back to grep once when rg is missing`() = runTest {
        search.missing = setOf(ContentSearchTool.Ripgrep)
        search.hits = (1..5).map { hit("f$it") }
        val presenter = presenter()

        presenter.query("x", SearchMode.Content)
        runCurrent()

        assertEquals(
            listOf("content $ROOT x Ripgrep ${LIMIT + 1}", "content $ROOT x Grep ${LIMIT + 1}"),
            search.calls,
        )
        val state = presenter.state.value
        assertEquals(ContentSearchTool.Grep, state.tool)
        assertEquals(LIMIT, state.results.size)
        assertTrue(state.truncated)
        assertNull(state.error)
    }

    @Test
    fun `with neither tool the search fails as tool missing, without looping`() = runTest {
        search.missing = ContentSearchTool.entries.toSet()
        val presenter = presenter()

        presenter.query("x", SearchMode.Content)
        runCurrent()

        assertEquals(2, search.calls.size)
        val state = presenter.state.value
        assertEquals(FilesFailure.ToolMissing, state.error)
        assertFalse(state.searching)
        assertTrue(state.results.isEmpty())
        assertFalse(state.isEmpty)
    }

    @Test
    fun `another failure does not fall back to grep`() = runTest {
        search.failure = FilesFailure.Unreachable
        val presenter = presenter()

        presenter.query("x", SearchMode.Content)
        runCurrent()

        assertEquals(1, search.calls.size)
        assertEquals(FilesFailure.Unreachable, presenter.state.value.error)
    }

    @Test
    fun `an unexpected exception shows as unknown`() = runTest {
        search.error = IllegalStateException("boom")
        val presenter = presenter()

        presenter.query("x")
        runCurrent()

        assertEquals(FilesFailure.Unknown, presenter.state.value.error)
        assertFalse(presenter.state.value.searching)
    }

    @Test
    fun `a blank query does not search`() = runTest {
        val presenter = presenter()

        presenter.query("   ")
        runCurrent()
        presenter.query("", SearchMode.Content)
        runCurrent()

        assertTrue(search.calls.isEmpty())
        assertFalse(presenter.state.value.searching)
        assertFalse(presenter.state.value.searched)
    }

    @Test
    fun `a multi-line content query is rejected before any call`() = runTest {
        val presenter = presenter()

        for (query in listOf("a\nb", "a\rb", "a\u0000b")) {
            presenter.query(query, SearchMode.Content)
            runCurrent()
            assertEquals(FilesFailure.Rejected, presenter.state.value.error)
            assertFalse(presenter.state.value.searching)
        }
        assertTrue(search.calls.isEmpty())
    }

    @Test
    fun `a new query cancels the search in flight, whose result never lands`() = runTest {
        val slow = CompletableDeferred<Unit>()
        search.gates["old"] = slow
        search.namesFor["old"] = listOf(file("old.kt"))
        search.namesFor["new"] = listOf(file("new.kt"))
        val presenter = presenter()

        presenter.query("old")
        runCurrent()
        presenter.onIntent(SearchIntent.QueryChanged("new"))
        val changed = presenter.state.value
        assertFalse(changed.searching)
        assertFalse(changed.searched)
        assertTrue(changed.results.isEmpty())

        presenter.onIntent(SearchIntent.Search)
        runCurrent()
        slow.complete(Unit)
        runCurrent()

        assertEquals(listOf(SearchResult.Name(file("new.kt"))), presenter.state.value.results)
        assertEquals("new", presenter.state.value.query)
    }

    @Test
    fun `a stale result that ignores cancellation never overwrites a newer one`() = runTest {
        val stale = object : FileSearch {
            val first = CompletableDeferred<Unit>()
            var count = 0

            override suspend fun findNames(directory: String, query: String, limit: Int): List<FileEntry> {
                count++
                if (query == "old") {
                    // Ignores cancellation: answers anyway, after the newer search has landed.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { first.await() }
                    return listOf(file("old.kt"))
                }
                return listOf(file("new.kt"))
            }

            override suspend fun searchContent(
                directory: String,
                query: String,
                tool: ContentSearchTool,
                limit: Int,
            ): List<ContentHit> = emptyList()
        }
        val presenter = DefaultSearchPresenter(ROOT, stale, backgroundScope, LIMIT)

        presenter.query("old")
        runCurrent()
        presenter.query("new")
        runCurrent()
        assertEquals(listOf(SearchResult.Name(file("new.kt"))), presenter.state.value.results)

        stale.first.complete(Unit)
        runCurrent()

        assertEquals(listOf(SearchResult.Name(file("new.kt"))), presenter.state.value.results)
        assertEquals(2, stale.count)
    }

    @Test
    fun `changing the mode clears the results and cancels the search`() = runTest {
        search.names = listOf(file("a.kt"))
        val presenter = presenter()
        presenter.query("a")
        runCurrent()

        presenter.onIntent(SearchIntent.SetMode(SearchMode.Content))

        val state = presenter.state.value
        assertEquals(SearchMode.Content, state.mode)
        assertEquals("a", state.query)
        assertFalse(state.searched)
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun `the same query or mode again changes nothing`() = runTest {
        search.names = listOf(file("a.kt"))
        val presenter = presenter()
        presenter.query("a")
        runCurrent()
        val before = presenter.state.value

        presenter.onIntent(SearchIntent.QueryChanged("a"))
        presenter.onIntent(SearchIntent.SetMode(SearchMode.Names))

        assertEquals(before, presenter.state.value)
    }

    @Test
    fun `search is ignored while one runs`() = runTest {
        search.gates["a"] = CompletableDeferred()
        val presenter = presenter()
        presenter.query("a")
        runCurrent()

        presenter.onIntent(SearchIntent.Search)
        runCurrent()

        assertEquals(1, search.calls.size)
    }

    @Test
    fun `retry searches again after an error, and only then`() = runTest {
        search.failure = FilesFailure.Unreachable
        search.names = listOf(file("a.kt"))
        val presenter = presenter()

        presenter.onIntent(SearchIntent.QueryChanged("a"))
        presenter.onIntent(SearchIntent.Retry)
        runCurrent()
        assertTrue(search.calls.isEmpty())

        presenter.onIntent(SearchIntent.Search)
        runCurrent()
        assertEquals(FilesFailure.Unreachable, presenter.state.value.error)

        search.failure = null
        presenter.onIntent(SearchIntent.Retry)
        assertTrue(presenter.state.value.searching)
        assertNull(presenter.state.value.error)
        runCurrent()

        assertEquals(2, search.calls.size)
        assertEquals(listOf(SearchResult.Name(file("a.kt"))), presenter.state.value.results)
        assertNull(presenter.state.value.error)

        presenter.onIntent(SearchIntent.Retry)
        runCurrent()
        assertEquals(2, search.calls.size)
    }

    @Test
    fun `retry after tool missing tries ripgrep first again`() = runTest {
        search.missing = ContentSearchTool.entries.toSet()
        val presenter = presenter()
        presenter.query("x", SearchMode.Content)
        runCurrent()

        search.missing = emptySet()
        presenter.onIntent(SearchIntent.Retry)
        runCurrent()

        assertEquals("content $ROOT x Ripgrep ${LIMIT + 1}", search.calls.last())
        assertEquals(ContentSearchTool.Ripgrep, presenter.state.value.tool)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a new search keeps the old results until its own land`() = runTest {
        search.names = listOf(file("a.kt"))
        val presenter = presenter()
        presenter.query("a")
        runCurrent()

        search.names = listOf(file("b.kt"))
        presenter.onIntent(SearchIntent.Search)
        assertTrue(presenter.state.value.searching)
        assertEquals(listOf(SearchResult.Name(file("a.kt"))), presenter.state.value.results)
        assertFalse(presenter.state.value.isEmpty)
        runCurrent()

        assertEquals(listOf(SearchResult.Name(file("b.kt"))), presenter.state.value.results)
    }

    @Test
    fun `opening a file, a folder and a content hit sends the right event`() = runTest {
        val presenter = presenter()
        val events = events(presenter)

        presenter.onIntent(SearchIntent.Open(SearchResult.Name(file("a.kt"))))
        presenter.onIntent(SearchIntent.Open(SearchResult.Name(folder("src"))))
        presenter.onIntent(SearchIntent.Open(SearchResult.Content(hit("b.kt", 12))))
        presenter.onIntent(SearchIntent.Open(SearchResult.Content(hit("c.kt", 0))))
        presenter.onIntent(SearchIntent.Close)
        runCurrent()

        assertEquals(
            listOf(
                SearchEvent.OpenFile("$ROOT/a.kt"),
                SearchEvent.OpenFolder("$ROOT/src"),
                SearchEvent.OpenFile("$ROOT/b.kt", 12),
                SearchEvent.OpenFile("$ROOT/c.kt", null),
                SearchEvent.Close,
            ),
            events,
        )
    }

    @Test
    fun `events sent before anyone collects wait for the collector`() = runTest {
        val presenter = presenter()
        presenter.onIntent(SearchIntent.Close)

        val events = events(presenter)

        assertEquals(listOf<SearchEvent>(SearchEvent.Close), events)
    }

    @Test
    fun `the state never prints the query or paths`() = runTest {
        search.names = listOf(file("secret-name.kt"))
        val presenter = presenter()
        presenter.query("secret-query")
        runCurrent()

        val printed = presenter.state.value.toString()
        assertFalse("secret" in printed)
        assertFalse(ROOT in printed)
    }
}
