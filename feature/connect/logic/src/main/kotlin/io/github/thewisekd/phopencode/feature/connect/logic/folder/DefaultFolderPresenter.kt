package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ActiveServer
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Browses the server of the active connection over SFTP and picks the project folder (P4.12).
 *
 * Browsing: one [SftpClient] with root `/`, so the whole server can be browsed; the server's own
 * permissions decide what can be read. The first listing starts at the login user's home, as
 * [SftpClient.home] reports it, in the canonical form of [FolderPaths] (P4.22). When asking for it
 * fails, or its answer has no canonical form, the listing starts at `/` instead, and that alone
 * shows no error. A home that is reported but cannot be listed shows the listing's error, like any
 * other folder. Until a listing has finished, [FolderIntent.Retry] starts at home again. A folder
 * shows its subfolders and the symlinks that lead to a folder, by name ignoring case; files and
 * broken links are left out.
 *
 * Paths are kept and stored in the canonical form of [FolderPaths]. `Up` takes the parent by the
 * text of the path, and stops at `/`.
 *
 * Errors: [FolderError.PermissionDenied] and [FolderError.NotFound] as SFTP reports them;
 * [FolderError.NotConnected] without an active server, without a connection that serves it (see
 * below), or when the SSH session ends under a request; [FolderError.Failed] for anything else, with
 * the failure's class chain in [FolderState.errorDetail] (see [FailureDetail]; nothing is logged).
 * While the connection state is [ConnectionState.Error], [FolderError.NotConnected] carries that
 * error in [FolderState.errorDetail] (see [ConnectionDetail]); while it is [ConnectionState.Idle] or
 * [ConnectionState.Closed], exactly `Idle` or `Closed` (P9.24); while a connect or reconnect is
 * under way, none.
 * The detail follows the state while the error shows: it appears, changes or goes as the state
 * turns to an error, to another error, or away from one (P9.22). A
 * new error, [FolderIntent.Retry], [FolderIntent.DismissError] or a listing clears the detail. The
 * folder shown stays after an error, and so does the client, unless the error came from the
 * connection: then the client is closed, and the next request ([FolderIntent.Retry]) opens a new
 * one.
 *
 * A listing that fails while, or because, its client's connection no longer serves the active
 * profile shows [FolderError.NotConnected], whatever the failure: no connection serves it when the
 * failure is handled, the one serving it has another generation or profile than the client, that
 * connection has ended though the state does not show it yet, or the failure is the
 * [IllegalStateException] of a client the presenter or the manager already closed (P9.21). Other
 * failures on the same connection keep their own error and detail. While
 * [FolderError.NotConnected] shows alone, with no listing under way, a connection with a newer
 * generation that serves the active profile lists again by itself, once per generation: the folder
 * shown, or the login home when none was. A listing that fails again waits for the next generation.
 *
 * Which server: the connection names its own profile in [ConnectionState.Connected.server]. A
 * client is opened or used only while the state is `Connected`, its `server.profileId` is
 * [ActiveServer]'s profile, and its generation is the one the client was opened on; otherwise the
 * client is closed and browsing shows [FolderError.NotConnected]. So the previous server's
 * connection, still standing for a moment after the active server changes, is never browsed under
 * the new one, and the new server's connection serves on the next request whichever of the two
 * changes came first. The state is read again after every suspension that closes a client, and
 * checked again after a client is opened: a client whose connection changed while it opened is
 * closed unused. A listing belongs to the profile of the connection whose client made it.
 *
 * Recent folders follow [RecentFoldersStore.observe] for [ActiveServer]'s profile. A storage
 * failure shows [FolderError.StorageFailed], which a later listing does not clear; a failed read
 * is tried again a second later, and again after each further failure. Selecting a folder stores it and then reports it, even when storing failed: the pick matters more than the
 * history. It is stored under the profile the folder shown was listed for, and only while that
 * is still the active profile, refused with [FolderError.Failed] after a server switch. Before any
 * listing has finished it is stored under the profile of the connection serving the active
 * profile at the pick, and refused with [FolderError.NotConnected] when none does.
 *
 * Nothing runs in the constructor; when [scope] ends, the client is closed.
 *
 * @param connection Browses through `ConnectionManager.openSftp`, which fails with
 *   `NotConnectedException` when no connection stands.
 * @param activeServer Whose recent folders to show and add to.
 * @param closer Closes SFTP clients; a close may wait for the server, so not on the screen's thread.
 */
class DefaultFolderPresenter(
    private val connection: ConnectionManager,
    private val activeServer: ActiveServer,
    private val recentFolders: RecentFoldersStore,
    private val scope: CoroutineScope,
    private val closer: CoroutineDispatcher = Dispatchers.IO,
) : FolderPresenter {
    private val mutableState = MutableStateFlow(FolderState())

    override val state: StateFlow<FolderState> = mutableState.asStateFlow()

    // Unlimited, so that onIntent can send without suspending and no event is dropped.
    private val eventChannel = Channel<FolderEvent>(Channel.UNLIMITED)

    override val events: Flow<FolderEvent> = eventChannel.receiveAsFlow()

    private val lock = Any()

    /** Guarded by [lock]: the open client, or null. */
    private var client: OwnedSftp? = null

    /** Guarded by [lock]: the [ConnectionState.Connected.generation] [client] was opened in. */
    private var clientGeneration = 0L

    /** Guarded by [lock]: the [ConnectionState.Connected.server] profile [client] was opened on. */
    private var clientProfile: ServerProfileId? = null

    /** Guarded by [lock]: [scope] ended, so no client may be kept. */
    private var closed = false

    /**
     * Guarded by [lock]: the `Connected.server.profileId` of the client that listed the folder
     * shown, or null before the first listing. A pick is stored only under this profile.
     */
    private var shownProfile: ServerProfileId? = null

    /** Guarded by [lock]: the listing under way; a newer one cancels it. */
    private var navigation: Job? = null

    /**
     * Guarded by [lock]: the newest [ConnectionState.Connected.generation] a listing that ended in
     * [FolderError.NotConnected] was made on, or 0. Only a newer generation lists again by itself.
     */
    private var lostGeneration = 0L

    /** Guarded by [lock]: the newest generation that has listed again by itself, or 0. */
    private var relistedGeneration = 0L

    /** One listing at a time, so that one client is opened and the shown folder is the last asked. */
    private val browsing = Mutex()

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { shutdown() }
        // The screen's scope may be Dispatchers.Main.immediate, which runs a launch at once; the
        // yield hands it back to the dispatcher, so nothing runs inside the constructor.
        scope.launch {
            yield()
            followRecent()
        }
        scope.launch {
            yield()
            connection.state.collect { state ->
                if (state is ConnectionState.Connected) relistIfReconnected(self = null)
                // The state read now, not the one collected: a newer one may have come meanwhile.
                followConnectionError()
            }
        }
        browse(null)
    }

    override fun onIntent(intent: FolderIntent) {
        when (intent) {
            is FolderIntent.Open -> canonical(intent.path)?.let(::browse)
            FolderIntent.Up -> {
                val current = mutableState.value.path ?: return
                if (current != FolderPaths.ROOT) browse(FolderPaths.parent(current))
            }
            is FolderIntent.Select -> select(intent.path)
            is FolderIntent.RemoveRecent -> removeRecent(intent.path)
            FolderIntent.Retry -> {
                // Browsing errors give way to the new listing; a storage failure is not about it.
                mutableState.update { state ->
                    state.withError(state.error.takeIf { it == FolderError.StorageFailed })
                }
                browse(mutableState.value.path)
            }
            FolderIntent.DismissError -> mutableState.update { it.withError(null) }
            FolderIntent.Close -> eventChannel.trySend(FolderEvent.Close)
        }
    }

    /** [path] in canonical form, below the folder shown when relative; null, with an error, if malformed. */
    private fun canonical(path: String): String? {
        val canonical = FolderPaths.canonical(path, base = mutableState.value.path ?: FolderPaths.ROOT)
        if (canonical == null) mutableState.update { it.withError(FolderError.Failed) }
        return canonical
    }

    /** Lists [path], or the login home when null. */
    private fun browse(path: String?) {
        synchronized(lock) {
            navigation?.cancel()
            navigation = scope.launch {
                yield()
                browsing.withLock { show(path) }
                relistIfReconnected(self = currentCoroutineContext()[Job])
            }
        }
    }

    /**
     * Lists again, once, when [FolderError.NotConnected] shows alone and a connection with a newer
     * generation than the lost one serves the active profile: the folder shown, or the login home
     * when none was. A listing under way, other than [self], is left alone.
     */
    private fun relistIfReconnected(self: Job?) {
        val connected = serving() ?: return
        // Under the lock, so that a navigation the user starts meanwhile is either seen here or
        // starts after this one and replaces it.
        synchronized(lock) {
            if (closed) return
            val running = navigation
            if (running != null && running !== self && running.isActive) return
            if (connected.generation <= lostGeneration || connected.generation <= relistedGeneration) return
            val state = mutableState.value
            if (state.error != FolderError.NotConnected || state.loading) return
            relistedGeneration = connected.generation
            mutableState.update { if (it.error == FolderError.NotConnected) it.withError(null) else it }
            browse(state.path)
        }
    }

    private suspend fun show(requested: String?) {
        mutableState.update { it.copy(loading = true) }
        val attempt = Attempt(startGeneration = (connection.state.value as? ConnectionState.Connected)?.generation ?: 0L)
        try {
            val (profile, path, entries) = list(requested, attempt)
            currentCoroutineContext().ensureActive()
            synchronized(lock) { shownProfile = profile }
            mutableState.update { current ->
                current.copy(
                    loading = false,
                    path = path,
                    entries = entries,
                    // A storage failure is not about browsing; it stays until dismissed.
                    error = current.error.takeIf { it == FolderError.StorageFailed },
                    errorDetail = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val lost = lostConnection(e, attempt)
            // An SFTP failure leaves the channel usable; anything else may have broken it.
            if (lost || e !is SshException.Sftp) discardClient()
            val error = if (lost) FolderError.NotConnected else errorOf(e)
            if (error == FolderError.NotConnected) {
                synchronized(lock) {
                    lostGeneration = maxOf(lostGeneration, attempt.startGeneration, attempt.generation ?: 0L)
                }
            }
            // Only class names, never a message: see FailureDetail and ConnectionDetail.
            val detail = if (error == FolderError.Failed) FailureDetail.of(e) else null
            mutableState.update { it.copy(loading = false).withError(error, detail).withConnectionDetail() }
            if (error == FolderError.NotConnected) followConnectionError()
        }
    }

    /**
     * Whether [failure] of [attempt] came from a connection that no longer serves the active
     * profile: none serves it now, or the one serving it is not the connection, by generation and
     * profile, the attempt's client was opened on, or that connection has ended even though the state
     * does not say so yet (asked through [ConnectionManager.withConnection]); or [failure] is the
     * [IllegalStateException] of a client already closed, by this presenter or by the manager with
     * its connection. Any other failure on the same connection is the listing's own.
     */
    private suspend fun lostConnection(failure: Exception, attempt: Attempt): Boolean {
        if (failure is NotConnectedException) return true
        val now = serving() ?: return true
        val generation = attempt.generation ?: return false
        if (now.generation != generation || now.server.profileId != attempt.profile) return true
        if (failure is IllegalStateException && attempt.client?.closed == true) return true
        val live = try {
            connection.withConnection { it.generation to it.server.profileId }
        } catch (_: NotConnectedException) {
            null
        }
        return live != generation to attempt.profile
    }

    /**
     * The profile of the client that listed, the folder listed ([requested], or the login home when
     * null), and the folders in it. The client used, and its connection, are noted in [attempt].
     */
    private suspend fun list(
        requested: String?,
        attempt: Attempt,
    ): Triple<ServerProfileId, String, List<FileEntry>> {
        val (profile, sftp) = client(attempt)
        val path = requested ?: home(sftp)
        val entries = sftp.list(path)
            .mapNotNull { entry -> navigable(sftp, path, entry) }
            .sortedWith(BY_NAME)
        return Triple(profile, path, entries)
    }

    /**
     * The login home in canonical form, or `/` when asking fails or the answer has no canonical
     * form. A failure that broke the client shows on the listing that follows.
     */
    private suspend fun home(sftp: SftpClient): String = try {
        FolderPaths.canonical(sftp.home()) ?: FolderPaths.ROOT
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        FolderPaths.ROOT
    }

    /** [entry] with its canonical path if it can be opened as a folder, else null. */
    private suspend fun navigable(sftp: SftpClient, folder: String, entry: FileEntry): FileEntry? {
        val path = FolderPaths.child(folder, entry.name)
        return when (entry.type) {
            FileEntryType.Directory -> entry.copy(path = path)
            FileEntryType.Symlink -> try {
                entry.copy(path = path).takeIf { sftp.stat(path).type == FileEntryType.Directory }
            } catch (_: SshException.Sftp) {
                // A broken link, or one whose target cannot be looked at.
                null
            }
            else -> null
        }
    }

    /**
     * A client on the connection standing now, with the profile that connection was made for, which
     * is the active profile. A client opened on an earlier connection is closed and a new one
     * opened; without a connection that serves the active profile, the client is closed and
     * [NotConnectedException] thrown. See [serving]. The client returned, and its connection, are
     * noted in [attempt].
     */
    private suspend fun client(attempt: Attempt): Pair<ServerProfileId, SftpClient> {
        while (true) {
            val connected = serving() ?: run {
                discardClient()
                throw NotConnectedException()
            }
            val (current, stale) = synchronized(lock) {
                val matches = clientGeneration == connected.generation && clientProfile == connected.server.profileId
                if (matches && client != null) client to false else null to (client != null)
            }
            if (current != null) {
                attempt.used(current, connected)
                return connected.server.profileId to current
            }
            if (stale) {
                // Closing suspends, and the connection or the active server may change meanwhile:
                // read both again.
                discardClient()
                continue
            }
            val opened = OwnedSftp(connection.openSftp(FolderPaths.ROOT))
            // openSftp opens on whatever connection stands when it runs; keep the client only if
            // that is still the one checked above, so it is never credited to another profile.
            val now = serving()
            val same = now != null && now.generation == connected.generation &&
                now.server.profileId == connected.server.profileId
            val ended = synchronized(lock) {
                if (!closed && same) {
                    client = opened
                    clientGeneration = connected.generation
                    clientProfile = connected.server.profileId
                }
                closed
            }
            if (ended) {
                closeQuietly(opened)
                throw CancellationException("The folder picker has ended")
            }
            if (!same) {
                closeQuietly(opened)
                throw NotConnectedException()
            }
            attempt.used(opened, connected)
            return connected.server.profileId to opened
        }
    }

    /**
     * This state with the detail of [FolderError.NotConnected], when that shows, set to the
     * connection state read now (see [ConnectionDetail.ofState]): the error of
     * [ConnectionState.Error], `Idle` or `Closed`, and null while a connect or reconnect is under
     * way. Any other error keeps its own detail.
     *
     * Called only inside [mutableState]'s `update`, so the state is read inside the compare-and-set:
     * a write that loses the race runs again and reads the state again, and the last write that
     * lands has read the state after every write before it. It reads and changes nothing else.
     */
    private fun FolderState.withConnectionDetail(): FolderState {
        if (error != FolderError.NotConnected) return this
        val detail = ConnectionDetail.ofState(connection.state.value)
        return if (errorDetail == detail) this else copy(errorDetail = detail)
    }

    /**
     * Brings the detail of [FolderError.NotConnected], while it shows, to the connection state now
     * (see [withConnectionDetail]). Called by the state watcher on every state, loading or not, and
     * by whoever publishes [FolderError.NotConnected], right after publishing it. So no state change
     * is missed: one made before this read is in it, and one made after it reaches the watcher,
     * which then finds the error published.
     */
    private fun followConnectionError() {
        mutableState.update { it.withConnectionDetail() }
    }

    /**
     * The connection standing now if it serves [profile], by default [ActiveServer]'s profile read
     * now: the state is `Connected` and its `server.profileId` is that profile. Null otherwise,
     * including without an active profile. A caller that has already read the active profile passes
     * it, so that a switch between two reads cannot mix two profiles.
     */
    private fun serving(profile: ServerProfileId? = activeServer.id.value): ConnectionState.Connected? {
        if (profile == null) return null
        val connected = connection.state.value as? ConnectionState.Connected ?: return null
        return connected.takeIf { it.server.profileId == profile }
    }

    private suspend fun discardClient() {
        val old = synchronized(lock) { client.also { client = null } } ?: return
        closeQuietly(old)
    }

    private suspend fun closeQuietly(sftp: SftpClient) {
        withContext(closer + NonCancellable) {
            try {
                sftp.close()
            } catch (_: Exception) {
                // Closing is best effort; the manager closes it with its connection anyway.
            }
        }
    }

    /** Runs once [scope] has ended: closes the client off the screen's thread. */
    private fun shutdown() {
        val old = synchronized(lock) {
            closed = true
            client.also { client = null }
        } ?: return
        CoroutineScope(closer).launch { closeQuietly(old) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun followRecent() {
        activeServer.id
            .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else recentOf(id) }
            .collect { recent -> mutableState.update { it.copy(recent = recent) } }
    }

    private fun recentOf(id: ServerProfileId): Flow<List<String>> =
        // observe() itself may throw rather than return a failing flow.
        flow { emitAll(recentFolders.observe(id)) }.retryWhen { e, _ ->
            if (e is CancellationException) return@retryWhen false
            // Shown at once, then read again after a pause: a store that failed once may recover,
            // and the delay keeps a store that keeps failing from being asked in a loop.
            mutableState.update { it.withError(FolderError.StorageFailed) }
            emit(emptyList())
            delay(RECENT_RETRY_DELAY)
            true
        }

    /**
     * The pick is stored under the profile of the connection that listed the folder shown, and only
     * while that is still the active profile; after a switch to another profile it is refused with
     * [FolderError.Failed]. Before any listing has finished (a recent folder tapped while the first
     * folder loads, or after it failed), it is stored under the profile of the connection serving the active
     * profile now (see [serving]), and refused with [FolderError.NotConnected] when none does.
     * Everything is read here, when the user picks, not after a launch, so a server switch in
     * between cannot put this server's folder in another server's history.
     */
    private fun select(requested: String) {
        val active = activeServer.id.value
        val listedFor = synchronized(lock) { shownProfile }
        if (active == null) {
            mutableState.update { it.withError(FolderError.NotConnected).withConnectionDetail() }
            followConnectionError()
            return
        }
        if (listedFor != null && listedFor != active) {
            mutableState.update { it.withError(FolderError.Failed) }
            return
        }
        // The profile read above, not read again: a switch in between must not change whose it is.
        val id = listedFor ?: serving(active)?.server?.profileId
        if (id == null) {
            mutableState.update { it.withError(FolderError.NotConnected).withConnectionDetail() }
            followConnectionError()
            return
        }
        val path = canonical(requested) ?: return
        scope.launch {
            storing { recentFolders.add(id, path) }
            eventChannel.trySend(FolderEvent.Selected(path))
        }
    }

    /** As in [select], the profile is the one active when the user asks. */
    private fun removeRecent(path: String) {
        val id = activeServer.id.value ?: return
        scope.launch { storing { recentFolders.remove(id, path) } }
    }

    /** Runs [block]; a failure shows [FolderError.StorageFailed]. Cancellation passes through. */
    private suspend fun storing(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.withError(FolderError.StorageFailed) }
        }
    }

    /**
     * One listing: the [ConnectionState.Connected.generation] standing when it started (0 if none
     * did), and the client it used with the generation and profile that client was opened on, once
     * it has one.
     */
    private class Attempt(val startGeneration: Long) {
        var client: OwnedSftp? = null
            private set
        var generation: Long? = null
            private set
        var profile: ServerProfileId? = null
            private set

        fun used(sftp: OwnedSftp, connected: ConnectionState.Connected) {
            client = sftp
            generation = connected.generation
            profile = connected.server.profileId
        }
    }

    /** A client of this presenter, which remembers that it was closed. */
    private class OwnedSftp(private val inner: SftpClient) : SftpClient by inner {
        @Volatile
        var closed = false
            private set

        override fun close() {
            closed = true
            inner.close()
        }
    }

    private companion object {
        /** How long to wait before reading recent folders again after a failed read. */
        val RECENT_RETRY_DELAY = 1.seconds

        val BY_NAME: Comparator<FileEntry> =
            compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }

        /** [error] in place of the one shown; [detail] goes with it, so no detail outlives its error. */
        fun FolderState.withError(error: FolderError?, detail: String? = null): FolderState =
            copy(error = error, errorDetail = detail)

        fun errorOf(e: Exception): FolderError = when (e) {
            is NotConnectedException, is SshException.Disconnected -> FolderError.NotConnected
            is SshException.Sftp -> when (e.failure) {
                SftpFailure.PermissionDenied -> FolderError.PermissionDenied
                SftpFailure.NotFound -> FolderError.NotFound
                else -> FolderError.Failed
            }
            else -> FolderError.Failed
        }
    }
}
