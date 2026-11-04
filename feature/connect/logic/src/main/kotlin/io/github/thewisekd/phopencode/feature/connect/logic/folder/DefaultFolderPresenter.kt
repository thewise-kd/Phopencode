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
 * permissions decide what can be read. The listing starts at `/`: the SFTP client offers no way to
 * learn the home folder (no `realpath`, and paths are relative to the root, not to the login
 * folder), and the presenter is not told the user name. A folder shows its subfolders and the
 * symlinks that lead to a folder, by name ignoring case; files and broken links are left out.
 *
 * Paths are kept and stored in the canonical form of [FolderPaths]. `Up` takes the parent by the
 * text of the path, and stops at `/`.
 *
 * Errors: [FolderError.PermissionDenied] and [FolderError.NotFound] as SFTP reports them;
 * [FolderError.NotConnected] without an active server, without a connection that serves it (see
 * below), or when the SSH session ends under a request; [FolderError.Failed] for anything else. The
 * folder shown stays after an error, and so does the client, unless the error came from the
 * connection: then the client is closed, and the next request ([FolderIntent.Retry]) opens a new
 * one.
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
    private var client: SftpClient? = null

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
     * A client on the connection standing now, with the profile that connection was made for, which
     * is the active profile. A client opened on an earlier connection is closed and a new one
     * opened; without a connection that serves the active profile, the client is closed and
     * [NotConnectedException] thrown. See [serving].
     */
    private suspend fun client(): Pair<ServerProfileId, SftpClient> {
        while (true) {
            val connected = serving() ?: run {
                discardClient()
                throw NotConnectedException()
            }
            val (current, stale) = synchronized(lock) {
                val matches = clientGeneration == connected.generation && clientProfile == connected.server.profileId
                if (matches && client != null) client to false else null to (client != null)
            }
            if (current != null) return connected.server.profileId to current
            if (stale) {
                // Closing suspends, and the connection or the active server may change meanwhile:
                // read both again.
                discardClient()
                continue
            }
            val opened = connection.openSftp(FolderPaths.ROOT)
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
            return connected.server.profileId to opened
        }
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
            mutableState.update { it.copy(error = FolderError.StorageFailed) }
            emit(emptyList())
            delay(RECENT_RETRY_DELAY)
            true
        }

    /**
     * The pick is stored under the profile of the connection that listed the folder shown, and only
     * while that is still the active profile; after a switch to another profile it is refused with
     * [FolderError.Failed]. Before any listing has finished (a recent folder tapped while `/` loads,
     * or after it failed), it is stored under the profile of the connection serving the active
     * profile now (see [serving]), and refused with [FolderError.NotConnected] when none does.
     * Everything is read here, when the user picks, not after a launch, so a server switch in
     * between cannot put this server's folder in another server's history.
     */
    private fun select(requested: String) {
        val active = activeServer.id.value
        val listedFor = synchronized(lock) { shownProfile }
        if (active == null) {
            mutableState.update { it.copy(error = FolderError.NotConnected) }
            return
        }
        if (listedFor != null && listedFor != active) {
            mutableState.update { it.copy(error = FolderError.Failed) }
            return
        }
        // The profile read above, not read again: a switch in between must not change whose it is.
        val id = listedFor ?: serving(active)?.server?.profileId
        if (id == null) {
            mutableState.update { it.copy(error = FolderError.NotConnected) }
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
