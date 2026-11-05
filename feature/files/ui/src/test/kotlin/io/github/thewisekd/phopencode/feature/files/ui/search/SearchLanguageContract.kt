package io.github.thewisekd.phopencode.feature.files.ui.search

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentSearchTool
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchMode
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the search in one language. */
internal data class ExpectedSearchStrings(
    val title: String,
    val queryLabel: String,
    val run: String,
    val names: String,
    val content: String,
    val hintNames: String,
    val hintContent: String,
    val searching: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val truncated: String,
    val tools: Map<ContentSearchTool, String>,
    /** Line 42 of a file. */
    val line42: String,
    val folder: String,
    val file: String,
    val symlink: String,
    val other: String,
    val errors: Map<FilesFailure, String>,
    val retry: String,
)

/** What the search says, the same in each language; a subclass sets the language and its texts. */
abstract class SearchLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedSearchStrings

    @Test
    fun `the controls are labelled`() {
        compose.setSearch(IDLE)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithText(strings.queryLabel).assertIsDisplayed()
        compose.onNodeWithTag(SearchTags.RUN).assertTextEquals(strings.run)
        compose.onNodeWithTag(SearchTags.MODE_NAMES).assertTextEquals(strings.names)
        compose.onNodeWithTag(SearchTags.MODE_CONTENT).assertTextEquals(strings.content)
    }

    @Test
    fun `the hint follows the mode`() {
        val harness = compose.setSearch(IDLE)
        compose.onNodeWithTag(SearchTags.HINT).assertTextEquals(strings.hintNames)

        harness.state.value = IDLE.copy(mode = SearchMode.Content)
        compose.onNodeWithTag(SearchTags.HINT).assertTextEquals(strings.hintContent)
    }

    @Test
    fun `searching is said in still text`() {
        compose.setSearch(SEARCHING)

        compose.onNodeWithTag(SearchTags.SEARCHING).assertIsDisplayed().assertContentDescriptionEquals(strings.searching)
    }

    @Test
    fun `an empty search is explained`() {
        compose.setSearch(EMPTY)

        compose.onNodeWithText(strings.emptyTitle).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a truncated search is explained`() {
        compose.setSearch(CONTENT.copy(truncated = true))

        compose.onNodeWithTag(SearchTags.TRUNCATED).performScrollTo().assertTextEquals(strings.truncated)
    }

    @Test
    fun `each tool is named`() {
        assertEquals(ContentSearchTool.entries.toSet(), strings.tools.keys)
        val harness = compose.setSearch(CONTENT)
        strings.tools.forEach { (tool, text) ->
            harness.state.value = CONTENT.copy(tool = tool)
            compose.onNodeWithTag(SearchTags.TOOL).performScrollTo().assertTextEquals(text)
        }
    }

    @Test
    fun `a content hit gives its line`() {
        compose.setSearch(CONTENT)

        compose.onNodeWithText(strings.line42).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a name hit gives its type`() {
        compose.setSearch(NAMES)

        compose.onNodeWithText("${strings.folder} · docs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${strings.file} · docs/README.md").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${strings.symlink} · current").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${strings.other} · agent.sock").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every failure is worded`() {
        assertEquals(FilesFailure.entries.toSet(), strings.errors.keys)
        val failed = TYPED.copy(searched = true)
        val harness = compose.setSearch(failed.copy(error = FilesFailure.Unknown))
        strings.errors.forEach { (failure, message) ->
            harness.state.value = failed.copy(error = failure)
            compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        }
        harness.state.value = failed.copy(error = FilesFailure.Unreachable)
        compose.onNodeWithText(strings.retry).performScrollTo().assertIsDisplayed()
    }
}
