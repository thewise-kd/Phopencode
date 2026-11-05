package io.github.thewisekd.phopencode.feature.files.ui.ops

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileAction
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOp
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpError
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsDialog
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.NameProblem
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the file operations in one language. */
internal data class ExpectedFileOpsStrings(
    val actions: Map<FileAction, String>,
    val cancel: String,
    val renameTitle: String,
    val renameLabel: String,
    val renameSubmit: String,
    val newFolderTitle: String,
    val newFolderLabel: String,
    val newFolderSubmit: String,
    val problems: Map<NameProblem, String>,
    val deleteFileTitle: String,
    /** The message for `ana kod.kt`. */
    val deleteFileMessage: String,
    val deleteFolderTitle: String,
    /** The message for `çizimler`. */
    val deleteFolderMessage: String,
    val delete: String,
    val busy: Map<FileOp, String>,
    /** "Could not …" for each operation, followed by a reason. */
    val errors: Map<FileOp, (String) -> String>,
    val reasons: Map<FilesFailure, String>,
    val ok: String,
)

/** What the file operations say, the same in each language; a subclass sets the language and its texts. */
abstract class FileOpsLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedFileOpsStrings

    @Test
    fun `every action and cancel in the menu are worded`() {
        assertEquals(FileAction.entries.toSet(), strings.actions.keys)
        compose.setFileOps(fileMenu)

        strings.actions.forEach { (action, label) -> compose.onNodeWithTag(action.tag).assertTextEquals(label) }
        compose.onNodeWithTag(FileOpsTags.MENU_CANCEL).assertTextEquals(strings.cancel)
    }

    @Test
    fun `the rename dialog is worded`() {
        compose.setFileOps(renaming)

        compose.onNodeWithTag(FileOpsTags.TITLE).assertTextEquals(strings.renameTitle)
        compose.onNodeWithText(strings.renameLabel, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(FileOpsTags.SUBMIT).assertTextEquals(strings.renameSubmit)
        compose.onNodeWithTag(FileOpsTags.CANCEL).assertTextEquals(strings.cancel)
    }

    @Test
    fun `the new folder dialog is worded`() {
        compose.setFileOps(newFolder)

        compose.onNodeWithTag(FileOpsTags.TITLE).assertTextEquals(strings.newFolderTitle)
        compose.onNodeWithText(strings.newFolderLabel, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(FileOpsTags.SUBMIT).assertTextEquals(strings.newFolderSubmit)
    }

    @Test
    fun `every name problem is explained`() {
        assertEquals(NameProblem.entries.toSet(), strings.problems.keys)
        val harness = compose.setFileOps(newFolder)
        strings.problems.forEach { (problem, message) ->
            harness.state.value = FileOpsState(dialog = FileOpsDialog.NewFolder(OPS_FOLDER, "x", problem))
            compose.onNodeWithTag(FileOpsTags.NAME_PROBLEM, useUnmergedTree = true).assertTextEquals(message)
        }
    }

    @Test
    fun `deleting a file asks with its name`() {
        compose.setFileOps(deletingFile)

        compose.onNodeWithTag(FileOpsTags.TITLE).assertTextEquals(strings.deleteFileTitle)
        compose.onNodeWithTag(FileOpsTags.DELETE_MESSAGE).assertTextEquals(strings.deleteFileMessage)
        compose.onNodeWithTag(FileOpsTags.DELETE_CONFIRM).assertTextEquals(strings.delete)
        compose.onNodeWithTag(FileOpsTags.CANCEL).assertTextEquals(strings.cancel)
    }

    @Test
    fun `deleting a folder says its content goes too`() {
        compose.setFileOps(deletingFolder)

        compose.onNodeWithTag(FileOpsTags.TITLE).assertTextEquals(strings.deleteFolderTitle)
        compose.onNodeWithTag(FileOpsTags.DELETE_MESSAGE).assertTextEquals(strings.deleteFolderMessage)
    }

    @Test
    fun `every running operation is worded`() {
        assertEquals(FileOp.entries.toSet(), strings.busy.keys)
        val harness = compose.setFileOps(renaming)
        strings.busy.forEach { (op, text) ->
            harness.state.value = when (op) {
                FileOp.Rename -> renaming
                FileOp.Delete -> deletingFile
                FileOp.CreateFolder -> newFolder
            }.copy(busy = op)
            compose.onNodeWithTag(FileOpsTags.BUSY).assertTextEquals(text)
        }
    }

    @Test
    fun `every failure of every operation is worded`() {
        assertEquals(FileOp.entries.toSet(), strings.errors.keys)
        assertEquals(FilesFailure.entries.toSet(), strings.reasons.keys)
        val harness = compose.setFileOps(FileOpsState())
        strings.errors.forEach { (op, error) ->
            strings.reasons.forEach { (failure, reason) ->
                harness.state.value = FileOpsState(error = FileOpError(op, failure))
                compose.onNodeWithText(error(reason)).assertIsDisplayed()
            }
        }
        compose.onNodeWithTag(FileOpsTags.ERROR_OK).assertTextEquals(strings.ok)
    }
}
