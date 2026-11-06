package io.github.thewisekd.phopencode.feature.git.ui.diff

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffContent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffHunk
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val COULD_NOT = "Could not read the diff."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class DiffScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `each line kind shows its marker and its line numbers`() {
        compose.setDiff(loaded(fileDiff()))

        val expected = listOf(
            // hunk, line, marker, old number, new number
            listOf(0, 0, " ", "1", "1"),
            listOf(0, 1, "-", "2", ""),
            listOf(0, 2, "+", "", "2"),
            listOf(0, 3, "+", "", "3"),
            listOf(0, 4, " ", "3", "4"),
            listOf(1, 0, "-", "40", ""),
            listOf(1, 1, "\\", "", ""),
            listOf(1, 2, "+", "", "41"),
        )
        expected.forEach { (hunk, line, marker, old, new) ->
            hunk as Int
            line as Int
            compose.lineRow(hunk, line)
            compose.linePart(hunk, line, DiffTags.MARKER).assertTextEquals(marker as String)
            compose.linePart(hunk, line, DiffTags.OLD_NUMBER).assertTextEquals(old as String)
            compose.linePart(hunk, line, DiffTags.NEW_NUMBER).assertTextEquals(new as String)
        }
        compose.linePart(0, 2, DiffTags.TEXT).assertTextEquals("val a = 2")
    }

    @Test
    fun `lines tell TalkBack their kind, number and text`() {
        compose.setDiff(loaded(fileDiff()))

        compose.lineRow(0, 0).assertContentDescriptionEquals("Unchanged, line 1: package demo")
        compose.lineRow(0, 1).assertContentDescriptionEquals("Deleted, line 2: val a = 1")
        compose.lineRow(0, 2).assertContentDescriptionEquals("Added, line 2: val a = 2")
        compose.lineRow(0, 4).assertContentDescriptionEquals("Unchanged, line 4: blank")
        compose.lineRow(1, 1).assertContentDescriptionEquals("No newline at end of file")
        compose.linePart(1, 1, DiffTags.TEXT).assertTextEquals("No newline at end of file")
    }

    @Test
    fun `hunks show their header`() {
        compose.setDiff(loaded(fileDiff()))

        compose.scrollListTo(DiffTags.hunk(0)).assertContentDescriptionEquals("Change at line 1, previously line 1")
        compose.onNodeWithTag(DiffTags.hunk(0)).assertTextEquals("@@ -1,3 +1,4 @@ fun main()")
        compose.scrollListTo(DiffTags.hunk(1)).assertTextEquals("@@ -40,1 +41,1 @@")
    }

    @Test
    fun `the header shows the addition and deletion counts`() {
        compose.setDiff(loaded(fileDiff()))

        compose.scrollListTo(DiffTags.COUNTS).assertContentDescriptionEquals("3 additions, 2 deletions")
        compose.onNode(hasText("+3") and hasAnyAncestor(hasTestTag(DiffTags.COUNTS)), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("-2") and hasAnyAncestor(hasTestTag(DiffTags.COUNTS)), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a single addition and deletion read in the singular`() {
        val hunk = DiffHunk(1, 1, 1, 1, lines = listOf(deleted("a", 1), added("b", 1)))
        compose.setDiff(loaded(fileDiff(DiffContent.Text(listOf(hunk)), additions = 1, deletions = 1)))

        compose.scrollListTo(DiffTags.COUNTS).assertContentDescriptionEquals("1 addition, 1 deletion")
    }

    @Test
    fun `the header shows the path, the change kind and a working diff's context`() {
        compose.setDiff(loaded(fileDiff()))

        compose.scrollListTo(DiffTags.PATH).assertTextEquals(FILE)
        compose.onNodeWithTag(DiffTags.KIND).assertTextEquals("Modified")
        compose.onNodeWithTag(DiffTags.CONTEXT).assertTextEquals("Working tree")
        compose.onNodeWithTag(DiffTags.OLD_PATH).assertDoesNotExist()
    }

    @Test
    fun `a rename shows the old path`() {
        compose.setDiff(loaded(fileDiff(kind = GitChangeKind.Renamed, oldPath = OLD_FILE)))

        compose.scrollListTo(DiffTags.OLD_PATH).assertTextEquals("Old path: $OLD_FILE")
        compose.onNodeWithTag(DiffTags.KIND).assertTextEquals("Renamed")
    }

    @Test
    fun `a staged diff says it is staged`() {
        compose.setDiff(loaded(fileDiff(), STAGED_ROUTE), STAGED_ROUTE)

        compose.scrollListTo(DiffTags.CONTEXT).assertTextEquals("Staged")
    }

    @Test
    fun `a commit's diff names the commit by its short hash`() {
        compose.setDiff(loaded(fileDiff(), COMMIT_ROUTE), COMMIT_ROUTE)

        compose.scrollListTo(DiffTags.CONTEXT).assertTextEquals("Commit 0123456")
    }

    @Test
    fun `every change kind has a label`() {
        val harness = compose.setDiff(loaded(fileDiff()))
        val labels = GitChangeKind.entries.map { kind ->
            harness.state.value = loaded(fileDiff(kind = kind))
            compose.waitForIdle()
            compose.scrollListTo(DiffTags.KIND).fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        }

        assertEquals(GitChangeKind.entries.size, labels.toSet().size, labels.toString())
        assertTrue(labels.none { it.isBlank() })
    }

    @Test
    fun `a long line scrolls sideways with every row while the gutters stay`() {
        val long = "x".repeat(300) + "END"
        val hunk = DiffHunk(1, 2, 1, 2, lines = listOf(context(long, 1, 1), added("short", 2)))
        compose.setDiff(loaded(fileDiff(DiffContent.Text(listOf(hunk)), additions = 1, deletions = 0)))
        compose.lineRow(0, 1)

        val startLong = compose.linePart(0, 0, DiffTags.TEXT).getUnclippedBoundsInRoot().left
        val startShort = compose.linePart(0, 1, DiffTags.TEXT).getUnclippedBoundsInRoot().left
        val gutter = compose.linePart(0, 0, DiffTags.OLD_NUMBER).getUnclippedBoundsInRoot().left
        val marker = compose.linePart(0, 0, DiffTags.MARKER).getUnclippedBoundsInRoot().right
        assertClose(startLong, startShort)
        assertTrue(marker <= startLong, "the text starts after the marker")

        compose.onNodeWithTag(DiffTags.LINES).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        val movedLong = compose.linePart(0, 0, DiffTags.TEXT).getUnclippedBoundsInRoot().left
        val movedShort = compose.linePart(0, 1, DiffTags.TEXT).getUnclippedBoundsInRoot().left
        assertTrue(movedLong < startLong, "the long line moved left: $startLong -> $movedLong")
        assertClose(movedLong, movedShort)
        assertClose(gutter, compose.linePart(0, 0, DiffTags.OLD_NUMBER).getUnclippedBoundsInRoot().left)
        assertClose(marker, compose.linePart(0, 0, DiffTags.MARKER).getUnclippedBoundsInRoot().right)
    }

    @Test
    fun `sideways scrolling ends at the end of the longest line`() {
        val long = "x".repeat(300) + "END"
        val hunk = DiffHunk(1, 2, 1, 2, lines = listOf(context(long, 1, 1), added("short", 2)))
        compose.setDiff(loaded(fileDiff(DiffContent.Text(listOf(hunk)), additions = 1, deletions = 0)))
        compose.lineRow(0, 0)
        val row = compose.lineRow(0, 0).getUnclippedBoundsInRoot()

        compose.onNodeWithTag(DiffTags.LINES).performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
        compose.waitForIdle()

        val text = compose.linePart(0, 0, DiffTags.TEXT).getUnclippedBoundsInRoot()
        // The line's end is in view, short of the row's end only by the text's end padding.
        assertTrue(text.right <= row.right, "the end of the line is not cut off: ${text.right} > ${row.right}")
        assertClose(row.right, text.right, tolerance = 16.dp)
        compose.onNodeWithTag(DiffTags.LINES).performSemanticsAction(SemanticsActions.ScrollBy) { it(-100_000f, 0f) }
        compose.waitForIdle()
        assertClose(
            compose.linePart(0, 0, DiffTags.TEXT).getUnclippedBoundsInRoot().left,
            compose.linePart(0, 0, DiffTags.MARKER).getUnclippedBoundsInRoot().right,
            tolerance = 16.dp,
        )
    }

    @Test
    fun `the screen draws in the dark theme too`() {
        compose.setDiff(loaded(fileDiff()), dark = true)

        compose.lineRow(0, 2).assertIsDisplayed()
    }

    @Test
    fun `a binary file says there are no lines`() {
        compose.setDiff(loaded(fileDiff(DiffContent.Binary, additions = 0, deletions = 0)))

        compose.scrollListTo(DiffTags.BINARY).assertIsDisplayed()
        compose.onNodeWithText("Binary file").assertExists()
        compose.onNodeWithText("git reports this file as binary, so there are no lines to show.").assertExists()
    }

    @Test
    fun `a diff too large names the limit`() {
        compose.setDiff(loaded(fileDiff(DiffContent.TooLarge(limitBytes = 1_048_576), additions = 1200, deletions = 30)))

        compose.scrollListTo(DiffTags.TOO_LARGE).assertIsDisplayed()
        compose.onNodeWithText("Diff too large").assertExists()
        compose.onNodeWithText("This diff is larger than the 1 MB limit, so none of it is shown.").assertExists()
        compose.scrollListTo(DiffTags.COUNTS).assertContentDescriptionEquals("1200 additions, 30 deletions")
    }

    @Test
    fun `an unchanged file says no line changed`() {
        compose.setDiff(loaded(fileDiff(DiffContent.Unchanged, additions = 0, deletions = 0)))

        compose.scrollListTo(DiffTags.UNCHANGED).assertIsDisplayed()
        compose.onNodeWithText("No line changed").assertExists()
        compose.onNodeWithText("Only details such as the file mode changed.").assertExists()
    }

    @Test
    fun `while loading it says so`() {
        compose.setDiff(DiffState(target = targetOf(WORKING_ROUTE)))

        compose.onNodeWithTag(DiffTags.LOADING).assertTextEquals("Reading the diff…")
        compose.onNodeWithTag(DiffTags.LIST).assertDoesNotExist()
    }

    @Test
    fun `a reload keeps the loaded diff on screen`() {
        compose.setDiff(loaded(fileDiff()).copy(loading = true))

        compose.lineRow(0, 0).assertIsDisplayed()
        compose.onNodeWithTag(DiffTags.LOADING).assertDoesNotExist()
    }

    @Test
    fun `a failure says why and retries`() {
        val harness = compose.setDiff(DiffState(target = targetOf(WORKING_ROUTE), loading = false, error = GitFailure.Unreachable))

        compose.onNodeWithTag(DiffTags.ERROR).assertExists()
        compose.onNodeWithText("$COULD_NOT The server could not be reached.").assertExists()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(listOf<DiffIntent>(DiffIntent.Retry), harness.intents)
    }

    @Test
    fun `every failure has its own message`() {
        val failures = listOf(
            GitFailure.NotConnected, GitFailure.Unreachable, GitFailure.TimedOut, GitFailure.Unauthorized,
            GitFailure.OutputTooLarge, GitFailure.NotRepository, GitFailure.GitMissing, GitFailure.Rejected,
            GitFailure.NotFound, GitFailure.CredentialRequired, GitFailure.NonFastForward, GitFailure.Diverged,
            GitFailure.WouldOverwrite, GitFailure.Conflict, GitFailure.GitError(128), GitFailure.GitError(null),
            GitFailure.Unknown,
        )
        val harness = compose.setDiff(DiffState(target = targetOf(WORKING_ROUTE), loading = false))
        val messages = failures.map { failure ->
            harness.state.value = DiffState(target = targetOf(WORKING_ROUTE), loading = false, error = failure)
            compose.waitForIdle()
            compose.onNodeWithTag(DiffTags.ERROR).fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        }

        assertEquals(failures.size, messages.toSet().size, messages.toString())
        assertTrue(messages.all { it.startsWith(COULD_NOT) })
        assertTrue("$COULD_NOT git failed with exit code 128." in messages)
    }

    @Test
    fun `a failed reload keeps the diff and shows the error above it`() {
        compose.setDiff(loaded(fileDiff()).copy(error = GitFailure.TimedOut))

        compose.onNodeWithTag(DiffTags.ERROR).assertExists()
        compose.lineRow(0, 0).assertIsDisplayed()
    }

    @Test
    fun `refresh and back send their intents`() {
        val harness = compose.setDiff(loaded(fileDiff()))

        compose.onNodeWithTag(DiffTags.REFRESH).assertTextEquals("Refresh").performClick()
        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf(DiffIntent.Refresh, DiffIntent.Close), harness.intents)
    }

    @Test
    fun `the title is the file name`() {
        compose.setDiff(loaded(fileDiff()))

        compose.onNodeWithText("main kodu.kt").assertIsDisplayed()
    }

    private fun assertClose(expected: Dp, actual: Dp, tolerance: Dp = 1.dp) {
        assertTrue(abs((expected - actual).value) <= tolerance.value, "expected $expected, was $actual")
    }
}
