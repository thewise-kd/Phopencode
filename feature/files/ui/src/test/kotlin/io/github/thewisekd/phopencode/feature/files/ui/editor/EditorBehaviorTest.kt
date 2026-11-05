package io.github.thewisekd.phopencode.feature.files.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorBlock
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the editor draws for each state of the contract and what it sends, in English: the texts of
 * both languages are in `EditorLanguageContract`. The code view is a sora editor inside an
 * `AndroidView`; it is reached by class, and edits are typed through its content as the keyboard
 * would. Highlighting, the keyboard and the cursor only the emulator shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EditorBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the root keeps the route's tag and the title is the file's name`() {
        compose.setEditor(CLEAN)

        compose.onNodeWithTag("placeholder:$ROUTE").assertIsDisplayed()
        compose.onNodeWithText("ana kod.kt").assertIsDisplayed()
    }

    @Test
    fun `loading shows a still line of text and nothing to edit or save`() {
        compose.setEditor(LOADING)

        compose.onNodeWithTag(EditorTags.LOADING).assertIsDisplayed()
        assertFalse(compose.hasTag(EditorTags.CODE))
        assertFalse(compose.hasTag(EditorTags.SAVE))
    }

    @Test
    fun `loaded text shows in an editable code view, and a clean file cannot be saved`() {
        val harness = compose.setEditor(CLEAN)

        compose.onNodeWithTag(EditorTags.CODE).assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.SAVE).assertIsNotEnabled()
        assertFalse(compose.hasTag(EditorTags.UNSAVED))
        assertFalse(compose.hasTag(EditorTags.SAVING))
        compose.runOnIdle {
            val editor = harness.codeEditor()
            assertEquals(CLEAN.text, editor.editorText())
            assertTrue(editor.editorEditable())
        }
    }

    @Test
    fun `an edit in the code view goes to the presenter as the whole new text`() {
        val harness = compose.setEditor(CLEAN)

        compose.runOnIdle { harness.codeEditor().typeAt(0, 0, "// ") }

        compose.runOnIdle {
            assertEquals(listOf<EditorIntent>(EditorIntent.TextChanged("// ${CLEAN.text}")), harness.intents)
        }
    }

    @Test
    fun `new text from the state replaces the editor's, as after a reload`() {
        val harness = compose.setEditor(DIRTY)

        harness.state.value = CLEAN

        compose.runOnIdle {
            assertEquals(CLEAN.text, harness.codeEditor().editorText())
            assertTrue(harness.intents.isEmpty())
        }
    }

    @Test
    fun `a dirty file says so and saves`() {
        val harness = compose.setEditor(DIRTY)

        compose.onNodeWithTag(EditorTags.UNSAVED).assertIsDisplayed()
        // The top bar does not scroll, so there is nothing to scroll to.
        compose.onNodeWithTag(EditorTags.SAVE).assertIsEnabled().performClick()

        assertEquals(listOf<EditorIntent>(EditorIntent.Save), harness.intents)
    }

    @Test
    fun `saving shows a still line of text and cannot save again`() {
        compose.setEditor(SAVING)

        compose.onNodeWithTag(EditorTags.SAVING).assertIsDisplayed()
        assertFalse(compose.hasTag(EditorTags.UNSAVED))
        compose.onNodeWithTag(EditorTags.SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(EditorTags.CODE).assertIsDisplayed()
    }

    @Test
    fun `the top bar's back sends Back`() {
        val harness = compose.setEditor(DIRTY)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<EditorIntent>(EditorIntent.Back), harness.intents)
    }

    @Test
    fun `system back sends Back, clean or dirty`() {
        val harness = compose.setEditor(CLEAN)

        compose.runOnIdle { harness.back!!.onBackPressed() }
        harness.state.value = DIRTY
        compose.runOnIdle { harness.back!!.onBackPressed() }

        assertEquals(listOf<EditorIntent>(EditorIntent.Back, EditorIntent.Back), harness.intents)
    }

    @Test
    fun `leaving with unsaved changes asks, and Discard leaves`() {
        val harness = compose.setEditor(DIRTY.copy(confirmingLeave = true))

        compose.onNodeWithTag(EditorTags.LEAVE).assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.LEAVE_DISCARD).performScrollTo().performClick()

        assertEquals(listOf<EditorIntent>(EditorIntent.ConfirmLeave), harness.intents)
    }

    @Test
    fun `Keep editing in the leave question stays`() {
        val harness = compose.setEditor(DIRTY.copy(confirmingLeave = true))

        compose.onNodeWithTag(EditorTags.LEAVE_KEEP).performScrollTo().performClick()

        assertEquals(listOf<EditorIntent>(EditorIntent.CancelLeave), harness.intents)
    }

    @Test
    fun `the question disappears with the state`() {
        val harness = compose.setEditor(DIRTY.copy(confirmingLeave = true))
        compose.onNodeWithTag(EditorTags.LEAVE).assertIsDisplayed()

        harness.state.value = DIRTY

        assertFalse(compose.hasTag(EditorTags.LEAVE))
    }

    @Test
    fun `a conflict offers overwrite`() {
        val harness = compose.setEditor(DIRTY.copy(conflict = true))

        compose.onNodeWithTag(EditorTags.CONFLICT).assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.CONFLICT_OVERWRITE).performScrollTo().performClick()

        assertEquals(listOf<EditorIntent>(EditorIntent.Overwrite), harness.intents)
    }

    @Test
    fun `a conflict offers reload`() {
        val harness = compose.setEditor(DIRTY.copy(conflict = true))

        compose.onNodeWithTag(EditorTags.CONFLICT_RELOAD).performScrollTo().performClick()

        assertEquals(listOf<EditorIntent>(EditorIntent.Reload), harness.intents)
    }

    @Test
    fun `a conflict can be put aside to keep editing`() {
        val harness = compose.setEditor(DIRTY.copy(conflict = true))

        compose.onNodeWithTag(EditorTags.CONFLICT_KEEP).performScrollTo().performClick()

        assertEquals(listOf<EditorIntent>(EditorIntent.DismissConflict), harness.intents)
    }

    @Test
    fun `the leave question goes over a conflict`() {
        compose.setEditor(DIRTY.copy(conflict = true, confirmingLeave = true))

        compose.onNodeWithTag(EditorTags.LEAVE).assertIsDisplayed()
        assertFalse(compose.hasTag(EditorTags.CONFLICT))
    }

    @Test
    fun `every block says why and offers nothing to save or retry`() {
        val harness = compose.setEditor(CLEAN.copy(text = "", blocked = EditorBlock.Binary))
        EditorBlock.entries.forEach { block ->
            harness.state.value = CLEAN.copy(text = "", blocked = block)

            compose.onNodeWithTag(EditorTags.BLOCKED).assertIsDisplayed()
            assertFalse(compose.hasTag(EditorTags.CODE))
            assertFalse(compose.hasTag(EditorTags.SAVE))
            assertFalse(compose.hasTag(EditorTags.LOAD_ERROR))
        }
        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `a failed load shows its reason and retries`() {
        val harness = compose.setEditor(CLEAN.copy(text = "", loadError = FilesFailure.Unreachable))

        compose.onNodeWithTag(EditorTags.LOAD_ERROR).assertIsDisplayed()
        assertFalse(compose.hasTag(EditorTags.CODE))
        assertFalse(compose.hasTag(EditorTags.SAVE))
        compose.onNodeWithText("Try again").performScrollTo().performClick()

        assertEquals(listOf<EditorIntent>(EditorIntent.Retry), harness.intents)
    }

    @Test
    fun `a failed save keeps the text, saves again on retry and can be dismissed`() {
        val harness = compose.setEditor(DIRTY.copy(saveError = FilesFailure.ServerFailed))

        compose.onNodeWithTag(EditorTags.SAVE_ERROR).assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.CODE).assertIsDisplayed()
        // The banner sits above the code view, which cannot be in a scrolling column.
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithContentDescription("Dismiss error").performClick()

        assertEquals(listOf(EditorIntent.Save, EditorIntent.DismissError), harness.intents)
    }

    @Test
    fun `a failed save offers no retry when nothing can be saved`() {
        compose.setEditor(SAVING.copy(saveError = FilesFailure.ServerFailed))

        compose.onNodeWithTag(EditorTags.SAVE_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }
}
