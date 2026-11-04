package io.github.thewisekd.phopencode.core.storage.repo

import androidx.datastore.core.DataStore
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyUpdate
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import java.io.IOException
import kotlinx.coroutines.flow.first

/**
 * [KnownHostsStore] over the known hosts file. Each read-and-write is one DataStore update, which
 * DataStore runs one at a time, so a decision is made on the key that is stored when it is written.
 * Every call throws [IOException] while the file is [KnownHostsFile.Unreadable]; [clear] recovers.
 */
internal class DataStoreKnownHostsStore(private val store: DataStore<KnownHostsFile>) : KnownHostsStore {
    override suspend fun approved(endpoint: HostEndpoint): HostKeyFingerprint? = store.data.first().hosts()[endpoint]

    override suspend fun approve(endpoint: HostEndpoint, fingerprint: HostKeyFingerprint): HostKeyUpdate =
        update { hosts ->
            when (val current = hosts[endpoint]) {
                null, fingerprint -> hosts + (endpoint to fingerprint) to HostKeyUpdate.Stored
                else -> hosts to HostKeyUpdate.Rejected(current)
            }
        }

    override suspend fun replace(
        endpoint: HostEndpoint,
        expected: HostKeyFingerprint,
        replacement: HostKeyFingerprint,
    ): HostKeyUpdate = update { hosts ->
        when (val current = hosts[endpoint]) {
            expected -> hosts + (endpoint to replacement) to HostKeyUpdate.Stored
            else -> hosts to HostKeyUpdate.Rejected(current)
        }
    }

    override suspend fun delete(endpoint: HostEndpoint) {
        update { hosts -> hosts - endpoint to Unit }
    }

    /** For `deleteAllData`. Also writes over an unreadable file. */
    suspend fun clear() {
        store.updateData { KnownHostsFile.Readable(emptyMap()) }
    }

    private suspend fun <R : Any> update(decide: (Map<HostEndpoint, HostKeyFingerprint>) -> Pair<Map<HostEndpoint, HostKeyFingerprint>, R>): R {
        var outcome: R? = null
        store.updateData { file ->
            val hosts = file.hosts()
            val (updated, result) = decide(hosts)
            outcome = result
            if (updated == hosts) file else KnownHostsFile.Readable(updated)
        }
        return checkNotNull(outcome)
    }

    private fun KnownHostsFile.hosts(): Map<HostEndpoint, HostKeyFingerprint> = when (this) {
        is KnownHostsFile.Readable -> hosts
        KnownHostsFile.Unreadable -> throw IOException("The known hosts file cannot be read")
    }
}
