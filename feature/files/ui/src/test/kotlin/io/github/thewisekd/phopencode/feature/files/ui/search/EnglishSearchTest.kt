package io.github.thewisekd.phopencode.feature.files.ui.search

import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentSearchTool
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val FAILED = "The search failed."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishSearchTest : SearchLanguageContract() {
    override val strings = ExpectedSearchStrings(
        title = "Search files",
        queryLabel = "Search for",
        run = "Search",
        names = "Names",
        content = "Content",
        hintNames = "Type part of a file or folder name, then search.",
        hintContent = "Type the text to find in the files, then search.",
        searching = "Searching…",
        emptyTitle = "No results",
        emptyMessage = "Nothing in this project matches. Try other words or the other mode.",
        truncated = "There are more results than shown. Make the search narrower.",
        tools = mapOf(
            ContentSearchTool.Ripgrep to "Searched with ripgrep.",
            ContentSearchTool.Grep to "Searched with grep, as ripgrep is not installed on the server.",
        ),
        line42 = "Line 42",
        folder = "Folder",
        file = "File",
        symlink = "Link",
        other = "Other",
        errors = mapOf(
            FilesFailure.NotConnected to "$FAILED There is no connection to the server.",
            FilesFailure.Unreachable to "$FAILED The server could not be reached.",
            FilesFailure.Unauthorized to "$FAILED The server refused the login.",
            FilesFailure.NotFound to "$FAILED The project folder was not found.",
            FilesFailure.PermissionDenied to "$FAILED The server's user may not read the folder.",
            FilesFailure.AlreadyExists to "$FAILED Something already has that name.",
            FilesFailure.NotEmpty to "$FAILED The folder is not empty.",
            FilesFailure.PathRejected to "$FAILED The path is outside the project or not valid.",
            FilesFailure.TooLarge to "$FAILED The result is too large.",
            FilesFailure.ToolMissing to "$FAILED Neither ripgrep nor grep is installed on the server.",
            FilesFailure.Rejected to "$FAILED The search text was refused; it must be a single line.",
            FilesFailure.ServerFailed to "$FAILED The server ran into an error.",
            FilesFailure.Unsupported to "$FAILED This is not supported yet.",
            FilesFailure.Unknown to "$FAILED An unexpected error occurred.",
        ),
        retry = "Try again",
    )
}
