package io.github.thewisekd.phopencode.feature.files.ui.editor

import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorBlock
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishEditorTest : EditorLanguageContract() {
    override val strings = ExpectedEditorStrings(
        loading = "Opening the file…",
        save = "Save",
        saving = "Saving…",
        unsaved = "Unsaved changes",
        blockedTitle = "This file cannot be edited",
        blocks = mapOf(
            EditorBlock.Binary to "It is not a text file.",
            EditorBlock.TooLarge to "It is too large to edit on the phone.",
            EditorBlock.Malformed to "Some of its bytes are not valid UTF-8, and saving would change them.",
        ),
        loadError = { "The file could not be opened. $it" },
        saveError = { "The file could not be saved. $it Your changes are kept." },
        reasons = mapOf(
            FilesFailure.NotConnected to "There is no connection to the server.",
            FilesFailure.Unreachable to "The server could not be reached.",
            FilesFailure.Unauthorized to "The server refused the login.",
            FilesFailure.NotFound to "The file was not found.",
            FilesFailure.PermissionDenied to "The server's user lacks permission for this file.",
            FilesFailure.AlreadyExists to "Something already has that name.",
            FilesFailure.NotEmpty to "The folder is not empty.",
            FilesFailure.PathRejected to "The path is outside the project or not valid.",
            FilesFailure.TooLarge to "The file is too large.",
            FilesFailure.ToolMissing to "A tool it needs is not installed on the server.",
            FilesFailure.Rejected to "The server refused the request.",
            FilesFailure.ServerFailed to "The server ran into an error.",
            FilesFailure.Unsupported to "This is not supported yet.",
            FilesFailure.Unknown to "An unexpected error occurred.",
        ),
        conflictTitle = "The file changed on the server",
        conflictMessage = "Someone changed this file since you opened or last saved it. Overwrite it with your " +
            "version, or reload the server's version and lose your changes.",
        overwrite = "Overwrite",
        reload = "Reload",
        keepEditing = "Keep editing",
        leaveTitle = "Discard unsaved changes?",
        leaveMessage = "Your changes to ana kod.kt are not saved. Leaving now discards them.",
        discard = "Discard",
    )
}
