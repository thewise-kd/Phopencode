package io.github.thewisekd.phopencode.feature.settings.logic.contract

import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractTypesTest {
    @Test
    fun `preferences start loading with the stored defaults`() {
        val state = PreferencesState()

        assertTrue(state.loading)
        assertEquals(AppLanguage.SYSTEM, state.language)
        assertEquals(ThemePreference.SYSTEM, state.theme)
        assertEquals(AppSettings.DEFAULT_FILE_SIZE_LIMIT_BYTES, state.fileSizeLimitBytes)
        assertNull(state.fileSizeProblem)
        assertEquals(DataDeletion.Idle, state.deletion)
        assertNull(state.error)
    }

    @Test
    fun `nothing can be changed while loading or deleting`() {
        assertFalse(PreferencesState().canEdit)
        assertTrue(PreferencesState(loading = false).canEdit)
        assertTrue(PreferencesState(loading = false, deletion = DataDeletion.Confirming).canEdit)
        assertFalse(PreferencesState(loading = false, deletion = DataDeletion.Deleting).canEdit)
    }

    @Test
    fun `the file size limit is whole mebibytes within its range`() {
        assertEquals(1024L * 1024L, PreferencesState.BYTES_PER_MIB)
        assertTrue(PreferencesState.MIN_FILE_SIZE_LIMIT_MIB >= 1)
        assertTrue(PreferencesState.MAX_FILE_SIZE_LIMIT_MIB > PreferencesState.MIN_FILE_SIZE_LIMIT_MIB)
        val defaultMib = AppSettings.DEFAULT_FILE_SIZE_LIMIT_BYTES / PreferencesState.BYTES_PER_MIB
        assertTrue(defaultMib in PreferencesState.MIN_FILE_SIZE_LIMIT_MIB..PreferencesState.MAX_FILE_SIZE_LIMIT_MIB)
    }

    @Test
    fun `cases are fixed`() {
        assertEquals(
            listOf(FileSizeProblem.Blank, FileSizeProblem.NotANumber, FileSizeProblem.TooSmall, FileSizeProblem.TooLarge),
            FileSizeProblem.entries,
        )
        assertEquals(listOf(PreferencesError.SaveFailed, PreferencesError.DeleteFailed), PreferencesError.entries)
    }
}
