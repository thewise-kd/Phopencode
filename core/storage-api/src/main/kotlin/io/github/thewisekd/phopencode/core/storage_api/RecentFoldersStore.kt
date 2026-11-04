package io.github.thewisekd.phopencode.core.storage_api

import io.github.thewisekd.phopencode.core.model.ServerProfileId
import kotlinx.coroutines.flow.Flow

/**
 * The folders on each server the user picked recently, kept per profile, newest first, at most
 * [MAX_PER_PROFILE] each. Paths are the server's and are kept exactly as given: the folder picker
 * passes them in one canonical form, and two spellings of one folder are two entries.
 *
 * `ProfileRepository.delete` does not reach this store. The caller that deletes a profile calls
 * [forget], as it deletes the profile's secrets. `SettingsRepository.deleteAllData` clears every
 * profile's folders. Writes throw [java.io.IOException] when storage cannot be written.
 */
interface RecentFoldersStore {
    /** Emits [profile]'s current list on collection, then again after every change to it. */
    fun observe(profile: ServerProfileId): Flow<List<String>>

    /** [profile]'s folders, newest first; empty if it has none. */
    suspend fun list(profile: ServerProfileId): List<String>

    /**
     * Puts [path] first in [profile]'s list. A path already there moves to the front rather than
     * appearing twice; the oldest path beyond [MAX_PER_PROFILE] is dropped.
     */
    suspend fun add(profile: ServerProfileId, path: String)

    /** Removes [path] from [profile]'s list, if it is there. */
    suspend fun remove(profile: ServerProfileId, path: String)

    /** Removes all of [profile]'s folders. Does nothing if it has none. */
    suspend fun forget(profile: ServerProfileId)

    companion object {
        const val MAX_PER_PROFILE: Int = 10
    }
}
