package io.github.thewisekd.phopencode.feature.files.ui.tree

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitBadge
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the tree in one language. A format takes a file name. */
internal data class ExpectedTreeStrings(
    val title: String,
    val up: String,
    val search: String,
    val transfers: String,
    val more: String,
    val actionsFor: (String) -> String,
    val folder: String,
    val file: String,
    val symlink: String,
    val other: String,
    val ignored: String,
    val badges: Map<GitBadge, String>,
    val loading: String,
    val refreshing: String,
    val emptyTitle: String,
    val emptyHidden: String,
    val showHiddenAction: String,
    val optionsTitle: String,
    val newFolder: String,
    val refresh: String,
    val showHidden: String,
    val sortHeading: String,
    val sortName: String,
    val sortModified: String,
    val sortSize: String,
    val close: String,
    val retry: String,
    val errors: Map<FilesFailure, String>,
)

/** The failures the tree words on their own; the others share [ExpectedTreeStrings.errors]' Unknown text. */
internal val OWN_MESSAGES = setOf(
    FilesFailure.NotConnected,
    FilesFailure.Unreachable,
    FilesFailure.Unauthorized,
    FilesFailure.NotFound,
    FilesFailure.PermissionDenied,
    FilesFailure.PathRejected,
    FilesFailure.ServerFailed,
    FilesFailure.Unsupported,
    FilesFailure.Unknown,
)

/** The failures after which listing the folder again cannot help. */
internal val NOT_RETRYABLE = setOf(
    FilesFailure.NotConnected,
    FilesFailure.Unauthorized,
    FilesFailure.NotFound,
    FilesFailure.PathRejected,
    FilesFailure.Unsupported,
)

/** What the tree shows, the same in each language; a subclass sets the language and its texts. */
abstract class TreeScreenLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedTreeStrings

    @Test
    fun `the listing shows the title, the path bar and the top actions`() {
        compose.setTreeScreen(listing)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithText(ROOT_NAME).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.up).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.search).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.transfers).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.more).assertIsDisplayed()
    }

    @Test
    fun `each kind of entry has its own icon description`() {
        compose.setTreeScreen(listing)

        compose.onAllNodesWithContentDescription(strings.folder, useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithContentDescription(strings.file, useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithContentDescription(strings.symlink, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.other, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `badges and the ignored marker are named`() {
        compose.setTreeScreen(listing)

        compose.onNodeWithContentDescription(strings.badges.getValue(GitBadge.Modified), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.badges.getValue(GitBadge.Untracked), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.badges.getValue(GitBadge.ContainsChanges), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText(strings.ignored, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `every badge has its own description`() {
        assertEquals(GitBadge.entries.toSet(), strings.badges.keys)
        assertEquals(GitBadge.entries.size, strings.badges.values.toSet().size, "descriptions must differ")
        val rows = GitBadge.entries.mapIndexed { index, badge -> file(index).copy(badge = badge) }
        compose.setTreeScreen(TreeState(directory = ROOT, loading = false, entries = rows))

        GitBadge.entries.forEachIndexed { index, badge ->
            compose.onNodeWithTag(TreeTags.LIST).performScrollToIndex(index)
            compose.onNodeWithContentDescription(strings.badges.getValue(badge), useUnmergedTree = true)
                .assertIsDisplayed()
        }
    }

    @Test
    fun `a row's action button names the entry`() {
        compose.setTreeScreen(listing)

        compose.onNodeWithContentDescription(strings.actionsFor(readme.entry.name)).assertIsDisplayed()
    }

    @Test
    fun `the first load says so`() {
        compose.setTreeScreen(TreeState(directory = ROOT))

        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `a reload says so above the rows`() {
        compose.setTreeScreen(listing.copy(refreshing = true))

        compose.onNodeWithContentDescription(strings.refreshing).assertIsDisplayed()
        compose.onNodeWithText(readme.entry.name).assertIsDisplayed()
    }

    @Test
    fun `an empty folder says hidden files are left out`() {
        compose.setTreeScreen(TreeState(directory = ROOT, loading = false))

        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyHidden).assertIsDisplayed()
        compose.onNodeWithText(strings.showHiddenAction).assertIsDisplayed()
    }

    @Test
    fun `an empty folder with hidden files shown only says it is empty`() {
        compose.setTreeScreen(TreeState(directory = ROOT, loading = false, showHidden = true))

        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyHidden).assertDoesNotExist()
        compose.onNodeWithText(strings.showHiddenAction).assertDoesNotExist()
    }

    @Test
    fun `the options name every control`() {
        compose.setTreeScreen(listing)

        compose.onNodeWithContentDescription(strings.more).performClick()

        compose.onNodeWithText(strings.optionsTitle).assertIsDisplayed()
        for (text in listOf(
            strings.newFolder, strings.refresh, strings.showHidden, strings.sortHeading,
            strings.sortName, strings.sortModified, strings.sortSize, strings.close,
        )) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `every failure has a message`() {
        assertEquals(FilesFailure.entries.toSet(), strings.errors.keys)
        assertEquals(
            OWN_MESSAGES.size,
            OWN_MESSAGES.map { strings.errors.getValue(it) }.toSet().size,
            "the failures the tree words on its own must differ",
        )
        for (failure in FilesFailure.entries - OWN_MESSAGES) {
            assertEquals(strings.errors.getValue(FilesFailure.Unknown), strings.errors.getValue(failure))
        }
    }

    @Test
    fun `not connected is explained`() = assertError(FilesFailure.NotConnected)

    @Test
    fun `unreachable is explained`() = assertError(FilesFailure.Unreachable)

    @Test
    fun `unauthorized is explained`() = assertError(FilesFailure.Unauthorized)

    @Test
    fun `not found is explained`() = assertError(FilesFailure.NotFound)

    @Test
    fun `permission denied is explained`() = assertError(FilesFailure.PermissionDenied)

    @Test
    fun `a rejected path is explained`() = assertError(FilesFailure.PathRejected)

    @Test
    fun `a server failure is explained`() = assertError(FilesFailure.ServerFailed)

    @Test
    fun `unsupported is explained`() = assertError(FilesFailure.Unsupported)

    @Test
    fun `an unknown failure is explained`() = assertError(FilesFailure.Unknown)

    @Test
    fun `a failure the tree does not word on its own is explained`() = assertError(FilesFailure.TooLarge)

    private fun assertError(failure: FilesFailure) {
        compose.setTreeScreen(TreeState(directory = ROOT, loading = false, error = failure))

        compose.onNodeWithTag(TreeTags.ERROR).assertIsDisplayed()
        compose.onAllNodesWithText(strings.errors.getValue(failure)).assertCountEquals(1)
        if (failure in NOT_RETRYABLE) {
            compose.onNodeWithText(strings.retry).assertDoesNotExist()
        } else {
            compose.onNodeWithText(strings.retry).assertIsDisplayed()
        }
    }
}
