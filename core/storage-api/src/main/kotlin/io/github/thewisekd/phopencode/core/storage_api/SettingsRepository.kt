package io.github.thewisekd.phopencode.core.storage_api

import kotlinx.coroutines.flow.Flow

enum class AppLanguage { SYSTEM, TURKISH, ENGLISH }

enum class ThemePreference { SYSTEM, LIGHT, DARK }

/**
 * The user's preferences. Values are not validated here, because settings read back from storage
 * must not crash; the settings screen validates input.
 *
 * @property fileSizeLimitBytes Files above this size get a warning instead of opening directly.
 * @property guideSeen The user finished or skipped the first-run guide, so it no longer opens by
 *   itself. Set by [SettingsRepository.markGuideSeen].
 * @property previewNoticeAccepted The user confirmed the live preview notice, so it is not shown
 *   again. Set by [SettingsRepository.acceptPreviewNotice].
 */
data class AppSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val fileSizeLimitBytes: Long = DEFAULT_FILE_SIZE_LIMIT_BYTES,
    val guideSeen: Boolean = false,
    val previewNoticeAccepted: Boolean = false,
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
     * Records that the first-run guide was finished or skipped: [AppSettings.guideSeen] becomes
     * true and stays true, across restarts, until [deleteAllData]. Calling it again changes nothing.
     * Throws [java.io.IOException] if the value cannot be stored.
     */
    suspend fun markGuideSeen()

    /**
     * Records that the user confirmed the live preview notice: [AppSettings.previewNoticeAccepted]
     * becomes true and stays true, across restarts, until [deleteAllData]. Calling it again changes
     * nothing. Throws [java.io.IOException] if the value cannot be stored.
     */
    suspend fun acceptPreviewNotice()

    /**
     * Deletes everything the app stored: secrets ([SecretStore.deleteAll]), profiles, known hosts
     * and settings, which return to their defaults (so the first-run guide and the preview notice
     * show again). Throws [java.io.IOException] if any part fails;
     * calling again finishes the job.
     */
    suspend fun deleteAllData()
}
