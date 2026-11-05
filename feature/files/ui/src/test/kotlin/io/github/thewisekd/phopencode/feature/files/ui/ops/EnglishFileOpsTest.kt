package io.github.thewisekd.phopencode.feature.files.ui.ops

import io.github.thewisekd.phopencode.feature.files.logic.contract.FileAction
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOp
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.NameProblem
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TAKEN = "Something in this folder already has that name."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishFileOpsTest : FileOpsLanguageContract() {
    override val strings = ExpectedFileOpsStrings(
        actions = mapOf(
            FileAction.Edit to "Edit",
            FileAction.Download to "Download",
            FileAction.Rename to "Rename",
            FileAction.Delete to "Delete",
        ),
        cancel = "Cancel",
        renameTitle = "Rename",
        renameLabel = "New name",
        renameSubmit = "Rename",
        newFolderTitle = "New folder",
        newFolderLabel = "Folder name",
        newFolderSubmit = "Create",
        problems = mapOf(
            NameProblem.Blank to "Enter a name.",
            NameProblem.Separator to "A name cannot contain /.",
            NameProblem.Reserved to "\".\" and \"..\" cannot be used as names.",
            NameProblem.TooLong to "The name is too long.",
            NameProblem.ControlCharacter to "The name contains a character that is not allowed.",
            NameProblem.Taken to TAKEN,
        ),
        deleteFileTitle = "Delete file?",
        deleteFileMessage = "ana kod.kt will be deleted from the server. This cannot be undone.",
        deleteFolderTitle = "Delete folder?",
        deleteFolderMessage = "The folder çizimler and everything in it will be deleted from the server. " +
            "This cannot be undone.",
        delete = "Delete",
        busy = mapOf(
            FileOp.Rename to "Renaming…",
            FileOp.Delete to "Deleting…",
            FileOp.CreateFolder to "Creating the folder…",
        ),
        errors = mapOf(
            FileOp.Rename to { "The item could not be renamed. $it" },
            FileOp.Delete to { "The item could not be deleted. $it" },
            FileOp.CreateFolder to { "The folder could not be created. $it" },
        ),
        reasons = mapOf(
            FilesFailure.NotConnected to "There is no connection to the server.",
            FilesFailure.Unreachable to "The server could not be reached.",
            FilesFailure.Unauthorized to "The server refused the login.",
            FilesFailure.NotFound to "It is no longer there; it may have been moved or deleted.",
            FilesFailure.PermissionDenied to "The server's user lacks permission for this.",
            FilesFailure.AlreadyExists to TAKEN,
            FilesFailure.NotEmpty to "The folder is not empty.",
            FilesFailure.PathRejected to "The path is outside the project or not valid.",
            FilesFailure.TooLarge to "It is too large.",
            FilesFailure.ToolMissing to "A tool it needs is not installed on the server.",
            FilesFailure.Rejected to "The server refused the request.",
            FilesFailure.ServerFailed to "The server ran into an error.",
            FilesFailure.Unsupported to "This is not supported yet.",
            FilesFailure.Unknown to "An unexpected error occurred.",
        ),
        ok = "OK",
    )
}
