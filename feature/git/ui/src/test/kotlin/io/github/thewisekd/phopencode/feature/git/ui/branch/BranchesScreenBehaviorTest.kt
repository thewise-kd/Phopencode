package io.github.thewisekd.phopencode.feature.git.ui.branch

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchActionError
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchDialog
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchNameProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the branch screen sends and which controls it enables; the texts are in English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class BranchesScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<BranchIntent>()

    private fun show(state: BranchesState) = compose.setBranchesScreen(state) { sent += it }

    private fun top(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().positionInRoot.y

    @Test
    fun `the root keeps the route's placeholder tag when loaded`() {
        show(loadedBranches)
        compose.onNodeWithTag("placeholder:$branchesRoute").assertIsDisplayed()
    }

    @Test
    fun `each section header is a heading with its count`() {
        show(loadedBranches)

        listOf(BranchTags.HEADER_LOCAL to 3, BranchTags.HEADER_REMOTE to 2).forEach { (tag, count) ->
            val header = hasTestTag(tag) and hasText(count.toString())
            compose.scrollBranchesTo(header)
            compose.onNode(header).assertIsDisplayed()
            assertTrue(SemanticsProperties.Heading in compose.onNode(header).fetchSemanticsNode().config)
        }
    }

    /** A tall screen, so that every row is composed at once and their places can be compared. */
    @Test
    @Config(qualifiers = "en-h2000dp")
    fun `local and remote branches are kept apart, each under its header`() {
        show(loadedBranches)

        val tops = listOf(
            top(hasTestTag(BranchTags.HEADER_LOCAL)),
            top(rowOf(mainBranch)),
            top(rowOf(featureBranch)),
            top(rowOf(fixBranch)),
            top(hasTestTag(BranchTags.HEADER_REMOTE)),
            top(rowOf(remoteMain)),
            top(rowOf(remoteFeature)),
        )
        assertEquals(tops.sorted(), tops)
        assertEquals(tops.size, tops.toSet().size)
        // A local name never shows as a remote row, nor the other way round.
        compose.onNode(hasTestTag(BranchTags.ROW_REMOTE) and hasText(mainBranch.name)).assertDoesNotExist()
        compose.onNode(hasTestTag(BranchTags.ROW_LOCAL) and hasText(remoteMain.name)).assertDoesNotExist()
    }

    @Test
    fun `a section without branches has no header`() {
        show(loadedBranches.copy(remote = emptyList()))

        compose.onNodeWithTag(BranchTags.HEADER_LOCAL).assertIsDisplayed()
        compose.onNodeWithTag(BranchTags.HEADER_REMOTE).assertDoesNotExist()
    }

    @Test
    fun `typing in the search sends it`() {
        show(loadedBranches)

        compose.onNodeWithTag(BranchTags.SEARCH).performTextInput("gir")

        assertEquals(listOf<BranchIntent>(BranchIntent.Search("gir")), sent)
    }

    @Test
    fun `the search shows the query and clears it`() {
        show(loadedBranches.copy(query = "gir"))

        compose.onNodeWithTag(BranchTags.SEARCH).assertTextContains("gir")
        compose.onNodeWithTag(BranchTags.SEARCH_CLEAR).performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.Search("")), sent)
    }

    @Test
    fun `an empty search has no clear button`() {
        show(loadedBranches)
        compose.onNodeWithTag(BranchTags.SEARCH_CLEAR).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en-h2000dp")
    fun `a search shows only the matching branches, counted`() {
        // The contract decides what matches; the screen draws its shown lists.
        show(loadedBranches.copy(query = "giriş"))
        compose.onNode(rowOf(featureBranch)).assertIsDisplayed()
        compose.onNode(rowOf(remoteFeature)).assertIsDisplayed()
        compose.onNode(rowOf(mainBranch)).assertDoesNotExist()
        compose.onNode(rowOf(fixBranch)).assertDoesNotExist()
        compose.onNode(rowOf(remoteMain)).assertDoesNotExist()
        compose.onNode(hasTestTag(BranchTags.HEADER_LOCAL) and hasText("1")).assertIsDisplayed()
        compose.onNode(hasTestTag(BranchTags.HEADER_REMOTE) and hasText("1")).assertIsDisplayed()
    }

    @Test
    fun `a search matching nothing says so and keeps the field`() {
        show(loadedBranches.copy(query = "yok-böyle"))

        compose.onNodeWithTag(BranchTags.NO_MATCH).assertIsDisplayed()
        compose.onNodeWithTag(BranchTags.SEARCH).assertIsDisplayed()
        compose.onNodeWithTag(BranchTags.LIST).assertDoesNotExist()
        compose.onNodeWithTag(BranchTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `a repository without branches says so`() {
        show(BranchesState(directory = ROOT, loading = false))

        compose.onNodeWithTag(BranchTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(BranchTags.NO_MATCH).assertDoesNotExist()
        compose.onNodeWithTag(BranchTags.LIST).assertDoesNotExist()
    }

    @Test
    fun `the current branch is marked, and only it`() {
        show(loadedBranches)

        compose.onNode(rowOf(mainBranch) and hasContentDescription("Current branch")).assertIsDisplayed()
        compose.onAllNodes(hasTestTag(BranchTags.CURRENT), useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(rowOf(featureBranch) and hasContentDescription("Current branch")).assertDoesNotExist()
        assertTrue(compose.onNode(rowOf(mainBranch)).fetchSemanticsNode().config[SemanticsProperties.Selected])
    }

    @Test
    fun `tapping a local branch switches to it`() {
        show(loadedBranches)

        compose.scrollBranchesTo(rowOf(featureBranch))
        compose.onNode(rowOf(featureBranch)).performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.Switch(featureBranch)), sent)
    }

    @Test
    fun `tapping a remote branch switches to it`() {
        show(loadedBranches)

        compose.scrollBranchesTo(rowOf(remoteMain))
        compose.onNode(rowOf(remoteMain)).performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.Switch(remoteMain)), sent)
    }

    @Test
    fun `tapping the current branch sends nothing`() {
        show(loadedBranches)

        compose.onNode(rowOf(mainBranch)).performClick()

        assertEquals(emptyList(), sent)
    }

    @Test
    fun `a local row shows its upstream and how far it is ahead and behind`() {
        show(loadedBranches)

        compose.onNode(rowOf(mainBranch) and hasText("Tracking origin/main · 2 ahead · 1 behind")).assertIsDisplayed()
        compose.scrollBranchesTo(rowOf(featureBranch))
        compose.onNode(rowOf(featureBranch) and hasText("No upstream branch")).assertIsDisplayed()
        compose.scrollBranchesTo(rowOf(fixBranch))
        compose.onNode(rowOf(fixBranch) and hasText("Tracking origin/fix/çıkış · Up to date")).assertIsDisplayed()
    }

    /** A tall screen, so that every row is composed and the lines can be counted. */
    @Test
    @Config(qualifiers = "en-h2000dp")
    fun `only local rows show an upstream line`() {
        show(loadedBranches)

        compose.onNode(rowOf(remoteMain) and hasText("No upstream branch")).assertDoesNotExist()
        compose.onAllNodes(hasTestTag(BranchTags.UPSTREAM), useUnmergedTree = true).assertCountEquals(3)
    }

    @Test
    fun `a row shows its tip's short hash and subject`() {
        show(loadedBranches)
        compose.onNode(rowOf(mainBranch) and hasText("0123456 · Ana dal düzeltmesi")).assertIsDisplayed()
    }

    @Test
    fun `the dirty tree warning switches anyway`() {
        show(loadedBranches.copy(dirty = true, dialog = BranchDialog.ConfirmSwitchDirty(featureBranch)))

        compose.onNodeWithTag(BranchTags.DIALOG_DIRTY).assertIsDisplayed()
        compose.onNode(hasText(featureBranch.name, substring = true) and hasAnyAncestor(hasTestTag(BranchTags.DIALOG_DIRTY)))
            .assertIsDisplayed()
        compose.onNodeWithText("Switch anyway").performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.Confirm), sent)
    }

    @Test
    fun `the dirty tree warning can be cancelled`() {
        show(loadedBranches.copy(dirty = true, dialog = BranchDialog.ConfirmSwitchDirty(featureBranch)))

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.DismissDialog), sent)
    }

    @Test
    fun `the new branch button opens the create dialog`() {
        show(loadedBranches)

        compose.onNodeWithTag(BranchTags.CREATE).performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.StartCreate()), sent)
    }

    @Test
    fun `typing a name in the create dialog sends it`() {
        show(loadedBranches.copy(dialog = BranchDialog.Create()))

        compose.onNodeWithTag(BranchTags.DIALOG_CREATE).assertIsDisplayed()
        compose.onNodeWithTag(BranchTags.CREATE_NAME).performTextInput("yeni")

        assertEquals(listOf<BranchIntent>(BranchIntent.EditName("yeni")), sent)
    }

    @Test
    fun `a name problem is shown and blocks the create`() {
        show(loadedBranches.copy(dialog = BranchDialog.Create(name = "a..b", problem = BranchNameProblem.Invalid)))

        compose.onNodeWithText("Git does not allow this name.").assertIsDisplayed()
        compose.onNodeWithTag(BranchTags.CREATE_SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun `a taken name is shown`() {
        show(loadedBranches.copy(dialog = BranchDialog.Create(name = "main", problem = BranchNameProblem.Taken)))

        compose.onNodeWithText("A local branch already has this name.").assertIsDisplayed()
        compose.onNodeWithTag(BranchTags.CREATE_SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun `a blank name blocks the create`() {
        show(loadedBranches.copy(dialog = BranchDialog.Create(name = "", problem = BranchNameProblem.Blank)))

        compose.onNodeWithText("Enter a name.").assertIsDisplayed()
        compose.onNodeWithTag(BranchTags.CREATE_SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun `a good name creates the branch`() {
        show(loadedBranches.copy(dialog = BranchDialog.Create(name = "yeni")))

        compose.onNodeWithTag(BranchTags.CREATE_NAME).assertTextContains("yeni")
        compose.onNodeWithTag(BranchTags.CREATE_SUBMIT).assertIsEnabled().performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.SubmitCreate), sent)
    }

    @Test
    fun `the create dialog toggles switching after`() {
        show(loadedBranches.copy(dialog = BranchDialog.Create(name = "yeni", switchAfter = true)))

        compose.onNodeWithTag(BranchTags.CREATE_SWITCH_AFTER).assertIsOn().performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.SetSwitchAfter(false)), sent)
    }

    @Test
    fun `switching after can be turned on again`() {
        show(loadedBranches.copy(dialog = BranchDialog.Create(name = "yeni", switchAfter = false)))

        compose.onNodeWithTag(BranchTags.CREATE_SWITCH_AFTER).assertIsOff().performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.SetSwitchAfter(true)), sent)
    }

    @Test
    fun `the create dialog names its start point`() {
        show(loadedBranches.copy(dialog = BranchDialog.Create(startPoint = "origin/main")))
        compose.onNodeWithTag(BranchTags.CREATE_FROM).assertTextContains("Starts from origin/main")
    }

    @Test
    fun `the create dialog can be cancelled`() {
        show(loadedBranches.copy(dialog = BranchDialog.Create(name = "yeni")))

        compose.onNodeWithTag(BranchTags.CREATE_CANCEL).performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.DismissDialog), sent)
    }

    @Test
    fun `a create waits while it runs`() {
        show(loadedBranches.copy(dialog = BranchDialog.Create(name = "yeni"), busy = BranchOp.Create))
        compose.onNodeWithTag(BranchTags.CREATE_SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun `a non-current local branch can be deleted`() {
        show(loadedBranches)

        compose.scrollBranchesTo(rowOf(featureBranch))
        compose.onNodeWithContentDescription("Delete feature/giriş").performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.Delete(featureBranch)), sent)
    }

    @Test
    fun `the delete button is a full touch target`() {
        show(loadedBranches)

        compose.scrollBranchesTo(rowOf(featureBranch))
        compose.onNodeWithContentDescription("Delete feature/giriş")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    /** A tall screen, so that every row is composed and the buttons can be counted. */
    @Test
    @Config(qualifiers = "en-h2000dp")
    fun `the current branch and remote branches cannot be deleted`() {
        show(loadedBranches)

        compose.onNodeWithContentDescription("Delete main").assertDoesNotExist()
        listOf(remoteMain, remoteFeature).forEach { remote ->
            compose.onNode(rowOf(remote)).assertIsDisplayed()
            compose.onNodeWithContentDescription("Delete ${remote.name}").assertDoesNotExist()
        }
        // Only the two non-current local branches carry one.
        compose.onAllNodes(hasTestTag(BranchTags.DELETE)).assertCountEquals(2)
    }

    @Test
    fun `a delete confirmation goes ahead`() {
        show(loadedBranches.copy(dialog = BranchDialog.ConfirmDelete(featureBranch)))

        compose.onNodeWithTag(BranchTags.DIALOG_DELETE).assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.Confirm), sent)
    }

    @Test
    fun `the unmerged delete confirmation deletes anyway`() {
        show(loadedBranches.copy(dialog = BranchDialog.ConfirmDeleteUnmerged(featureBranch)))

        compose.onNodeWithTag(BranchTags.DIALOG_UNMERGED).assertIsDisplayed()
        compose.onNode(hasText(featureBranch.name, substring = true) and hasAnyAncestor(hasTestTag(BranchTags.DIALOG_UNMERGED)))
            .assertIsDisplayed()
        compose.onNodeWithText("Delete anyway").performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.Confirm), sent)
    }

    @Test
    fun `the unmerged delete confirmation can be cancelled`() {
        show(loadedBranches.copy(dialog = BranchDialog.ConfirmDeleteUnmerged(featureBranch)))

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.DismissDialog), sent)
    }

    @Test
    fun `a running operation says so and locks the rows`() {
        show(loadedBranches.copy(busy = BranchOp.Switch))

        compose.onNodeWithTag(BranchTags.BUSY).assertIsDisplayed()
        compose.onNodeWithContentDescription("Switching branch…").assertIsDisplayed()
        compose.scrollBranchesTo(rowOf(featureBranch))
        compose.onNode(rowOf(featureBranch)).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Delete feature/giriş").assertIsNotEnabled()
        compose.onNodeWithTag(BranchTags.CREATE).assertIsNotEnabled()
        compose.onNodeWithTag(BranchTags.REFRESH).assertIsNotEnabled()
    }

    @Test
    fun `the first load waits`() {
        show(BranchesState(directory = ROOT))

        compose.onNodeWithTag(BranchTags.LOADING).assertIsDisplayed()
        compose.onNodeWithTag(BranchTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(BranchTags.REFRESH).assertIsNotEnabled()
    }

    @Test
    fun `a failed list shows its error and retries`() {
        show(BranchesState(directory = ROOT, loading = false, error = GitFailure.Unreachable))

        compose.onNodeWithTag(BranchTags.ERROR).assertIsDisplayed()
        compose.onNodeWithTag(BranchTags.SEARCH).assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.Retry), sent)
    }

    @Test
    fun `no retry is offered when the connection is gone`() {
        show(BranchesState(directory = ROOT, loading = false, error = GitFailure.NotConnected))

        compose.onNodeWithTag(BranchTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `refresh lists again, or retries after a failure`() {
        val holder = compose.setSwappableBranchesScreen(loadedBranches) { sent += it }

        compose.onNodeWithTag(BranchTags.REFRESH).performClick()
        holder.value = BranchesState(directory = ROOT, loading = false, error = GitFailure.TimedOut)
        compose.onNodeWithTag(BranchTags.REFRESH).performClick()

        assertEquals(listOf(BranchIntent.Refresh, BranchIntent.Retry), sent)
    }

    @Test
    fun `a failed action shows above the rows and can be dismissed`() {
        show(loadedBranches.copy(actionError = BranchActionError(BranchOp.Switch, GitFailure.WouldOverwrite)))

        compose.onNodeWithTag(BranchTags.ACTION_ERROR).assertIsDisplayed()
        compose.onNode(rowOf(mainBranch)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss error").performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.DismissError), sent)
    }

    @Test
    fun `back closes the screen`() {
        show(loadedBranches)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<BranchIntent>(BranchIntent.Close), sent)
    }
}
