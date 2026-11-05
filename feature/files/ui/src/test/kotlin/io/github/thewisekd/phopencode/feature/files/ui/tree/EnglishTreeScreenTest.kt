package io.github.thewisekd.phopencode.feature.files.ui.tree

import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitBadge
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishTreeScreenTest : TreeScreenLanguageContract() {
    override val strings = ENGLISH_TREE_STRINGS
}

private const val EN_NOT_LISTED = "The folder could not be listed."

internal val ENGLISH_TREE_STRINGS = ExpectedTreeStrings(
    title = "Files",
    up = "Up to the parent folder",
    search = "Search files",
    transfers = "Transfers",
    more = "Folder options",
    actionsFor = { "Actions for $it" },
    folder = "Folder",
    file = "File",
    symlink = "Link",
    other = "Other",
    ignored = "Ignored",
    badges = mapOf(
        GitBadge.Added to "Added",
        GitBadge.Modified to "Modified",
        GitBadge.Deleted to "Deleted",
        GitBadge.Renamed to "Renamed",
        GitBadge.Untracked to "Untracked",
        GitBadge.ContainsChanges to "Contains changes",
    ),
    loading = "Loading files…",
    refreshing = "Refreshing…",
    emptyTitle = "This folder is empty",
    emptyHidden = "Hidden files are not shown.",
    showHiddenAction = "Show hidden",
    optionsTitle = "Folder options",
    newFolder = "New folder",
    refresh = "Refresh",
    showHidden = "Show hidden files",
    sortHeading = "Sort by",
    sortName = "Name",
    sortModified = "Last modified",
    sortSize = "Size",
    close = "Close",
    retry = "Try again",
    errors = mapOf(
        FilesFailure.NotConnected to "Not connected to the server. Go back and connect again.",
        FilesFailure.Unreachable to "The server could not be reached.",
        FilesFailure.Unauthorized to "The server refused the login. Go back and connect again.",
        FilesFailure.NotFound to "This folder no longer exists.",
        FilesFailure.PermissionDenied to "Permission denied: this folder cannot be opened with your account.",
        FilesFailure.PathRejected to "This folder is outside the project folder.",
        FilesFailure.ServerFailed to "The server failed to list this folder.",
        FilesFailure.Unsupported to "Browsing files is not available on this connection.",
        FilesFailure.Unknown to EN_NOT_LISTED,
        FilesFailure.AlreadyExists to EN_NOT_LISTED,
        FilesFailure.NotEmpty to EN_NOT_LISTED,
        FilesFailure.TooLarge to EN_NOT_LISTED,
        FilesFailure.ToolMissing to EN_NOT_LISTED,
        FilesFailure.Rejected to EN_NOT_LISTED,
    ),
)
