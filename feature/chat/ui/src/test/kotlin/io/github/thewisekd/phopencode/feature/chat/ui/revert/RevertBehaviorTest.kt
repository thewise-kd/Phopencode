package io.github.thewisekd.phopencode.feature.chat.ui.revert

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the turn diff page sends and shows, in English: the texts of both languages are in `RevertLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class RevertBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val placeholder = "placeholder:$DIFF_ROUTE"

    // The page

    @Test
    fun `the root keeps the navigation tag in every state`() {
        val harness = compose.setTurnDiff(RevertState())
        compose.onNodeWithTag(placeholder).assertIsDisplayed()
        listOf(LOADED, STAGED, STAGED_ELSEWHERE, LOADED.copy(files = emptyList())).forEach {
            harness.state.value = it
            compose.onNodeWithTag(placeholder).assertIsDisplayed()
        }
    }

    @Test
    fun `back sends Close and nothing else`() {
        val harness = compose.setTurnDiff(LOADED)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<RevertIntent>(RevertIntent.Close), harness.intents)
    }

    @Test
    fun `the file list is a lazy list`() {
        compose.setTurnDiff(LOADED)

        compose.onNodeWithTag(RevertTags.LIST).assert(hasScrollAction())
        compose.onNodeWithTag(RevertTags.LIST)
            .performScrollToNode(hasTestTag(RevertTags.file(2)))
    }

    @Test
    fun `a long list reaches its last file`() {
        val many = (1..60).map { FileDiff(VcsFile("f$it.kt", VcsFileStatus.Modified, 1, 1), "+x") }
        compose.setTurnDiff(LOADED.copy(files = many))

        compose.onNodeWithTag(RevertTags.LIST).performScrollToNode(hasTestTag(RevertTags.file(59)))
        compose.onNodeWithTag(RevertTags.file(59)).assertIsDisplayed()
    }

    // The files

    @Test
    fun `each file shows its path, status and line counts`() {
        compose.setTurnDiff(LOADED)

        compose.onNodeWithTag(RevertTags.file(0)).performScrollTo()
            .assertTextEquals(EDITED_PATH, "Modified", "+1,234", "−1")
        compose.onNodeWithTag(RevertTags.file(1)).performScrollTo()
            .assertTextEquals(ADDED_PATH, "Added", "+1", "−0")
    }

    @Test
    fun `a file without counts leaves them out and shows an unknown status as the server gives it`() {
        compose.setTurnDiff(LOADED)

        compose.onNodeWithTag(RevertTags.file(2)).performScrollTo().assertTextEquals(BINARY_PATH, "binary")
    }

    @Test
    fun `diffs start closed and a tap opens and closes one`() {
        compose.setTurnDiff(LOADED)
        compose.onNodeWithTag(RevertTags.diff(0)).assertDoesNotExist()
        val row = compose.onNodeWithTag(RevertTags.file(0)).performScrollTo()
        assertEquals("Collapsed", row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals("Show diff", row.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)

        row.performClick()

        compose.onNodeWithTag(RevertTags.diff(0)).performScrollTo().assertTextEquals(EDITED_PATCH)
        compose.onNodeWithTag(RevertTags.diff(1)).assertDoesNotExist()
        val open = compose.onNodeWithTag(RevertTags.file(0)).performScrollTo().fetchSemanticsNode().config
        assertEquals("Expanded", open.getOrNull(SemanticsProperties.StateDescription))
        assertEquals("Hide diff", open.getOrNull(SemanticsActions.OnClick)?.label)

        compose.onNodeWithTag(RevertTags.file(0)).performScrollTo().performClick()
        compose.onNodeWithTag(RevertTags.diff(0)).assertDoesNotExist()
    }

    @Test
    fun `opening a diff sends nothing`() {
        val harness = compose.setTurnDiff(LOADED)

        compose.onNodeWithTag(RevertTags.file(1)).performScrollTo().performClick()

        compose.onNodeWithTag(RevertTags.diff(1)).performScrollTo().assertTextEquals(ADDED_PATCH)
        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `a file without a patch says so when opened`() {
        compose.setTurnDiff(LOADED)

        compose.onNodeWithTag(RevertTags.file(2)).performScrollTo().performClick()

        compose.onNodeWithTag(RevertTags.diff(2)).assertDoesNotExist()
        compose.onNodeWithText("No diff to show. It may be a binary file.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a long diff is cut and says how many lines are left`() {
        val big = FileDiff(VcsFile("big.kt", VcsFileStatus.Modified, 500, 0), longPatch(DIFF_MAX_LINES + 200))
        compose.setTurnDiff(LOADED.copy(files = listOf(big)))

        compose.onNodeWithTag(RevertTags.file(0)).performScrollTo().performClick()

        compose.onNodeWithTag(RevertTags.diff(0)).assertTextEquals(longPatch(DIFF_MAX_LINES))
        compose.onNodeWithTag(RevertTags.more(0)).performScrollTo().assertTextEquals("200 more lines")
    }

    @Test
    fun `a diff with a very long line is cut by characters`() {
        val line = "+" + "x".repeat(DIFF_MAX_CHARS + 99)
        val wide = FileDiff(VcsFile("wide.min.js", VcsFileStatus.Modified, 1, 0), line)
        compose.setTurnDiff(LOADED.copy(files = listOf(wide)))

        compose.onNodeWithTag(RevertTags.file(0)).performScrollTo().performClick()

        compose.onNodeWithTag(RevertTags.diff(0)).assertTextEquals(line.take(DIFF_MAX_CHARS))
        compose.onNodeWithTag(RevertTags.more(0)).performScrollTo().assertTextEquals("100 more characters")
    }

    @Test
    fun `the summary counts the files and their lines`() {
        compose.setTurnDiff(LOADED)

        compose.onNodeWithTag(RevertTags.SUMMARY).performScrollTo().assertTextEquals("3 files changed · +1,235 −1")
    }

    @Test
    fun `while loading the page says so and has no files`() {
        compose.setTurnDiff(RevertState())

        compose.onNodeWithText("Loading the changes…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RevertTags.SUMMARY).assertDoesNotExist()
        compose.onNodeWithTag(RevertTags.REVERT).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a turn without file changes says so and can still be reverted`() {
        val harness = compose.setTurnDiff(LOADED.copy(files = emptyList()))

        compose.onNodeWithText("No file changes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RevertTags.REVERT).performScrollTo().performClick()

        assertEquals(listOf<RevertIntent>(RevertIntent.Revert), harness.intents)
    }

    @Test
    fun `a failed load shows its reason and a retry that sends Retry`() {
        val harness = compose.setTurnDiff(RevertState(loading = false, enabled = true, loadError = ChatFailure.Unreachable))

        compose.onNodeWithText("The changes could not be loaded. The server could not be reached.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try again").performScrollTo().performClick()

        assertEquals(listOf<RevertIntent>(RevertIntent.Retry), harness.intents)
    }

    // Reverting

    @Test
    fun `revert stages only and asks for nothing final`() {
        val harness = compose.setTurnDiff(LOADED)
        compose.onNodeWithTag(RevertTags.CONFIRM).assertDoesNotExist()

        compose.onNodeWithTag(RevertTags.REVERT).performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf<RevertIntent>(RevertIntent.Revert), harness.intents)
        compose.onNodeWithTag(RevertTags.CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `revert is off when the page cannot revert`() {
        val harness = compose.setTurnDiff(LOADED.copy(enabled = false))
        compose.onNodeWithTag(RevertTags.REVERT).performScrollTo().assertIsNotEnabled()

        harness.state.value = LOADED.copy(pending = RevertAction.Stage)
        compose.onNodeWithTag(RevertTags.REVERT).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(RevertTags.PENDING).performScrollTo().assertTextEquals("Preparing the revert…")
    }

    @Test
    fun `a staged revert of this turn asks for confirmation with confirm and cancel`() {
        compose.setTurnDiff(STAGED)

        compose.onNodeWithTag(RevertTags.REVERT).assertDoesNotExist()
        compose.onNodeWithTag(RevertTags.CONFIRMATION).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RevertTags.CONFIRM).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(RevertTags.CANCEL).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `confirm sends Confirm`() {
        val harness = compose.setTurnDiff(STAGED)

        compose.onNodeWithTag(RevertTags.CONFIRM).performScrollTo().performClick()

        assertEquals(listOf<RevertIntent>(RevertIntent.Confirm), harness.intents)
    }

    @Test
    fun `cancel sends Cancel`() {
        val harness = compose.setTurnDiff(STAGED)

        compose.onNodeWithTag(RevertTags.CANCEL).performScrollTo().performClick()

        assertEquals(listOf<RevertIntent>(RevertIntent.Cancel), harness.intents)
    }

    @Test
    fun `while a step is on its way the confirmation's buttons are off and say what runs`() {
        val harness = compose.setTurnDiff(STAGED.copy(pending = RevertAction.Commit))
        compose.onNodeWithTag(RevertTags.CONFIRM).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(RevertTags.CANCEL).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(RevertTags.PENDING).performScrollTo().assertTextEquals("Reverting…")

        harness.state.value = STAGED.copy(pending = RevertAction.Cancel)
        compose.onNodeWithTag(RevertTags.PENDING).performScrollTo().assertTextEquals("Cancelling the revert…")
    }

    @Test
    fun `a revert staged for another turn can only be cancelled`() {
        val harness = compose.setTurnDiff(STAGED_ELSEWHERE)

        compose.onNodeWithTag(RevertTags.CONFIRM).assertDoesNotExist()
        compose.onNodeWithTag(RevertTags.REVERT).assertDoesNotExist()
        compose.onNodeWithTag(RevertTags.OTHER_TURN).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RevertTags.CANCEL).performScrollTo().performClick()

        assertEquals(listOf<RevertIntent>(RevertIntent.Cancel), harness.intents)
    }

    @Test
    fun `a failed step shows its reason and dismissing it sends DismissError`() {
        val harness = compose.setTurnDiff(STAGED.copy(error = RevertError(RevertAction.Commit, ChatFailure.Conflict)))

        compose.onNodeWithText("The turn could not be reverted. The session is busy or changed meanwhile.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss error").performScrollTo().performClick()

        assertEquals(listOf<RevertIntent>(RevertIntent.DismissError), harness.intents)
    }

    @Test
    fun `the buttons are big enough to tap`() {
        val harness = compose.setTurnDiff(LOADED)
        compose.onNodeWithTag(RevertTags.REVERT).performScrollTo().assertTouchSizeIsAtLeast(48.dp)
        compose.onNodeWithTag(RevertTags.file(0)).performScrollTo().assertTouchSizeIsAtLeast(48.dp)

        harness.state.value = STAGED
        compose.onNodeWithTag(RevertTags.CONFIRM).performScrollTo().assertTouchSizeIsAtLeast(48.dp)
        compose.onNodeWithTag(RevertTags.CANCEL).performScrollTo().assertTouchSizeIsAtLeast(48.dp)
    }
}
