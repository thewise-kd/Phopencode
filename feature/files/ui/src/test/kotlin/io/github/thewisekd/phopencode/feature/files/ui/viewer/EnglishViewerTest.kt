package io.github.thewisekd.phopencode.feature.files.ui.viewer

import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val COULD_NOT = "The file could not be opened."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishViewerTest : ViewerLanguageContract() {
    override val strings = ExpectedViewerStrings(
        loading = "Opening the file…",
        edit = "Edit",
        rendered = "Rendered",
        raw = "Raw",
        malformed = "Some bytes are not valid UTF-8 and show as �. This file cannot be edited.",
        binaryTitle = "Not a text file",
        binaryMessage = "This file (2 KB) is neither text nor an image the app can show.",
        tooLargeTitle = "File too large",
        tooLargeWarning = "This file is 3 MB, above the 1 MB viewing limit. Opening it may be slow.",
        tooLargeBlocked = "This file is 100 MB, above the 10 MB limit. It cannot be opened on the phone.",
        openAnyway = "Open anyway",
        errors = mapOf(
            FilesFailure.NotConnected to "$COULD_NOT There is no connection to the server.",
            FilesFailure.Unreachable to "$COULD_NOT The server could not be reached.",
            FilesFailure.Unauthorized to "$COULD_NOT The server refused the login.",
            FilesFailure.NotFound to "$COULD_NOT The file was not found.",
            FilesFailure.PermissionDenied to "$COULD_NOT The server's user may not read it.",
            FilesFailure.AlreadyExists to "$COULD_NOT Something already has that name.",
            FilesFailure.NotEmpty to "$COULD_NOT The folder is not empty.",
            FilesFailure.PathRejected to "$COULD_NOT The path is outside the project or not valid.",
            FilesFailure.TooLarge to "$COULD_NOT The file is too large.",
            FilesFailure.ToolMissing to "$COULD_NOT A tool it needs is not installed on the server.",
            FilesFailure.Rejected to "$COULD_NOT The server refused the request.",
            FilesFailure.ServerFailed to "$COULD_NOT The server ran into an error.",
            FilesFailure.Unsupported to "$COULD_NOT This is not supported yet.",
            FilesFailure.Unknown to "$COULD_NOT An unexpected error occurred.",
        ),
        retry = "Try again",
        imageDescription = "Preview of logo.png",
        sizes = mapOf(
            512L to "512 B",
            1_536L to "1.5 KB",
            5_242_880L to "5 MB",
            1_288_490_189L to "1.2 GB",
        ),
    )
}
