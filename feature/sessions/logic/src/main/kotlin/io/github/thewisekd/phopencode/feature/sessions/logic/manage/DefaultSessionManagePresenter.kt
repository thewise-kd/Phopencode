package io.github.thewisekd.phopencode.feature.sessions.logic.manage

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.SessionParentFilter
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.api.SessionUpdate
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionServer
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.ManageAction
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.ManageActionError
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManagePresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Deletes, renames and archives one session (P5.6).
 *
 * Loading: `SessionApi.get` for the session, then `SessionApi.list` with
 * `SessionParentFilter.ChildrenOf` and a limit of one, archived children included, for
 * [SessionManageState.hasChildSessions]. A failed children call leaves it null (unknown, so the
 * warning says children may be deleted) and does not fail the load. The children call takes the
 * client of the connection standing when it starts, not the one the session came from, and is
 * asked once more if that client is closed under it.
 *
 * Which server: the server is the one of the first `ConnectionState.Connected` this presenter
 * loads from, named by its [ConnectionServer] (profile and endpoint). Every later call, loads and
 * actions alike, goes to the client of the connection standing at that moment only if it is
 * `Connected` with that very server; a reconnect to the same server is used, a connection to any
 * other profile or endpoint is never asked anything, and the call fails with
 * [SessionsFailure.NotConnected] instead.
 *
 * Actions: one at a time ([SessionManageState.busy]), and only once the session has loaded and is
 * not known to be gone. Deleting is sent only after [SessionManageIntent.RequestDelete] and then
 * [SessionManageIntent.ConfirmDelete], once: after it succeeded, nothing more is sent, and
 * [SessionManageEvent.Deleted] carries the session's folder. An action that finds the session gone
 * also sets [SessionManageState.loadError] to [SessionsFailure.NotFound].
 *
 * Nothing runs in the constructor: the screen's scope may run a launch at once, so loading starts
 * after a `yield()`.
 *
 * @param sessionId The session to manage, from the route.
 * @param connection The server is the one of `ConnectionState.Connected`.
 */
class DefaultSessionManagePresenter(
    private val sessionId: SessionId,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
) : SessionManagePresenter {
    private val mutableState = MutableStateFlow(SessionManageState())

    override val state: StateFlow<SessionManageState> = mutableState.asStateFlow()

    // Unlimited, so that onIntent can send without suspending and no event is dropped.
    private val eventChannel = Channel<SessionManageEvent>(Channel.UNLIMITED)

    override val events: Flow<SessionManageEvent> = eventChannel.receiveAsFlow()

    private val lock = Any()

    /** Guarded by [lock]: the server the session was first loaded from; null before any load. */
    private var server: ConnectionServer? = null

    /** The session was deleted here; nothing more is sent. */
    @Volatile
    private var deleted = false

    init {
        // The screen's scope may be Dispatchers.Main.immediate, which runs a launch at once; the
        // yield hands it back to the dispatcher, so nothing runs inside the constructor.
        scope.launch {
            yield()
            load()
        }
    }

    override fun onIntent(intent: SessionManageIntent) {
        when (intent) {
            is SessionManageIntent.TitleChanged -> mutableState.update { it.copy(title = intent.title) }
            SessionManageIntent.SaveTitle -> saveTitle()
            is SessionManageIntent.SetArchived -> setArchived(intent.archived)
            SessionManageIntent.RequestDelete -> requestDelete()
            SessionManageIntent.ConfirmDelete -> confirmDelete()
            SessionManageIntent.CancelDelete -> mutableState.update { it.copy(confirmingDelete = false) }
            SessionManageIntent.Retry -> retry()
            SessionManageIntent.DismissError -> mutableState.update { it.copy(actionError = null) }
            SessionManageIntent.Close -> eventChannel.trySend(SessionManageEvent.Close)
        }
    }

    private fun retry() {
        val start = claim { current ->
            if (deleted || current.loading || current.loadError == null || current.busy != null) {
                null
            } else {
                current.copy(loading = true)
            }
        }
        if (start) scope.launch { load() }
    }

    /**
     * Applies [change] to the state in one atomic step, so that two taps cannot both pass the same
     * check; [change] returns null to leave the state as it is. Whether it changed the state.
     */
    private inline fun claim(crossinline change: (SessionManageState) -> SessionManageState?): Boolean {
        var claimed = false
        mutableState.update { current ->
            val next = change(current)
            claimed = next != null
            next ?: current
        }
        return claimed
    }

    private suspend fun load() {
        mutableState.update { it.copy(loading = true) }
        try {
            val client = client(bind = true)
            val session = client.sessions.get(sessionId)
            val children = hasChildren()
            mutableState.update { current ->
                // Text the user typed over the old title stays; an untouched field follows the server.
                val untouched = current.title == current.currentTitle.orEmpty()
                current.copy(
                    loading = false,
                    currentTitle = session.title,
                    title = if (untouched) session.title.orEmpty() else current.title,
                    archived = session.archived,
                    directory = session.directory,
                    hasChildSessions = children,
                    loadError = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(loading = false, loadError = failureOf(e)) }
        }
    }

    /**
     * Whether the session has a child, archived ones included; null when the server could not say.
     *
     * The client is resolved again from the connection standing now, under the bound-server rule,
     * so a reconnect during the session fetch is asked on the new connection. When the client is
     * closed under the query, it is asked once more on the client of the server's connection
     * standing then.
     */
    private suspend fun hasChildren(): Boolean? {
        repeat(CHILDREN_ATTEMPTS) {
            try {
                return client(bind = false).sessions.list(
                    SessionQuery(limit = 1, parent = SessionParentFilter.ChildrenOf(sessionId)),
                ).items.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.error != ApiError.Closed) return null
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun saveTitle() {
        val title = mutableState.value.title.trim()
        start(ManageAction.Rename, allowed = { it.canSaveTitle }) { client ->
            client.sessions.update(sessionId, SessionUpdate(title = title))
            mutableState.update { current ->
                // A title typed while the rename ran stays.
                val typed = current.title.trim() != title
                current.copy(currentTitle = title, title = if (typed) current.title else title)
            }
        }
    }

    private fun setArchived(archived: Boolean) {
        val action = if (archived) ManageAction.Archive else ManageAction.Unarchive
        start(action, allowed = { it.archived != archived }) { client ->
            client.sessions.update(sessionId, SessionUpdate(archived = archived))
            mutableState.update { it.copy(archived = archived) }
        }
    }

    private fun requestDelete() {
        claim { current -> current.copy(confirmingDelete = true).takeIf { current.canAct() } }
    }

    private fun confirmDelete() {
        start(ManageAction.Delete, allowed = { it.confirmingDelete }) { client ->
            client.sessions.delete(sessionId)
            deleted = true
            eventChannel.trySend(SessionManageEvent.Deleted(mutableState.value.directory))
        }
    }

    /**
     * Starts [action] if no other runs, the session is loaded, and [allowed] holds; the check and
     * the claim of [SessionManageState.busy] are one step, so a second tap sends nothing.
     */
    private fun start(
        action: ManageAction,
        allowed: (SessionManageState) -> Boolean,
        block: suspend (OpencodeClient) -> Unit,
    ) {
        val claimed = claim { current ->
            if (!current.canAct() || !allowed(current)) {
                null
            } else {
                current.copy(busy = action, confirmingDelete = false, actionError = null)
            }
        }
        if (!claimed) return
        scope.launch {
            try {
                block(client(bind = false))
                mutableState.update { it.copy(busy = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = failureOf(e)
                mutableState.update { current ->
                    current.copy(
                        busy = null,
                        actionError = ManageActionError(action, failure),
                        loadError = if (failure == SessionsFailure.NotFound) failure else current.loadError,
                    )
                }
            }
        }
    }

    private fun SessionManageState.canAct(): Boolean =
        !deleted && !loading && loadError == null && busy == null && synchronized(lock) { server != null }

    /**
     * The client of the connection standing now, if it is `Connected` with the session's server.
     * With [bind], a presenter that has no server yet takes this connection's.
     *
     * @throws NotConnectedHere Otherwise.
     */
    private fun client(bind: Boolean): OpencodeClient {
        val connected = connection.state.value as? ConnectionState.Connected ?: throw NotConnectedHere()
        synchronized(lock) {
            val bound = server
            if (bound == null && bind) {
                server = connected.server
            } else if (bound != connected.server) {
                throw NotConnectedHere()
            }
        }
        return connected.client
    }

    /** No connection serves the session's server. */
    private class NotConnectedHere : Exception()

    private companion object {
        /** The children query and one retry after its client was closed. */
        const val CHILDREN_ATTEMPTS = 2

        fun failureOf(e: Exception): SessionsFailure = when (e) {
            is ApiException -> SessionsFailure.of(e.error)
            is NotConnectedHere -> SessionsFailure.NotConnected
            else -> SessionsFailure.Unknown
        }
    }
}
