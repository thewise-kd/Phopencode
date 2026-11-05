package io.github.thewisekd.phopencode.feature.files.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.ComposeContentTestRule
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

internal fun ComposeContentTestRule.setSearch(state: SearchState): SearchHarness {
    val harness = SearchHarness(state)
    setContent { harness.Content() }
    return harness
}
