package io.github.thewisekd.phopencode.feature.sessions.ui.manage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.ManageAction
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.ManageActionError
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The texts of the management screen in one language. An error reads as the sentence of what failed
 * ([loadFailed] or [actionFailed]) followed by the sentence of why ([reasons]), with one space.
 */
internal data class ExpectedManageStrings(
    val title: String,
    val loading: String,
    val titleLabel: String,
    val save: String,
    val folder: (String) -> String,
    val archivedNote: String,
    val archive: String,
    val unarchive: String,
    val delete: String,
    val confirmTitle: String,
    val irreversible: String,
    val childrenDeleted: String,
    val childrenMaybeDeleted: String,
    val confirmDelete: String,
    val cancel: String,
    val busy: Map<ManageAction, String>,
    val loadFailed: String,
    val actionFailed: Map<ManageAction, String>,
    val reasons: Map<SessionsFailure, String>,
)

/** What the management screen shows, the same in each language; a subclass sets the language and its texts. */
abstract class SessionManageLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedManageStrings

    private fun shows(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    private fun dialogShows(text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(isDialog())).assertIsDisplayed()

    @Test
    fun `a loaded session shows its naming field, folder and actions`() {
        compose.setManageScreen(loaded())

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        shows(strings.titleLabel)
        shows(CURRENT_TITLE)
        shows(strings.save)
        shows(strings.folder(DIRECTORY))
        shows(strings.archive)
        shows(strings.delete)
        compose.onNodeWithText(strings.archivedNote).assertDoesNotExist()
        compose.onNodeWithText(strings.unarchive).assertDoesNotExist()
    }

    @Test
    fun `an archived session says so and offers to restore it`() {
        compose.setManageScreen(loaded(archived = true))

        shows(strings.archivedNote)
        shows(strings.unarchive)
        compose.onNodeWithText(strings.archive).assertDoesNotExist()
    }

    @Test
    fun `the confirmation says deleting cannot be undone`() {
        compose.setManageScreen(loaded(hasChildSessions = false).copy(confirmingDelete = true))

        dialogShows(strings.confirmTitle)
        dialogShows(strings.irreversible)
        dialogShows(strings.confirmDelete)
        dialogShows(strings.cancel)
        compose.onNodeWithText(strings.childrenDeleted).assertDoesNotExist()
        compose.onNodeWithText(strings.childrenMaybeDeleted).assertDoesNotExist()
    }

    @Test
    fun `the confirmation warns that child sessions are deleted too`() {
        compose.setManageScreen(loaded(hasChildSessions = true).copy(confirmingDelete = true))

        dialogShows(strings.irreversible)
        dialogShows(strings.childrenDeleted)
    }

    @Test
    fun `the confirmation warns that child sessions may be deleted when that is not known`() {
        compose.setManageScreen(loaded(hasChildSessions = null).copy(confirmingDelete = true))

        dialogShows(strings.irreversible)
        dialogShows(strings.childrenMaybeDeleted)
    }

    @Test
    fun `loading says so`() {
        compose.setManageScreen(SessionManageState())

        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `every action under way says so`() {
        assertEquals(ManageAction.entries.toSet(), strings.busy.keys)
        assertEquals(strings.busy.size, strings.busy.values.toSet().size, "messages must differ")
        val harness = compose.setManageScreen(loaded())

        for ((action, message) in strings.busy) {
            harness.state.value = loaded().copy(busy = action)

            compose.onNodeWithContentDescription(message).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `deleting says so in the dialog too`() {
        compose.setManageScreen(loaded().copy(confirmingDelete = true, busy = ManageAction.Delete))

        compose.onNode(hasContentDescription(strings.busy.getValue(ManageAction.Delete)) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
    }

    @Test
    fun `every load failure has its own message`() {
        assertEquals(SessionsFailure.entries.toSet(), strings.reasons.keys)
        assertEquals(strings.reasons.size, strings.reasons.values.toSet().size, "messages must differ")
        val harness = compose.setManageScreen(SessionManageState(loading = false))

        for ((failure, reason) in strings.reasons) {
            harness.state.value = SessionManageState(loading = false, loadError = failure)

            shows("${strings.loadFailed} $reason")
        }
    }

    @Test
    fun `every failed action says which one and why`() {
        assertEquals(ManageAction.entries.toSet(), strings.actionFailed.keys)
        assertEquals(strings.actionFailed.size, strings.actionFailed.values.toSet().size, "messages must differ")
        val harness = compose.setManageScreen(loaded())

        for ((action, failed) in strings.actionFailed) {
            for ((failure, reason) in strings.reasons) {
                harness.state.value = loaded().copy(actionError = ManageActionError(action, failure))

                shows("$failed $reason")
            }
        }
    }

    @Test
    fun `the root keeps the placeholder tag`() {
        compose.setManageScreen(SessionManageState())

        compose.onNodeWithTag("placeholder:$TEST_ROUTE").assertExists()
    }
}
