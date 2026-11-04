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
import kotlinx.coroutines.flow.combine
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
 * permissions decide what can be read. The listing starts at `/`: the SFTP client offers no way to
 * learn the home folder (no `realpath`, and paths are relative to the root, not to the login
 * folder), and the presenter is not told the user name. A folder shows its subfolders and the
 * symlinks that lead to a folder, by name ignoring case; files and broken links are left out.
 *
 * Paths are kept and stored in the canonical form of [FolderPaths]. `Up` takes the parent by the
 * text of the path, and stops at `/`.
 *
 * Errors: [FolderError.PermissionDenied] and [FolderError.NotFound] as SFTP reports them;
 * [FolderError.NotConnected] without an active server, without a standing connection, or when the
 * SSH session ends under a request; [FolderError.Failed] for anything else. The folder shown stays
 * after an error, and so does the client, unless the error came from the connection: then the
 * client is closed, and the next request ([FolderIntent.Retry]) opens a new one. A client opened on
 * an earlier connection is replaced too, as the manager closes it when its connection ends, and so
 * is a client opened while another profile was active: a client belongs to one connection and one
 * profile, and a listing belongs to its client's profile. After the active profile changes, a
 * client is opened only on a connection newer than every one seen before the change (see
 * [admit]); until then browsing shows [FolderError.NotConnected].
 *
 * Recent folders follow [RecentFoldersStore.observe] for [ActiveServer]'s profile. A storage
 * failure shows [FolderError.StorageFailed], which a later listing does not clear; a failed read
 * is tried again a second later, and again after each further failure. Selecting a folder stores it and then reports it, even when storing failed: the pick matters more than the
 * history. It is stored under the profile active at the pick, and refused with
 * [FolderError.Failed] when that is not the profile the folder shown was listed for.
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
    private var client: SftpClient? = null

    /** Guarded by [lock]: the [ConnectionState.Connected.generation] [client] was opened in. */
    private var clientGeneration = 0L

    /** Guarded by [lock]: the active profile [client] was opened for. */
    private var clientProfile: ServerProfileId? = null

    /** Guarded by [lock]: [scope] ended, so no client may be kept. */
    private var closed = false

    /**
     * Guarded by [lock]: the profile of the client that listed the folder shown, or null before the
     * first listing. A pick is stored only under this profile.
     */
    private var shownProfile: ServerProfileId? = null

    /** Guarded by [lock]: the active profile [admit] last saw; see [admit]. */
    private var admittedProfile: ServerProfileId? = null

    /** Guarded by [lock]: whether [admit] has seen a profile yet. */
    private var profileSeen = false

    /** Guarded by [lock]: the oldest connection generation that may serve [admittedProfile]. */
    private var minGeneration = Long.MIN_VALUE

    /** Guarded by [lock]: the newest connection generation seen, under any profile. */
    private var newestGeneration = Long.MIN_VALUE

    /** Guarded by [lock]: the listing under way; a newer one cancels it. */
    private var navigation: Job? = null

    /** One listing at a time, so that one client is opened and the shown folder is the last asked. */
    private val browsing = Mutex()

    init {
        // Only reads: the connection standing when the picker opens is trusted for its profile.
        admit(activeServer.id.value, connection.state.value)
        scope.coroutineContext[Job]?.invokeOnCompletion { shutdown() }
        // The screen's scope may be Dispatchers.Main.immediate, which runs a launch at once; the
        // yield hands it back to the dispatcher, so nothing runs inside the constructor.
        scope.launch {
            yield()
            followRecent()
        }
        scope.launch {
            yield()
            // Sees each generation as it comes, so that a later profile change knows how new the
            // connection must be.
            combine(activeServer.id, connection.state, ::Pair).collect { (profile, state) -> admit(profile, state) }
        }
        browse(FolderPaths.ROOT)
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
            FolderIntent.Retry -> browse(mutableState.value.path ?: FolderPaths.ROOT)
            FolderIntent.DismissError -> mutableState.update { it.copy(error = null) }
            FolderIntent.Close -> eventChannel.trySend(FolderEvent.Close)
        }
    }

    /** [path] in canonical form, below the folder shown when relative; null, with an error, if malformed. */
    private fun canonical(path: String): String? {
        val canonical = FolderPaths.canonical(path, base = mutableState.value.path ?: FolderPaths.ROOT)
        if (canonical == null) mutableState.update { it.copy(error = FolderError.Failed) }
        return canonical
    }

    private fun browse(path: String) {
        synchronized(lock) {
            navigation?.cancel()
            navigation = scope.launch {
                yield()
                browsing.withLock { show(path) }
            }
        }
    }

    private suspend fun show(path: String) {
        mutableState.update { it.copy(loading = true) }
        try {
            val (profile, entries) = list(path)
            currentCoroutineContext().ensureActive()
            synchronized(lock) { shownProfile = profile }
            mutableState.update { current ->
                current.copy(
                    loading = false,
                    path = path,
                    entries = entries,
                    // A storage failure is not about browsing; it stays until dismissed.
                    error = current.error.takeIf { it == FolderError.StorageFailed },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An SFTP failure leaves the channel usable; anything else may have broken it.
            if (e !is SshException.Sftp) discardClient()
            mutableState.update { it.copy(loading = false, error = errorOf(e)) }
        }
    }

    /** The folders in [path], and the profile of the client that listed them. */
    private suspend fun list(path: String): Pair<ServerProfileId, List<FileEntry>> {
        val (profile, sftp) = client()
        val entries = sftp.list(path)
            .mapNotNull { entry -> navigable(sftp, path, entry) }
            .sortedWith(BY_NAME)
        return profile to entries
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
     * The client for the connection standing now and the active profile, with that profile. One
     * opened for an earlier connection or another profile is closed and a new one opened, so that a
     * listing never speaks for a server other than the active one.
     */
    private suspend fun client(): Pair<ServerProfileId, SftpClient> {
        val profile = activeServer.id.value
        val state = connection.state.value
        val connected = state as? ConnectionState.Connected
        if (profile == null || connected == null || !admit(profile, state)) {
            discardClient()
            throw NotConnectedException()
        }
        val current = synchronized(lock) {
            client?.takeIf { clientGeneration == connected.generation && clientProfile == profile }
        }
        if (current != null) return profile to current
        discardClient()
        val opened = connection.openSftp(FolderPaths.ROOT)
        val keep = synchronized(lock) {
            if (!closed) {
                client = opened
                clientGeneration = connected.generation
                clientProfile = profile
            }
            !closed
        }
        if (!keep) {
            closeQuietly(opened)
            throw CancellationException("The folder picker has ended")
        }
        return profile to opened
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

    /**
     * Whether [state] is a connection that may serve [profile], the active profile; it also records
     * what it sees. The rule:
     *
     * - The first profile seen, when the presenter is built, is served by the connection standing
     *   then and by any later one: the picker opens on a server already connected.
     * - Once the active profile changes, only a `Connected` generation newer than every generation
     *   seen before the change serves it. The connection flow sets [ActiveServer] just before it
     *   connects, so for a moment the previous server's connection still stands under the new
     *   profile; its generation is not new enough, and browsing shows
     *   [FolderError.NotConnected] until the new connection stands. Generations only grow, so a
     *   Retry then works.
     *
     * Generations are seen here when the presenter is built, by a collector of both flows, and on
     * every request. A connection that came and went unseen between two of these cannot be told
     * apart; that would need the manager to say which profile it is connected to.
     */
    private fun admit(profile: ServerProfileId?, state: ConnectionState): Boolean = synchronized(lock) {
        if (!profileSeen) {
            profileSeen = true
            admittedProfile = profile
        } else if (profile != admittedProfile) {
            admittedProfile = profile
            minGeneration = if (newestGeneration == Long.MIN_VALUE) Long.MIN_VALUE else newestGeneration + 1
        }
        val generation = (state as? ConnectionState.Connected)?.generation ?: return@synchronized false
        newestGeneration = maxOf(newestGeneration, generation)
        profile != null && generation >= minGeneration
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
            mutableState.update { it.copy(error = FolderError.StorageFailed) }
            emit(emptyList())
            delay(RECENT_RETRY_DELAY)
            true
        }

    /**
     * The profile is read here, when the user picks, not after a launch: a server switch in between
     * must not put this server's folder in another server's history. A pick made while another
     * server is active than the one the folder shown was listed for is refused.
     */
    private fun select(requested: String) {
        val id = activeServer.id.value
        if (id == null) {
            mutableState.update { it.copy(error = FolderError.NotConnected) }
            return
        }
        val listedFor = synchronized(lock) { shownProfile }
        if (listedFor != null && listedFor != id) {
            mutableState.update { it.copy(error = FolderError.Failed) }
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
            mutableState.update { it.copy(error = FolderError.StorageFailed) }
        }
    }

    private companion object {
        /** How long to wait before reading recent folders again after a failed read. */
        val RECENT_RETRY_DELAY = 1.seconds

        val BY_NAME: Comparator<FileEntry> =
            compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }

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
