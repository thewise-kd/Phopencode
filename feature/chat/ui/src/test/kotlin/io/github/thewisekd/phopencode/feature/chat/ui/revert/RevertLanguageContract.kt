package io.github.thewisekd.phopencode.feature.chat.ui.revert

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.StagedRevert
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the turn diff page in one language; numbers are written as the language does. */
internal data class ExpectedRevertStrings(
    val title: String,
    /** The summary of [LOADED], and of one file of one line added. */
    val summary: String,
    val summaryOne: String,
    /** The status label of each file status. */
    val statuses: Map<VcsFileStatus, String>,
    /** The counts of [EDITED_PATH] in [LOADED]. */
    val additions: String,
    val deletions: String,
    val showDiff: String,
    val hideDiff: String,
    val expanded: String,
    val collapsed: String,
    val noPatch: String,
    val moreLines: String,
    val moreLine: String,
    val moreCharacters: String,
    val loading: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val intro: String,
    val revert: String,
    val confirmTitle: String,
    /** The confirmation of [STAGED], with two files. */
    val confirmMessage: String,
    val confirmMessageOne: String,
    val confirmMessageNone: String,
    val cancel: String,
    val otherTurn: String,
    val pending: Map<RevertAction, String>,
    /** A step's failure, each action with [ChatFailure.Unknown]. */
    val stepErrors: Map<RevertAction, String>,
    /** A failed load, for each reason. */
    val loadErrors: Map<ChatFailure, String>,
    val retry: String,
)

/** What the page says, the same in each language; a subclass sets the language and its texts. */
abstract class RevertLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedRevertStrings

    @Test
    fun `the page has its title and summary`() {
        val harness = compose.setTurnDiff(LOADED)
        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithTag(RevertTags.SUMMARY).performScrollTo().assertTextEquals(strings.summary)

        harness.state.value = LOADED.copy(files = listOf(FileDiff(VcsFile("a", VcsFileStatus.Added, 1, 0))))
        compose.onNodeWithTag(RevertTags.SUMMARY).performScrollTo().assertTextEquals(strings.summaryOne)
    }

    @Test
    fun `a file names its status and counts in the language`() {
        val harness = compose.setTurnDiff(LOADED)
        compose.onNodeWithTag(RevertTags.file(0)).performScrollTo()
            .assertTextEquals(EDITED_PATH, strings.statuses.getValue(VcsFileStatus.Modified), strings.additions, strings.deletions)

        strings.statuses.forEach { (status, label) ->
            harness.state.value = LOADED.copy(files = listOf(FileDiff(VcsFile("a.kt", status))))
            compose.onNodeWithTag(RevertTags.file(0)).performScrollTo().assertTextEquals("a.kt", label)
        }
    }

    @Test
    fun `a file reads whether its diff is open and what a tap does`() {
        compose.setTurnDiff(LOADED)
        val closed = compose.onNodeWithTag(RevertTags.file(2)).performScrollTo().fetchSemanticsNode().config
        assertEquals(strings.collapsed, closed.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(strings.showDiff, closed.getOrNull(SemanticsActions.OnClick)?.label)

        compose.onNodeWithTag(RevertTags.file(2)).performScrollTo().performClick()

        val open = compose.onNodeWithTag(RevertTags.file(2)).performScrollTo().fetchSemanticsNode().config
        assertEquals(strings.expanded, open.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(strings.hideDiff, open.getOrNull(SemanticsActions.OnClick)?.label)
        compose.onNodeWithText(strings.noPatch).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a cut diff says what is left`() {
        val harness = compose.setTurnDiff(LOADED.copy(files = listOf(FileDiff(VcsFile("a", VcsFileStatus.Added), longPatch(DIFF_MAX_LINES + 200)))))
        compose.onNodeWithTag(RevertTags.file(0)).performScrollTo().performClick()
        compose.onNodeWithTag(RevertTags.more(0)).performScrollTo().assertTextEquals(strings.moreLines)

        harness.state.value = LOADED.copy(files = listOf(FileDiff(VcsFile("a", VcsFileStatus.Added), longPatch(DIFF_MAX_LINES + 1))))
        compose.onNodeWithTag(RevertTags.more(0)).performScrollTo().assertTextEquals(strings.moreLine)

        harness.state.value = LOADED.copy(files = listOf(FileDiff(VcsFile("a", VcsFileStatus.Added), "x".repeat(DIFF_MAX_CHARS + 1_500))))
        compose.onNodeWithTag(RevertTags.more(0)).performScrollTo().assertTextEquals(strings.moreCharacters)
    }

    @Test
    fun `the page says when it is loading or has no files`() {
        val harness = compose.setTurnDiff(RevertState())
        compose.onNodeWithText(strings.loading).performScrollTo().assertIsDisplayed()

        harness.state.value = LOADED.copy(files = emptyList())
        compose.onNodeWithText(strings.emptyTitle).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `revert explains itself and asks before it is final`() {
        val harness = compose.setTurnDiff(LOADED)
        compose.onNodeWithText(strings.intro).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RevertTags.REVERT).performScrollTo().assertTextEquals(strings.revert)

        harness.state.value = STAGED
        compose.onNodeWithText(strings.confirmTitle).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.confirmMessage).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RevertTags.CONFIRM).performScrollTo().assertTextEquals(strings.revert)
        compose.onNodeWithTag(RevertTags.CANCEL).performScrollTo().assertTextEquals(strings.cancel)

        harness.state.value = STAGED.copy(staged = StagedRevert(THIS_TURN, files = FILES.take(1), thisTurn = true))
        compose.onNodeWithText(strings.confirmMessageOne).performScrollTo().assertIsDisplayed()

        harness.state.value = STAGED.copy(staged = StagedRevert(THIS_TURN, thisTurn = true))
        compose.onNodeWithText(strings.confirmMessageNone).performScrollTo().assertIsDisplayed()

        harness.state.value = STAGED_ELSEWHERE
        compose.onNodeWithText(strings.otherTurn).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RevertTags.CANCEL).performScrollTo().assertTextEquals(strings.cancel)
    }

    @Test
    fun `each step says while it runs`() {
        val harness = compose.setTurnDiff(LOADED)
        strings.pending.forEach { (action, text) ->
            harness.state.value = (if (action == RevertAction.Stage) LOADED else STAGED).copy(pending = action)
            compose.onNodeWithTag(RevertTags.PENDING).performScrollTo().assertTextEquals(text)
        }
        assertEquals(RevertAction.entries.toSet(), strings.pending.keys)
    }

    @Test
    fun `each failed step has its own message`() {
        val harness = compose.setTurnDiff(LOADED)
        strings.stepErrors.forEach { (action, text) ->
            harness.state.value = STAGED.copy(error = RevertError(action, ChatFailure.Unknown))
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        assertEquals(RevertAction.entries.toSet(), strings.stepErrors.keys)
    }

    @Test
    fun `every load failure has its own message and a retry`() {
        val harness = compose.setTurnDiff(RevertState(loading = false, loadError = ChatFailure.Unknown))
        strings.loadErrors.forEach { (failure, text) ->
            harness.state.value = RevertState(loading = false, loadError = failure)
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(strings.retry).performScrollTo().assertIsDisplayed()
        assertEquals(ChatFailure.entries.toSet(), strings.loadErrors.keys)
    }
}
