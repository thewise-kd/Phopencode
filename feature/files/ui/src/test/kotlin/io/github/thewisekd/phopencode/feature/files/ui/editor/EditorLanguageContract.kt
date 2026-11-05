package io.github.thewisekd.phopencode.feature.files.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorBlock
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the editor in one language. */
internal data class ExpectedEditorStrings(
    val loading: String,
    val save: String,
    val saving: String,
    val unsaved: String,
    val blockedTitle: String,
    val blocks: Map<EditorBlock, String>,
    /** "Could not open" followed by a reason. */
    val loadError: (String) -> String,
    /** "Could not save" followed by a reason. */
    val saveError: (String) -> String,
    val reasons: Map<FilesFailure, String>,
    val conflictTitle: String,
    val conflictMessage: String,
    val overwrite: String,
    val reload: String,
    val keepEditing: String,
    val leaveTitle: String,
    /** The message for `ana kod.kt`. */
    val leaveMessage: String,
    val discard: String,
)

/** What the editor says, the same in each language; a subclass sets the language and its texts. */
abstract class EditorLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedEditorStrings

    @Test
    fun `loading says the file is opening`() {
        compose.setEditor(LOADING)

        compose.onNodeWithTag(EditorTags.LOADING).assertTextEquals(strings.loading)
    }

    @Test
    fun `save, saving and unsaved are worded`() {
        val harness = compose.setEditor(DIRTY)

        compose.onNodeWithTag(EditorTags.SAVE).assertTextEquals(strings.save)
        compose.onNodeWithTag(EditorTags.UNSAVED).assertTextEquals(strings.unsaved)
        harness.state.value = SAVING
        compose.onNodeWithTag(EditorTags.SAVING).assertTextEquals(strings.saving)
    }

    @Test
    fun `every block is explained`() {
        assertEquals(EditorBlock.entries.toSet(), strings.blocks.keys)
        val harness = compose.setEditor(CLEAN.copy(blocked = EditorBlock.Binary))
        strings.blocks.forEach { (block, message) ->
            harness.state.value = CLEAN.copy(blocked = block)
            compose.onNodeWithText(strings.blockedTitle).assertIsDisplayed()
            compose.onNodeWithText(message).assertIsDisplayed()
        }
    }

    @Test
    fun `every failure is worded, for loading and for saving`() {
        assertEquals(FilesFailure.entries.toSet(), strings.reasons.keys)
        val harness = compose.setEditor(CLEAN.copy(loadError = FilesFailure.Unknown))
        strings.reasons.forEach { (failure, reason) ->
            harness.state.value = CLEAN.copy(loadError = failure)
            compose.onNodeWithText(strings.loadError(reason)).assertIsDisplayed()
            harness.state.value = DIRTY.copy(saveError = failure)
            compose.onNodeWithText(strings.saveError(reason)).assertIsDisplayed()
        }
    }

    @Test
    fun `the conflict is explained with its choices`() {
        compose.setEditor(DIRTY.copy(conflict = true))

        compose.onNodeWithText(strings.conflictTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.conflictMessage).assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.CONFLICT_OVERWRITE).assertTextEquals(strings.overwrite)
        compose.onNodeWithTag(EditorTags.CONFLICT_RELOAD).assertTextEquals(strings.reload)
        compose.onNodeWithTag(EditorTags.CONFLICT_KEEP).assertTextEquals(strings.keepEditing)
    }

    @Test
    fun `the leave question names the file`() {
        compose.setEditor(DIRTY.copy(confirmingLeave = true))

        compose.onNodeWithText(strings.leaveTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.leaveMessage).assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.LEAVE_DISCARD).assertTextEquals(strings.discard)
        compose.onNodeWithTag(EditorTags.LEAVE_KEEP).assertTextEquals(strings.keepEditing)
    }
}
