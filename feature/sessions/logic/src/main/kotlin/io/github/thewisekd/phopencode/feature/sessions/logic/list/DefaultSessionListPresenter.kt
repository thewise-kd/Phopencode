package io.github.thewisekd.phopencode.feature.sessions.logic.list

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.PageCursor
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionParentFilter
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionBadge
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListPresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionRow
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The root sessions of the active connection's server (P5.2), as the contract's
 * [SessionListState] describes them.
 *
 * Which server: the client of `ConnectionState.Connected`. Each new connection (a new client)
 * opens its own event stream, and each `ServerEvent.Connected` on it, the first one included,
 * loads the first page again; so the first page is always fetched after the stream is open, and
 * nothing that happens in between is missed. A connection made for another profile clears the
 * rows first, so one server's sessions never show under another. Without a connection (`Idle`,
 * `Error`, `Closed`) the list shows [SessionsFailure.NotConnected]; while one is being made, the
 * list waits as it is.
 *
 * Loading: a first page lists roots only, newest first, with the search and archive filters; then
 * the running sessions (`activeStatuses`) and the pending forms and permissions are fetched. Those
 * two are only for the badge: their failure does not fail the list. Pending items are listed per
 * folder: the list's own, or, when it lists every folder, each folder its rows are in (a row with
 * no folder stands for the server's default one). Events that arrive while a first page loads are
 * held and applied on top of it, so a page older than the event never undoes it.
 *
 * Badge: a form or permission waits on the root session it belongs to. An item of a child session
 * is mapped to its root through the parent chain, which is learned from loaded rows, from
 * `SessionCreated` and, failing those, from `SessionApi.get`, and kept in a cache of at most
 * [MAX_KNOWN_PARENTS] sessions.
 *
 * Live updates: new root sessions that pass the filters are added at the top; an unarchived one is
 * put in its place by `updatedAt`, unless that place is below the last page loaded. A search is
 * matched on the client by title for sessions that arrive live or are renamed, as the server's
 * search cannot be asked about one session: a row renamed to a title that does not match leaves
 * the results. A page or a lookup can be answered after a session was deleted, archived (while
 * archived ones are hidden) or renamed out of the search; such sessions are remembered since the
 * current first page started (at most [MAX_GONE]) and dropped from the late answer. Every path
 * that stops a page load also clears `loadingMore`. Changes that arrive while a new session is
 * looked up (title, usage, how its execution ended) are applied to the answer before it is shown.
 * A row that brings in a folder not seen yet, from a page or live, fetches that folder's pending
 * items once.
 *
 * Nothing runs in the constructor: the work starts in [scope], after a `yield`.
 */
class DefaultSessionListPresenter(
    private val directory: String?,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : SessionListPresenter {
    private val mutableState = MutableStateFlow(SessionListState(directory = directory))

    override val state: StateFlow<SessionListState> = mutableState.asStateFlow()

    // Unlimited, so that onIntent can send without suspending and no event is dropped.
    private val eventChannel = Channel<SessionListEvent>(Channel.UNLIMITED)

    override val events: Flow<SessionListEvent> = eventChannel.receiveAsFlow()

    /** Guards everything below, which is changed only in short blocks that never suspend. */
    private val lock = Any()

    /** The roots shown, in order. */
    private val rows = mutableListOf<Session>()

    private var cursor: PageCursor? = null
    private var endReached = false

    /** Every session the server says runs, child sessions included. */
    private val running = HashSet<SessionId>()

    /** Pending forms and permissions, by [formKey] or [permissionKey], to the session they belong to. */
    private val pending = HashMap<String, SessionId>()

    /** The folders whose pending items [pending] holds; a null folder is the server's default. */
    private val fetchedFolders = HashSet<String?>()

    /** Child to parent; a root maps to null. Most recently used kept. */
    private val parents = BoundedMap<SessionId, SessionId?>(MAX_KNOWN_PARENTS, accessOrder = true)

    /**
     * Sessions deleted, archived while archived ones are hidden, or renamed out of the search, since
     * the current first page started. A later page or a lookup that left before the change still
     * holds them; they are dropped from it. The oldest are forgotten past [MAX_GONE].
     */
    private val gone = BoundedMap<SessionId, Unit>(MAX_GONE, accessOrder = false)

    /**
     * Sessions being looked up to be added, with the changes that arrived for them meanwhile, to
     * apply to the answer in order.
     */
    private val lookups = HashMap<SessionId, MutableList<(Session) -> Session>>()

    /** Folders whose pending items are being fetched for rows added after the first page. */
    private val fetchingFolders = HashSet<String?>()

    /** Bumped with each first page: a lookup started before it is answered by that page instead. */
    private var epoch = 0L

    /** Sessions whose root is being looked up. */
    private val resolving = HashSet<SessionId>()

    /** The filters of the first page loaded or loading. */
    private var filters = Filters(query = "", showArchived = false)

    /** The connection served now, or null. */
    private var live: Live? = null

    /** The profile whose sessions [rows] hold. */
    private var shownProfile: ServerProfileId? = null

    /** Events held while a first page or a page's pending items load; null when none are held. */
    private var held: MutableList<ServerEvent>? = null

    private var firstLoad: Job? = null
    private var nextLoad: Job? = null
    private var loadToken = 0L
    private var search: Job? = null

    /** Bumped to serve the current connection again, with a new event stream. */
    private val restarts = MutableStateFlow(0)

    private data class Filters(val query: String, val showArchived: Boolean)

    private class Live(val client: OpencodeClient, val scope: CoroutineScope) {
        @Volatile
        var streaming = true
    }

    private sealed interface Link {
        data class Up(val connected: ConnectionState.Connected) : Link

        /** A connection is being made or made again. */
        data object Connecting : Link

        data object Down : Link
    }

    private enum class Mode { Sync, Refresh, Retry, Filters }

    init {
        // The screen's scope may be Dispatchers.Main.immediate, which runs a launch at once; the
        // yield hands it back to the dispatcher, so nothing runs inside the constructor.
        scope.launch {
            yield()
            follow()
        }
    }

    override fun onIntent(intent: SessionListIntent) {
        when (intent) {
            is SessionListIntent.QueryChanged -> {
                mutableState.update { it.copy(query = intent.query) }
                search?.cancel()
                search = scope.launch {
                    delay(SEARCH_DEBOUNCE)
                    filtersChanged()
                }
            }
            is SessionListIntent.ShowArchived -> {
                if (mutableState.value.showArchived == intent.show) return
                mutableState.update { it.copy(showArchived = intent.show) }
                filtersChanged()
            }
            SessionListIntent.Refresh -> reload(Mode.Refresh)
            SessionListIntent.LoadMore -> current()?.let(::loadMore)
            SessionListIntent.Retry -> {
                val shown = mutableState.value
                val live = current()
                if (shown.error == null && shown.loadMoreError != null && live != null) loadMore(live) else reload(Mode.Retry)
            }
            is SessionListIntent.Open -> eventChannel.trySend(SessionListEvent.OpenChat(intent.id))
            is SessionListIntent.Manage -> eventChannel.trySend(SessionListEvent.OpenManage(intent.id))
            SessionListIntent.Create -> eventChannel.trySend(SessionListEvent.OpenCreate(directory))
            SessionListIntent.DismissError -> mutableState.update { it.copy(error = null) }
        }
    }

    private fun current(): Live? = synchronized(lock) { live }

    // Connection

    private suspend fun follow() {
        combine(connection.state.map(::linkOf).distinctUntilChanged(), restarts) { link, _ -> link }
            .collectLatest { link ->
                when (link) {
                    is Link.Up -> serve(link.connected)
                    Link.Connecting -> wait(failed = false)
                    Link.Down -> wait(failed = true)
                }
            }
    }

    private fun linkOf(state: ConnectionState): Link = when (state) {
        is ConnectionState.Connected -> Link.Up(state)
        ConnectionState.Idle, ConnectionState.Closed, is ConnectionState.Error -> Link.Down
        else -> Link.Connecting
    }

    /** No client: what was loading stopped with the connection's work. */
    private fun wait(failed: Boolean) {
        synchronized(lock) {
            live = null
            held = null
            invalidateLocked()
            mutableState.update {
                it.copy(
                    loading = if (failed) false else it.loading,
                    refreshing = false,
                    loadingMore = false,
                    error = if (failed) SessionsFailure.NotConnected else it.error,
                )
            }
        }
    }

    /** Follows [connected]'s event stream until the connection changes. */
    private suspend fun serve(connected: ConnectionState.Connected): Nothing = coroutineScope {
        val current = Live(connected.client, this)
        val profile = connected.server.profileId
        synchronized(lock) {
            live = current
            held = null
            invalidateLocked()
            epoch++
            gone.clear()
            parents.clear()
            resolving.clear()
            if (shownProfile != profile) {
                if (shownProfile != null) clearLocked()
                shownProfile = profile
            }
        }
        try {
            connected.client.events.events(EVENT_TYPES).collect { event -> onServerEvent(current, event) }
            current.streaming = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            current.streaming = false
            failFirst(current, SessionsFailure.of(e.error))
        } catch (_: Exception) {
            current.streaming = false
            failFirst(current, SessionsFailure.Unknown)
        }
        // Kept, so that a refresh still reaches this client; a retry opens the stream again.
        awaitCancellation()
    }

    /** Another server's rows: nothing of them may stay. */
    private fun clearLocked() {
        rows.clear()
        cursor = null
        endReached = false
        running.clear()
        pending.clear()
        fetchedFolders.clear()
        mutableState.update {
            it.copy(loading = true, refreshing = false, loadingMore = false, rows = emptyList(), endReached = false,
                error = null, loadMoreError = null)
        }
    }

    private fun failFirst(live: Live, failure: SessionsFailure) {
        synchronized(lock) {
            if (this.live !== live) return
            invalidateLocked()
            mutableState.update { it.copy(loading = false, refreshing = false, error = failure) }
        }
    }

    /**
     * Stops the page loads under way and makes their answers stale. Every path that does so goes
     * through here, so that a stopped next page never stays shown as loading.
     */
    private fun invalidateLocked() {
        loadToken++
        firstLoad?.cancel()
        nextLoad?.cancel()
        firstLoad = null
        nextLoad = null
        mutableState.update { it.copy(loadingMore = false) }
    }

    private fun onServerEvent(live: Live, event: ServerEvent) {
        if (event is ServerEvent.Connected) {
            loadFirst(live, Mode.Sync)
            return
        }
        synchronized(lock) {
            if (this.live !== live) return
            held?.let {
                it += event
                return
            }
        }
        apply(live, event)
    }

    // Loading

    private fun reload(mode: Mode) {
        val live = current()
        if (live == null || !live.streaming) {
            // Serves the connection again: a new stream, whose Connected loads the first page.
            mutableState.update { it.copy(refreshing = false) }
            restarts.update { it + 1 }
            return
        }
        loadFirst(live, mode)
    }

    private fun filtersChanged() {
        val shown = mutableState.value
        val wanted = Filters(shown.query.trim(), shown.showArchived)
        val live = synchronized(lock) {
            if (wanted == filters) return
            live
        }
        if (live != null && live.streaming) {
            loadFirst(live, Mode.Filters)
        } else {
            // Loaded with these filters once a connection serves.
            synchronized(lock) {
                filters = wanted
                rows.clear()
                cursor = null
                endReached = false
                publishLocked()
            }
        }
    }

    private fun loadFirst(live: Live, mode: Mode) {
        val token: Long
        val wanted: Filters
        synchronized(lock) {
            if (this.live !== live) return
            invalidateLocked()
            token = loadToken
            epoch++
            gone.clear()
            if (held == null) held = mutableListOf()
            val shown = mutableState.value
            wanted = Filters(shown.query.trim(), shown.showArchived)
            filters = wanted
            val clear = mode == Mode.Filters
            if (clear) {
                rows.clear()
                cursor = null
                endReached = false
            }
            mutableState.update {
                it.copy(
                    loading = mode != Mode.Refresh && (clear || it.rows.isEmpty()),
                    refreshing = mode == Mode.Refresh,
                    loadingMore = false,
                    rows = if (clear) emptyList() else it.rows,
                    error = null,
                    loadMoreError = null,
                )
            }
            firstLoad = live.scope.launch { firstPage(live, token, wanted) }
        }
    }

    private suspend fun firstPage(live: Live, token: Long, wanted: Filters) {
        val client = live.client
        val roots = try {
            val page = client.sessions.list(queryOf(wanted, cursor = null))
            val active = optional { client.sessions.activeStatuses() }
            val roots = page.items.filter { it.isRoot }.distinctBy { it.id }
            synchronized(lock) {
                if (token != loadToken) return
                rows.clear()
                rows += roots
                roots.forEach { parents[it.id] = null }
                cursor = page.nextCursor
                endReached = page.isEnd
                if (active != null) {
                    running.clear()
                    running += active.keys
                }
                mutableState.update { it.copy(loading = false, refreshing = false, endReached = endReached) }
                publishLocked()
            }
            roots
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = if (e is ApiException) SessionsFailure.of(e.error) else SessionsFailure.Unknown
            val replay = synchronized(lock) {
                if (token != loadToken) return
                mutableState.update { it.copy(loading = false, refreshing = false, error = failure) }
                held.also { held = null }
            }
            replay?.forEach { apply(live, it) }
            return
        }
        val (items, folders) = pendingIn(client, foldersOf(roots))
        val replay = synchronized(lock) {
            if (token != loadToken) return
            pending.clear()
            pending += items
            fetchedFolders.clear()
            fetchedFolders += folders
            publishLocked()
            held.also { held = null }
        }
        replay?.forEach { apply(live, it) }
        resolvePending(live)
    }

    private fun loadMore(live: Live) {
        synchronized(lock) {
            val shown = mutableState.value
            if (this.live !== live || !live.streaming) return
            if (firstLoad?.isActive == true || nextLoad?.isActive == true) return
            if (shown.loading || shown.refreshing || endReached) return
            val from = cursor ?: return
            val token = loadToken
            val wanted = filters
            mutableState.update { it.copy(loadingMore = true, loadMoreError = null) }
            nextLoad = live.scope.launch { nextPage(live, token, wanted, from) }
        }
    }

    private suspend fun nextPage(live: Live, token: Long, wanted: Filters, from: PageCursor) {
        val client = live.client
        val added = try {
            val page = client.sessions.list(queryOf(wanted, cursor = from))
            synchronized(lock) {
                if (token != loadToken) return
                val known = rows.mapTo(HashSet()) { it.id }
                val added = page.items.filter { it.isRoot && !gone.containsKey(it.id) && known.add(it.id) }
                rows += added
                added.forEach { parents[it.id] = null }
                cursor = page.nextCursor
                endReached = page.isEnd
                mutableState.update { it.copy(loadingMore = false, endReached = endReached) }
                publishLocked()
                added
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = if (e is ApiException) SessionsFailure.of(e.error) else SessionsFailure.Unknown
            synchronized(lock) {
                if (token != loadToken) return
                mutableState.update { it.copy(loadingMore = false, loadMoreError = failure) }
            }
            return
        }
        fetchNewFolders(live, token, added)
    }

    /**
     * Fetches, once, the pending items of the folders [added] rows brought in that are not known
     * yet, holding events meanwhile so that the answer does not undo them. For rows added after
     * the first page under [token], from a page or live.
     */
    private suspend fun fetchNewFolders(live: Live, token: Long, added: List<Session>) {
        val wanted = synchronized(lock) {
            val folders = foldersOf(added) - fetchedFolders - fetchingFolders
            if (folders.isEmpty() || token != loadToken) return
            fetchingFolders += folders
            if (held == null) held = mutableListOf()
            folders
        }
        try {
            val (items, folders) = pendingIn(live.client, wanted)
            val replay = synchronized(lock) {
                if (token != loadToken) return
                pending += items
                fetchedFolders += folders
                publishLocked()
                held.also { held = null }
            }
            replay?.forEach { apply(live, it) }
            resolvePending(live)
        } finally {
            synchronized(lock) { fetchingFolders -= wanted }
        }
    }

    private fun queryOf(wanted: Filters, cursor: PageCursor?) = SessionQuery(
        limit = pageSize,
        order = SortOrder.Descending,
        search = wanted.query.takeIf { it.isNotEmpty() },
        parent = SessionParentFilter.RootsOnly,
        archived = if (wanted.showArchived) null else false,
        directory = directory,
        cursor = cursor,
    )

    /** The folders whose pending items show on [sessions]' badges. */
    private fun foldersOf(sessions: List<Session>): Set<String?> =
        if (directory != null) setOf(directory) else sessions.mapTo(LinkedHashSet()) { it.directory }

    /**
     * The pending forms and permissions of [folders], and the folders both lists came back for. A
     * failed list leaves its folder out: the badge is not worth failing the list for.
     */
    private suspend fun pendingIn(client: OpencodeClient, folders: Set<String?>): Pair<Map<String, SessionId>, Set<String?>> {
        val items = HashMap<String, SessionId>()
        val fetched = HashSet<String?>()
        for (folder in folders) {
            val location = folder?.let(::Location)
            val forms = optional { client.interactions.listForms(sessionId = null, location = location) }
            val permissions = optional { client.interactions.listPermissions(sessionId = null, location = location) }
            forms?.forEach { items[formKey(it.id.value)] = it.sessionId }
            permissions?.forEach { items[permissionKey(it.id.value)] = it.sessionId }
            if (forms != null && permissions != null) fetched += folder
        }
        return items to fetched
    }

    private suspend fun <T> optional(call: suspend () -> T): T? = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // Live updates

    private fun apply(live: Live, event: ServerEvent) {
        when (event) {
            is ServerEvent.SessionCreated -> {
                synchronized(lock) { parents[event.sessionId] = event.parentId }
                if (event.parentId == null) addNew(live, event.sessionId, atTop = true)
            }
            is ServerEvent.SessionForked -> addNew(live, event.sessionId, atTop = true)
            is ServerEvent.SessionDeleted -> change {
                val id = event.sessionId
                gone[id] = Unit
                rows.removeAll { it.id == id }
                running -= id
                pending.values.removeAll { it == id }
                parents.remove(id)
            }
            is ServerEvent.SessionRenamed -> change {
                val query = filters.query
                if (query.isNotEmpty() && event.title?.contains(query, ignoreCase = true) != true) {
                    // Out of the results, loaded or not: the server's search no longer finds it either.
                    rows.removeAll { it.id == event.sessionId }
                    gone[event.sessionId] = Unit
                } else {
                    updateLocked(event.sessionId) { it.copy(title = event.title) }
                }
            }
            is ServerEvent.SessionArchived -> change {
                if (filters.showArchived) {
                    replaceLocked(event.sessionId) { it.copy(archived = true) }
                } else {
                    gone[event.sessionId] = Unit
                    rows.removeAll { it.id == event.sessionId }
                }
            }
            is ServerEvent.SessionUnarchived -> {
                val shown = synchronized(lock) {
                    gone.remove(event.sessionId)
                    replaceLocked(event.sessionId) { it.copy(archived = false) }
                }
                if (shown) change { } else addNew(live, event.sessionId, atTop = false)
            }
            is ServerEvent.UsageUpdated -> change { updateLocked(event.sessionId) { it.copy(usage = event.usage) } }
            is ServerEvent.ExecutionStarted -> change { running += event.sessionId }
            is ServerEvent.ExecutionSucceeded -> ended(event.sessionId, SessionOutcome.Succeeded)
            is ServerEvent.ExecutionFailed -> ended(event.sessionId, SessionOutcome.Failed)
            is ServerEvent.ExecutionInterrupted -> ended(event.sessionId, SessionOutcome.Interrupted)
            is ServerEvent.FormCreated -> {
                change { pending[formKey(event.form.id.value)] = event.form.sessionId }
                resolvePending(live)
            }
            is ServerEvent.FormReplied -> change { pending -= formKey(event.formId.value) }
            is ServerEvent.FormCancelled -> change { pending -= formKey(event.formId.value) }
            is ServerEvent.PermissionAsked -> {
                change { pending[permissionKey(event.request.id.value)] = event.request.sessionId }
                resolvePending(live)
            }
            is ServerEvent.PermissionReplied -> change { pending -= permissionKey(event.requestId.value) }
            else -> Unit
        }
    }

    private fun ended(id: SessionId, outcome: SessionOutcome) = change {
        running -= id
        updateLocked(id) { it.copy(outcome = outcome) }
    }

    /** Changes the row [id], or, while it is being looked up, its answer once it comes. */
    private fun updateLocked(id: SessionId, update: (Session) -> Session) {
        if (!replaceLocked(id, update)) lookups[id]?.add(update)
    }

    private inline fun change(block: () -> Unit) = synchronized(lock) {
        block()
        publishLocked()
    }

    /** Whether a row [id] was there to change. */
    private inline fun replaceLocked(id: SessionId, update: (Session) -> Session): Boolean {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return false
        rows[index] = update(rows[index])
        return true
    }

    /**
     * Fetches [id] and shows it if it belongs in this list: at the top when new ([atTop]), or else
     * in its place by `updatedAt`, as long as that place is within the pages loaded.
     */
    private fun addNew(live: Live, id: SessionId, atTop: Boolean) {
        val started = synchronized(lock) {
            if (rows.any { it.id == id } || lookups.containsKey(id)) return
            lookups[id] = mutableListOf()
            epoch
        }
        live.scope.launch {
            val (token, added) = try {
                val fetched = optional { live.client.sessions.get(id) } ?: return@launch
                synchronized(lock) {
                    if (this@DefaultSessionListPresenter.live !== live) return@launch
                    parents[fetched.id] = fetched.parentId
                    // Deleted or hidden while the lookup ran, or answered by a first page since.
                    if (epoch != started || gone.containsKey(id)) return@launch
                    val session = lookups[id].orEmpty().fold(fetched) { current, update -> update(current) }
                    if (!belongs(session) || rows.any { it.id == id }) return@launch
                    val index = if (atTop) 0 else placeOf(session) ?: return@launch
                    rows.add(index, session)
                    publishLocked()
                    loadToken to session
                }
            } finally {
                synchronized(lock) { lookups.remove(id) }
            }
            fetchNewFolders(live, token, listOf(added))
        }
    }

    private fun belongs(session: Session): Boolean {
        val wanted = filters
        return session.isRoot &&
            (directory == null || session.directory == directory) &&
            (wanted.showArchived || !session.archived) &&
            (wanted.query.isEmpty() || session.title?.contains(wanted.query, ignoreCase = true) == true)
    }

    /** Where [session] goes among the rows by `updatedAt`; null when below the last page loaded. */
    private fun placeOf(session: Session): Int? {
        val updated = session.updatedAt
        val index = if (updated == null) {
            rows.size
        } else {
            rows.indexOfFirst { row -> row.updatedAt.let { it == null || it < updated } }.takeIf { it >= 0 } ?: rows.size
        }
        return index.takeIf { it < rows.size || endReached }
    }

    // Roots of pending items

    /** Looks up the root of each pending item's session that is not known yet. */
    private fun resolvePending(live: Live) {
        val owners = synchronized(lock) {
            val ids = rows.mapTo(HashSet()) { it.id }
            pending.values.toSet().filter { rootOf(it, ids) == null && resolving.add(it) }
        }
        owners.forEach { owner ->
            live.scope.launch {
                try {
                    climb(live, owner)
                } finally {
                    synchronized(lock) {
                        resolving -= owner
                        if (this@DefaultSessionListPresenter.live === live) publishLocked()
                    }
                }
            }
        }
    }

    /** Learns the parents above [owner] until a root, asking the server for the ones not known. */
    private suspend fun climb(live: Live, owner: SessionId) {
        var id = owner
        repeat(MAX_DEPTH) {
            val known = synchronized(lock) { if (parents.containsKey(id)) Known(parents[id]) else null }
            val parent = if (known != null) {
                known.parent
            } else {
                val session = optional { live.client.sessions.get(id) } ?: return
                synchronized(lock) { parents[id] = session.parentId }
                session.parentId
            }
            id = parent ?: return
        }
    }

    private class Known(val parent: SessionId?)

    /** The root of [id] among [ids] as far as [parents] tells, or null if not known. */
    private fun rootOf(id: SessionId, ids: Set<SessionId>): SessionId? {
        var at = id
        repeat(MAX_DEPTH) {
            if (at in ids) return at
            if (!parents.containsKey(at)) return null
            at = parents[at] ?: return null
        }
        return null
    }

    // State

    private fun publishLocked() {
        val ids = rows.mapTo(HashSet()) { it.id }
        val waiting = pending.values.mapNotNullTo(HashSet()) { rootOf(it, ids) }
        val shown = rows.map { session -> rowOf(session, badgeOf(session, waiting)) }
        mutableState.update { it.copy(rows = shown, endReached = endReached) }
    }

    private fun badgeOf(session: Session, waiting: Set<SessionId>): SessionBadge = when {
        session.id in waiting -> SessionBadge.WaitingForInput
        session.id in running -> SessionBadge.Running
        session.outcome == SessionOutcome.Failed -> SessionBadge.Failed
        session.outcome == SessionOutcome.Interrupted -> SessionBadge.Interrupted
        else -> SessionBadge.Idle
    }

    private fun rowOf(session: Session, badge: SessionBadge) = SessionRow(
        id = session.id,
        title = session.title,
        directory = session.directory,
        status = badge,
        usage = session.usage,
        updatedAt = session.updatedAt,
        archived = session.archived,
    )

    /** A map of at most [capacity] entries that forgets the eldest, by use or by insertion. */
    private class BoundedMap<K, V>(private val capacity: Int, accessOrder: Boolean) :
        LinkedHashMap<K, V>(16, 0.75f, accessOrder) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 30

        /** How long typing must pause before the search is sent. */
        val SEARCH_DEBOUNCE: Duration = 300.milliseconds

        /** The most sessions whose parent is remembered, to map a child's form to its root. */
        const val MAX_KNOWN_PARENTS: Int = 512

        /** The most removed sessions remembered against late answers. */
        const val MAX_GONE: Int = 256

        /** The deepest parent chain followed; deeper ones are taken as unknown. */
        private const val MAX_DEPTH = 16

        private fun formKey(id: String) = "form:$id"

        private fun permissionKey(id: String) = "permission:$id"

        /** What the list follows; `server.connected` arrives on every stream whatever the filter. */
        private val EVENT_TYPES = setOf(
            "session.created",
            "session.deleted",
            "session.renamed",
            "session.archived",
            "session.unarchived",
            "session.forked",
            "session.usage.updated",
            "session.execution.started",
            "session.execution.succeeded",
            "session.execution.failed",
            "session.execution.interrupted",
            "form.created",
            "form.replied",
            "form.cancelled",
            "permission.asked",
            "permission.replied",
        )
    }
}
