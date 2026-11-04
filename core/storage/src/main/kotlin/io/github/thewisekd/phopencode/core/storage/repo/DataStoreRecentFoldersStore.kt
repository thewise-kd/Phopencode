package io.github.thewisekd.phopencode.core.storage.repo

import androidx.datastore.core.DataStore
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** [RecentFoldersStore] over the recent folders file. Every change is one atomic DataStore update. */
internal class DataStoreRecentFoldersStore(private val store: DataStore<RecentFolders>) : RecentFoldersStore {
    override fun observe(profile: ServerProfileId): Flow<List<String>> =
        store.data.map { it[profile].orEmpty() }.distinctUntilChanged()

    override suspend fun list(profile: ServerProfileId): List<String> = store.data.first()[profile].orEmpty()

    override suspend fun add(profile: ServerProfileId, path: String) {
        update(profile) { paths -> (listOf(path) + (paths - path)).take(RecentFoldersStore.MAX_PER_PROFILE) }
    }

    override suspend fun remove(profile: ServerProfileId, path: String) {
        update(profile) { paths -> paths - path }
    }

    override suspend fun forget(profile: ServerProfileId) {
        update(profile) { emptyList() }
    }

    /** For `deleteAllData`. */
    suspend fun clear() {
        store.updateData { emptyMap() }
    }

    private suspend fun update(profile: ServerProfileId, change: (List<String>) -> List<String>) {
        store.updateData { all ->
            val current = all[profile].orEmpty()
            val updated = change(current)
            when {
                updated == current -> all
                updated.isEmpty() -> all - profile
                else -> all + (profile to updated)
            }
        }
    }
}
