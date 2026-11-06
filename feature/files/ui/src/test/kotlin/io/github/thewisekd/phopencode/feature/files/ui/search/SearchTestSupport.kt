package io.github.thewisekd.phopencode.feature.files.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentHit
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentSearchTool
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchMode
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchResult
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Made-up paths: no real server layout or user name. */
internal const val ROOT = "/work/demo proje"

internal val ROUTE = FilesRoute.Search(directory = ROOT)

internal val docsHit = SearchResult.Name(FileEntry(path = "$ROOT/docs", type = FileEntryType.Directory))
internal val readmeHit = SearchResult.Name(FileEntry(path = "$ROOT/docs/README.md", type = FileEntryType.File))
internal val linkHit = SearchResult.Name(FileEntry(path = "$ROOT/current", type = FileEntryType.Symlink))
internal val socketHit = SearchResult.Name(FileEntry(path = "$ROOT/agent.sock", type = FileEntryType.Unknown("socket")))

internal val mainHit = SearchResult.Content(
    ContentHit(path = "$ROOT/src/main.kt", line = 42, text = "    fun main() = println(\"merhaba\")"),
)
internal val utilHit = SearchResult.Content(ContentHit(path = "$ROOT/src/util.kt", line = 7, text = "val main = 1"))

/** A path the server gave outside the project's folder: it shows whole. */
internal val outsideHit = SearchResult.Content(ContentHit(path = "/elsewhere/x.kt", line = 1, text = "main"))

/** Nothing typed yet. */
internal val IDLE = SearchState(directory = ROOT)

internal val TYPED = IDLE.copy(query = "main")

internal val SEARCHING = TYPED.copy(searching = true)

internal val NAMES = TYPED.copy(searched = true, results = listOf(docsHit, readmeHit, linkHit, socketHit))

internal val CONTENT = TYPED.copy(
    mode = SearchMode.Content,
    searched = true,
    results = listOf(mainHit, utilHit),
    tool = ContentSearchTool.Ripgrep,
)

internal val EMPTY = TYPED.copy(searched = true)

/** [count] content hits in one file, one per line. */
internal fun manyHits(count: Int): SearchState = CONTENT.copy(
    results = List(count) { SearchResult.Content(ContentHit(path = "$ROOT/big.txt", line = it + 1, text = "main $it")) },
)

/** The search screen in the app theme, with [state] changeable by the test and every intent recorded. */
internal class SearchHarness(initial: SearchState) {
    val state: MutableState<SearchState> = mutableStateOf(initial)
    val intents = mutableListOf<SearchIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            SearchScreen(route = ROUTE, state = state.value, onIntent = { intents += it })
        }
    }
}

/**
 * The node in the list showing [text], scrolled into view. The list composes only the rows in view,
 * so on a short screen a hit below it does not exist yet, and a plain `performScrollTo` on it finds
 * nothing.
 */
internal fun ComposeContentTestRule.listText(text: String): SemanticsNodeInteraction {
    onNodeWithTag(SearchTags.LIST).performScrollToNode(hasText(text))
    return onNodeWithText(text)
}

/**
 * Checks each of [results] draws its own row tagged [rowTag]: the list composes only the rows in
 * view, so on a short screen a count of the composed rows misses those below it.
 *
 * Walks the whole list from its start, half a screen at a time, and records where in the content
 * each row it meets starts: its unclipped offset in the list plus how far the list has scrolled.
 * The scroll is measured by the nodes that stay composed across a step, as a lazy list's own scroll
 * value is only an estimate. Every hit must start at a position of its own, so a hit that draws no
 * row, anywhere in the list, leaves one position short.
 */
internal fun ComposeContentTestRule.assertEachHitDrawn(results: List<SearchResult>, rowTag: String) {
    val list = onNodeWithTag(SearchTags.LIST)
    val inList = onAllNodes(hasAnyAncestor(hasTestTag(SearchTags.LIST)), useUnmergedTree = true)
    fun positions() = inList.fetchSemanticsNodes().associate { it.id to it.positionInRoot.y }

    list.performScrollToIndex(0)
    val listBounds = list.fetchSemanticsNode().boundsInRoot
    val step = listBounds.height / 2
    val starts = mutableSetOf<Int>()
    var scrolled = 0f
    repeat(MAX_STEPS) {
        onAllNodesWithTag(rowTag).fetchSemanticsNodes().forEach {
            starts += (it.positionInRoot.y - listBounds.top + scrolled).roundToInt()
        }
        val before = positions()
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, step) }
        val after = positions()
        val stayed = before.keys.intersect(after.keys)
        assertTrue(stayed.isNotEmpty(), "no node stayed in view across a step of half the list")
        // What most of them moved by: a node the list reused for another item moves by something else.
        val moved = stayed.map { before.getValue(it) - after.getValue(it) }
            .groupingBy { it.roundToInt() }.eachCount().maxBy { it.value }.key
        if (moved == 0) {
            assertEquals(results.size, starts.size, "rows drawn at positions of their own: $starts")
            return
        }
        scrolled += moved
    }
    fail("the end of the list was not reached in $MAX_STEPS steps")
}

/** More steps than any list here needs to reach its end. */
private const val MAX_STEPS = 200

internal fun ComposeContentTestRule.setSearch(state: SearchState): SearchHarness {
    val harness = SearchHarness(state)
    setContent { harness.Content() }
    return harness
}
