package io.github.thewisekd.phopencode.feature.git.ui.log

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the history screen draws and sends; the texts are in English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class LogScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<LogIntent>()

    private fun show(state: LogState) = compose.setLogScreen(state) { sent += it }

    private fun rowOf(subject: String) = hasTestTag(LogTags.ROW) and hasText(subject, substring = true)

    @Test
    fun `the root keeps the route's placeholder tag when loaded`() {
        show(loadedLog.copy(endReached = true))
        compose.onNodeWithTag("placeholder:$logRoute").assertIsDisplayed()
    }

    @Test
    fun `a row shows the subject, short hash, author and date`() {
        show(loadedLog.copy(endReached = true))

        val row = rowOf(plainCommit.subject)
        compose.scrollLogTo(row)
        compose.onNode(row).assertIsDisplayed()
        for (part in listOf("0123456", plainCommit.authorName, expectedDate(AUTHORED, Locale.ENGLISH))) {
            compose.onNode(row and hasText(part, substring = true)).assertIsDisplayed()
        }
        compose.onNode(row and hasText(HASH, substring = true)).assertDoesNotExist()
    }

    @Test
    fun `a merge commit carries the merge marker, a plain one does not`() {
        show(loadedLog.copy(endReached = true))

        compose.scrollLogTo(rowOf(mergeCommit.subject))
        compose.onNode(rowOf(mergeCommit.subject) and hasText("Merge", substring = true)).assertIsDisplayed()
        compose.onNode(rowOf(plainCommit.subject) and hasText("Merge", substring = true)).assertDoesNotExist()
        compose.onAllNodesWithTag(LogTags.MERGE, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `tapping a row opens its commit`() {
        show(loadedLog.copy(endReached = true))

        compose.scrollLogTo(rowOf(mergeCommit.subject))
        compose.onNode(rowOf(mergeCommit.subject)).performClick()

        assertEquals(listOf<LogIntent>(LogIntent.OpenCommit(MERGE_HASH)), sent)
    }

    @Test
    fun `rows are at least a touch target tall`() {
        show(loadedLog.copy(endReached = true))
        compose.onAllNodesWithTag(LogTags.ROW)[0].assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the first load shows a still line and no list`() {
        show(LogState(directory = ROOT))

        compose.onNodeWithTag(LogTags.LOADING).assertIsDisplayed()
        compose.onNodeWithContentDescription("Reading the history…").assertIsDisplayed()
        compose.onNodeWithTag(LogTags.LIST).assertDoesNotExist()
        compose.onNodeWithTag(LogTags.REFRESH).assertIsNotEnabled()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a failed read explains itself and retries`() {
        show(LogState(directory = ROOT, loading = false, error = GitFailure.Unreachable))

        compose.onNodeWithTag(LogTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("The server could not be reached.").assertIsDisplayed()
        compose.onNodeWithTag(LogTags.LIST).assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(listOf<LogIntent>(LogIntent.Retry), sent)
    }

    @Test
    fun `a lost connection offers no retry`() {
        show(LogState(directory = ROOT, loading = false, error = GitFailure.NotConnected))

        compose.onNodeWithText("Not connected to the server. Go back and connect again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `an unworded failure takes the fallback`() {
        show(LogState(directory = ROOT, loading = false, error = GitFailure.GitError(128)))
        compose.onNodeWithText("The history could not be read.").assertIsDisplayed()
    }

    @Test
    fun `a branch without commits says so`() {
        show(LogState(directory = ROOT, loading = false, endReached = true))

        compose.onNodeWithTag(LogTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("No commits yet").assertIsDisplayed()
        compose.onNodeWithText("This branch has no commits.").assertIsDisplayed()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `more is loaded only when the end of the list comes into view`() {
        show(loadedLog.copy(commits = manyCommits(30)))
        compose.waitForIdle()
        assertTrue(sent.isEmpty(), "nothing is loaded before the end is in view: $sent")

        compose.scrollLogTo(hasTestTag(LogTags.LOADING_MORE))
        compose.waitForIdle()

        assertEquals(listOf<LogIntent>(LogIntent.LoadMore), sent)
    }

    @Test
    fun `a short list loads more at once`() {
        show(loadedLog)
        compose.waitForIdle()
        assertEquals(listOf<LogIntent>(LogIntent.LoadMore), sent)
    }

    @Test
    fun `a page that is loading shows its row and asks for nothing more`() {
        show(loadedLog.copy(loadingMore = true))

        compose.scrollLogTo(hasTestTag(LogTags.LOADING_MORE))
        compose.onNodeWithTag(LogTags.LOADING_MORE).assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading more commits…").assertIsDisplayed()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the end of the history is said and nothing more is asked`() {
        show(loadedLog.copy(endReached = true))

        compose.scrollLogTo(hasTestTag(LogTags.END))
        compose.onNodeWithText("Start of the history").assertIsDisplayed()
        compose.onNodeWithTag(LogTags.LOADING_MORE).assertDoesNotExist()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a failed page shows an error row whose retry loads more`() {
        show(loadedLog.copy(moreError = GitFailure.TimedOut))

        compose.scrollLogTo(hasTestTag(LogTags.MORE_ERROR))
        compose.onNodeWithTag(LogTags.MORE_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Git took too long to answer.").assertIsDisplayed()
        compose.waitForIdle()
        assertTrue(sent.isEmpty(), "a failed page is not retried on its own: $sent")

        compose.onNode(hasText("Try again") and hasAnyAncestor(hasTestTag(LogTags.MORE_ERROR))).performClick()

        assertEquals(listOf<LogIntent>(LogIntent.LoadMore), sent)
        compose.scrollLogTo(rowOf(plainCommit.subject))
        compose.onNode(rowOf(plainCommit.subject)).assertIsDisplayed()
    }

    @Test
    fun `an unworded failed page takes its own fallback`() {
        show(loadedLog.copy(moreError = GitFailure.Unknown))

        compose.scrollLogTo(hasTestTag(LogTags.MORE_ERROR))
        compose.onNodeWithText("More commits could not be read.").assertIsDisplayed()
    }

    @Test
    fun `the filter names the current branch and offers every branch`() {
        show(loadedLog.copy(endReached = true))

        compose.onNodeWithTag(LogTags.FILTER).assertIsDisplayed()
        compose.onNodeWithText("Branch: Current branch").assertIsDisplayed()
        compose.onNodeWithTag(LogTags.FILTER).performClick()

        compose.onNodeWithTag(LogTags.filterOption(null)).assertIsDisplayed().assertIsSelected()
        for (branch in loadedLog.branches) {
            compose.onNodeWithTag(LogTags.filterOption(branch)).assertIsDisplayed()
        }
    }

    @Test
    fun `choosing a branch filters to it`() {
        show(loadedLog.copy(endReached = true))

        compose.onNodeWithTag(LogTags.FILTER).performClick()
        compose.onNodeWithTag(LogTags.filterOption("feature/giriş")).performClick()

        assertEquals(listOf<LogIntent>(LogIntent.FilterBranch("feature/giriş")), sent)
        compose.onNodeWithTag(LogTags.filterOption("main")).assertDoesNotExist()
    }

    @Test
    fun `choosing the current branch clears the filter`() {
        show(loadedLog.copy(branch = "origin/main", endReached = true))

        compose.onNodeWithText("Branch: origin/main").assertIsDisplayed()
        compose.onNodeWithTag(LogTags.FILTER).performClick()
        compose.onNodeWithTag(LogTags.filterOption("origin/main")).assertIsSelected()
        compose.onNodeWithTag(LogTags.filterOption(null)).performClick()

        assertEquals(listOf<LogIntent>(LogIntent.FilterBranch(null)), sent)
    }

    @Test
    fun `choosing the branch already shown sends nothing`() {
        show(loadedLog.copy(branch = "main", endReached = true))

        compose.onNodeWithTag(LogTags.FILTER).performClick()
        compose.onNodeWithTag(LogTags.filterOption("main")).performClick()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the filter stays while a branch fails to load`() {
        show(loadedLog.copy(branch = "main", commits = emptyList(), error = GitFailure.NotFound))

        compose.onNodeWithText("The commit or branch is no longer there.").assertIsDisplayed()
        compose.onNodeWithTag(LogTags.FILTER).assertIsDisplayed()
    }

    @Test
    fun `without branches there is no filter`() {
        show(loadedLog.copy(branches = emptyList(), endReached = true))
        compose.onNodeWithTag(LogTags.FILTER).assertDoesNotExist()
    }

    @Test
    fun `refresh starts over, or retries after a failed read`() {
        show(loadedLog.copy(endReached = true))
        compose.onNodeWithContentDescription("Refresh").assertIsEnabled().performClick()
        assertEquals(listOf<LogIntent>(LogIntent.Refresh), sent)
    }

    @Test
    fun `refresh after a failed read retries`() {
        show(LogState(directory = ROOT, loading = false, error = GitFailure.TimedOut))
        compose.onNodeWithTag(LogTags.REFRESH).performClick()
        assertEquals(listOf<LogIntent>(LogIntent.Retry), sent)
    }

    @Test
    fun `back closes the screen`() {
        show(loadedLog.copy(endReached = true))
        compose.onNodeWithContentDescription("Go back").performClick()
        assertEquals(listOf<LogIntent>(LogIntent.Close), sent)
    }

    @Test
    fun `every commit gets one row`() {
        show(loadedLog.copy(endReached = true))
        compose.onAllNodesWithTag(LogTags.ROW).assertCountEquals(2)
    }
}
