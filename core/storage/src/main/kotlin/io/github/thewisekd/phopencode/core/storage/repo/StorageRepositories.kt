package io.github.thewisekd.phopencode.core.storage.repo

import android.content.Context
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The profile, known hosts and settings repositories, on DataStore files in the app's private
 * `files/datastore/` folder. Create exactly one per process: DataStore refuses a second instance on
 * the same file while the first one's [scope] is active.
 *
 * Passwords and keys never reach these files; [secrets] holds them, and
 * [SettingsRepository.deleteAllData] clears it with the rest.
 */
class StorageRepositories(
    context: Context,
    secrets: SecretStore,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val app = context.applicationContext
    private val profileRepository = DataStoreProfileRepository(profileDataStore(scope) { app.dataStoreFile(PROFILES_FILE) })
    private val knownHostsStore = DataStoreKnownHostsStore(knownHostsDataStore(scope) { app.dataStoreFile(KNOWN_HOSTS_FILE) })

    val profiles: ProfileRepository = profileRepository

    val knownHosts: KnownHostsStore = knownHostsStore

    val settings: SettingsRepository = DataStoreSettingsRepository(
        settingsDataStore(scope) { app.preferencesDataStoreFile(SETTINGS_NAME) },
        secrets,
        profileRepository,
        knownHostsStore,
    )

    private companion object {
        const val PROFILES_FILE = "profiles.json"
        const val KNOWN_HOSTS_FILE = "known_hosts.json"
        const val SETTINGS_NAME = "settings"
    }
}
