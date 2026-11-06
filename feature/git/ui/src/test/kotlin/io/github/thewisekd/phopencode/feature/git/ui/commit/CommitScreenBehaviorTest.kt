package io.github.thewisekd.phopencode.feature.git.ui.commit

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitError
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitMessageProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the commit screen sends and which controls it enables; the texts are in English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class CommitScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<CommitIntent>()

    private fun show(state: CommitState) = compose.setCommitScreen(state) { sent += it }

    @Test
    fun `the root keeps the route's placeholder tag when loaded`() {
        show(loaded)
        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the message field shows the message`() {
        show(loaded)
        compose.onNode(hasTestTag(CommitTags.MESSAGE) and hasText(loaded.message)).assertIsDisplayed()
    }

    @Test
    fun `typing sends the edited message`() {
        val holder = stateHolder(loaded.copy(message = ""))
        compose.setCommitScreen(holder) { intent ->
            sent += intent
            if (intent is CommitIntent.EditMessage) holder.value = holder.value.copy(message = intent.text)
        }

        compose.onNodeWithTag(CommitTags.MESSAGE).performTextInput("Düzelt")

        assertEquals(CommitIntent.EditMessage("Düzelt"), sent.filterIsInstance<CommitIntent.EditMessage>().last())
        compose.onNode(hasTestTag(CommitTags.MESSAGE) and hasText("Düzelt")).assertIsDisplayed()
    }

    @Test
    fun `the message field takes more than one line`() {
        show(loaded.copy(message = "Summary\n\nBody line"))

        // A single-line outlined field is 56 dp high; three lines need clearly more.
        compose.onNodeWithTag(CommitTags.MESSAGE).assertHeightIsAtLeast(88.dp)
        compose.onNode(hasTestTag(CommitTags.MESSAGE) and hasText("Summary\n\nBody line")).assertIsDisplayed()
    }

    @Test
    fun `commit sends the commit intent`() {
        show(loaded)

        compose.onNodeWithTag(CommitTags.COMMIT).assertIsEnabled().performClick()

        assertEquals(listOf<CommitIntent>(CommitIntent.Commit), sent)
    }

    @Test
    fun `commit waits while nothing is staged, a blank message, a load or a running operation`() {
        val holder = stateHolder(loaded.copy(staged = emptyList()))
        compose.setCommitScreen(holder) { sent += it }
        listOf(
            loaded.copy(staged = emptyList()),
            loaded.copy(message = "  \n "),
            loaded.copy(loading = true),
            loaded.copy(busy = CommitOp.Stage),
            loaded.copy(busy = CommitOp.Commit),
        ).forEach { state ->
            holder.value = state
            compose.onNodeWithTag(CommitTags.COMMIT).assertIsNotEnabled()
        }
    }

    @Test
    fun `a blank message shows the problem and marks the field`() {
        show(loaded.copy(message = "", messageProblem = CommitMessageProblem.Blank))

        compose.onNodeWithTag(CommitTags.MESSAGE_PROBLEM, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Write a commit message first.", useUnmergedTree = true).assertExists()
        val config = compose.onNodeWithTag(CommitTags.MESSAGE).fetchSemanticsNode().config
        assertTrue(SemanticsProperties.Error in config)
    }

    @Test
    fun `no problem is shown without one`() {
        show(loaded.copy(message = ""))
        compose.onNodeWithTag(CommitTags.MESSAGE_PROBLEM, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a rejecting hook shows its output`() {
        val output = "pre-commit: trailing whitespace\nsrc/a.kt:3"
        show(loaded.copy(error = CommitError.HookRejected(output)))

        compose.onNodeWithTag(CommitTags.HOOK_ERROR).assertIsDisplayed()
        compose.onNodeWithText("A hook refused the commit. Nothing was committed.").assertIsDisplayed()
        compose.onNode(hasText(output) and hasAnyAncestor(hasTestTag(CommitTags.HOOK_OUTPUT)), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the hook output is monospace`() {
        show(loaded.copy(error = CommitError.HookRejected("lint failed")))

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("lint failed"), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            .action?.invoke(layouts)
        assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
    }

    @Test
    fun `a long hook output scrolls in its box`() {
        val output = (1..200).joinToString("\n") { "line $it of the hook" }
        show(loaded.copy(error = CommitError.HookRejected(output)))

        val box = compose.onNodeWithTag(CommitTags.HOOK_OUTPUT).fetchSemanticsNode()
        assertTrue(SemanticsProperties.VerticalScrollAxisRange in box.config, "the output must scroll")
    }

    @Test
    fun `a silent hook says it printed nothing`() {
        show(loaded.copy(error = CommitError.HookRejected("")))
        compose.onNodeWithText("The hook printed nothing.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `dismissing the hook error sends dismiss`() {
        show(loaded.copy(error = CommitError.HookRejected("no")))

        compose.onNode(hasContentDescription("Dismiss error") and hasAnyAncestor(hasTestTag(CommitTags.HOOK_ERROR)))
            .performClick()

        assertEquals(listOf<CommitIntent>(CommitIntent.DismissError), sent)
    }

    @Test
    fun `a failed operation is worded and dismissed`() {
        show(loaded.copy(error = CommitError.Failed(CommitOp.Stage, GitFailure.NotFound)))

        compose.onNodeWithTag(CommitTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("The file or folder is no longer there.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss error").performClick()
        assertEquals(listOf<CommitIntent>(CommitIntent.DismissError), sent)
    }

    @Test
    fun `committing with nothing staged is worded`() {
        show(loaded.copy(staged = emptyList(), error = CommitError.NothingStaged))
        compose.onNodeWithText("Nothing is staged. Stage a file before committing.").assertIsDisplayed()
    }

    @Test
    fun `stage sends the exact change of its row`() {
        show(loaded)

        compose.scrollCommitTo(rowOf(staged = false, deletedFile))
        compose.onNodeWithContentDescription("Stage gone.txt").performClick()
        compose.scrollCommitTo(rowOf(staged = false, untrackedFile))
        compose.onNodeWithContentDescription("Stage todo list.txt").performClick()

        assertEquals(listOf<CommitIntent>(CommitIntent.Stage(deletedFile), CommitIntent.Stage(untrackedFile)), sent)
        assertEquals("lib/gone.txt", (sent[0] as CommitIntent.Stage).change.path)
        assertEquals("notes/todo list.txt", (sent[1] as CommitIntent.Stage).change.path)
    }

    @Test
    fun `unstage sends the exact change of its row`() {
        show(loaded)

        compose.scrollCommitTo(rowOf(staged = true, renamedFile))
        compose.onNodeWithContentDescription("Unstage new name.md").performClick()

        assertEquals(listOf<CommitIntent>(CommitIntent.Unstage(renamedFile)), sent)
        assertEquals("docs/new name.md", (sent.single() as CommitIntent.Unstage).change.path)
    }

    @Test
    fun `stage all and unstage all send their intents`() {
        show(loaded)

        compose.scrollCommitTo(hasTestTag(CommitTags.UNSTAGE_ALL))
        compose.onNodeWithTag(CommitTags.UNSTAGE_ALL).performClick()
        compose.scrollCommitTo(hasTestTag(CommitTags.STAGE_ALL))
        compose.onNodeWithTag(CommitTags.STAGE_ALL).performClick()

        assertEquals(listOf(CommitIntent.UnstageAll, CommitIntent.StageAll), sent)
    }

    @Test
    fun `a tap on a row opens its diff, staged or not`() {
        show(loaded)

        compose.scrollCommitTo(rowOf(staged = true, stagedFile))
        compose.onNode(rowOf(staged = true, stagedFile)).assertHasClickAction().performClick()
        compose.scrollCommitTo(rowOf(staged = false, modifiedFile))
        compose.onNode(rowOf(staged = false, modifiedFile)).performClick()

        assertEquals(
            listOf<CommitIntent>(
                CommitIntent.OpenDiff(stagedFile, staged = true),
                CommitIntent.OpenDiff(modifiedFile, staged = false),
            ),
            sent,
        )
    }

    @Test
    fun `discard asks for its row's change`() {
        show(loaded)

        compose.scrollCommitTo(rowOf(staged = false, modifiedFile))
        compose.onNodeWithContentDescription("Discard changes to README.md").performClick()

        assertEquals(listOf<CommitIntent>(CommitIntent.RequestDiscard(modifiedFile)), sent)
    }

    @Test
    fun `untracked, unmerged and staged rows have no discard`() {
        show(loaded)

        listOf(true to stagedFile, true to renamedFile, false to untrackedFile, false to conflictedFile)
            .forEach { (staged, change) ->
                compose.scrollCommitTo(rowOf(staged, change))
                compose.onNode(hasTestTag(CommitTags.DISCARD) and hasAnyAncestor(rowOf(staged, change)))
                    .assertDoesNotExist()
            }
        listOf(modifiedFile, deletedFile).forEach { change ->
            compose.scrollCommitTo(rowOf(staged = false, change))
            compose.onNode(hasTestTag(CommitTags.DISCARD) and hasAnyAncestor(rowOf(staged = false, change)))
                .assertIsDisplayed()
        }
    }

    @Test
    fun `every eligible kind can be discarded and the others cannot`() {
        val rows = GitChangeKind.entries.mapIndexed { index, kind -> GitFileChange("dir/file-$index.txt", kind) }
        show(CommitState(directory = ROOT, loading = false, unstaged = rows))

        rows.forEach { change ->
            compose.scrollCommitTo(rowOf(staged = false, change))
            val discard = compose.onNode(hasTestTag(CommitTags.DISCARD) and hasAnyAncestor(rowOf(staged = false, change)))
            if (CommitState.canDiscard(change)) discard.assertExists() else discard.assertDoesNotExist()
        }
    }

    @Test
    fun `the discard dialog names the file and confirms`() {
        show(loaded.copy(confirmDiscard = modifiedFile))

        compose.onNodeWithTag(CommitTags.DISCARD_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText(
            "The changes to README.md that are not staged will be lost for good. This cannot be undone.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Discard").performClick()

        assertEquals(listOf<CommitIntent>(CommitIntent.ConfirmDiscard), sent)
    }

    @Test
    fun `the discard dialog cancels`() {
        show(loaded.copy(confirmDiscard = modifiedFile))

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(listOf<CommitIntent>(CommitIntent.CancelDiscard), sent)
    }

    @Test
    fun `request, confirm and cancel run through the dialog`() {
        val holder = stateHolder(loaded)
        compose.setCommitScreen(holder) { intent ->
            sent += intent
            holder.value = when (intent) {
                is CommitIntent.RequestDiscard -> holder.value.copy(confirmDiscard = intent.change)
                CommitIntent.CancelDiscard, CommitIntent.ConfirmDiscard -> holder.value.copy(confirmDiscard = null)
                else -> holder.value
            }
        }

        compose.onNodeWithTag(CommitTags.DISCARD_DIALOG).assertDoesNotExist()
        compose.scrollCommitTo(rowOf(staged = false, deletedFile))
        compose.onNodeWithContentDescription("Discard changes to gone.txt").performClick()
        compose.onNodeWithText("lib/gone.txt", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag(CommitTags.DISCARD_DIALOG).assertDoesNotExist()

        compose.onNodeWithContentDescription("Discard changes to gone.txt").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithTag(CommitTags.DISCARD_DIALOG).assertDoesNotExist()

        assertEquals(
            listOf(
                CommitIntent.RequestDiscard(deletedFile),
                CommitIntent.CancelDiscard,
                CommitIntent.RequestDiscard(deletedFile),
                CommitIntent.ConfirmDiscard,
            ),
            sent,
        )
    }

    @Test
    fun `the first load shows progress and no list`() {
        show(CommitState(directory = ROOT))

        compose.onNodeWithTag(CommitTags.LOADING).assertIsDisplayed()
        compose.onNodeWithContentDescription("Reading the changes…").assertIsDisplayed()
        compose.onNodeWithTag(CommitTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(CommitTags.header(staged = true)).assertDoesNotExist()
    }

    @Test
    fun `a clean tree has nothing to commit`() {
        show(clean)

        compose.scrollCommitTo(hasTestTag(CommitTags.EMPTY))
        compose.onNodeWithTag(CommitTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Nothing to commit").assertIsDisplayed()
        compose.onNodeWithTag(CommitTags.COMMIT).assertIsNotEnabled()
    }

    @Test
    fun `nothing staged yet is hinted under the staged header`() {
        show(loaded.copy(staged = emptyList()))

        compose.scrollCommitTo(hasTestTag(CommitTags.NOTHING_STAGED))
        compose.onNodeWithText("Nothing is staged yet. Stage the files to commit.").assertIsDisplayed()
    }

    @Test
    fun `the group headers count their rows`() {
        show(loaded)

        listOf(true to loaded.staged.size, false to loaded.unstaged.size).forEach { (staged, count) ->
            val header = hasTestTag(CommitTags.header(staged)) and hasText(count.toString())
            compose.scrollCommitTo(header)
            compose.onNode(header).assertIsDisplayed()
            assertTrue(SemanticsProperties.Heading in compose.onNode(header).fetchSemanticsNode().config)
        }
    }

    @Test
    fun `a running operation is worded and the rows wait`() {
        val holder = stateHolder(loaded)
        compose.setCommitScreen(holder) { sent += it }
        mapOf(
            CommitOp.Stage to "Staging…",
            CommitOp.Unstage to "Unstaging…",
            CommitOp.Discard to "Discarding…",
            CommitOp.Commit to "Committing…",
        ).forEach { (op, text) ->
            holder.value = loaded.copy(busy = op)
            compose.onNodeWithTag(CommitTags.BUSY).assertIsDisplayed()
            compose.onNodeWithContentDescription(text).assertIsDisplayed()
        }
        compose.scrollCommitTo(rowOf(staged = false, modifiedFile))
        compose.onNodeWithContentDescription("Stage README.md").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Discard changes to README.md").assertIsNotEnabled()
        compose.scrollCommitTo(hasTestTag(CommitTags.STAGE_ALL))
        compose.onNodeWithTag(CommitTags.STAGE_ALL).assertIsNotEnabled()
        // The message stays editable while files are staged, but not while the commit runs.
        compose.scrollCommitTo(hasTestTag(CommitTags.MESSAGE))
        compose.onNodeWithTag(CommitTags.MESSAGE).assertIsNotEnabled()
        holder.value = loaded.copy(busy = CommitOp.Stage)
        compose.onNodeWithTag(CommitTags.MESSAGE).assertIsEnabled()
    }

    @Test
    fun `row buttons are 48 dp targets`() {
        show(loaded)

        compose.scrollCommitTo(rowOf(staged = false, modifiedFile))
        listOf("Stage README.md", "Discard changes to README.md").forEach { description ->
            compose.onNodeWithContentDescription(description)
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `a rename names the file it came from`() {
        show(loaded)

        val row = rowOf(staged = true, renamedFile) and hasText("from docs/old name.md", substring = true)
        compose.scrollCommitTo(row)
        compose.onNode(row).assertIsDisplayed()
    }

    @Test
    fun `back closes the screen`() {
        show(loaded)
        compose.onNodeWithContentDescription("Go back").performClick()
        assertEquals(listOf<CommitIntent>(CommitIntent.Close), sent)
    }

    @Test
    fun `refresh reads the files again`() {
        show(loaded)
        compose.onNodeWithTag(CommitTags.REFRESH).performClick()
        assertEquals(listOf<CommitIntent>(CommitIntent.Refresh), sent)
    }

    @Test
    fun `refresh waits while loading or busy`() {
        val holder = stateHolder(loaded.copy(loading = true))
        compose.setCommitScreen(holder) { sent += it }
        compose.onNodeWithTag(CommitTags.REFRESH).assertIsNotEnabled()
        holder.value = loaded.copy(busy = CommitOp.Commit)
        compose.onNodeWithTag(CommitTags.REFRESH).assertIsNotEnabled()
        holder.value = loaded
        compose.onNodeWithTag(CommitTags.REFRESH).assertIsEnabled()
    }

    @Test
    fun `only one discard button per eligible row`() {
        show(CommitState(directory = ROOT, loading = false, unstaged = listOf(modifiedFile)))
        compose.onAllNodesWithTag(CommitTags.DISCARD).assertCountEquals(1)
    }
}
