package io.github.thewisekd.phopencode.feature.git.ui.commit

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitError
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitMessageProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the commit screen in one language. A format takes a file name or a path. */
internal data class ExpectedCommitStrings(
    val title: String,
    val refresh: String,
    val loading: String,
    val messageLabel: String,
    val messageBlank: String,
    val commit: String,
    val busy: Map<CommitOp, String>,
    val groupStaged: String,
    val groupUnstaged: String,
    val stageAll: String,
    val unstageAll: String,
    val nothingStagedHint: String,
    val stage: (String) -> String,
    val unstage: (String) -> String,
    val discard: (String) -> String,
    val renamedFrom: (String) -> String,
    val kinds: Map<GitChangeKind, String>,
    val emptyTitle: String,
    val emptyMessage: String,
    val discardTitle: String,
    val discardMessage: (String) -> String,
    val discardConfirm: String,
    val cancel: String,
    val hookRejected: String,
    val hookNoOutput: String,
    val nothingStaged: String,
    /** The failures the screen words on its own, and the fallback of each operation for the rest. */
    val errors: Map<GitFailure, String>,
    val fallbacks: Map<CommitOp, String>,
)

/** The failures each language words on its own; the others take the operation's fallback. */
internal val WORDED = listOf(
    GitFailure.NotConnected,
    GitFailure.Unreachable,
    GitFailure.TimedOut,
    GitFailure.Unauthorized,
    GitFailure.OutputTooLarge,
    GitFailure.NotRepository,
    GitFailure.GitMissing,
    GitFailure.Rejected,
    GitFailure.NotFound,
    GitFailure.Conflict,
)

internal val FALLBACK = listOf(
    GitFailure.CredentialRequired,
    GitFailure.NonFastForward,
    GitFailure.Diverged,
    GitFailure.WouldOverwrite,
    GitFailure.GitError(exitCode = 1),
    GitFailure.GitError(exitCode = null),
    GitFailure.Unknown,
)

/** What the commit screen shows, the same in each language; a subclass sets the language and its texts. */
abstract class CommitLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedCommitStrings

    @Test
    fun `the top bar, the field and the button are named`() {
        compose.setCommitScreen(loaded)

        compose.onNode(hasText(strings.title) and hasAnyAncestor(hasTestTag(CommitTags.LIST)).not()).assertExists()
        compose.onNodeWithContentDescription(strings.refresh).assertIsDisplayed()
        compose.onNode(hasTestTag(CommitTags.MESSAGE) and hasText(strings.messageLabel)).assertIsDisplayed()
        compose.onNode(hasTestTag(CommitTags.COMMIT) and hasText(strings.commit)).assertIsDisplayed()
    }

    @Test
    fun `the blank message is worded`() {
        compose.setCommitScreen(loaded.copy(message = "", messageProblem = CommitMessageProblem.Blank))
        compose.onNodeWithText(strings.messageBlank, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the group headers and bulk actions are named`() {
        compose.setCommitScreen(loaded)

        listOf(true to strings.groupStaged, false to strings.groupUnstaged).forEach { (staged, title) ->
            val header = hasTestTag(CommitTags.header(staged)) and hasText(title)
            compose.scrollCommitTo(header)
            compose.onNode(header).assertIsDisplayed()
        }
        compose.scrollCommitTo(hasText(strings.unstageAll))
        compose.onNodeWithText(strings.unstageAll).assertIsDisplayed()
        compose.scrollCommitTo(hasText(strings.stageAll))
        compose.onNodeWithText(strings.stageAll).assertIsDisplayed()
    }

    @Test
    fun `nothing staged yet is worded`() {
        compose.setCommitScreen(loaded.copy(staged = emptyList()))
        compose.scrollCommitTo(hasText(strings.nothingStagedHint))
        compose.onNodeWithText(strings.nothingStagedHint).assertIsDisplayed()
    }

    @Test
    fun `each row's actions name its file`() {
        compose.setCommitScreen(loaded)

        listOf(
            rowOf(staged = true, stagedFile) to strings.unstage(fileName(stagedFile.path)),
            rowOf(staged = false, modifiedFile) to strings.stage(fileName(modifiedFile.path)),
            rowOf(staged = false, modifiedFile) to strings.discard(fileName(modifiedFile.path)),
            rowOf(staged = false, untrackedFile) to strings.stage(fileName(untrackedFile.path)),
        ).forEach { (row, description) ->
            compose.scrollCommitTo(row)
            compose.onNode(hasContentDescription(description) and hasAnyAncestor(row)).assertIsDisplayed()
        }
    }

    @Test
    fun `every kind of change is worded`() {
        assertEquals(GitChangeKind.entries.toSet(), strings.kinds.keys)
        assertEquals(GitChangeKind.entries.size, strings.kinds.values.toSet().size, "kinds must differ")
        val rows = GitChangeKind.entries.mapIndexed { index, kind -> GitFileChange("dir/file-$index.txt", kind) }
        compose.setCommitScreen(CommitState(directory = ROOT, loading = false, unstaged = rows))

        rows.forEach { change ->
            val row = rowOf(staged = false, change) and hasText(strings.kinds.getValue(change.kind), substring = true)
            compose.scrollCommitTo(row)
            compose.onNode(row).assertIsDisplayed()
        }
    }

    @Test
    fun `a rename names the file it came from`() {
        compose.setCommitScreen(loaded)

        val row = rowOf(staged = true, renamedFile) and
            hasText(strings.renamedFrom(renamedFile.originalPath!!), substring = true)
        compose.scrollCommitTo(row)
        compose.onNode(row).assertIsDisplayed()
    }

    @Test
    fun `the discard dialog names the file and says the changes are lost`() {
        compose.setCommitScreen(loaded.copy(confirmDiscard = deletedFile))

        compose.onNodeWithText(strings.discardTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.discardMessage(deletedFile.path)).assertIsDisplayed()
        compose.onNodeWithText(strings.discardConfirm).assertIsDisplayed()
        compose.onNodeWithText(strings.cancel).assertIsDisplayed()
    }

    @Test
    fun `a clean tree is worded`() {
        compose.setCommitScreen(clean)

        compose.scrollCommitTo(hasTestTag(CommitTags.EMPTY))
        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).assertIsDisplayed()
    }

    @Test
    fun `the first load is worded`() {
        compose.setCommitScreen(CommitState(directory = ROOT))
        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `every running operation is worded`() {
        assertEquals(CommitOp.entries.toSet(), strings.busy.keys)
        CommitOp.entries.forEach { op ->
            show(loaded.copy(busy = op))
            compose.onNodeWithContentDescription(strings.busy.getValue(op)).assertIsDisplayed()
        }
    }

    @Test
    fun `a rejecting hook is worded`() {
        compose.setCommitScreen(loaded.copy(error = CommitError.HookRejected("")))

        compose.onNodeWithText(strings.hookRejected).assertIsDisplayed()
        compose.onNodeWithText(strings.hookNoOutput, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `committing with nothing staged is worded`() {
        compose.setCommitScreen(loaded.copy(error = CommitError.NothingStaged))
        compose.onNodeWithText(strings.nothingStaged).assertIsDisplayed()
    }

    @Test
    fun `the worded failures differ`() {
        assertEquals(WORDED.toSet(), strings.errors.keys)
        assertEquals(CommitOp.entries.toSet(), strings.fallbacks.keys)
        val texts = WORDED.map { strings.errors.getValue(it) } + strings.fallbacks.values + strings.nothingStaged
        assertEquals(texts.size, texts.toSet().size, "every worded failure must have its own text")
    }

    @Test
    fun `every worded failure is explained whatever failed`() {
        CommitOp.entries.forEach { op ->
            WORDED.forEach { failure ->
                assertError(loaded.copy(error = CommitError.Failed(op, failure)), strings.errors.getValue(failure))
            }
        }
    }

    @Test
    fun `other failures take the fallback of what failed`() {
        CommitOp.entries.forEach { op ->
            FALLBACK.forEach { failure ->
                assertError(loaded.copy(error = CommitError.Failed(op, failure)), strings.fallbacks.getValue(op))
            }
        }
    }

    /** Content can be set once per rule: a state holder swaps the state shown. */
    private val shownState = mutableStateOf<CommitState?>(null)
    private var started = false

    private fun show(state: CommitState) {
        shownState.value = state
        if (!started) {
            started = true
            compose.setContent {
                PhopencodeTheme(darkTheme = false) {
                    Surface {
                        shownState.value?.let { CommitScreen(route = route, state = it, onIntent = {}) }
                    }
                }
            }
        }
    }

    private fun assertError(state: CommitState, message: String) {
        show(state)
        compose.onNodeWithTag(CommitTags.ERROR).assertIsDisplayed()
        compose.onAllNodesWithText(message).assertCountEquals(1)
    }
}
