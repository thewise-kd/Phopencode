package io.github.thewisekd.phopencode.feature.files.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentHit
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentSearchTool
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileSearch
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchMode
import io.github.thewisekd.phopencode.feature.files.logic.search.DefaultSearchPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which intents the search's controls send, and what each state enables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SearchBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the root keeps the placeholder tag`() {
        compose.setSearch(IDLE)

        compose.onNodeWithTag("placeholder:$ROUTE").assertExists()
    }

    @Test
    fun `typing sends the query as typed`() {
        val harness = compose.setSearch(IDLE)

        compose.onNodeWithTag(SearchTags.QUERY).performTextInput("  ana kod ")

        assertEquals(listOf<SearchIntent>(SearchIntent.QueryChanged("  ana kod ")), harness.intents)
    }

    @Test
    fun `the field shows the state's query`() {
        compose.setSearch(TYPED)

        compose.onNodeWithTag(SearchTags.QUERY).assertTextContains("main")
    }

    @Test
    fun `the search button searches only when the state can`() {
        val harness = compose.setSearch(IDLE)
        compose.onNodeWithTag(SearchTags.RUN).assertIsNotEnabled()

        harness.state.value = SEARCHING
        compose.onNodeWithTag(SearchTags.RUN).assertIsNotEnabled()

        harness.state.value = TYPED
        compose.onNodeWithTag(SearchTags.RUN).assertIsEnabled().performClick()

        assertEquals(listOf<SearchIntent>(SearchIntent.Search), harness.intents)
    }

    @Test
    fun `the keyboard's search action searches when the state can`() {
        val harness = compose.setSearch(IDLE)
        compose.onNodeWithTag(SearchTags.QUERY).performImeAction()
        assertEquals(emptyList(), harness.intents)

        harness.state.value = TYPED
        compose.onNodeWithTag(SearchTags.QUERY).performImeAction()

        assertEquals(listOf<SearchIntent>(SearchIntent.Search), harness.intents)
    }

    @Test
    fun `the mode switch shows the mode and changes it`() {
        val harness = compose.setSearch(TYPED)
        compose.onNodeWithTag(SearchTags.MODE_NAMES).assertIsSelected()
        compose.onNodeWithTag(SearchTags.MODE_CONTENT).assertIsNotSelected()

        compose.onNodeWithTag(SearchTags.MODE_NAMES).performClick()
        compose.onNodeWithTag(SearchTags.MODE_CONTENT).performClick()

        assertEquals(listOf<SearchIntent>(SearchIntent.SetMode(SearchMode.Content)), harness.intents)
    }

    @Test
    fun `back closes the search`() {
        val harness = compose.setSearch(NAMES)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<SearchIntent>(SearchIntent.Close), harness.intents)
    }

    @Test
    fun `tapping a name hit opens it`() {
        val harness = compose.setSearch(NAMES)

        compose.onNodeWithText("Folder · docs").performScrollTo().performClick()
        compose.onNodeWithText("File · docs/README.md").performScrollTo().performClick()

        assertEquals(listOf<SearchIntent>(SearchIntent.Open(docsHit), SearchIntent.Open(readmeHit)), harness.intents)
    }

    @Test
    fun `a name hit shows its path relative to the project and its type`() {
        compose.setSearch(NAMES)

        compose.onNodeWithText("Link · current").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Other · agent.sock").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a content hit shows its path, line and text`() {
        compose.setSearch(CONTENT)

        compose.onNodeWithText("src/main.kt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Line 42").assertIsDisplayed()
        compose.onNodeWithText("fun main() = println(\"merhaba\")").assertIsDisplayed()
    }

    @Test
    fun `a path outside the project shows whole`() {
        compose.setSearch(CONTENT.copy(results = listOf(outsideHit)))

        compose.onNodeWithText("/elsewhere/x.kt").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `tapping a content hit opens it`() {
        val harness = compose.setSearch(CONTENT)

        compose.onNodeWithText("Line 7").performScrollTo().performClick()

        assertEquals(listOf<SearchIntent>(SearchIntent.Open(utilHit)), harness.intents)
    }

    @Test
    fun `with the real presenter a tapped content hit opens the file at its line`() {
        val search = object : FileSearch {
            override suspend fun findNames(directory: String, query: String, limit: Int): List<FileEntry> = emptyList()

            override suspend fun searchContent(
                directory: String,
                query: String,
                tool: ContentSearchTool,
                limit: Int,
            ): List<ContentHit> = listOf(mainHit.hit, utilHit.hit)
        }
        val presenter = DefaultSearchPresenter(ROOT, search, scope)
        compose.setContent {
            val state by presenter.state.collectAsStateWithLifecycle()
            PhopencodeTheme(darkTheme = false) { SearchScreen(ROUTE, state, presenter::onIntent) }
        }

        compose.onNodeWithTag(SearchTags.QUERY).performTextInput("main")
        compose.onNodeWithTag(SearchTags.MODE_CONTENT).performClick()
        compose.onNodeWithTag(SearchTags.RUN).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(SearchTags.CONTENT_ROW).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("Line 42").performScrollTo().performClick()

        val event = runBlocking { withTimeout(5_000) { presenter.events.first() } }
        assertEquals(SearchEvent.OpenFile(mainHit.hit.path, 42), event)
    }

    @Test
    fun `a far hit in a long list can be reached and opened`() {
        val state = manyHits(500)
        val harness = compose.setSearch(state)

        compose.onNodeWithTag(SearchTags.LIST).performScrollToNode(hasText("Line 500"))
        compose.onNodeWithText("Line 500").performClick()

        assertEquals(listOf<SearchIntent>(SearchIntent.Open(state.results.last())), harness.intents)
    }

    @Test
    fun `the same hit twice draws twice`() {
        compose.setSearch(CONTENT.copy(results = listOf(mainHit, mainHit)))

        assertEquals(2, compose.onAllNodesWithTag(SearchTags.CONTENT_ROW).fetchSemanticsNodes().size)
    }

    @Test
    fun `a new search keeps the old results while it runs`() {
        compose.setSearch(CONTENT.copy(searching = true))

        compose.onNodeWithTag(SearchTags.SEARCHING).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Line 42").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `before a search a hint shows, and no empty state`() {
        val harness = compose.setSearch(IDLE)
        compose.onNodeWithTag(SearchTags.HINT).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(SearchTags.EMPTY).fetchSemanticsNodes().isEmpty())

        harness.state.value = EMPTY
        compose.onNodeWithTag(SearchTags.EMPTY).performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(SearchTags.HINT).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a truncated search says so and a full one does not`() {
        val harness = compose.setSearch(CONTENT)
        assertTrue(compose.onAllNodesWithTag(SearchTags.TRUNCATED).fetchSemanticsNodes().isEmpty())

        harness.state.value = CONTENT.copy(truncated = true)
        compose.onNodeWithTag(SearchTags.TRUNCATED).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the tool shows for content and not for names`() {
        val harness = compose.setSearch(NAMES)
        assertTrue(compose.onAllNodesWithTag(SearchTags.TOOL).fetchSemanticsNodes().isEmpty())

        harness.state.value = CONTENT
        compose.onNodeWithTag(SearchTags.TOOL).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a failure that another try can fix offers retry`() {
        val harness = compose.setSearch(TYPED.copy(searched = true, error = FilesFailure.Unreachable))

        compose.onNodeWithText("Try again").performScrollTo().performClick()

        assertEquals(listOf<SearchIntent>(SearchIntent.Retry), harness.intents)
    }

    @Test
    fun `a failure another try cannot fix offers no retry`() {
        compose.setSearch(TYPED.copy(mode = SearchMode.Content, searched = true, error = FilesFailure.Rejected))

        compose.onNodeWithTag(SearchTags.ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a failure offers no retry when the query is blank`() {
        compose.setSearch(IDLE.copy(query = " ", searched = true, error = FilesFailure.Unreachable))

        compose.onNodeWithTag(SearchTags.ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }
}

