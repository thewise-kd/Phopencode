package io.github.thewisekd.phopencode.feature.notify.logic.pending

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.PageCursor
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionParentFilter
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyConnection
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingEvent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingItem
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The forms and permission requests waiting on every session of the active connection's server
 * (P11.11), as the contract's [PendingState] describes them.
 *
 * Driven by the latest value of [NotifyConnection.generations], collected in [scope] after a
 * `yield()`:
 * - A new generation empties the list and follows that connection: its client's event stream is
 *   opened, and each `ServerEvent.Connected` on it, the first one included, reads the list. So the
 *   list is always read after the stream is open, and nothing that happens in between is missed.
 * - No generation (null) empties the list and shows [NotifyFailure.NotConnected].
 * - A call that finds a connection other than the generation it was started for does nothing: the
 *   newer generation is reported next and has a turn of its own.
 *
 * Reading: the root sessions are listed (newest first, at most [MAX_SESSION_PAGES] pages) to learn
 * their folders; then the forms and permissions of the server's default folder and of each of those
 * folders are listed and combined, one item per key. Each item's session is taken from the listing
 * or looked up (`SessionApi.get`) for its title and parent; a failed lookup leaves both null. A
 * folder other than the default one whose lists fail is skipped, so a stale folder of an old session
 * cannot keep the screen in error: the items it showed before stay as they were. Only a failure to
 * list the root sessions or the default folder fails the read: the items shown stay, with the
 * failure.
 *
 * One serialization point: every change to the list (a read's answer, an event, live or held, a
 * session lookup's answer) happens in one block under [lock] that never suspends, and nothing is
 * published halfway. Events that arrive while a read runs are held and applied on top of its answer
 * in the same block, so an answer older than an event never undoes it. A dropped stream applies the
 * held events at once. `FormCreated` and `PermissionAsked` add an item at the end;
 * `FormReplied`, `FormCancelled` and `PermissionReplied` remove it; `SessionDeleted` removes the
 * items of that session and of its children; `SessionRenamed` changes their title. An item read
 * again keeps its place.
 *
 * Sessions: each read fetches the session of every item again (roots from the listing, others with
 * `SessionApi.get`), so a reconnect picks up what changed while the stream was down. An event-added
 * item whose session is not known is looked up. Per connection, one sequence number ticks on every
 * fetch start and every session event; each session kept records the fetch it came from. An answer
 * from a fetch older than the one kept is dropped, a rename newer than the fetch wins over its
 * title, and an answer for a session deleted in this connection, or whose parent was, removes that
 * session's items. Sessions that have items are never forgotten; the bound
 * [MAX_KNOWN_SESSIONS] applies to the others.
 *
 * Every path that ends a read (its answer, its failure, a dropped stream, a stream that ends or
 * fails for good) applies the events held during it in the same locked block. A stream that ends
 * stops its reads and lookups at once, so [PendingIntent.Refresh] can open it again right away.
 *
 * Every path that puts an item in the list (a live or held creation event, a read's answer, a
 * lookup's answer) goes through one check, which refuses an item whose session, or a known
 * ancestor of it, was deleted on this connection.
 *
 * A dropped stream (`ServerEvent.Disconnected`) keeps the items and shows the failure; the stream
 * reconnects by itself, and its `Connected` reads the list again. A stream that fails for good
 * shows its failure too; [PendingIntent.Refresh] then opens it again.
 *
 * Nothing here logs. Failures become a [NotifyFailure], which carries no server text.
 *
 * @param connection The client of the active connection.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultPendingPresenter(
    private val connection: NotifyConnection,
    private val scope: CoroutineScope,
) : PendingPresenter {
    private val mutableState = MutableStateFlow(PendingState())

    override val state: StateFlow<PendingState> = mutableState.asStateFlow()

    // Unlimited, so that onIntent can send without suspending and no event is dropped.
    private val eventChannel = Channel<PendingEvent>(Channel.UNLIMITED)

    override val events: Flow<PendingEvent> = eventChannel.receiveAsFlow()

    /** Guards everything below, which is changed only in short blocks that never suspend. */
    private val lock = Any()

    /** The items shown, by [PendingItem.key], in their order. */
    private var entries = LinkedHashMap<String, PendingItem>()

    /** The folder each item shown was last listed in, by key; null is the default folder. */
    private val origins = HashMap<String, String?>()

    /** The generation whose items [entries] hold; null for none, or none yet. */
    private var shownGeneration: Long? = null

    /** Whether a generation has been reported, with or without a connection. */
    private var reported = false

    /** Whether a read of [shownGeneration] has finished, so the list is no longer loading. */
    private var hasRead = false

    /** The stream followed now, or null while there is none. */
    private var live: Live? = null

    /** Events held while a read runs; null when none runs. */
    private var held: MutableList<ServerEvent>? = null

    private var readJob: Job? = null

    /** Bumped with each read started or stopped: only the newest read's answer counts. */
    private var readToken = 0L

    /** Bumped to follow the current generation again, with a new event stream. */
    private val restarts = MutableStateFlow(0)

    /**
     * One event stream of one generation. [sessions] and [looking] are guarded by [lock].
     *
     * @property scope Lives as long as the stream: reads and lookups run here.
     */
    private class Live(val generation: Long, val client: OpencodeClient, val scope: CoroutineScope) {
        /**
         * Sessions known on this connection, least recently used first. Trimmed to
         * [MAX_KNOWN_SESSIONS] by [pruneLocked], which never drops a session that has items.
         */
        val sessions = LinkedHashMap<SessionId, Known>(16, 0.75f, true)

        /** Sessions being looked up. */
        val looking = HashSet<SessionId>()

        /** Ticks on every fetch start and every session event. */
        var seq = 0L

        /** The latest rename of each session, with the [seq] it came at; trimmed like [sessions]. */
        val renames = LinkedHashMap<SessionId, Rename>(16, 0.75f, true)

        /** Sessions deleted on this connection, oldest first, at most [MAX_DELETED]. */
        val deleted = LinkedHashSet<SessionId>()
    }

    /** A session as fetched by a call that started at [seq], renames since applied. */
    private class Known(val session: Session, val seq: Long)

    private class Rename(val seq: Long, val title: String?)

    /** Sessions to look up once the lock is released, each with the [Live.seq] its fetch starts at. */
    private class Lookups(val live: Live) {
        val ids = LinkedHashMap<SessionId, Long>()
    }

    init {
        // The screen's scope may be Dispatchers.Main.immediate, which runs a launch at once; the
        // yield hands it back to the dispatcher, so nothing runs inside the constructor.
        scope.launch {
            yield()
            follow()
        }
    }

    override fun onIntent(intent: PendingIntent) {
        when (intent) {
            PendingIntent.Refresh -> refresh()
            is PendingIntent.Open ->
                eventChannel.trySend(PendingEvent.OpenSession(intent.item.sessionId, intent.item.isChild))
            PendingIntent.Connect -> eventChannel.trySend(PendingEvent.OpenServerList)
            PendingIntent.DismissError -> mutableState.update { it.copy(error = null) }
            PendingIntent.Close -> eventChannel.trySend(PendingEvent.Close)
        }
    }

    // Connection

    private suspend fun follow() {
        combine(connection.generations, restarts) { generation, _ -> generation }
            .collectLatest { generation -> if (generation == null) notConnected() else serve(generation) }
    }

    private fun notConnected() {
        synchronized(lock) {
            reported = true
            live = null
            stopReadLocked()
            entries = LinkedHashMap()
            origins.clear()
            shownGeneration = null
            hasRead = false
            mutableState.value = PendingState(loading = false, error = NotifyFailure.NotConnected)
        }
    }

    /** Follows [generation]'s event stream until the generation changes. */
    private suspend fun serve(generation: Long): Nothing {
        synchronized(lock) {
            live = null
            stopReadLocked()
            if (!reported || shownGeneration != generation) {
                reported = true
                entries = LinkedHashMap()
                origins.clear()
                shownGeneration = generation
                hasRead = false
                mutableState.value = PendingState(loading = true)
            }
        }
        var current: Live? = null
        val failure = try {
            connection.call { client, on ->
                // Another connection than reported: the generations report it next.
                if (on != generation) awaitCancellation()
                coroutineScope {
                    val started = Live(on, client, this)
                    current = started
                    synchronized(lock) { live = started }
                    client.events.events(EVENT_TYPES).collect { event -> onServerEvent(started, event) }
                    // The stream is over: its reads and lookups stop now rather than being waited for.
                    coroutineContext.cancelChildren()
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            NotifyFailure.of(e.error)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Not an ApiException, against the client's word; its message may quote the server.
            NotifyFailure.Unknown
        }
        synchronized(lock) {
            if (live == null || live === current) {
                // The stream is over, its scope too: lookups are not started, a refresh reads again.
                current?.let { endReadLocked(Lookups(it)) } ?: stopReadLocked()
                live = null
                mutableState.update {
                    it.copy(loading = false, refreshing = false, error = failure ?: it.error)
                }
            }
        }
        // Kept until the generation changes; a refresh follows this one again.
        awaitCancellation()
    }

    private fun refresh() {
        val following = synchronized(lock) {
            if (!reported || shownGeneration == null) return
            live
        }
        if (following != null) {
            startRead(following, refresh = true)
        } else {
            // The stream ended: follow the generation again, whose Connected reads the list.
            mutableState.update { it.copy(refreshing = it.items.isNotEmpty(), error = null) }
            restarts.update { it + 1 }
        }
    }

    private fun onServerEvent(live: Live, event: ServerEvent) {
        when (event) {
            is ServerEvent.Connected -> startRead(live, refresh = false)
            is ServerEvent.Disconnected -> {
                val lookups = Lookups(live)
                synchronized(lock) {
                    if (this.live !== live) return
                    endReadLocked(lookups)
                    // The stream comes back by itself; its Connected reads the list again.
                    mutableState.update {
                        it.copy(loading = false, refreshing = false, error = NotifyFailure.of(event.reason))
                    }
                }
                start(lookups)
            }
            else -> {
                val lookups = Lookups(live)
                synchronized(lock) {
                    if (this.live !== live) return
                    held?.let {
                        it += event
                        return
                    }
                    applyLocked(lookups, event)
                    publishLocked()
                }
                start(lookups)
            }
        }
    }

    // Reading

    private fun startRead(live: Live, refresh: Boolean) {
        synchronized(lock) {
            if (this.live !== live) return
            // Events held for a read replaced here still apply, on top of the new one's answer.
            val waiting = held
            stopReadLocked()
            val token = readToken
            held = waiting ?: mutableListOf()
            mutableState.update {
                it.copy(
                    loading = !hasRead,
                    refreshing = hasRead && (refresh || it.refreshing),
                    error = null,
                )
            }
            val since = ++live.seq
            readJob = live.scope.launch { read(live, token, since) }
        }
    }

    /** Stops the read running, if any, and makes its answer stale. */
    private fun stopReadLocked() {
        readToken++
        readJob?.cancel()
        readJob = null
        held = null
    }

    /** Reads the list; [since] is the [Live.seq] the read started at. */
    private suspend fun read(live: Live, token: Long, since: Long) {
        val lookups = Lookups(live)
        try {
            val found = readAll(live.client)
            synchronized(lock) {
                if (token != readToken || this.live !== live) return
                merge(live, found, since)
                hasRead = true
                mutableState.update { it.copy(loading = false, refreshing = false, error = null) }
                endReadLocked(lookups)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            val failure = if (e is ApiException) NotifyFailure.of(e.error) else NotifyFailure.Unknown
            synchronized(lock) {
                if (token != readToken || this.live !== live) return
                mutableState.update { it.copy(loading = false, refreshing = false, error = failure) }
                endReadLocked(lookups)
            }
        }
        start(lookups)
    }

    /**
     * Ends the read, if one runs, applying the events held during it, and publishes. Every path that
     * ends a read goes through here, in the same locked block as what ended it.
     */
    private fun endReadLocked(lookups: Lookups) {
        val waiting = held
        stopReadLocked()
        waiting?.forEach { applyLocked(lookups, it) }
        publishLocked()
    }

    /**
     * Every pending item of the server, in the order read, with its session where known. Fails when
     * the root sessions or the default folder cannot be listed; another folder that cannot is left
     * out and named in [Read.failed].
     */
    private suspend fun readAll(client: OpencodeClient): Read {
        val sessions = HashMap<SessionId, Session>()
        val folders = LinkedHashSet<String?>()
        folders += null
        var cursor: PageCursor? = null
        var pages = 0
        while (pages++ < MAX_SESSION_PAGES) {
            val found = client.sessions.list(
                SessionQuery(limit = SESSION_PAGE_SIZE, order = SortOrder.Descending, parent = SessionParentFilter.RootsOnly, cursor = cursor),
            )
            found.items.forEach { sessions[it.id] = it }
            found.items.forEach { session -> session.directory?.takeIf { it.isNotBlank() }?.let(folders::add) }
            if (found.isEnd) break
            cursor = found.nextCursor
        }

        val forms = LinkedHashMap<String, PendingItem>()
        val permissions = LinkedHashMap<String, PendingItem>()
        val origins = HashMap<String, String?>()
        val failed = HashSet<String>()
        for (folder in folders) {
            val location = folder?.let(::Location)
            val listed = if (folder == null) {
                // The default folder's failure fails the read.
                listFolder(client, location)
            } else {
                // Another folder may be stale, for example an old session's: its failure is skipped.
                optional { listFolder(client, location) } ?: run {
                    failed += folder
                    null
                }
            } ?: continue
            listed.forEach { item ->
                val into = if (item is PendingItem.FormItem) forms else permissions
                if (into.putIfAbsent(item.key, item) == null) origins[item.key] = folder
            }
        }
        val items = forms.values + permissions.values

        // Fetched again on every read, child sessions included: a title may have changed while down.
        for (id in items.map { it.sessionId }.distinct()) {
            if (id in sessions) continue
            optional { client.sessions.get(id) }?.let { sessions[id] = it }
        }
        return Read(items, sessions, origins, failed)
    }

    /** One folder's forms and permissions, forms first. */
    private suspend fun listFolder(client: OpencodeClient, location: Location?): List<PendingItem> {
        val forms = client.interactions.listForms(sessionId = null, location = location)
        val permissions = client.interactions.listPermissions(sessionId = null, location = location)
        return forms.map { PendingItem.FormItem(it, sessionTitle = null, parentId = null) } +
            permissions.map { PendingItem.PermissionItem(it, sessionTitle = null, parentId = null) }
    }

    /**
     * What one read found.
     *
     * @property items Without their sessions, which [merge] adds.
     * @property sessions The sessions fetched: the roots listed and the sessions of [items].
     * @property origins The folder each item was listed in, by key; null is the default folder.
     * @property failed The folders, other than the default one, whose lists could not be read.
     */
    private class Read(
        val items: List<PendingItem>,
        val sessions: Map<SessionId, Session>,
        val origins: Map<String, String?>,
        val failed: Set<String>,
    )

    /**
     * Takes [read] as the list: items still there keep their place, new ones go at the end. An item
     * shown before whose folder could not be read is kept, as nothing is known about it.
     */
    private fun merge(live: Live, read: Read, since: Long) {
        val kept = HashMap<SessionId, Session>()
        read.sessions.values.forEach { session -> rememberLocked(live, session, since)?.let { kept[session.id] = it } }
        val fresh = LinkedHashMap<String, PendingItem>()
        read.items.forEach { item ->
            val session = kept[item.sessionId] ?: live.sessions[item.sessionId]?.session
            admitLocked(live, item, session)?.let { fresh[item.key] = it }
        }
        val merged = LinkedHashMap<String, PendingItem>()
        entries.forEach { (key, shown) ->
            val item = fresh[key] ?: shown.takeIf { folderOf(live, it) in read.failed && !goneLocked(live, it.sessionId) }
            if (item != null) merged[key] = item
        }
        fresh.forEach { (key, item) -> merged.putIfAbsent(key, item) }
        entries = merged
        origins.keys.retainAll(merged.keys)
        origins += read.origins
    }

    /** The folder [item] was listed in, or else its session's; null for the default or unknown. */
    private fun folderOf(live: Live, item: PendingItem): String? =
        if (origins.containsKey(item.key)) {
            origins[item.key]
        } else {
            live.sessions[item.sessionId]?.session?.directory?.takeIf { it.isNotBlank() }
        }

    private suspend fun <T> optional(call: suspend () -> T): T? = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        null
    }

    // Live updates

    /** Applies [event] to the list; sessions to look up go to [lookups]. Under [lock]. */
    private fun applyLocked(lookups: Lookups, event: ServerEvent) {
        val live = lookups.live
        when (event) {
            is ServerEvent.FormCreated ->
                addLocked(lookups, PendingItem.FormItem(event.form, sessionTitle = null, parentId = null))
            is ServerEvent.PermissionAsked ->
                addLocked(lookups, PendingItem.PermissionItem(event.request, sessionTitle = null, parentId = null))
            is ServerEvent.FormReplied -> entries.remove(formKey(event.formId.value))
            is ServerEvent.FormCancelled -> entries.remove(formKey(event.formId.value))
            is ServerEvent.PermissionReplied -> entries.remove(permissionKey(event.requestId.value))
            is ServerEvent.SessionDeleted -> {
                live.seq++
                deleteLocked(live, event.sessionId)
            }
            is ServerEvent.SessionRenamed -> {
                val id = event.sessionId
                live.renames[id] = Rename(++live.seq, event.title)
                live.sessions[id]?.let { live.sessions[id] = Known(it.session.copy(title = event.title), it.seq) }
                entries.replaceAll { _, item -> if (item.sessionId == id) item.titled(event.title) else item }
            }
            else -> Unit
        }
    }

    /**
     * Adds [item] at the end, or updates it in its place; looks its session up if not known. An item
     * of a deleted session is refused. Under [lock].
     */
    private fun addLocked(lookups: Lookups, item: PendingItem) {
        val live = lookups.live
        val known = live.sessions[item.sessionId]?.session
        val admitted = admitLocked(live, item, known)
        if (admitted == null) {
            entries.remove(item.key)
            return
        }
        entries[item.key] = admitted
        if (known == null && live.looking.add(item.sessionId)) lookups.ids[item.sessionId] = ++live.seq
    }

    /**
     * [item] with [session]'s title and parent, ready to show; null if its session, or a known
     * ancestor of it, was deleted on this connection. Every path that puts an item in the list
     * comes through here. Under [lock].
     */
    private fun admitLocked(live: Live, item: PendingItem, session: Session?): PendingItem? {
        if (goneLocked(live, item.sessionId)) return null
        val parent = session?.parentId
        if (parent != null && goneLocked(live, parent)) return null
        return item.withSession(session)
    }

    /** Whether [id], or an ancestor of it as far as the sessions known tell, was deleted. Under [lock]. */
    private fun goneLocked(live: Live, id: SessionId): Boolean {
        var at: SessionId = id
        repeat(MAX_DEPTH) {
            if (at in live.deleted) return true
            at = live.sessions[at]?.session?.parentId ?: return false
        }
        return false
    }

    /**
     * Forgets [id], deleted on this connection, and removes its items and those of its sessions
     * below, as far as known. Under [lock].
     */
    private fun deleteLocked(live: Live, id: SessionId) {
        live.deleted += id
        if (live.deleted.size > MAX_DELETED) live.deleted.remove(live.deleted.first())
        entries.values.removeAll { it.parentId == id || goneLocked(live, it.sessionId) }
        live.sessions.remove(id)
        live.renames.remove(id)
    }

    /**
     * Keeps [session], fetched by a call that started at [since]: an answer older than the one kept
     * is dropped, and a rename that came later wins over its title. A session deleted on this
     * connection, or whose parent was, is not kept: its items are removed. Returns the session kept,
     * or null for a deleted one. Under [lock].
     */
    private fun rememberLocked(live: Live, session: Session, since: Long): Session? {
        val id = session.id
        if (id in live.deleted) return null
        val parent = session.parentId
        if (parent != null && goneLocked(live, parent)) {
            deleteLocked(live, id)
            return null
        }
        live.sessions[id]?.let { if (it.seq > since) return it.session }
        val rename = live.renames[id]
        val kept = if (rename != null && rename.seq > since) session.copy(title = rename.title) else session
        live.sessions[id] = Known(kept, since)
        return kept
    }

    /** Starts the lookups collected under the lock, now that it is released. */
    private fun start(lookups: Lookups) {
        lookups.ids.forEach { (id, since) -> look(lookups.live, id, since) }
    }

    private fun look(live: Live, id: SessionId, since: Long) {
        live.scope.launch {
            val session = try {
                optional { live.client.sessions.get(id) }
            } finally {
                synchronized(lock) { live.looking -= id }
            }
            if (session == null) return@launch
            synchronized(lock) {
                if (this@DefaultPendingPresenter.live !== live) return@launch
                val kept = rememberLocked(live, session, since)
                if (kept != null) {
                    val shown = entries.entries.iterator()
                    while (shown.hasNext()) {
                        val entry = shown.next()
                        if (entry.value.sessionId != id) continue
                        val admitted = admitLocked(live, entry.value, kept)
                        if (admitted == null) shown.remove() else entry.setValue(admitted)
                    }
                }
                publishLocked()
            }
        }
    }

    private fun publishLocked() {
        live?.let(::pruneLocked)
        val items = entries.values.toList()
        mutableState.update { it.copy(items = items) }
    }

    /**
     * Trims what [live] remembers about sessions to [MAX_KNOWN_SESSIONS], least recently used
     * first, keeping every session that has items. Under [lock].
     */
    private fun pruneLocked(live: Live) {
        if (live.sessions.size <= MAX_KNOWN_SESSIONS && live.renames.size <= MAX_KNOWN_SESSIONS) return
        val used = entries.values.mapTo(HashSet()) { it.sessionId }
        trim(live.sessions, used)
        trim(live.renames, used)
    }

    private fun trim(map: LinkedHashMap<SessionId, *>, used: Set<SessionId>) {
        val keys = map.keys.iterator()
        while (map.size > MAX_KNOWN_SESSIONS && keys.hasNext()) {
            if (keys.next() !in used) keys.remove()
        }
    }

    override fun toString(): String = "DefaultPendingPresenter"

    companion object {
        /** The root sessions asked for per page, to learn the folders they are in. */
        const val SESSION_PAGE_SIZE: Int = 100

        /** The most pages of root sessions read; folders of older sessions are left out. */
        const val MAX_SESSION_PAGES: Int = 10

        /**
         * The most sessions without items whose title and parent are remembered per connection;
         * sessions with items are always kept.
         */
        const val MAX_KNOWN_SESSIONS: Int = 512

        /** The most deleted sessions remembered per connection, against late answers. */
        const val MAX_DELETED: Int = 1024

        /** The deepest parent chain followed; deeper ancestors are not checked. */
        private const val MAX_DEPTH = 16

        private fun formKey(id: String) = "form:$id"

        private fun permissionKey(id: String) = "permission:$id"

        /** What the list follows; `server.connected` arrives on every stream whatever the filter. */
        private val EVENT_TYPES = setOf(
            "form.created",
            "form.replied",
            "form.cancelled",
            "permission.asked",
            "permission.replied",
            "session.deleted",
            "session.renamed",
        )

        private fun PendingItem.withSession(session: Session?): PendingItem = when (this) {
            is PendingItem.FormItem -> copy(sessionTitle = session?.title, parentId = session?.parentId)
            is PendingItem.PermissionItem -> copy(sessionTitle = session?.title, parentId = session?.parentId)
        }

        private fun PendingItem.titled(title: String?): PendingItem = when (this) {
            is PendingItem.FormItem -> copy(sessionTitle = title)
            is PendingItem.PermissionItem -> copy(sessionTitle = title)
        }
    }
}
