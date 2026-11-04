package io.github.thewisekd.phopencode.feature.connect.ui.folder

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the folder picker in one language. Formats take a folder path. */
internal data class ExpectedFolderStrings(
    val title: String,
    val up: String,
    val select: String,
    val folders: String,
    val folder: String,
    val symlink: String,
    val recent: String,
    val removeRecent: (String) -> String,
    val loading: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val retry: String,
    val dismiss: String,
    val errors: Map<FolderError, String>,
)

/** What the folder picker shows, the same in each language; a subclass sets the language and its texts. */
abstract class FolderScreenLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedFolderStrings

    /** The errors after which listing the folder again can help. */
    private val retryable = setOf(FolderError.PermissionDenied, FolderError.NotFound, FolderError.Failed)

    @Test
    fun `browsing shows the path, the folders and the actions`() {
        compose.setFolderScreen(browsing)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithText(CURRENT).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.up).assertIsDisplayed()
        compose.onNodeWithText(strings.select).assertIsDisplayed()
        compose.onNodeWithText(strings.folders).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(strings.folder, useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithContentDescription(strings.symlink, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `recent folders have a heading and a remove action`() {
        compose.setFolderScreen(browsing.copy(recent = listOf(RECENT_TWO)))

        compose.onNodeWithText(strings.recent).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.removeRecent(RECENT_TWO)).assertIsDisplayed()
    }

    @Test
    fun `loading says so`() {
        compose.setFolderScreen(FolderState(loading = true))

        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `an empty folder says so`() {
        compose.setFolderScreen(FolderState(loading = false, path = CURRENT))

        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).assertIsDisplayed()
    }

    @Test
    fun `every error has its own message`() {
        assertEquals(FolderError.entries.toSet(), strings.errors.keys)
        assertEquals(FolderError.entries.size, strings.errors.values.toSet().size, "messages must differ")
    }

    @Test
    fun `not connected is explained`() = assertError(FolderError.NotConnected)

    @Test
    fun `permission denied is explained`() = assertError(FolderError.PermissionDenied)

    @Test
    fun `not found is explained`() = assertError(FolderError.NotFound)

    @Test
    fun `a failure is explained`() = assertError(FolderError.Failed)

    @Test
    fun `a storage failure is explained`() = assertError(FolderError.StorageFailed)

    private fun assertError(error: FolderError) {
        compose.setFolderScreen(browsing.copy(error = error))

        compose.onNodeWithText(strings.errors.getValue(error)).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.dismiss).assertIsDisplayed()
        if (error in retryable) {
            compose.onNodeWithText(strings.retry).assertIsDisplayed()
        } else {
            compose.onNodeWithText(strings.retry).assertDoesNotExist()
        }
    }
}
