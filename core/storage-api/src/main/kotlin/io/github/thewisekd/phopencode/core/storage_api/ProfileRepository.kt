package io.github.thewisekd.phopencode.core.storage_api

import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import kotlinx.coroutines.flow.Flow

/**
 * The saved servers. A [ServerProfile] holds references only, so no password passes through here.
 * Lists are always sorted by [MOST_RECENTLY_USED_FIRST].
 */
interface ProfileRepository {
    /** Emits the current list on collection, then again after every change. */
    fun observe(): Flow<List<ServerProfile>>

    suspend fun list(): List<ServerProfile>

    /** Adds [profile], or replaces the one with the same ID. Marking a profile used is a save. */
    suspend fun save(profile: ServerProfile)

    /**
     * Removes the profile with this ID, if any. The secrets it refers to stay in [SecretStore]; the
     * caller deletes them, because a key may be shared by several profiles.
     */
    suspend fun delete(id: ServerProfileId)

    companion object {
        /** Used profiles by time, then never-used ones; name and ID keep the order stable. */
        val MOST_RECENTLY_USED_FIRST: Comparator<ServerProfile> =
            compareByDescending<ServerProfile> { it.lastUsedAt }.thenBy { it.name }.thenBy { it.id.value }
    }
}
