package io.github.thewisekd.phopencode.core.storage.repo

import androidx.datastore.core.DataStore
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** [SshKeyIndex] over the key index file. Every change is one atomic DataStore update. */
internal class DataStoreSshKeyIndex(private val store: DataStore<List<SshKeyEntry>>) : SshKeyIndex {
    override fun observe(): Flow<List<SshKeyEntry>> = store.data.map { it.sorted() }

    override suspend fun list(): List<SshKeyEntry> = store.data.first().sorted()

    override suspend fun get(id: SshKeyId): SshKeyEntry? = store.data.first().firstOrNull { it.id == id }

    override suspend fun save(entry: SshKeyEntry) {
        store.updateData { keys -> keys.filterNot { it.id == entry.id } + entry }
    }

    override suspend fun delete(id: SshKeyId) {
        store.updateData { keys -> keys.filterNot { it.id == id } }
    }

    /** For `deleteAllData`. */
    suspend fun clear() {
        store.updateData { emptyList() }
    }

    private fun List<SshKeyEntry>.sorted() = sortedWith(SshKeyIndex.BY_NAME)
}
