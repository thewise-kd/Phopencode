package io.github.thewisekd.phopencode.feature.files.ui.transfer

import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishTransferTest : TransferLanguageContract() {
    override val strings = ExpectedTransferStrings(
        title = "Transfers",
        upload = "Upload",
        clearFinished = "Clear finished",
        folder = "Uploads go to belgeler",
        emptyTitle = "No transfers",
        emptyMessage = "Upload files from this phone into the folder, or download a file from its menu in the file list.",
        queued = "Waiting",
        uploading = "Uploading",
        uploadingPercent = "Uploading, 45%",
        downloading = "Downloading",
        awaiting = "Waiting for your answer",
        uploaded = "Uploaded",
        downloaded = "Downloaded",
        cancelled = "Cancelled",
        failed = { "Failed. $it" },
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
        cancel = "Cancel",
        overwriteTitle = "Replace notlar.md?",
        overwriteMessage = "A file named notlar.md is already in this folder. Replacing it cannot be undone.",
        replace = "Replace",
        skip = "Skip",
        pickerMissing = "No app on this phone can pick files.",
        channel = "File transfers",
        notificationUploading = "Uploading ana kod.kt",
        notificationDownloading = "Downloading veri.bin",
        notificationWaiting = "Waiting to transfer rapor.pdf",
        notificationAwaitingTitle = "notlar.md already exists",
        notificationAwaitingText = "Open the app to replace or skip it.",
        notificationOneMore = "1 more file waiting",
        notificationThreeMore = "3 more files waiting",
        notificationCancel = "Cancel all",
    )
}
