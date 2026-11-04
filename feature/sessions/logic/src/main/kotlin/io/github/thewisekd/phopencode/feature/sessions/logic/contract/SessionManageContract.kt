package io.github.thewisekd.phopencode.feature.sessions.logic.contract

/**
 * Delete, rename and archive one session on the active connection's server (P5.6, P5.7).
 *
 * Where it comes from: the session from `SessionApi.get`; whether it has child sessions from
 * `SessionApi.list` with `SessionParentFilter.ChildrenOf` (one item is enough). Renaming and
 * archiving send `SessionApi.update` with `SessionUpdate.title` or `SessionUpdate.archived`;
 * deleting sends `SessionApi.delete`, which deletes the child sessions too and cannot be undone.
 *
 * @property currentTitle The title the server has; null when it has none. Prints masked.
 * @property title The naming field as typed; starts as [currentTitle]. Prints masked.
 * @property hasChildSessions Whether deleting deletes child sessions too; null when not known, in
 *   which case the warning says they may be deleted.
 * @property confirmingDelete The confirmation, warning that deleting cannot be undone, is shown.
 * @property busy The action under way; the others wait.
 * @property loadError The session could not be loaded; [SessionsFailure.NotFound] once it is gone.
 * @property actionError The last action failed.
 */
data class SessionManageState(
    val loading: Boolean = true,
    val currentTitle: String? = null,
    val title: String = "",
    val archived: Boolean = false,
    val directory: String? = null,
    val hasChildSessions: Boolean? = null,
    val confirmingDelete: Boolean = false,
    val busy: ManageAction? = null,
    val loadError: SessionsFailure? = null,
    val actionError: ManageActionError? = null,
) {
    /** Whether [SessionManageIntent.SaveTitle] would change anything: not blank and not the same. */
    val canSaveTitle: Boolean
        get() = !loading && busy == null && title.isNotBlank() && title.trim() != currentTitle

    override fun toString(): String =
        "SessionManageState(loading=$loading, currentTitle=***, title=***, archived=$archived, " +
            "directory=$directory, hasChildSessions=$hasChildSessions, confirmingDelete=$confirmingDelete, " +
            "busy=$busy, loadError=$loadError, actionError=$actionError)"
}

enum class ManageAction {
    Rename,
    Archive,
    Unarchive,
    Delete,
}

data class ManageActionError(val action: ManageAction, val reason: SessionsFailure)

sealed interface SessionManageIntent {
    data class TitleChanged(val title: String) : SessionManageIntent {
        override fun toString(): String = "TitleChanged(***)"
    }

    /** Renames to [SessionManageState.title], trimmed. */
    data object SaveTitle : SessionManageIntent

    /** Archives, or with false un-archives. */
    data class SetArchived(val archived: Boolean) : SessionManageIntent

    /** Asks for confirmation; nothing is deleted yet. */
    data object RequestDelete : SessionManageIntent

    /** Deletes the session and its child sessions, irreversibly. */
    data object ConfirmDelete : SessionManageIntent

    data object CancelDelete : SessionManageIntent

    /** Loads the session again after [SessionManageState.loadError]. */
    data object Retry : SessionManageIntent

    data object DismissError : SessionManageIntent

    data object Close : SessionManageIntent
}

sealed interface SessionManageEvent {
    /**
     * The session is gone: every screen of it, its chat included, is left behind for a session list.
     *
     * @property directory The deleted session's folder ([SessionManageState.directory]); a list opened
     *   for it shows that folder, null shows every folder.
     */
    data class Deleted(val directory: String?) : SessionManageEvent

    data object Close : SessionManageEvent
}

interface SessionManagePresenter : Presenter<SessionManageState, SessionManageIntent, SessionManageEvent>
