package io.github.thewisekd.phopencode.feature.sessions.ui.manage

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.ManageAction
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.ManageActionError
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SAVE = "Save title"
private const val ARCHIVE = "Archive"
private const val UNARCHIVE = "Restore from archive"
private const val DELETE = "Delete session…"
private const val CONFIRM_TITLE = "Delete this session?"
private const val CONFIRM_DELETE = "Delete"
private const val CANCEL = "Cancel"
private const val RETRY = "Try again"
private const val DISMISS = "Dismiss error"
private const val BACK = "Go back"

/** What the management screen sends, in English: the texts of both languages are in `SessionManageLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SessionManageScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    /** Scrolled into view first: at API 35+ the insets push the content down. */
    private fun button(text: String): SemanticsNodeInteraction = compose.onNodeWithText(text).performScrollTo()

    private fun field(): SemanticsNodeInteraction = compose.onNodeWithTag(SESSION_MANAGE_TITLE_FIELD_TAG).performScrollTo()

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    // Naming

    @Test
    fun `the naming field shows the title as typed`() {
        compose.setManageScreen(loaded(title = "Yeni ad"))

        field().assertTextContains("Yeni ad")
    }

    @Test
    fun `typing sends TitleChanged and nothing else`() {
        val harness = compose.setManageScreen(loaded())

        field().performTextReplacement("Yeni ad")

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.TitleChanged("Yeni ad")), harness.intents)
    }

    @Test
    fun `save sends SaveTitle when the title changed`() {
        val harness = compose.setManageScreen(loaded(title = "Yeni ad"))

        button(SAVE).assertIsEnabled().performClick()

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.SaveTitle), harness.intents)
    }

    @Test
    fun `the keyboard's done key saves a changed title`() {
        val harness = compose.setManageScreen(loaded(title = "Yeni ad"))

        field().performImeAction()

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.SaveTitle), harness.intents)
    }

    @Test
    fun `an unchanged or blank title cannot be saved`() {
        val harness = compose.setManageScreen(loaded())
        button(SAVE).assertIsNotEnabled()
        field().performImeAction()

        harness.state.value = loaded(title = "   ")
        button(SAVE).assertIsNotEnabled()
        field().performImeAction()

        assertTrue(harness.intents.isEmpty())
    }

    // Archiving

    @Test
    fun `archive sends SetArchived true`() {
        val harness = compose.setManageScreen(loaded())

        button(ARCHIVE).performClick()

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.SetArchived(true)), harness.intents)
        compose.onNodeWithText(UNARCHIVE).assertDoesNotExist()
    }

    @Test
    fun `an archived session can be restored`() {
        val harness = compose.setManageScreen(loaded(archived = true))

        button(UNARCHIVE).performClick()

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.SetArchived(false)), harness.intents)
        compose.onNodeWithText(ARCHIVE).assertDoesNotExist()
    }

    // Deleting

    @Test
    fun `delete only asks for confirmation`() {
        val harness = compose.setManageScreen(loaded())

        button(DELETE).performClick()

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.RequestDelete), harness.intents)
        // The presenter opens the dialog, not the screen.
        compose.onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()
    }

    @Test
    fun `confirming sends ConfirmDelete`() {
        val harness = compose.setManageScreen(loaded().copy(confirmingDelete = true))

        inDialog(CONFIRM_DELETE).performClick()

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.ConfirmDelete), harness.intents)
    }

    @Test
    fun `cancelling sends CancelDelete`() {
        val harness = compose.setManageScreen(loaded().copy(confirmingDelete = true))

        inDialog(CANCEL).performClick()

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.CancelDelete), harness.intents)
    }

    @Test
    fun `the dialog follows the state`() {
        val harness = compose.setManageScreen(loaded().copy(confirmingDelete = true))
        compose.onNodeWithText(CONFIRM_TITLE).assertExists()

        harness.state.value = loaded()

        compose.onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()
    }

    // Busy

    @Test
    fun `while busy no action can be taken`() {
        for (action in ManageAction.entries) {
            val harness = compose.setManageScreenOnce(loaded(title = "Yeni ad").copy(busy = action))

            field().assertIsNotEnabled()
            button(SAVE).assertIsNotEnabled()
            button(ARCHIVE).assertIsNotEnabled()
            button(DELETE).assertIsNotEnabled()
            assertTrue(harness.intents.isEmpty(), "$action")
        }
    }

    @Test
    fun `while deleting the dialog's buttons are disabled`() {
        val harness = compose.setManageScreen(loaded().copy(confirmingDelete = true, busy = ManageAction.Delete))

        inDialog(CONFIRM_DELETE).assertIsNotEnabled()
        inDialog(CANCEL).assertIsNotEnabled()
        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `while loading no action is offered`() {
        compose.setManageScreen(SessionManageState())

        compose.onNodeWithTag(SESSION_MANAGE_TITLE_FIELD_TAG).assertDoesNotExist()
        compose.onNodeWithText(SAVE).assertDoesNotExist()
        compose.onNodeWithText(ARCHIVE).assertDoesNotExist()
        compose.onNodeWithText(DELETE).assertDoesNotExist()
    }

    // Errors and navigation

    @Test
    fun `a load error offers retry and no action`() {
        val harness = compose.setManageScreen(SessionManageState(loading = false, loadError = SessionsFailure.Unreachable))

        compose.onNodeWithText(DELETE).assertDoesNotExist()
        button(RETRY).performClick()

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.Retry), harness.intents)
    }

    @Test
    fun `a session that is gone offers no retry`() {
        compose.setManageScreen(SessionManageState(loading = false, loadError = SessionsFailure.NotFound))

        compose.onNodeWithText(RETRY).assertDoesNotExist()
        compose.onNodeWithText(DELETE).assertDoesNotExist()
    }

    @Test
    fun `dismissing an action error sends DismissError`() {
        val harness = compose.setManageScreen(
            loaded().copy(actionError = ManageActionError(ManageAction.Rename, SessionsFailure.Conflict)),
        )

        compose.onNodeWithContentDescription(DISMISS).performScrollTo().performClick()

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.DismissError), harness.intents)
    }

    @Test
    fun `back sends Close`() {
        val harness = compose.setManageScreen(loaded())

        compose.onNodeWithContentDescription(BACK).performClick()

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.Close), harness.intents)
    }

    @Test
    fun `every button is at least 48 dp`() {
        val harness = compose.setManageScreen(loaded(title = "Yeni ad"))
        button(SAVE).assertTouchSizeIsAtLeast(48.dp)
        button(ARCHIVE).assertTouchSizeIsAtLeast(48.dp)
        button(DELETE).assertTouchSizeIsAtLeast(48.dp)

        harness.state.value = loaded(archived = true).copy(confirmingDelete = true)
        inDialog(CONFIRM_DELETE).assertTouchSizeIsAtLeast(48.dp)
        inDialog(CANCEL).assertTouchSizeIsAtLeast(48.dp)
    }

    /** A screen so short that the actions start below the fold: they are reached by scrolling. */
    @Test
    fun `on a short screen the actions are reached by scrolling`() {
        val harness = compose.setManageScreen(loaded(), height = 200.dp)

        button(DELETE).performClick()

        assertEquals(listOf<SessionManageIntent>(SessionManageIntent.RequestDelete), harness.intents)
    }

    /** One composition per test rule: the loop above swaps the state instead of setting content again. */
    private var onceHarness: ManageScreenHarness? = null

    private fun ComposeContentTestRule.setManageScreenOnce(
        state: SessionManageState,
    ): ManageScreenHarness {
        val harness = onceHarness ?: setManageScreen(state).also { onceHarness = it }
        harness.state.value = state
        harness.intents.clear()
        return harness
    }
}
