package io.github.thewisekd.phopencode.feature.sessions.ui.manage

import io.github.thewisekd.phopencode.feature.sessions.logic.contract.ManageAction
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishSessionManageScreenTest : SessionManageLanguageContract() {
    override val strings = ExpectedManageStrings(
        title = "Manage session",
        loading = "Loading the session…",
        titleLabel = "Session title",
        save = "Save title",
        folder = { "Folder: $it" },
        archivedNote = "This session is archived.",
        archive = "Archive",
        unarchive = "Restore from archive",
        delete = "Delete session…",
        confirmTitle = "Delete this session?",
        irreversible = "Deleting cannot be undone.",
        childrenDeleted = "Its child sessions will be deleted too.",
        childrenMaybeDeleted = "Any child sessions it has may be deleted too.",
        confirmDelete = "Delete",
        cancel = "Cancel",
        busy = mapOf(
            ManageAction.Rename to "Renaming the session…",
            ManageAction.Archive to "Archiving the session…",
            ManageAction.Unarchive to "Restoring the session from the archive…",
            ManageAction.Delete to "Deleting the session…",
        ),
        loadFailed = "The session could not be loaded.",
        actionFailed = mapOf(
            ManageAction.Rename to "The session could not be renamed.",
            ManageAction.Archive to "The session could not be archived.",
            ManageAction.Unarchive to "The session could not be restored from the archive.",
            ManageAction.Delete to "The session could not be deleted.",
        ),
        reasons = mapOf(
            SessionsFailure.NotConnected to "There is no connection to the server. Go back and connect again.",
            SessionsFailure.Unreachable to "The server could not be reached. Check the connection and try again.",
            SessionsFailure.Unauthorized to "The server refused the credentials.",
            SessionsFailure.NotFound to "The session no longer exists; it may have been deleted on another device.",
            SessionsFailure.Conflict to "The session is busy or was changed meanwhile.",
            SessionsFailure.Rejected to "The server refused the request.",
            SessionsFailure.ServerFailed to "The server ran into an error.",
            SessionsFailure.Unknown to "An unexpected error occurred.",
        ),
    )
}
