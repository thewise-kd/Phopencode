package io.github.thewisekd.phopencode.feature.git.ui.status

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the status screen sends and which controls it enables; the texts are in English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class StatusScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<StatusIntent>()

    private fun show(state: StatusState) = compose.setStatusScreen(state) { sent += it }

    @Test
    fun `the root keeps the route's placeholder tag when loaded`() {
        show(loaded)
        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `every group header is a heading with its count`() {
        show(loaded)

        listOf(
            StatusGroup.Conflicted to loaded.conflicted.size,
            StatusGroup.Staged to loaded.staged.size,
            StatusGroup.Unstaged to loaded.unstaged.size,
            StatusGroup.Untracked to loaded.untracked.size,
        ).forEach { (group, count) ->
            val header = hasTestTag(StatusTags.header(group)) and hasText(count.toString())
            compose.scrollStatusTo(header)
            compose.onNode(header).assertIsDisplayed()
            assertTrue(SemanticsProperties.Heading in compose.onNode(header).fetchSemanticsNode().config)
        }
    }

    /** A tall screen, so that every header is composed at once and their places can be compared. */
    @Test
    @Config(qualifiers = "en-h2000dp")
    fun `the groups come in order, each header above its rows`() {
        show(loaded)

        fun top(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().positionInRoot.y
        val tops = listOf(
            top(hasTestTag(StatusTags.header(StatusGroup.Conflicted))),
            top(rowOf(StatusGroup.Conflicted, conflictedFile)),
            top(hasTestTag(StatusTags.header(StatusGroup.Staged))),
            top(rowOf(StatusGroup.Staged, stagedFile)),
            top(rowOf(StatusGroup.Staged, renamedFile)),
            top(hasTestTag(StatusTags.header(StatusGroup.Unstaged))),
            top(rowOf(StatusGroup.Unstaged, unstagedFile)),
            top(rowOf(StatusGroup.Unstaged, deletedFile)),
            top(hasTestTag(StatusTags.header(StatusGroup.Untracked))),
            top(rowOf(StatusGroup.Untracked, untrackedFile)),
        )
        assertEquals(tops.sorted(), tops)
        assertEquals(tops.size, tops.toSet().size)
    }

    @Test
    fun `an empty group has no header`() {
        show(loaded.copy(conflicted = emptyList(), untracked = emptyList()))

        compose.onNodeWithTag(StatusTags.header(StatusGroup.Conflicted)).assertDoesNotExist()
        compose.scrollStatusTo(hasTestTag(StatusTags.header(StatusGroup.Unstaged)))
        compose.onNodeWithTag(StatusTags.header(StatusGroup.Untracked)).assertDoesNotExist()
    }

    @Test
    fun `every row sits in its group and shows its name and folder`() {
        show(loaded)

        val rows = listOf(
            StatusGroup.Conflicted to conflictedFile,
            StatusGroup.Staged to stagedFile,
            StatusGroup.Staged to renamedFile,
            StatusGroup.Unstaged to unstagedFile,
            StatusGroup.Unstaged to deletedFile,
            StatusGroup.Untracked to untrackedFile,
        )
        rows.forEach { (group, change) ->
            compose.scrollStatusTo(rowOf(group, change))
            compose.onNode(rowOf(group, change)).assertIsDisplayed()
        }
        compose.scrollStatusTo(rowOf(StatusGroup.Staged, stagedFile))
        compose.onNode(rowOf(StatusGroup.Staged, stagedFile) and hasText("src/main", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `a file changed in the index and in the working tree has a row in both groups`() {
        val both = GitFileChange("app/Both.kt", GitChangeKind.Modified)
        show(StatusState(directory = ROOT, loading = false, branch = branch, staged = listOf(both), unstaged = listOf(both)))

        compose.scrollStatusTo(rowOf(StatusGroup.Staged, both))
        compose.onNode(rowOf(StatusGroup.Staged, both)).assertIsDisplayed()
        compose.scrollStatusTo(rowOf(StatusGroup.Unstaged, both))
        compose.onNode(rowOf(StatusGroup.Unstaged, both)).assertIsDisplayed()
    }

    @Test
    fun `staging an unstaged row sends its exact change`() {
        show(loaded)

        tapAction("Stage README.md", rowOf(StatusGroup.Unstaged, unstagedFile))

        assertEquals(listOf<StatusIntent>(StatusIntent.Stage(unstagedFile)), sent)
    }

    @Test
    fun `staging an untracked row sends its exact change`() {
        show(loaded)

        tapAction("Stage todo list.txt", rowOf(StatusGroup.Untracked, untrackedFile))

        assertEquals(listOf<StatusIntent>(StatusIntent.Stage(untrackedFile)), sent)
        assertEquals("notes/todo list.txt", (sent.single() as StatusIntent.Stage).change.path)
    }

    @Test
    fun `marking a conflict resolved stages it`() {
        show(loaded)

        tapAction("Mark çakışma.kt resolved", rowOf(StatusGroup.Conflicted, conflictedFile))

        assertEquals(listOf<StatusIntent>(StatusIntent.Stage(conflictedFile)), sent)
    }

    @Test
    fun `unstaging a staged row sends its exact change`() {
        show(loaded)

        tapAction("Unstage new name.md", rowOf(StatusGroup.Staged, renamedFile))

        assertEquals(listOf<StatusIntent>(StatusIntent.Unstage(renamedFile)), sent)
    }

    @Test
    fun `a row's action is a 48 dp target`() {
        show(loaded)

        compose.scrollStatusTo(rowOf(StatusGroup.Unstaged, unstagedFile))
        compose.onNodeWithContentDescription("Stage README.md")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `tapping a staged row opens the staged diff`() {
        show(loaded)

        compose.scrollStatusTo(rowOf(StatusGroup.Staged, stagedFile))
        compose.onNode(rowOf(StatusGroup.Staged, stagedFile)).assertHasClickAction().performClick()

        assertEquals(listOf<StatusIntent>(StatusIntent.OpenDiff(stagedFile, staged = true)), sent)
    }

    @Test
    fun `tapping an unstaged, untracked or conflicted row opens the working diff`() {
        show(loaded)

        listOf(
            StatusGroup.Unstaged to deletedFile,
            StatusGroup.Untracked to untrackedFile,
            StatusGroup.Conflicted to conflictedFile,
        ).forEach { (group, change) ->
            compose.scrollStatusTo(rowOf(group, change))
            compose.onNode(rowOf(group, change)).performClick()
        }

        assertEquals(
            listOf<StatusIntent>(
                StatusIntent.OpenDiff(deletedFile, staged = false),
                StatusIntent.OpenDiff(untrackedFile, staged = false),
                StatusIntent.OpenDiff(conflictedFile, staged = false),
            ),
            sent,
        )
    }

    @Test
    fun `a pending row and its action wait`() {
        show(loaded.copy(pending = setOf(unstagedFile.path)))

        compose.scrollStatusTo(rowOf(StatusGroup.Unstaged, unstagedFile))
        compose.onNode(rowOf(StatusGroup.Unstaged, unstagedFile)).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stage README.md").assertIsNotEnabled().performClick()
        compose.scrollStatusTo(rowOf(StatusGroup.Unstaged, deletedFile))
        compose.onNode(rowOf(StatusGroup.Unstaged, deletedFile)).assertIsEnabled()

        assertEquals(emptyList(), sent)
    }

    @Test
    fun `stage all and unstage all sit on their group headers`() {
        show(loaded)

        compose.scrollStatusTo(hasTestTag(StatusTags.STAGE_ALL))
        compose.onNodeWithTag(StatusTags.STAGE_ALL).assertIsDisplayed()
        compose.onNode(hasTestTag(StatusTags.STAGE_ALL) and hasAnyAncestor(hasTestTag(StatusTags.header(StatusGroup.Unstaged))))
            .assertIsDisplayed()
        compose.onNodeWithTag(StatusTags.STAGE_ALL).performClick()
        compose.scrollStatusTo(hasTestTag(StatusTags.UNSTAGE_ALL))
        compose.onNodeWithTag(StatusTags.UNSTAGE_ALL).performClick()

        assertEquals(listOf(StatusIntent.StageAll, StatusIntent.UnstageAll), sent)
    }

    @Test
    fun `stage all moves to the untracked header when nothing tracked is unstaged`() {
        show(loaded.copy(unstaged = emptyList()))

        compose.scrollStatusTo(hasTestTag(StatusTags.STAGE_ALL))
        compose.onNode(hasTestTag(StatusTags.STAGE_ALL) and hasAnyAncestor(hasTestTag(StatusTags.header(StatusGroup.Untracked))))
            .performClick()

        assertEquals(listOf<StatusIntent>(StatusIntent.StageAll), sent)
    }

    @Test
    fun `only conflicts offer no stage all`() {
        show(StatusState(directory = ROOT, loading = false, branch = branch, conflicted = listOf(conflictedFile)))

        compose.onNodeWithTag(StatusTags.STAGE_ALL).assertDoesNotExist()
        compose.onNodeWithTag(StatusTags.UNSTAGE_ALL).assertDoesNotExist()
    }

    @Test
    fun `the hub actions send their intents`() {
        show(loaded)

        listOf(
            StatusTags.COMMIT to StatusIntent.OpenCommit,
            StatusTags.LOG to StatusIntent.OpenLog,
            StatusTags.BRANCHES to StatusIntent.OpenBranches,
            StatusTags.REMOTE to StatusIntent.OpenRemote,
        ).forEach { (tag, intent) ->
            sent.clear()
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            assertEquals(listOf(intent), sent, tag)
        }
        sent.clear()
        compose.onNodeWithTag(StatusTags.REFRESH).performClick()
        assertEquals(listOf<StatusIntent>(StatusIntent.Refresh), sent)
    }

    @Test
    fun `back closes the screen`() {
        show(loaded)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<StatusIntent>(StatusIntent.Close), sent)
    }

    @Test
    fun `refresh waits while the status loads or reloads`() {
        show(loaded.copy(refreshing = true))

        compose.onNodeWithTag(StatusTags.REFRESH).assertIsNotEnabled()
        compose.onNodeWithTag(StatusTags.REFRESHING).assertIsDisplayed()
        compose.scrollStatusTo(rowOf(StatusGroup.Staged, stagedFile))
        compose.onNode(rowOf(StatusGroup.Staged, stagedFile)).assertIsDisplayed()
    }

    @Test
    fun `the first load shows only its progress`() {
        show(StatusState(directory = ROOT))

        compose.onNodeWithTag(StatusTags.LOADING).assertIsDisplayed()
        compose.onNodeWithTag(StatusTags.REFRESH).assertIsNotEnabled()
        compose.onNodeWithTag(StatusTags.CLEAN).assertDoesNotExist()
        compose.onNodeWithTag(StatusTags.COMMIT).assertDoesNotExist()
        compose.onNodeWithTag(StatusTags.BRANCH).assertDoesNotExist()
    }

    @Test
    fun `a clean repository says so`() {
        show(clean)

        compose.onNodeWithTag(StatusTags.CLEAN).assertIsDisplayed()
        StatusGroup.entries.forEach { compose.onNodeWithTag(StatusTags.header(it)).assertDoesNotExist() }
        compose.onNodeWithTag(StatusTags.BRANCH).assertIsDisplayed()
    }

    @Test
    fun `a failed read shows only the error, and retry reads again`() {
        show(StatusState(directory = ROOT, loading = false, error = GitFailure.Unreachable))

        compose.onNodeWithTag(StatusTags.ERROR).assertIsDisplayed()
        compose.onNodeWithTag(StatusTags.LIST).assertDoesNotExist()
        compose.onNodeWithTag(StatusTags.COMMIT).assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(listOf<StatusIntent>(StatusIntent.Retry), sent)
    }

    @Test
    fun `refresh after a failed read retries`() {
        show(StatusState(directory = ROOT, loading = false, error = GitFailure.TimedOut))

        compose.onNodeWithTag(StatusTags.REFRESH).performClick()

        assertEquals(listOf<StatusIntent>(StatusIntent.Retry), sent)
    }

    @Test
    fun `retry is not offered when the connection is gone`() {
        show(StatusState(directory = ROOT, loading = false, error = GitFailure.NotConnected))

        compose.onNodeWithTag(StatusTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a failed stage shows above the rows and can be dismissed`() {
        show(loaded.copy(actionError = GitFailure.Rejected))

        compose.onNodeWithTag(StatusTags.ACTION_ERROR).assertIsDisplayed()
        compose.scrollStatusTo(rowOf(StatusGroup.Staged, stagedFile))
        compose.onNode(rowOf(StatusGroup.Staged, stagedFile)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss error").performClick()

        assertEquals(listOf<StatusIntent>(StatusIntent.DismissError), sent)
    }

    @Test
    fun `the branch line names the branch and the upstream with its counts`() {
        show(loaded)

        compose.onNodeWithTag(StatusTags.BRANCH).assertIsDisplayed()
        compose.onNode(hasTestTag(StatusTags.BRANCH) and hasText("main")).assertIsDisplayed()
        compose.onNode(hasTestTag(StatusTags.UPSTREAM) and hasText("origin/main", substring = true))
            .assertIsDisplayed()
        compose.onNode(hasTestTag(StatusTags.UPSTREAM) and hasText("2 ahead", substring = true)).assertIsDisplayed()
        compose.onNode(hasTestTag(StatusTags.UPSTREAM) and hasText("1 behind", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a detached head shows its short commit`() {
        show(clean.copy(branch = GitBranchStatus(head = null, commit = HASH)))

        compose.onNode(hasTestTag(StatusTags.BRANCH) and hasText("0123456", substring = true)).assertIsDisplayed()
        compose.onNode(hasText(HASH, substring = true)).assertDoesNotExist()
        compose.onNodeWithTag(StatusTags.UPSTREAM).assertDoesNotExist()
    }

    @Test
    fun `the branch's counts are left out when it has no upstream`() {
        show(clean.copy(branch = GitBranchStatus(head = "feature/x", commit = HASH)))

        compose.onNode(hasTestTag(StatusTags.BRANCH) and hasText("feature/x")).assertIsDisplayed()
        compose.onNode(hasTestTag(StatusTags.UPSTREAM)).assertDoesNotExist()
        compose.onNode(hasTestTag(StatusTags.NO_UPSTREAM)).assertIsDisplayed()
    }

    private fun tapAction(description: String, row: SemanticsMatcher) {
        compose.scrollStatusTo(row)
        compose.onNode(hasContentDescription(description) and hasAnyAncestor(row)).assertIsEnabled().performClick()
    }
}
