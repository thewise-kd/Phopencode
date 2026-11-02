package io.github.thewisekd.phopencode.core.storage_api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppSettingsTest {
    @Test
    fun `defaults follow the system`() {
        val settings = AppSettings()

        assertEquals(AppLanguage.SYSTEM, settings.language)
        assertEquals(ThemePreference.SYSTEM, settings.theme)
    }

    @Test
    fun `the default file size limit is positive`() {
        assertEquals(AppSettings.DEFAULT_FILE_SIZE_LIMIT_BYTES, AppSettings().fileSizeLimitBytes)
        assertTrue(AppSettings.DEFAULT_FILE_SIZE_LIMIT_BYTES > 0)
    }

    @Test
    fun `the languages are system, Turkish and English`() {
        assertEquals(
            listOf(AppLanguage.SYSTEM, AppLanguage.TURKISH, AppLanguage.ENGLISH),
            AppLanguage.entries.toList(),
        )
    }

    @Test
    fun `the themes are system, light and dark`() {
        assertEquals(
            listOf(ThemePreference.SYSTEM, ThemePreference.LIGHT, ThemePreference.DARK),
            ThemePreference.entries.toList(),
        )
    }
}
