package io.github.thewisekd.phopencode.feature.files.ui.ops

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileAction
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOp
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpError
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsDialog
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.NameProblem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the file operations draw for each state of the contract and what they send, in English: the
 * texts of both languages are in `FileOpsLanguageContract`. The presenter is not used: the state is
 * set by the test, and the intents are recorded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class FileOpsBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val allSurfaces = listOf(
        FileOpsTags.MENU,
        FileOpsTags.NAME_DIALOG,
        FileOpsTags.DELETE_DIALOG,
        FileOpsTags.ERROR_DIALOG,
    )

    @Test
    fun `nothing open draws nothing`() {
        compose.setFileOps(FileOpsState())

        allSurfaces.forEach { assertFalse(compose.hasTag(it), it) }
    }

    @Test
    fun `a running operation with nothing open draws nothing`() {
        compose.setFileOps(FileOpsState(busy = FileOp.Delete))

        allSurfaces.forEach { assertFalse(compose.hasTag(it), it) }
    }

    @Test
    fun `a file's menu names it and offers its actions in order`() {
        compose.setFileOps(fileMenu)

        compose.onNodeWithTag(FileOpsTags.MENU).assertIsDisplayed()
        compose.onNodeWithTag(FileOpsTags.MENU_TITLE).assertTextContains(opsFile.name)
        FileAction.entries.forEach { compose.onNodeWithTag(it.tag).assertExists() }
        val tops = FileAction.entries.map {
            compose.onNodeWithTag(it.tag).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun `a folder's menu offers only what the state lists`() {
        compose.setFileOps(folderMenu)

        compose.onNodeWithTag(FileOpsTags.MENU_TITLE).assertTextContains(opsFolder.name)
        assertTrue(compose.hasTag(FileAction.Rename.tag))
        assertTrue(compose.hasTag(FileAction.Delete.tag))
        assertFalse(compose.hasTag(FileAction.Edit.tag))
        assertFalse(compose.hasTag(FileAction.Download.tag))
    }

    @Test
    fun `each menu item chooses its action`() {
        val harness = compose.setFileOps(fileMenu)

        FileAction.entries.forEach { compose.onNodeWithTag(it.tag).performScrollTo().performClick() }

        compose.runOnIdle {
            assertEquals<List<FileOpsIntent>>(FileAction.entries.map { FileOpsIntent.Choose(it) }, harness.intents)
        }
    }

    @Test
    fun `cancel closes the menu`() {
        val harness = compose.setFileOps(fileMenu)

        compose.onNodeWithTag(FileOpsTags.MENU_CANCEL).performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf<FileOpsIntent>(FileOpsIntent.Cancel), harness.intents) }
    }

    @Test
    fun `rename shows the typed name and sends every change`() {
        val harness = compose.setFileOps(renaming)

        compose.onNodeWithTag(FileOpsTags.NAME_DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(FileOpsTags.NAME_FIELD).assertTextContains(opsFile.name)
        compose.onNodeWithTag(FileOpsTags.NAME_FIELD).performTextReplacement("yeni ad.kt")

        compose.runOnIdle {
            assertEquals(listOf<FileOpsIntent>(FileOpsIntent.NameChanged("yeni ad.kt")), harness.intents)
        }
    }

    @Test
    fun `submit follows canSubmit and sends Submit`() {
        val harness = compose.setFileOps(renaming.copy(dialog = FileOpsDialog.Rename(opsFile, name = " ")))

        compose.onNodeWithTag(FileOpsTags.SUBMIT).assertIsNotEnabled()
        harness.state.value = renaming
        compose.onNodeWithTag(FileOpsTags.SUBMIT).performScrollTo().assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf<FileOpsIntent>(FileOpsIntent.Submit), harness.intents) }
    }

    @Test
    fun `a name problem shows under the field and blocks submit`() {
        compose.setFileOps(FileOpsState(dialog = FileOpsDialog.Rename(opsFile, "a/b", NameProblem.Separator)))

        compose.onNodeWithTag(FileOpsTags.NAME_PROBLEM, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(FileOpsTags.SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun `no problem, no problem line`() {
        compose.setFileOps(renaming)

        assertFalse(compose.hasTag(FileOpsTags.NAME_PROBLEM))
    }

    @Test
    fun `the new folder dialog starts empty and sends what is typed`() {
        val harness = compose.setFileOps(newFolder)

        compose.onNodeWithTag(FileOpsTags.NAME_DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(FileOpsTags.SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(FileOpsTags.NAME_FIELD).performTextReplacement("yeni klasör")
        harness.state.value = FileOpsState(dialog = FileOpsDialog.NewFolder(OPS_FOLDER, "yeni klasör"))
        compose.onNodeWithTag(FileOpsTags.SUBMIT).performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(FileOpsIntent.NameChanged("yeni klasör"), FileOpsIntent.Submit),
                harness.intents,
            )
        }
    }

    @Test
    fun `cancel closes a name dialog`() {
        val harness = compose.setFileOps(newFolder)

        compose.onNodeWithTag(FileOpsTags.CANCEL).performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf<FileOpsIntent>(FileOpsIntent.Cancel), harness.intents) }
    }

    @Test
    fun `a running rename takes no input and says so`() {
        compose.setFileOps(renaming.copy(busy = FileOp.Rename))

        compose.onNodeWithTag(FileOpsTags.BUSY).assertIsDisplayed()
        compose.onNodeWithTag(FileOpsTags.NAME_FIELD).assertIsNotEnabled()
        compose.onNodeWithTag(FileOpsTags.SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun `deleting a file asks with its name and sends ConfirmDelete`() {
        val harness = compose.setFileOps(deletingFile)

        compose.onNodeWithTag(FileOpsTags.DELETE_DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(FileOpsTags.DELETE_MESSAGE).assertTextContains(opsFile.name, substring = true)
        compose.onNodeWithTag(FileOpsTags.DELETE_CONFIRM).performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf<FileOpsIntent>(FileOpsIntent.ConfirmDelete), harness.intents) }
    }

    @Test
    fun `deleting a folder says its content goes too`() {
        compose.setFileOps(deletingFolder)

        compose.onNodeWithTag(FileOpsTags.DELETE_MESSAGE)
            .assertTextContains(opsFolder.name, substring = true)
            .assertTextContains("everything in it", substring = true)
    }

    @Test
    fun `cancel keeps the entry`() {
        val harness = compose.setFileOps(deletingFolder)

        compose.onNodeWithTag(FileOpsTags.CANCEL).performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf<FileOpsIntent>(FileOpsIntent.Cancel), harness.intents) }
    }

    @Test
    fun `a running delete cannot be confirmed again`() {
        compose.setFileOps(deletingFolder.copy(busy = FileOp.Delete))

        compose.onNodeWithTag(FileOpsTags.BUSY).assertIsDisplayed()
        compose.onNodeWithTag(FileOpsTags.DELETE_CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `a failure shows in the open dialog and can be dismissed`() {
        val harness = compose.setFileOps(
            deletingFolder.copy(error = FileOpError(FileOp.Delete, FilesFailure.PermissionDenied)),
        )

        compose.onNodeWithTag(FileOpsTags.ERROR).assertIsDisplayed()
        assertFalse(compose.hasTag(FileOpsTags.ERROR_DIALOG))
        compose.onNodeWithContentDescription("Dismiss error").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf<FileOpsIntent>(FileOpsIntent.DismissError), harness.intents) }
    }

    @Test
    fun `a failure with nothing open shows on its own and can be dismissed`() {
        val harness = compose.setFileOps(FileOpsState(error = FileOpError(FileOp.CreateFolder, FilesFailure.Unreachable)))

        compose.onNodeWithTag(FileOpsTags.ERROR_DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(FileOpsTags.ERROR_OK).performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf<FileOpsIntent>(FileOpsIntent.DismissError), harness.intents) }
    }

    @Test
    fun `a name problem, not the failure, shows for a taken name`() {
        compose.setFileOps(FileOpsState(dialog = FileOpsDialog.NewFolder(OPS_FOLDER, "src", NameProblem.Taken)))

        compose.onNodeWithText("Something in this folder already has that name.", useUnmergedTree = true)
            .assertIsDisplayed()
        assertFalse(compose.hasTag(FileOpsTags.ERROR))
    }
}
