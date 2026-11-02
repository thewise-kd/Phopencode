package io.github.thewisekd.phopencode.core.storage_api

import kotlinx.coroutines.flow.Flow

enum class AppLanguage { SYSTEM, TURKISH, ENGLISH }

enum class ThemePreference { SYSTEM, LIGHT, DARK }

/**
 * The user's preferences. Values are not validated here, because settings read back from storage
 * must not crash; the settings screen validates input.
 *
 * @property fileSizeLimitBytes Files above this size get a warning instead of opening directly.
 */
data class AppSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val fileSizeLimitBytes: Long = DEFAULT_FILE_SIZE_LIMIT_BYTES,
) {
    companion object {
        const val DEFAULT_FILE_SIZE_LIMIT_BYTES: Long = 1024L * 1024L
    }
}

interface SettingsRepository {
    /** Emits the current settings on collection, then again after every change. */
    fun observe(): Flow<AppSettings>

    suspend fun setLanguage(language: AppLanguage)

    suspend fun setTheme(theme: ThemePreference)

    suspend fun setFileSizeLimit(bytes: Long)

    /**
     * Deletes everything the app stored: secrets ([SecretStore.deleteAll]), profiles, known hosts
     * and settings, which return to their defaults. Throws [java.io.IOException] if any part fails;
     * calling again finishes the job.
     */
    suspend fun deleteAllData()
}
