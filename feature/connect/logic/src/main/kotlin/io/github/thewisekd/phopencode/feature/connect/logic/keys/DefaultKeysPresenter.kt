package io.github.thewisekd.phopencode.feature.connect.logic.keys

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.ssh.key.GeneratedKey
import io.github.thewisekd.phopencode.core.ssh.key.SshKeyException
import io.github.thewisekd.phopencode.core.ssh.key.SshKeys
import io.github.thewisekd.phopencode.core.ssh.key.SshPublicKey
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysState
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Key management (P4.8): lists the keys of [SshKeyIndex], generates and imports keys, deletes them
 * and copies their public half.
 *
 * A private key goes only into [SecretStore]: never into the index, the state, an event or a log.
 * Every private key or passphrase buffer this presenter holds, including the content of a
 * [KeysIntent.Import], is wiped once it is no longer needed. Passphrases arrive as `String`s from
 * the contract; those cannot be wiped, only the arrays made from them.
 *
 * Writes go secrets first, index last, so that an index entry never points at a missing secret;
 * when a write fails part-way, the secrets already written are removed again. Deletes go the other
 * way round, index first.
 *
 * Only one generate, import or delete runs at a time; such an intent sent while [KeysState.busy]
 * is dropped (an import's content is wiped).
 *
 * @param secrets Holds each key's private half and passphrase.
 * @param profiles A key a saved server logs in with is not deleted.
 * @param newId Makes the IDs of a new key and of its `SecretRef`s.
 * @param worker Runs key generation and parsing, which are CPU work, off the screen's thread.
 */
class DefaultKeysPresenter(
    private val keyIndex: SshKeyIndex,
    private val secrets: SecretStore,
    private val profiles: ProfileRepository,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : KeysPresenter {
    private val mutableState = MutableStateFlow(KeysState())

    override val state: StateFlow<KeysState> = mutableState.asStateFlow()

    private val eventChannel = Channel<KeysEvent>(Channel.BUFFERED)

    override val events: Flow<KeysEvent> = eventChannel.receiveAsFlow()

    /** An encrypted key waiting for its passphrase. Guarded by [lock]. */
    private var pending: PendingImport? = null
    private val lock = Any()

    init {
        // The screen may go away with a key still waiting; its bytes must not outlive it.
        scope.coroutineContext[Job]?.invokeOnCompletion { takePending()?.wipe() }
        scope.launch {
            // The screen's scope may run at once (Main.immediate): loading never runs in the constructor.
            yield()
            keyIndex.observe()
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    mutableState.update { it.copy(loading = false, error = KeysError.LoadFailed) }
                }
                .collect { keys -> mutableState.update { it.copy(loading = false, keys = keys) } }
        }
    }

    override fun onIntent(intent: KeysIntent) {
        when (intent) {
            is KeysIntent.Generate -> generate(intent.name, intent.passphrase)
            is KeysIntent.Import -> import(intent.name, intent.content)
            is KeysIntent.SubmitPassphrase -> submitPassphrase(intent.passphrase)
            KeysIntent.CancelImport -> cancelImport()
            is KeysIntent.CopyPublicKey -> copyPublicKey(intent.id)
            is KeysIntent.RequestDelete -> requestDelete(intent.id)
            KeysIntent.ConfirmDelete -> confirmDelete()
            KeysIntent.CancelDelete -> mutableState.update { it.copy(pendingDelete = null) }
            KeysIntent.DismissError -> mutableState.update { it.copy(error = null) }
            KeysIntent.Close -> {
                cancelImport()
                eventChannel.trySend(KeysEvent.Close)
            }
        }
    }

    // Generate

    private fun generate(name: String, passphrase: String?) {
        val keyName = name.trim()
        if (keyName.isEmpty() || keyName.any { it.isISOControl() }) {
            mutableState.update { it.copy(error = KeysError.GenerateFailed) }
            return
        }
        if (!claimBusy()) return
        scope.launch { finish(generateAndStore(keyName, passphrase?.takeIf { it.isNotEmpty() })) }
    }

    private suspend fun generateAndStore(name: String, passphrase: String?): KeysError? {
        // Owned from the moment the worker makes it: a cancellation can drop withContext's result,
        // so the key is closed through this holder, never through that result.
        val holder = AtomicReference<GeneratedKey?>()
        try {
            val chars = passphrase?.toCharArray()
            try {
                withContext(worker) {
                    val key = SshKeys.generateEd25519(comment = name, passphrase = chars)
                    holder.set(key)
                    KeyMaterialHooks.generated?.invoke(key)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                return KeysError.GenerateFailed
            } finally {
                chars?.fill(ZERO)
            }
            val key = holder.get() ?: return KeysError.GenerateFailed
            val file = key.privateKeyBytes()
            return try {
                store(name, key.publicKey, file, passphrase)
            } finally {
                file.fill(0)
            }
        } finally {
            holder.getAndSet(null)?.close()
        }
    }

    // Import

    private fun import(name: String, content: ByteArray) {
        if (!claimBusy()) {
            content.fill(0)
            return
        }
        // A new file replaces one still waiting for its passphrase.
        takePending()?.wipe()
        mutableState.update { it.copy(importAwaitingPassphrase = false) }
        // ATOMIC: the body starts even if the scope is cancelled first, so readAndStore always owns
        // and wipes the bytes. The same holds for a submitted passphrase below.
        scope.launch(start = CoroutineStart.ATOMIC) { finish(readAndStore(PendingImport(name, content), passphrase = null)) }
    }

    private fun submitPassphrase(passphrase: String) {
        if (!claimBusy()) return
        val waiting = takePending()
        if (waiting == null) {
            finish(null)
            return
        }
        scope.launch(start = CoroutineStart.ATOMIC) { finish(readAndStore(waiting, passphrase)) }
    }

    private fun cancelImport() {
        if (state.value.busy) return
        takePending()?.wipe()
        mutableState.update { it.copy(importAwaitingPassphrase = false) }
    }

    /**
     * Reads [import] with [passphrase] and stores it. A key that needs a passphrase, or got a wrong
     * one, waits again; in every other case the content is wiped here.
     */
    private suspend fun readAndStore(import: PendingImport, passphrase: String?): KeysError? {
        var keep = false
        try {
            val chars = passphrase?.toCharArray()
            val publicKey = try {
                withContext(worker) { SshKeys.read(import.content, chars).publicKey }
            } catch (failure: SshKeyException.PassphraseRequired) {
                keep = true
                return if (passphrase == null) null else KeysError.WrongPassphrase
            } catch (failure: SshKeyException.WrongPassphrase) {
                keep = true
                return KeysError.WrongPassphrase
            } catch (failure: SshKeyException.Unsupported) {
                return KeysError.ImportUnsupported
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                return KeysError.ImportMalformed
            } finally {
                chars?.fill(ZERO)
            }
            val name = import.name.trim().takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: publicKey.type
            return store(name, publicKey, import.content, passphrase?.takeIf { it.isNotEmpty() })
        } finally {
            if (keep) {
                synchronized(lock) { pending = import }
                mutableState.update { it.copy(importAwaitingPassphrase = true) }
            } else {
                import.wipe()
                mutableState.update { it.copy(importAwaitingPassphrase = false) }
            }
        }
    }

    // Storing

    /**
     * Writes the private key file, then the passphrase, then the index entry. On a failure it
     * removes what it wrote, unless the index entry may exist: then the secrets stay, so that no
     * entry points at a missing secret.
     */
    private suspend fun store(name: String, publicKey: SshPublicKey, file: ByteArray, passphrase: String?): KeysError? {
        val id = SshKeyId(newId())
        val privateRef = SecretRef(newId())
        val passphraseRef = passphrase?.let { SecretRef(newId()) }
        val written = mutableListOf<SecretRef>()
        val entry = try {
            SshKeyEntry(
                id = id,
                name = name,
                type = publicKey.type,
                fingerprint = publicKey.fingerprintSha256,
                publicKey = publicKey.authorizedKeysLine(),
                privateKeyRef = privateRef,
                passphraseRef = passphraseRef,
            )
        } catch (failure: IllegalArgumentException) {
            return KeysError.SaveFailed
        }
        try {
            written += privateRef
            SecretValue.ofBytes(file).use { secrets.put(privateRef, it) }
            if (passphraseRef != null && passphrase != null) {
                written += passphraseRef
                SecretValue.ofText(passphrase).use { secrets.put(passphraseRef, it) }
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) { deleteQuietly(written) }
            if (failure is CancellationException) throw failure
            return KeysError.SaveFailed
        }
        try {
            keyIndex.save(entry)
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                val entryGone = try {
                    keyIndex.delete(id)
                    true
                } catch (cleanup: Exception) {
                    false
                }
                if (entryGone) deleteQuietly(written)
            }
            if (failure is CancellationException) throw failure
            return KeysError.SaveFailed
        }
        return null
    }

    /** @return Whether every secret was removed. */
    private suspend fun deleteQuietly(refs: List<SecretRef>): Boolean {
        var all = true
        for (ref in refs) {
            try {
                secrets.delete(ref)
            } catch (failure: Exception) {
                all = false
            }
        }
        return all
    }

    // Copy and delete

    private fun copyPublicKey(id: SshKeyId) {
        val entry = state.value.keys.firstOrNull { it.id == id } ?: return
        eventChannel.trySend(KeysEvent.CopyPublicKey(entry.publicKey))
    }

    private fun requestDelete(id: SshKeyId) {
        val entry = state.value.keys.firstOrNull { it.id == id } ?: return
        mutableState.update { it.copy(pendingDelete = entry) }
    }

    private fun confirmDelete() {
        val entry = state.value.pendingDelete ?: return
        if (!claimBusy()) return
        mutableState.update { it.copy(pendingDelete = null) }
        scope.launch { finish(delete(entry)) }
    }

    /** Index entry first, then its secrets: an entry never points at a missing secret. */
    private suspend fun delete(entry: SshKeyEntry): KeysError? {
        try {
            val inUse = profiles.list().any { profile ->
                val auth = profile.auth
                auth is SshAuth.PrivateKey && auth.keyRef == entry.privateKeyRef
            }
            if (inUse) return KeysError.KeyInUse
            keyIndex.delete(entry.id)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            return KeysError.DeleteFailed
        }
        val removed = withContext(NonCancellable) { deleteQuietly(listOfNotNull(entry.privateKeyRef, entry.passphraseRef)) }
        return if (removed) null else KeysError.DeleteFailed
    }

    // Busy

    /** Marks the presenter busy; false if it already was. */
    private fun claimBusy(): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.busy) return false
            if (mutableState.compareAndSet(current, current.copy(busy = true, error = null))) return true
        }
    }

    private fun finish(error: KeysError?) {
        mutableState.update { it.copy(busy = false, error = error ?: it.error) }
    }

    private fun takePending(): PendingImport? = synchronized(lock) { pending.also { pending = null } }

    /** Not a data class: printing or comparing it must never touch the key. */
    private class PendingImport(val name: String, val content: ByteArray) {
        fun wipe() = content.fill(0)

        override fun toString(): String = "PendingImport(***)"
    }

    private companion object {
        const val ZERO = '\u0000'
    }
}
