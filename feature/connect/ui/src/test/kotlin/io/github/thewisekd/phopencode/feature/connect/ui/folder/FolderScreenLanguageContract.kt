package io.github.thewisekd.phopencode.feature.connect.ui.folder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The texts of the folder picker in one language. Formats take a folder path, [details] a failure's
 * detail; [debugHeading] heads the debug detail box of a debuggable build.
 */
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
    val details: (String) -> String,
    val debugHeading: String,
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

    @Test
    fun `a failure with a detail shows it under the message`() {
        compose.setFolderScreen(browsing.copy(error = FolderError.Failed, errorDetail = DETAIL))

        compose.onNodeWithText(strings.errors.getValue(FolderError.Failed)).assertIsDisplayed()
        compose.onNodeWithText(strings.details(DETAIL)).assertIsDisplayed()
    }

    @Test
    fun `a failure without a detail shows no detail line`() {
        compose.setFolderScreen(browsing.copy(error = FolderError.Failed))

        compose.onNodeWithText(strings.errors.getValue(FolderError.Failed)).assertIsDisplayed()
        compose.onNodeWithText(strings.details(""), substring = true).assertDoesNotExist()
    }

    @Test
    fun `not connected with a connection error's detail shows it under the message`() {
        compose.setFolderScreen(browsing.copy(error = FolderError.NotConnected, errorDetail = CONNECTION_DETAIL))

        compose.onNodeWithText(strings.errors.getValue(FolderError.NotConnected)).assertIsDisplayed()
        compose.onNodeWithText(strings.details(CONNECTION_DETAIL)).assertIsDisplayed()
    }

    @Test
    fun `not connected without a detail shows no detail line`() {
        compose.setFolderScreen(browsing.copy(error = FolderError.NotConnected))

        compose.onNodeWithText(strings.errors.getValue(FolderError.NotConnected)).assertIsDisplayed()
        compose.onNodeWithText(strings.details(""), substring = true).assertDoesNotExist()
    }

    @Test
    fun `other errors show no detail line`() {
        var state by mutableStateOf(browsing)
        compose.setContent { FolderScreenUnderTest(state) }

        for (error in FolderError.entries - FolderError.Failed - FolderError.NotConnected) {
            state = browsing.copy(error = error, errorDetail = DETAIL)
            compose.waitForIdle()

            compose.onNodeWithText(strings.errors.getValue(error)).assertIsDisplayed()
            compose.onNodeWithText(DETAIL, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `a failure with a debug detail shows it in a box under its heading`() {
        compose.setFolderScreen(
            browsing.copy(error = FolderError.Failed, errorDetail = DETAIL, debugDetail = DEBUG_DETAIL),
        )

        compose.onNodeWithText(strings.details(DETAIL)).assertIsDisplayed()
        compose.onNodeWithText(strings.debugHeading).assertIsDisplayed()
        compose.onNodeWithText(DEBUG_DETAIL).assertIsDisplayed()
        compose.onNodeWithTag(DEBUG_DETAIL_TAG).assertIsDisplayed()
    }

    @Test
    fun `not connected with a debug detail shows it in a box under its heading`() {
        compose.setFolderScreen(
            browsing.copy(error = FolderError.NotConnected, errorDetail = "Idle", debugDetail = DEBUG_DETAIL),
        )

        compose.onNodeWithText(strings.details("Idle")).assertIsDisplayed()
        compose.onNodeWithText(strings.debugHeading).assertIsDisplayed()
        compose.onNodeWithText(DEBUG_DETAIL).assertIsDisplayed()
    }

    @Test
    fun `without a debug detail there is no debug box`() {
        var state by mutableStateOf(browsing)
        compose.setContent { FolderScreenUnderTest(state) }

        for (error in listOf(FolderError.Failed, FolderError.NotConnected)) {
            state = browsing.copy(error = error, errorDetail = DETAIL)
            compose.waitForIdle()

            compose.onNodeWithText(strings.details(DETAIL)).assertIsDisplayed()
            compose.onNodeWithText(strings.debugHeading).assertDoesNotExist()
            compose.onNodeWithTag(DEBUG_DETAIL_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun `other errors show no debug box`() {
        var state by mutableStateOf(browsing)
        compose.setContent { FolderScreenUnderTest(state) }

        for (error in FolderError.entries - FolderError.Failed - FolderError.NotConnected) {
            state = browsing.copy(error = error, debugDetail = DEBUG_DETAIL)
            compose.waitForIdle()

            compose.onNodeWithText(strings.debugHeading).assertDoesNotExist()
            compose.onNodeWithText(DEBUG_DETAIL).assertDoesNotExist()
        }
    }

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
