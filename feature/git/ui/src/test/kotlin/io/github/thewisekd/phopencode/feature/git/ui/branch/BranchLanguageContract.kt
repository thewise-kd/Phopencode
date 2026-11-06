package io.github.thewisekd.phopencode.feature.git.ui.branch

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchActionError
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchDialog
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchNameProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the branch screen in one language. A format takes a branch name or a count. */
internal data class ExpectedBranchStrings(
    val title: String,
    val refresh: String,
    val create: String,
    val loading: String,
    val busy: Map<BranchOp, String>,
    val search: String,
    val searchClear: String,
    val local: String,
    val remote: String,
    val current: String,
    val delete: (String) -> String,
    val tracking: (String) -> String,
    val ahead: (Int) -> String,
    val behind: (Int) -> String,
    val upToDate: String,
    val noUpstream: String,
    val emptyTitle: String,
    val noMatchTitle: String,
    val dirtyTitle: String,
    val dirtyConfirm: String,
    val cancel: String,
    val deleteTitle: String,
    val deleteConfirm: String,
    val unmergedTitle: String,
    val unmergedConfirm: String,
    val createTitle: String,
    val createName: String,
    val createFrom: (String) -> String,
    val createFromHead: String,
    val createSwitchAfter: String,
    val createSubmit: String,
    val problems: Map<BranchNameProblem, String>,
    val retry: String,
    /** The failures the screen words on its own, and the fallbacks for the rest. */
    val errors: Map<GitFailure, String>,
    val readFallback: String,
    val actionFallbacks: Map<BranchOp, String>,
)

/** The failures each language words on its own; the others take a fallback. */
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
    GitFailure.WouldOverwrite,
    GitFailure.Conflict,
)

internal val FALLBACK = listOf(
    GitFailure.CredentialRequired,
    GitFailure.NonFastForward,
    GitFailure.Diverged,
    GitFailure.GitError(exitCode = 128),
    GitFailure.GitError(exitCode = null),
    GitFailure.Unknown,
)

/** What the branch screen shows, the same in each language; a subclass sets the language and its texts. */
abstract class BranchLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedBranchStrings

    @Test
    fun `the top bar and the search are named`() {
        compose.setBranchesScreen(loadedBranches.copy(query = "x"))

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.refresh).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.create).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.searchClear).assertIsDisplayed()
    }

    @Test
    fun `the empty search shows its hint`() {
        compose.setBranchesScreen(loadedBranches)
        compose.onNode(hasTestTag(BranchTags.SEARCH) and hasText(strings.search)).assertIsDisplayed()
    }

    @Test
    fun `the sections, the current mark and the rows are worded`() {
        compose.setBranchesScreen(loadedBranches)

        compose.onNode(hasTestTag(BranchTags.HEADER_LOCAL) and hasText(strings.local)).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.current).assertIsDisplayed()
        val upstream = listOf(strings.tracking("origin/main"), strings.ahead(2), strings.behind(1)).joinToString(" · ")
        compose.onNode(rowOf(mainBranch) and hasText(upstream)).assertIsDisplayed()
        compose.scrollBranchesTo(rowOf(featureBranch))
        compose.onNode(rowOf(featureBranch) and hasText(strings.noUpstream)).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.delete(featureBranch.name)).assertIsDisplayed()
        compose.scrollBranchesTo(rowOf(fixBranch))
        compose.onNode(rowOf(fixBranch) and hasText("${strings.tracking(fixBranch.upstream!!)} · ${strings.upToDate}"))
            .assertIsDisplayed()
        val remoteHeader = hasTestTag(BranchTags.HEADER_REMOTE) and hasText(strings.remote)
        compose.scrollBranchesTo(remoteHeader)
        compose.onNode(remoteHeader).assertIsDisplayed()
    }

    @Test
    fun `one commit ahead is worded for one`() {
        compose.setBranchesScreen(loadedBranches.copy(local = listOf(mainBranch.copy(ahead = 1, behind = 0))))

        compose.onNode(rowOf(mainBranch) and hasText("${strings.tracking("origin/main")} · ${strings.ahead(1)}"))
            .assertIsDisplayed()
    }

    @Test
    fun `the empty and no-match states are worded`() {
        val holder = compose.setSwappableBranchesScreen(BranchesState(directory = ROOT, loading = false))
        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()

        holder.value = loadedBranches.copy(query = "yok-böyle")
        compose.onNodeWithText(strings.noMatchTitle).assertIsDisplayed()
    }

    @Test
    fun `the first load and running operations are worded`() {
        val holder = compose.setSwappableBranchesScreen(BranchesState(directory = ROOT))
        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()

        BranchOp.entries.forEach { op ->
            holder.value = loadedBranches.copy(busy = op)
            compose.onNodeWithContentDescription(strings.busy.getValue(op)).assertIsDisplayed()
        }
    }

    @Test
    fun `the dirty warning is worded`() {
        compose.setBranchesScreen(loadedBranches.copy(dirty = true, dialog = BranchDialog.ConfirmSwitchDirty(featureBranch)))

        compose.onNodeWithText(strings.dirtyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.dirtyConfirm).assertIsDisplayed()
        compose.onNodeWithText(strings.cancel).assertIsDisplayed()
    }

    @Test
    fun `the delete confirmations are worded`() {
        val holder = compose.setSwappableBranchesScreen(loadedBranches.copy(dialog = BranchDialog.ConfirmDelete(featureBranch)))
        compose.onNodeWithText(strings.deleteTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.deleteConfirm).assertIsDisplayed()

        holder.value = loadedBranches.copy(dialog = BranchDialog.ConfirmDeleteUnmerged(featureBranch))
        compose.onNodeWithText(strings.unmergedTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.unmergedConfirm).assertIsDisplayed()
        compose.onNode(hasText(featureBranch.name, substring = true) and hasAnyAncestor(hasTestTag(BranchTags.DIALOG_UNMERGED)))
            .assertIsDisplayed()
    }

    @Test
    fun `the create dialog is worded`() {
        val holder = compose.setSwappableBranchesScreen(loadedBranches.copy(dialog = BranchDialog.Create()))
        compose.onNodeWithText(strings.createTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.createName).assertIsDisplayed()
        compose.onNodeWithText(strings.createFromHead).assertIsDisplayed()
        compose.onNodeWithText(strings.createSwitchAfter).assertIsDisplayed()
        compose.onNodeWithText(strings.createSubmit).assertIsDisplayed()
        compose.onNodeWithText(strings.cancel).assertIsDisplayed()

        holder.value = loadedBranches.copy(dialog = BranchDialog.Create(startPoint = "origin/main"))
        compose.onNodeWithText(strings.createFrom("origin/main")).assertIsDisplayed()
    }

    @Test
    fun `every name problem is worded`() {
        assertEquals(BranchNameProblem.entries.toSet(), strings.problems.keys)
        assertEquals(strings.problems.size, strings.problems.values.toSet().size, "problems must differ")
        val holder = compose.setSwappableBranchesScreen(loadedBranches)
        BranchNameProblem.entries.forEach { problem ->
            holder.value = loadedBranches.copy(dialog = BranchDialog.Create(name = "x", problem = problem))
            compose.onNodeWithText(strings.problems.getValue(problem)).assertIsDisplayed()
        }
    }

    @Test
    fun `the worded failures differ`() {
        assertEquals(WORDED.toSet(), strings.errors.keys)
        assertEquals(BranchOp.entries.toSet(), strings.actionFallbacks.keys)
        val texts = WORDED.map { strings.errors.getValue(it) } + strings.readFallback + strings.actionFallbacks.values
        assertEquals(texts.size, texts.toSet().size, "every worded failure must have its own text")
    }

    @Test
    fun `every failure listing the branches is explained`() {
        val holder = compose.setSwappableBranchesScreen(BranchesState(directory = ROOT))
        (WORDED + FALLBACK).forEach { failure ->
            holder.value = BranchesState(directory = ROOT, loading = false, error = failure)
            compose.onNodeWithTag(BranchTags.ERROR).assertIsDisplayed()
            compose.onAllNodesWithText(strings.errors[failure] ?: strings.readFallback).assertCountEquals(1)
        }
    }

    @Test
    fun `every failed action is explained, by its operation`() {
        val holder = compose.setSwappableBranchesScreen(loadedBranches)
        BranchOp.entries.forEach { op ->
            (WORDED + FALLBACK).forEach { failure ->
                holder.value = loadedBranches.copy(actionError = BranchActionError(op, failure))
                compose.onNodeWithTag(BranchTags.ACTION_ERROR).assertIsDisplayed()
                val expected = strings.errors[failure] ?: strings.actionFallbacks.getValue(op)
                compose.onAllNodesWithText(expected).assertCountEquals(1)
            }
        }
    }

    @Test
    fun `retry is offered after a failed list`() {
        compose.setBranchesScreen(BranchesState(directory = ROOT, loading = false, error = GitFailure.Unreachable))
        compose.onNodeWithText(strings.retry).assertIsDisplayed()
    }
}
