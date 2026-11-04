package io.github.thewisekd.phopencode.feature.connect.logic.serverlist

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * The saved servers, most recently used first. It follows [ProfileRepository.observe] from the
 * moment its scope runs, so changes made on other screens show up here.
 *
 * Selecting a server saves it with [clock]'s time as its last use, which moves it on top, and only
 * then opens the connection flow. Deleting a server removes the profile, then forgets its recent
 * folders and deletes the secrets only it refers to. Storage failures become the state's
 * [ServerListError], never a crash. Select and delete run one at a time, so a selection cannot
 * save back a server that a delete has just removed.
 *
 * @param secrets Deleting a server deletes the secrets only it refers to.
 * @param keyIndex A key's secrets belong to the index, so deleting a server leaves them.
 * @param recentFolders Deleting a server forgets its recent folders.
 * @param clock Marks a selected server as used.
 */
class DefaultServerListPresenter(
    private val profiles: ProfileRepository,
    private val secrets: SecretStore,
    private val keyIndex: SshKeyIndex,
    private val recentFolders: RecentFoldersStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : ServerListPresenter {
    private val mutableState = MutableStateFlow(ServerListState())

    override val state: StateFlow<ServerListState> = mutableState.asStateFlow()

    // Unlimited, so that onIntent can send without suspending and no event is dropped.
    private val eventChannel = Channel<ServerListEvent>(Channel.UNLIMITED)

    override val events: Flow<ServerListEvent> = eventChannel.receiveAsFlow()

    /** Serializes the writes that select and delete make. */
    private val writes = Mutex()

    init {
        // The screen's scope may be Dispatchers.Main.immediate, which runs a launch at once; the
        // yield hands it back to the dispatcher, so nothing runs inside the constructor.
        scope.launch {
            yield()
            load()
        }
    }

    override fun onIntent(intent: ServerListIntent) {
        when (intent) {
            is ServerListIntent.Select -> scope.launch { select(intent.id) }
            ServerListIntent.Add -> send(ServerListEvent.OpenForm(null))
            is ServerListIntent.Edit -> send(ServerListEvent.OpenForm(intent.id))
            ServerListIntent.OpenKeys -> send(ServerListEvent.OpenKeys)
            ServerListIntent.OpenSettings -> send(ServerListEvent.OpenSettings)
            is ServerListIntent.RequestDelete -> mutableState.update { current ->
                current.copy(pendingDelete = current.servers.firstOrNull { it.id == intent.id })
            }
            ServerListIntent.ConfirmDelete -> confirmDelete()
            ServerListIntent.CancelDelete -> mutableState.update { it.copy(pendingDelete = null) }
            ServerListIntent.DismissError -> mutableState.update { it.copy(error = null) }
        }
    }

    private fun send(event: ServerListEvent) {
        eventChannel.trySend(event)
    }

    private suspend fun load() {
        // observe() itself may throw rather than return a failing flow.
        val updates = attempt(ServerListError.LoadFailed) { profiles.observe() }
        if (updates == null) {
            mutableState.update { it.copy(loading = false) }
            return
        }
        updates
            .catch { e ->
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = ServerListError.LoadFailed) }
            }
            .collect { list ->
                val sorted = list.sortedWith(ProfileRepository.MOST_RECENTLY_USED_FIRST)
                mutableState.update { current ->
                    current.copy(
                        loading = false,
                        servers = sorted,
                        // A pending server deleted or changed elsewhere follows the repository.
                        pendingDelete = current.pendingDelete?.let { pending -> sorted.firstOrNull { it.id == pending.id } },
                    )
                }
            }
    }

    private suspend fun select(id: ServerProfileId) {
        val selected = writes.withLock {
            attempt(ServerListError.SaveFailed) {
                if (profiles.list().none { it.id == id }) return@attempt false
                val usedAt = clock.now()
                // ProfileRepository has no atomic update, and the mutex covers this presenter only.
                // Re-reading right before the save keeps another screen's latest edit and skips a
                // server deleted meanwhile; a change landing between this read and the save is
                // still lost or undone.
                val fresh = profiles.list().firstOrNull { it.id == id } ?: return@attempt false
                profiles.save(fresh.copy(lastUsedAt = usedAt))
                true
            } ?: false
        }
        if (selected) send(ServerListEvent.OpenProgress(id))
    }

    private fun confirmDelete() {
        var target: ServerProfile? = null
        mutableState.update { current ->
            target = current.pendingDelete
            current.copy(pendingDelete = null)
        }
        val profile = target ?: return
        scope.launch { writes.withLock { delete(profile.id) } }
    }

    private suspend fun delete(id: ServerProfileId) {
        // Read everything first, so a failure here leaves the server and its secrets as they were.
        val plan = attempt(ServerListError.DeleteFailed) {
            val all = profiles.list()
            val profile = all.firstOrNull { it.id == id } ?: return@attempt null
            val stillUsed = all.filter { it.id != id }.flatMap { it.secretRefs() }.toSet() +
                keyIndex.list().flatMap { listOfNotNull(it.privateKeyRef, it.passphraseRef) }
            profile.secretRefs().filterNot { it in stillUsed }
        } ?: return

        attempt(ServerListError.DeleteFailed) { profiles.delete(id) } ?: return

        // The profile is gone: try every cleanup step, even after one fails.
        //
        // The plan is a snapshot taken before the delete, so a profile or key saved after it is not
        // seen. That cannot cost another server its secret: the server form (P4.6) and key
        // management (P4.8) always store a secret under a fresh random SecretRef, so no profile or key
        // saved later can adopt a ref planned for deletion here.
        attempt(ServerListError.DeleteFailed) { recentFolders.forget(id) }
        plan.forEach { ref -> attempt(ServerListError.DeleteFailed) { secrets.delete(ref) } }
    }

    /**
     * Runs [block]; a failure shows [error] and returns null. Cancellation passes through. Any
     * exception counts, not only the IOException the stores promise: a storage bug must not crash
     * the app's start screen.
     */
    private suspend fun <T> attempt(error: ServerListError, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        mutableState.update { it.copy(error = error) }
        null
    }

    private fun ServerProfile.secretRefs(): List<SecretRef> {
        val authRefs = when (val auth = auth) {
            is SshAuth.Password -> listOf(auth.secretRef)
            is SshAuth.PrivateKey -> listOfNotNull(auth.keyRef, auth.passphraseRef)
        }
        return authRefs + listOfNotNull(opencodeCredential)
    }
}
