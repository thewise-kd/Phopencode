package io.github.thewisekd.phopencode.feature.connect.logic.serverform

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormAuthMethod
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormField
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormFieldError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormState
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * Adds or edits a server (P4.6).
 *
 * Passwords never reach the state or the profile. The typed text is turned into a [SecretValue] at
 * once and held here, outside the state; it is wiped when replaced, once a save has written it, and
 * when [scope] ends. A save works on a copy of it, wiped when the save ends, so that a failed save
 * keeps the typed password for another try, and text typed while a save runs is kept, not wiped.
 *
 * A save writes in this order, so that no profile ever points at a secret that is not there:
 * 1. Each new secret under a fresh [SecretRef], never over an existing one. The saved profile still
 *    points at its old secrets, which are untouched.
 * 2. The profile, which switches from the old references to the new ones in one write.
 * 3. The secrets the profile no longer refers to, best effort: a failure here leaves an orphan
 *    entry, never a missing one.
 *
 * If step 1 fails, the new entries are deleted and the profile is not written. If step 2 fails, the
 * stored profile is read back and judged by its secret references: new entries it refers to are
 * kept, and the save counts; new entries it does not refer to are deleted; if it cannot be read,
 * all are kept, because a profile written in spite of the error would otherwise point at nothing.
 * A new server's ID is made once, so a retry replaces such a profile. A save cancelled during step 2 follows the same rules
 * before the cancellation goes on. A key's secrets belong to the key index and are never
 * deleted here.
 *
 * @param serverId The server to edit, or null for a new one.
 * @param keyIndex The keys to log in with.
 * @param newId Makes the IDs of a new profile and of its `SecretRef`s.
 */
class DefaultServerFormPresenter(
    private val serverId: ServerProfileId?,
    private val profiles: ProfileRepository,
    private val secrets: SecretStore,
    private val keyIndex: SshKeyIndex,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ServerFormPresenter {
    private val mutableState = MutableStateFlow(ServerFormState(isNew = serverId == null))

    override val state: StateFlow<ServerFormState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ServerFormEvent>(Channel.BUFFERED)

    override val events: Flow<ServerFormEvent> = eventChannel.receiveAsFlow()

    /** Orders typed-secret changes against a save's snapshot and its published flags. */
    private val secretsLock = Any()

    private val password = HeldSecret()
    private val opencodePassword = HeldSecret()

    /** The profile as last read or saved; null for a new server not saved yet. */
    @Volatile
    private var original: ServerProfile? = null

    /** False while the server to edit has not been read, or could not be. */
    @Volatile
    private var editable: Boolean = serverId == null

    @Volatile
    private var opencodeCleared: Boolean = false

    /**
     * A new server's ID, made on the first save attempt and kept for every retry, so that a retry
     * after a write that committed in spite of an error replaces that profile rather than adding a
     * second one.
     */
    @Volatile
    private var draftId: ServerProfileId? = null

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            password.clear()
            opencodePassword.clear()
        }
        scope.launch { load() }
    }

    override fun onIntent(intent: ServerFormIntent) {
        when (intent) {
            is ServerFormIntent.NameChanged -> edit(ServerFormField.Name) { copy(name = intent.name) }
            is ServerFormIntent.HostChanged -> edit(ServerFormField.Host) { copy(host = intent.host) }
            is ServerFormIntent.PortChanged -> edit(ServerFormField.Port) { copy(port = intent.port) }
            is ServerFormIntent.OpencodePortChanged ->
                edit(ServerFormField.OpencodePort) { copy(opencodePort = intent.port) }
            is ServerFormIntent.UsernameChanged ->
                edit(ServerFormField.Username) { copy(username = intent.username) }
            is ServerFormIntent.AuthMethodChanged -> mutableState.update {
                it.copy(
                    authMethod = intent.method,
                    fieldErrors = it.fieldErrors - ServerFormField.Password - ServerFormField.Key,
                )
            }
            is ServerFormIntent.PasswordChanged -> synchronized(secretsLock) {
                val entered = password.set(intent.password)
                edit(ServerFormField.Password) { copy(passwordEntered = entered) }
            }
            is ServerFormIntent.KeySelected -> edit(ServerFormField.Key) { copy(selectedKey = intent.id) }
            is ServerFormIntent.OpencodePasswordChanged -> synchronized(secretsLock) {
                val entered = opencodePassword.set(intent.password)
                mutableState.update { it.copy(opencodePasswordEntered = entered) }
            }
            ServerFormIntent.ClearOpencodePassword -> synchronized(secretsLock) {
                opencodeCleared = true
                opencodePassword.clear()
                mutableState.update { it.copy(opencodePasswordEntered = false, storedOpencodePassword = false) }
            }
            ServerFormIntent.ManageKeys -> eventChannel.trySend(ServerFormEvent.OpenKeys)
            ServerFormIntent.Save -> save()
            ServerFormIntent.Cancel -> eventChannel.trySend(ServerFormEvent.Close)
            ServerFormIntent.DismissError -> mutableState.update { it.copy(error = null) }
        }
    }

    private inline fun edit(field: ServerFormField, crossinline change: ServerFormState.() -> ServerFormState) {
        mutableState.update { it.change().copy(fieldErrors = it.fieldErrors - field) }
    }

    private suspend fun load() {
        // A scope on an immediate dispatcher would run this inside the constructor; let it return.
        yield()
        if (serverId != null && !loadProfile(serverId)) return
        keyIndex.observe()
            .catch { failure ->
                if (failure is CancellationException) throw failure
                mutableState.update { it.copy(loading = false, error = ServerFormError.LoadFailed) }
            }
            .collect { keys ->
                val keyRef = (original?.auth as? SshAuth.PrivateKey)?.keyRef
                mutableState.update { form ->
                    val selected = form.selectedKey
                        ?: keyRef?.let { ref -> keys.firstOrNull { it.privateKeyRef == ref }?.id }
                    form.copy(loading = false, keys = keys, selectedKey = selected)
                }
            }
    }

    /** Fills the form from the saved server; false, with the error shown, if it cannot. */
    private suspend fun loadProfile(id: ServerProfileId): Boolean {
        try {
            val profile = profiles.list().firstOrNull { it.id == id }
            if (profile == null) {
                mutableState.update { it.copy(loading = false, error = ServerFormError.NotFound) }
                return false
            }
            val passwordRef = (profile.auth as? SshAuth.Password)?.secretRef
            val storedPassword = passwordRef != null && isReadable(passwordRef)
            val storedOpencode = profile.opencodeCredential?.let { isReadable(it) } ?: false
            original = profile
            editable = true
            mutableState.update {
                it.copy(
                    name = profile.name,
                    host = profile.host,
                    port = profile.port.toString(),
                    opencodePort = profile.opencodePort?.toString().orEmpty(),
                    username = profile.username,
                    authMethod = when (profile.auth) {
                        is SshAuth.Password -> ServerFormAuthMethod.Password
                        is SshAuth.PrivateKey -> ServerFormAuthMethod.Key
                    },
                    storedPassword = storedPassword,
                    storedOpencodePassword = storedOpencode,
                )
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(loading = false, error = ServerFormError.LoadFailed) }
            return false
        }
    }

    /** Whether [ref] holds a secret that can be decrypted. The value read is wiped at once. */
    private suspend fun isReadable(ref: SecretRef): Boolean =
        when (val result = secrets.read(ref)) {
            is SecretReadResult.Found -> {
                result.value.close()
                true
            }
            SecretReadResult.NotFound, SecretReadResult.Unreadable -> false
        }

    /**
     * Takes everything the save writes here, before anything is launched: the form, a copy of each
     * typed password with its version, and whether the opencode password is to be cleared. An edit
     * sent after Save, even before the save's coroutine runs, is never part of this save.
     */
    private fun save() {
        var form: ServerFormState? = null
        mutableState.update { current ->
            if (current.saving || current.loading || !editable) {
                form = null
                current
            } else {
                form = current
                current.copy(saving = true, error = null)
            }
        }
        val snapshot = form ?: return
        val taken = synchronized(secretsLock) {
            Taken(
                login = password.snapshot(withCopy = snapshot.authMethod == ServerFormAuthMethod.Password),
                opencode = opencodePassword.snapshot(withCopy = true),
                clearOpencode = opencodeCleared,
            )
        }
        val job = scope.launch {
            try {
                write(snapshot, taken)
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
        // Wipes the copies even if the scope ended before the save could start.
        job.invokeOnCompletion { taken.close() }
    }

    private suspend fun write(form: ServerFormState, taken: Taken) {
        val profile = validate(form, hasNewPassword = taken.login.copy != null, clearOpencode = taken.clearOpencode)
            ?: return
        val written = store(profile, taken.login.copy, taken.opencode.copy) ?: return
        taken.close()
        val old = original
        original = written
        publishSaved(written, taken)
        forgetSuperseded(old, written)
        eventChannel.trySend(ServerFormEvent.Saved(written.id))
    }

    /**
     * Wipes what [taken] wrote, if nothing was typed or cleared since, and publishes the flags from
     * what is held now, in one step under [secretsLock] with no suspension: an edit lands either
     * before it, and is kept, or after it, and sets its own flag.
     */
    private fun publishSaved(written: ServerProfile, taken: Taken) {
        synchronized(secretsLock) {
            password.clearIfUnchanged(taken.login.version)
            // A clear asked for after Save bumped the version too, and still applies.
            if (opencodePassword.clearIfUnchanged(taken.opencode.version)) opencodeCleared = false
            mutableState.update {
                it.copy(
                    passwordEntered = password.isHeld(),
                    storedPassword = written.auth is SshAuth.Password,
                    opencodePasswordEntered = opencodePassword.isHeld(),
                    storedOpencodePassword = written.opencodeCredential != null && !opencodeCleared,
                )
            }
        }
    }

    /**
     * The profile to save, with the current references and [PENDING] where a new secret goes; or
     * null, with the field errors or the save error shown.
     */
    private suspend fun validate(
        form: ServerFormState,
        hasNewPassword: Boolean,
        clearOpencode: Boolean,
    ): ServerProfile? {
        val errors = mutableMapOf<ServerFormField, ServerFormFieldError>()
        fun <T> Checked<T>.valueFor(field: ServerFormField): T? = when (this) {
            is Checked.Valid -> value
            is Checked.Invalid -> {
                errors[field] = error
                null
            }
        }
        val host = checkHost(form.host).valueFor(ServerFormField.Host)
        val port = checkPort(form.port).valueFor(ServerFormField.Port)
        val username = checkUsername(form.username).valueFor(ServerFormField.Username)
        val opencodePort = checkOptionalPort(form.opencodePort)
        if (opencodePort is Checked.Invalid) errors[ServerFormField.OpencodePort] = opencodePort.error
        val old = original
        val auth: SshAuth? = when (form.authMethod) {
            ServerFormAuthMethod.Password -> {
                val storedRef = (old?.auth as? SshAuth.Password)?.secretRef?.takeIf { form.storedPassword }
                when {
                    hasNewPassword -> SshAuth.Password(PENDING)
                    storedRef != null -> SshAuth.Password(storedRef)
                    else -> {
                        errors[ServerFormField.Password] = ServerFormFieldError.Required
                        null
                    }
                }
            }
            ServerFormAuthMethod.Key -> {
                val keyId = form.selectedKey
                if (keyId == null) {
                    errors[ServerFormField.Key] = ServerFormFieldError.Required
                    null
                } else {
                    val key = try {
                        keyIndex.get(keyId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        mutableState.update { it.copy(error = ServerFormError.SaveFailed) }
                        return null
                    }
                    if (key == null) {
                        errors[ServerFormField.Key] = ServerFormFieldError.Invalid
                        null
                    } else {
                        SshAuth.PrivateKey(key.privateKeyRef, key.passphraseRef)
                    }
                }
            }
        }
        mutableState.update { it.copy(fieldErrors = errors.toMap()) }
        if (host == null || port == null || username == null || auth == null) return null
        if (opencodePort !is Checked.Valid) return null
        return ServerProfile(
            id = old?.id ?: newProfileId(),
            name = form.name.trim().ifEmpty { host },
            host = host,
            port = port,
            username = username,
            auth = auth,
            opencodeCredential = if (clearOpencode) null else old?.opencodeCredential,
            lastUsedAt = old?.lastUsedAt,
            opencodePort = opencodePort.value,
        )
    }

    /**
     * Writes the new secrets under fresh references, then [draft] pointing at them. Returns the
     * profile written, or null with [ServerFormError.SaveFailed] shown and nothing left behind that
     * the stored profile could point at.
     */
    private suspend fun store(draft: ServerProfile, login: SecretValue?, opencode: SecretValue?): ServerProfile? {
        val created = mutableListOf<SecretRef>()
        suspend fun putNew(value: SecretValue): SecretRef {
            val ref = SecretRef(newId())
            created += ref
            secrets.put(ref, value)
            return ref
        }
        val profile = try {
            draft.copy(
                auth = if (login != null) SshAuth.Password(putNew(login)) else draft.auth,
                opencodeCredential = if (opencode != null) putNew(opencode) else draft.opencodeCredential,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) { deleteQuietly(created) }
            throw e
        } catch (_: Exception) {
            deleteQuietly(created)
            return saveFailed()
        }
        try {
            profiles.save(profile)
            return profile
        } catch (e: CancellationException) {
            // Same rules as a failure below, so a save cut short leaves no secret behind for nothing.
            withContext(NonCancellable) { settleFailedWrite(profile, created) }
            throw e
        } catch (_: Exception) {
            return settleFailedWrite(profile, created) ?: saveFailed()
        }
    }

    /**
     * After a profile write that failed or was cancelled, reads the stored profile back and decides
     * by secret references, never by comparing whole profiles, since another writer may have changed
     * its name or `lastUsedAt` meanwhile:
     * - each new secret the stored profile refers to is in use and kept; the others are deleted;
     * - if storage cannot be read, every new secret is kept, at worst as an orphan.
     *
     * Returns the stored profile if our write committed (it refers to a new secret, or equals
     * [profile] when there was none), or null.
     */
    private suspend fun settleFailedWrite(profile: ServerProfile, created: List<SecretRef>): ServerProfile? {
        val stored = try {
            profiles.list().firstOrNull { it.id == profile.id }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val inUse = stored?.secretRefs().orEmpty()
        deleteQuietly(created.filter { it !in inUse })
        val committed = stored != null && (created.any { it in inUse } || stored == profile)
        return stored.takeIf { committed }
    }

    private fun ServerProfile.secretRefs(): Set<SecretRef> = buildSet {
        when (val login = auth) {
            is SshAuth.Password -> add(login.secretRef)
            is SshAuth.PrivateKey -> {
                add(login.keyRef)
                login.passphraseRef?.let(::add)
            }
        }
        opencodeCredential?.let(::add)
    }

    /** Deletes the password secrets [old] had and [saved] no longer refers to. Never a key's. */
    private suspend fun forgetSuperseded(old: ServerProfile?, saved: ServerProfile) {
        if (old == null) return
        val superseded = buildList {
            val oldPassword = (old.auth as? SshAuth.Password)?.secretRef
            if (oldPassword != null && oldPassword != (saved.auth as? SshAuth.Password)?.secretRef) add(oldPassword)
            val oldOpencode = old.opencodeCredential
            if (oldOpencode != null && oldOpencode != saved.opencodeCredential) add(oldOpencode)
        }
        deleteQuietly(superseded)
    }

    private suspend fun deleteQuietly(refs: List<SecretRef>) {
        refs.forEach { ref ->
            try {
                secrets.delete(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left as an orphan entry: harmless, as nothing points at it.
            }
        }
    }

    private fun newProfileId(): ServerProfileId =
        draftId ?: ServerProfileId(newId()).also { draftId = it }

    private fun saveFailed(): ServerProfile? {
        mutableState.update { it.copy(error = ServerFormError.SaveFailed) }
        return null
    }

    private companion object {
        /** Stands in for the reference of a secret not written yet; never saved. */
        val PENDING = SecretRef("")
    }
}

/** What one save took from the form's typed secrets. Closing wipes the copies; again does nothing. */
private class Taken(
    val login: HeldSecret.Snapshot,
    val opencode: HeldSecret.Snapshot,
    val clearOpencode: Boolean,
) {
    fun close() {
        login.copy?.close()
        opencode.copy?.close()
    }
}

/**
 * A secret typed into the form, as wipeable bytes. Thread-safe. Every change bumps a version, so a
 * save can tell whether the value it wrote is still the one held.
 */
private class HeldSecret {
    private var value: SecretValue? = null
    private var version = 0L

    /** What a save took: a copy it owns and closes (null if none was held) and the version it saw. */
    class Snapshot(val copy: SecretValue?, val version: Long)

    /** Replaces the held value, wiping the old one; empty text holds none. Returns whether one is held. */
    @Synchronized
    fun set(text: String): Boolean {
        value?.close()
        value = if (text.isEmpty()) null else SecretValue.ofText(text)
        version++
        return value != null
    }

    /** The version, and a copy of the value only if [withCopy]. */
    @Synchronized
    fun snapshot(withCopy: Boolean): Snapshot {
        val copy = if (withCopy) {
            value?.let { held ->
                val bytes = held.revealBytes()
                try {
                    SecretValue.ofBytes(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
        } else {
            null
        }
        return Snapshot(copy, version)
    }

    /**
     * Wipes the held value if it is still the one of [version], that is, nothing was typed or
     * cleared since; a value typed after the snapshot stays. Returns whether it was unchanged.
     */
    @Synchronized
    fun clearIfUnchanged(version: Long): Boolean {
        if (this.version != version) return false
        value?.close()
        value = null
        this.version++
        return true
    }

    @Synchronized
    fun isHeld(): Boolean = value != null

    @Synchronized
    fun clear() {
        value?.close()
        value = null
        version++
    }
}
