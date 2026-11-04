package io.github.thewisekd.phopencode.core.storage.repo

import androidx.datastore.core.DataStore
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** [ProfileRepository] over the profile file. Every change is one atomic DataStore update. */
internal class DataStoreProfileRepository(private val store: DataStore<List<ServerProfile>>) : ProfileRepository {
    override fun observe(): Flow<List<ServerProfile>> = store.data.map { it.sorted() }

    override suspend fun list(): List<ServerProfile> = store.data.first().sorted()

    override suspend fun save(profile: ServerProfile) {
        store.updateData { profiles -> profiles.filterNot { it.id == profile.id } + profile }
    }

    override suspend fun delete(id: ServerProfileId) {
        store.updateData { profiles -> profiles.filterNot { it.id == id } }
    }

    /** For `deleteAllData`. */
    suspend fun clear() {
        store.updateData { emptyList() }
    }

    private fun List<ServerProfile>.sorted() = sortedWith(ProfileRepository.MOST_RECENTLY_USED_FIRST)
}
