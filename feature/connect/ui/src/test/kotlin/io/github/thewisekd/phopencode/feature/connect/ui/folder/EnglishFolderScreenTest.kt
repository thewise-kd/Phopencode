package io.github.thewisekd.phopencode.feature.connect.ui.folder

import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishFolderScreenTest : FolderScreenLanguageContract() {
    override val strings = ENGLISH_FOLDER_STRINGS
}

internal val ENGLISH_FOLDER_STRINGS = ExpectedFolderStrings(
    title = "Choose a project folder",
    up = "Up to the parent folder",
    select = "Select this folder",
    folders = "Folders",
    folder = "Folder",
    symlink = "Link to a folder",
    recent = "Recent folders",
    removeRecent = { "Remove $it from recent folders" },
    loading = "Loading folders…",
    emptyTitle = "No folders here",
    emptyMessage = "This folder has no subfolders. You can still select it.",
    retry = "Try again",
    dismiss = "Dismiss error",
    errors = mapOf(
        FolderError.NotConnected to "Not connected to the server. Go back and connect again.",
        FolderError.PermissionDenied to "Permission denied: this folder cannot be opened with your account.",
        FolderError.NotFound to "This folder no longer exists.",
        FolderError.Failed to "The folder could not be listed.",
        FolderError.StorageFailed to "Recent folders could not be read or saved on this phone.",
    ),
)
