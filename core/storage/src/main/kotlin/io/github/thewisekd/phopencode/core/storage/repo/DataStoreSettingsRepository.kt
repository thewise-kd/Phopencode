package io.github.thewisekd.phopencode.core.storage.repo

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

private val LANGUAGE = stringPreferencesKey("language")
private val THEME = stringPreferencesKey("theme")
private val FILE_SIZE_LIMIT = longPreferencesKey("file_size_limit_bytes")

/** How long an observer waits before reading a settings file again that failed to read. */
private val READ_RETRY_DELAY = 1.seconds

/**
 * Settings read back from storage never crash: a missing value, an unknown name or a value of
 * another type is the default. Lookups go through [Preferences.asMap], because a typed lookup of a
 * value stored with another type throws.
 */
private fun Preferences.toSettings(): AppSettings {
    val values = asMap()
    return AppSettings(
        language = enumOrNull<AppLanguage>(values[LANGUAGE]) ?: AppLanguage.SYSTEM,
        theme = enumOrNull<ThemePreference>(values[THEME]) ?: ThemePreference.SYSTEM,
        fileSizeLimitBytes = values[FILE_SIZE_LIMIT] as? Long ?: AppSettings.DEFAULT_FILE_SIZE_LIMIT_BYTES,
    )
}

private inline fun <reified E : Enum<E>> enumOrNull(stored: Any?): E? = enumValues<E>().firstOrNull { it.name == stored }

/**
 * [SettingsRepository] over a Preferences DataStore. [deleteAllData] also clears the other stores,
 * so it is given them.
 */
internal class DataStoreSettingsRepository(
    private val store: DataStore<Preferences>,
    private val secrets: SecretStore,
    private val profiles: DataStoreProfileRepository,
    private val knownHosts: DataStoreKnownHostsStore,
) : SettingsRepository {
    /**
     * A settings file that cannot be read gives the defaults, so the app still starts, and is read
     * again after [READ_RETRY_DELAY], so the observer still gets later changes. Other errors and
     * cancellation end the flow.
     */
    override fun observe(): Flow<AppSettings> = store.data
        .map { it.toSettings() }
        .retryWhen { cause, _ ->
            if (cause !is IOException) return@retryWhen false
            emit(AppSettings())
            delay(READ_RETRY_DELAY)
            true
        }
        .distinctUntilChanged()

    override suspend fun setLanguage(language: AppLanguage) {
        store.edit { it[LANGUAGE] = language.name }
    }

    override suspend fun setTheme(theme: ThemePreference) {
        store.edit { it[THEME] = theme.name }
    }

    override suspend fun setFileSizeLimit(bytes: Long) {
        store.edit { it[FILE_SIZE_LIMIT] = bytes }
    }

    /**
     * Tries every part even when one fails, so a retry has less left to do, then throws the first
     * failure. A failure that is not an [IOException] is wrapped in one, as the contract promises.
     */
    override suspend fun deleteAllData() {
        val parts: List<suspend () -> Unit> = listOf(
            { secrets.deleteAll() },
            { profiles.clear() },
            { knownHosts.clear() },
            { store.edit { it.clear() } },
        )
        var failure: Exception? = null
        for (part in parts) {
            try {
                part()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it as? IOException ?: IOException("Could not delete all data", it) }
    }
}

/** Opens the settings file. A corrupt file is replaced by the defaults. */
internal fun settingsDataStore(scope: CoroutineScope, produceFile: () -> File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = produceFile,
    )
