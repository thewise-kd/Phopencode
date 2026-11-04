package io.github.thewisekd.phopencode.core.testing.storage

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyUpdate
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

private fun failIf(unavailable: Boolean) {
    if (unavailable) throw IOException("Fake storage is unavailable")
}

/**
 * Keeps secrets in memory, with the interface's ownership rules: it copies what it is given, and
 * every read hands out a value of its own. It never prints or exposes a secret except through
 * [read].
 *
 * @property unavailable Makes every function throw [IOException], as storage that cannot be reached.
 */
class FakeSecretStore : SecretStore {
    private sealed interface Entry {
        class Readable(val bytes: ByteArray) : Entry

        data object Unreadable : Entry
    }

    private val entries = LinkedHashMap<SecretRef, Entry>()

    @Volatile
    var unavailable: Boolean = false

    val entryCount: Int
        get() = synchronized(entries) { entries.size }

    /** Leaves an entry that exists and can never be decrypted. */
    fun seedUnreadable(ref: SecretRef) {
        synchronized(entries) { entries[ref] = Entry.Unreadable }
    }

    override suspend fun put(ref: SecretRef, value: SecretValue) {
        failIf(unavailable)
        val bytes = value.revealBytes()
        synchronized(entries) { entries.put(ref, Entry.Readable(bytes)) }.wipe()
    }

    override suspend fun read(ref: SecretRef): SecretReadResult {
        failIf(unavailable)
        // Copies under the lock, because a replace or a delete wipes the stored array.
        return synchronized(entries) {
            when (val entry = entries[ref]) {
                null -> SecretReadResult.NotFound
                Entry.Unreadable -> SecretReadResult.Unreadable
                is Entry.Readable -> SecretReadResult.Found(SecretValue.ofBytes(entry.bytes))
            }
        }
    }

    override suspend fun delete(ref: SecretRef) {
        failIf(unavailable)
        synchronized(entries) { entries.remove(ref) }.wipe()
    }

    override suspend fun deleteAll() {
        failIf(unavailable)
        synchronized(entries) {
            entries.values.forEach { it.wipe() }
            entries.clear()
        }
    }

    private fun Entry?.wipe() {
        (this as? Entry.Readable)?.bytes?.fill(0)
    }
}

/** @property unavailable Makes every suspending function throw [IOException]. */
class FakeProfileRepository : ProfileRepository {
    private val profiles = MutableStateFlow<Map<ServerProfileId, ServerProfile>>(emptyMap())

    @Volatile
    var unavailable: Boolean = false

    private fun Map<ServerProfileId, ServerProfile>.sorted() =
        values.sortedWith(ProfileRepository.MOST_RECENTLY_USED_FIRST)

    override fun observe(): Flow<List<ServerProfile>> = profiles.map { it.sorted() }

    override suspend fun list(): List<ServerProfile> {
        failIf(unavailable)
        return profiles.value.sorted()
    }

    override suspend fun save(profile: ServerProfile) {
        failIf(unavailable)
        profiles.update { it + (profile.id to profile) }
    }

    override suspend fun delete(id: ServerProfileId) {
        failIf(unavailable)
        profiles.update { it - id }
    }

    internal fun clear() {
        failIf(unavailable)
        profiles.value = emptyMap()
    }
}

/**
 * @property unavailable Makes every suspending function throw [IOException]. The interface promises
 *   no such failure; it is here for [SettingsRepository.deleteAllData]'s partial failure.
 */
class FakeKnownHostsStore : KnownHostsStore {
    private val approved = HashMap<HostEndpoint, HostKeyFingerprint>()

    @Volatile
    var unavailable: Boolean = false

    override suspend fun approved(endpoint: HostEndpoint): HostKeyFingerprint? {
        failIf(unavailable)
        return synchronized(approved) { approved[endpoint] }
    }

    override suspend fun approve(endpoint: HostEndpoint, fingerprint: HostKeyFingerprint): HostKeyUpdate {
        failIf(unavailable)
        return synchronized(approved) {
            when (val current = approved[endpoint]) {
                null, fingerprint -> {
                    approved[endpoint] = fingerprint
                    HostKeyUpdate.Stored
                }
                else -> HostKeyUpdate.Rejected(current)
            }
        }
    }

    override suspend fun replace(
        endpoint: HostEndpoint,
        expected: HostKeyFingerprint,
        replacement: HostKeyFingerprint,
    ): HostKeyUpdate {
        failIf(unavailable)
        return synchronized(approved) {
            when (val current = approved[endpoint]) {
                expected -> {
                    approved[endpoint] = replacement
                    HostKeyUpdate.Stored
                }
                else -> HostKeyUpdate.Rejected(current)
            }
        }
    }

    override suspend fun delete(endpoint: HostEndpoint) {
        failIf(unavailable)
        synchronized(approved) { approved.remove(endpoint) }
    }

    internal fun clear() {
        failIf(unavailable)
        synchronized(approved) { approved.clear() }
    }
}

/** @property unavailable Makes every suspending function throw [IOException]. */
class FakeSshKeyIndex : SshKeyIndex {
    private val keys = MutableStateFlow<Map<SshKeyId, SshKeyEntry>>(emptyMap())

    @Volatile
    var unavailable: Boolean = false

    private fun Map<SshKeyId, SshKeyEntry>.sorted() = values.sortedWith(SshKeyIndex.BY_NAME)

    override fun observe(): Flow<List<SshKeyEntry>> = keys.map { it.sorted() }

    override suspend fun list(): List<SshKeyEntry> {
        failIf(unavailable)
        return keys.value.sorted()
    }

    override suspend fun get(id: SshKeyId): SshKeyEntry? {
        failIf(unavailable)
        return keys.value[id]
    }

    override suspend fun save(entry: SshKeyEntry) {
        failIf(unavailable)
        keys.update { it + (entry.id to entry) }
    }

    override suspend fun delete(id: SshKeyId) {
        failIf(unavailable)
        keys.update { it - id }
    }

    internal fun clear() {
        failIf(unavailable)
        keys.value = emptyMap()
    }
}

/** @property unavailable Makes every suspending function throw [IOException]. */
class FakeRecentFoldersStore : RecentFoldersStore {
    private val folders = MutableStateFlow<Map<ServerProfileId, List<String>>>(emptyMap())

    @Volatile
    var unavailable: Boolean = false

    override fun observe(profile: ServerProfileId): Flow<List<String>> =
        folders.map { it[profile].orEmpty() }.distinctUntilChanged()

    override suspend fun list(profile: ServerProfileId): List<String> {
        failIf(unavailable)
        return folders.value[profile].orEmpty()
    }

    override suspend fun add(profile: ServerProfileId, path: String) {
        update(profile) { paths -> (listOf(path) + (paths - path)).take(RecentFoldersStore.MAX_PER_PROFILE) }
    }

    override suspend fun remove(profile: ServerProfileId, path: String) {
        update(profile) { paths -> paths - path }
    }

    override suspend fun forget(profile: ServerProfileId) {
        update(profile) { emptyList() }
    }

    private fun update(profile: ServerProfileId, change: (List<String>) -> List<String>) {
        failIf(unavailable)
        folders.update { all ->
            val updated = change(all[profile].orEmpty())
            if (updated.isEmpty()) all - profile else all + (profile to updated)
        }
    }

    internal fun clear() {
        failIf(unavailable)
        folders.value = emptyMap()
    }
}

/**
 * [deleteAllData] reaches into the stores it is given, so share them with the test to see it.
 * It tries every part even when one fails, then throws the first failure.
 *
 * @property unavailable Makes every suspending function throw [IOException].
 */
class FakeSettingsRepository(
    private val secrets: FakeSecretStore = FakeSecretStore(),
    private val profiles: FakeProfileRepository = FakeProfileRepository(),
    private val knownHosts: FakeKnownHostsStore = FakeKnownHostsStore(),
    private val keyIndex: FakeSshKeyIndex = FakeSshKeyIndex(),
    private val recentFolders: FakeRecentFoldersStore = FakeRecentFoldersStore(),
) : SettingsRepository {
    private val settings = MutableStateFlow(AppSettings())

    @Volatile
    var unavailable: Boolean = false

    override fun observe(): Flow<AppSettings> = settings.asStateFlow()

    override suspend fun setLanguage(language: AppLanguage) {
        failIf(unavailable)
        settings.update { it.copy(language = language) }
    }

    override suspend fun setTheme(theme: ThemePreference) {
        failIf(unavailable)
        settings.update { it.copy(theme = theme) }
    }

    override suspend fun setFileSizeLimit(bytes: Long) {
        failIf(unavailable)
        settings.update { it.copy(fileSizeLimitBytes = bytes) }
    }

    override suspend fun deleteAllData() {
        val parts: List<suspend () -> Unit> = listOf(
            { secrets.deleteAll() },
            { profiles.clear() },
            { knownHosts.clear() },
            { keyIndex.clear() },
            { recentFolders.clear() },
            {
                failIf(unavailable)
                settings.value = AppSettings()
            },
        )
        var failure: IOException? = null
        parts.forEach { part ->
            try {
                part()
            } catch (e: IOException) {
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
    }
}
