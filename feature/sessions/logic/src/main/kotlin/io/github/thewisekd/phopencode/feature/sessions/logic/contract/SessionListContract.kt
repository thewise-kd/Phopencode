package io.github.thewisekd.phopencode.feature.sessions.logic.contract

import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TokenUsage
import kotlin.time.Instant

/**
 * The root sessions of the active connection's server (P5.2, P5.3).
 *
 * Where it comes from: `SessionApi.list` with `SessionParentFilter.RootsOnly`, newest first, a page
 * at a time following `Page.nextCursor` until `Page.isEnd`; [query] as `SessionQuery.search`,
 * [directory] as `SessionQuery.directory`, and `archived = false` unless [showArchived]. The badge
 * comes from `SessionApi.activeStatuses`, the pending forms and permissions
 * (`InteractionApi.listForms`, `listPermissions`) and `Session.outcome`. Live updates come from the
 * client's `EventApi.events`: `SessionCreated`, `SessionDeleted`, `SessionRenamed`,
 * `SessionArchived`, `SessionUnarchived`, `UsageUpdated`, the `Execution*` events and the form and
 * permission events; each `ServerEvent.Connected` and each new connection reloads the first page.
 *
 * @property directory The folder the list is limited to, from the route; null lists every folder.
 * @property loading The first page is loading and nothing is shown yet.
 * @property refreshing A pull to refresh reloads the first page while [rows] stay shown.
 * @property rows Root sessions only, most recently updated first.
 * @property query The search text as typed. Prints masked.
 * @property loadingMore The next page is loading below [rows].
 * @property endReached No page follows the last one loaded.
 * @property error Loading or refreshing the first page failed; shown over [rows] when there are any.
 * @property loadMoreError Loading the next page failed; [rows] stay, and asking again retries it.
 */
data class SessionListState(
    val directory: String? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val rows: List<SessionRow> = emptyList(),
    val query: String = "",
    val showArchived: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: SessionsFailure? = null,
    val loadMoreError: SessionsFailure? = null,
) {
    /** Loaded, with nothing to show and nothing failed: the empty state. */
    val isEmpty: Boolean
        get() = !loading && rows.isEmpty() && error == null

    /** An empty list then means no match, not no sessions. */
    val isSearching: Boolean
        get() = query.isNotBlank()

    /**
     * The list is limited to one folder, which the files and git screens can open: [directory] is
     * set and not blank.
     */
    val hasFolder: Boolean
        get() = !directory.isNullOrBlank()

    override fun toString(): String =
        "SessionListState(directory=$directory, loading=$loading, refreshing=$refreshing, rows=$rows, " +
            "query=***, showArchived=$showArchived, loadingMore=$loadingMore, endReached=$endReached, " +
            "error=$error, loadMoreError=$loadMoreError)"
}

/**
 * One root session in the list.
 *
 * @property title Null when the server has not named it yet. A title is made from the prompt, so it
 *   prints masked.
 * @property usage The session's tokens and cost, from `Session.usage` and then `UsageUpdated`; null
 *   when the server reported none.
 */
data class SessionRow(
    val id: SessionId,
    val title: String?,
    val directory: String?,
    val status: SessionBadge,
    val usage: TokenUsage? = null,
    val updatedAt: Instant? = null,
    val archived: Boolean = false,
) {
    override fun toString(): String =
        "SessionRow(id=${id.value}, title=***, directory=$directory, status=$status, usage=$usage, " +
            "updatedAt=$updatedAt, archived=$archived)"
}

/**
 * The status badge of a row. When more than one holds, the first in this order wins: a session that
 * runs and waits for an answer shows [WaitingForInput].
 */
enum class SessionBadge {
    /** A form or a permission request of the session, or of one of its child sessions, waits. */
    WaitingForInput,

    /** In `SessionApi.activeStatuses`, or started and not ended since. */
    Running,

    /** The last execution failed (`Session.outcome` or `ExecutionFailed`). */
    Failed,

    /** The last execution was interrupted. */
    Interrupted,

    Idle,
}

sealed interface SessionListIntent {
    /** The search text as typed; the presenter waits for typing to pause before it asks. */
    data class QueryChanged(val query: String) : SessionListIntent {
        override fun toString(): String = "QueryChanged(***)"
    }

    data class ShowArchived(val show: Boolean) : SessionListIntent

    /** Pull to refresh: reloads the first page. */
    data object Refresh : SessionListIntent

    /** The end of [SessionListState.rows] came into view. Does nothing at the end or while loading. */
    data object LoadMore : SessionListIntent

    /** Tries the failed load again: the first page, or the next one after [SessionListState.loadMoreError]. */
    data object Retry : SessionListIntent

    data class Open(val id: SessionId) : SessionListIntent

    /** Delete, rename or archive. */
    data class Manage(val id: SessionId) : SessionListIntent

    data object Create : SessionListIntent

    /** Opens the files of [SessionListState.directory]; nothing when the list has no folder. */
    data object OpenFiles : SessionListIntent

    /** Opens git for [SessionListState.directory]; nothing when the list has no folder. */
    data object OpenGit : SessionListIntent

    data object DismissError : SessionListIntent
}

sealed interface SessionListEvent {
    data class OpenChat(val id: SessionId) : SessionListEvent

    data class OpenManage(val id: SessionId) : SessionListEvent

    /** @property directory The folder to preselect: the list's own, if it has one. */
    data class OpenCreate(val directory: String?) : SessionListEvent

    /** Opens `FilesRoute.Tree` for [directory], the list's folder. */
    data class OpenFiles(val directory: String) : SessionListEvent

    /** Opens `GitRoute.Status` for [directory], the list's folder. */
    data class OpenGit(val directory: String) : SessionListEvent
}

interface SessionListPresenter : Presenter<SessionListState, SessionListIntent, SessionListEvent>
