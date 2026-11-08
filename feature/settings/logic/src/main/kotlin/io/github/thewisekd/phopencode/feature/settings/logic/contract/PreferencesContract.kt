package io.github.thewisekd.phopencode.feature.settings.logic.contract

import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference

/**
 * The preferences, `SettingsRoute.Preferences` (P10.8 logic, P10.9 screen): the language (system,
 * Turkish, English), the theme, the file size limit, deleting all data, and a link to the licenses.
 *
 * Where it comes from: [SettingsRepository.observe]; every change is stored there, and the state
 * follows what was stored.
 *
 * Rules the logic keeps:
 * - A language is applied with [LocaleApplier] once stored, so it shows at once.
 * - The file size limit is typed in whole mebibytes ([fileSizeLimitText]); only a value within
 *   [MIN_FILE_SIZE_LIMIT_MIB]..[MAX_FILE_SIZE_LIMIT_MIB] is stored, in bytes. Anything else sets
 *   [fileSizeProblem] and stores nothing.
 * - Data is deleted only after [PreferencesIntent.ConfirmDelete] while [deletion] is
 *   [DataDeletion.Confirming]: the connection is ended with [Disconnector], then
 *   [SettingsRepository.deleteAllData] runs, then [PreferencesEvent.DataDeleted] is sent. A failure
 *   sets [PreferencesError.DeleteFailed]; asking again finishes the job.
 *
 * @property fileSizeLimitBytes What is stored.
 * @property fileSizeLimitText What the field shows, in mebibytes; follows [fileSizeLimitBytes] until
 *   the user types.
 * @property fileSizeProblem Why [fileSizeLimitText] was not stored; cleared when it is edited to a
 *   valid value.
 * @property error The last change or the deletion failed.
 */
data class PreferencesState(
    val loading: Boolean = true,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val fileSizeLimitBytes: Long = AppSettings.DEFAULT_FILE_SIZE_LIMIT_BYTES,
    val fileSizeLimitText: String = "",
    val fileSizeProblem: FileSizeProblem? = null,
    val deletion: DataDeletion = DataDeletion.Idle,
    val error: PreferencesError? = null,
) {
    /** Whether the screen takes changes: not before the settings are read, nor while deleting. */
    val canEdit: Boolean
        get() = !loading && deletion != DataDeletion.Deleting

    companion object {
        const val BYTES_PER_MIB: Long = 1024L * 1024L

        const val MIN_FILE_SIZE_LIMIT_MIB: Long = 1L

        const val MAX_FILE_SIZE_LIMIT_MIB: Long = 100L
    }
}

enum class FileSizeProblem {
    Blank,

    /** Not a whole number. */
    NotANumber,

    /** Below [PreferencesState.MIN_FILE_SIZE_LIMIT_MIB]. */
    TooSmall,

    /** Above [PreferencesState.MAX_FILE_SIZE_LIMIT_MIB]. */
    TooLarge,
}

enum class DataDeletion {
    Idle,

    /** The confirmation is open; nothing is deleted yet. */
    Confirming,

    Deleting,
}

enum class PreferencesError {
    /** A language, theme or limit could not be stored. */
    SaveFailed,

    /** Deleting all data failed part way; asking again finishes it. */
    DeleteFailed,
}

sealed interface PreferencesIntent {
    data class SetLanguage(val language: AppLanguage) : PreferencesIntent

    data class SetTheme(val theme: ThemePreference) : PreferencesIntent

    /** The limit field edited, in mebibytes; stored when valid. */
    data class EditFileSizeLimit(val text: String) : PreferencesIntent

    /** Opens the confirmation: [DataDeletion.Confirming]. */
    data object RequestDelete : PreferencesIntent

    /** Deletes everything, only while [DataDeletion.Confirming]. */
    data object ConfirmDelete : PreferencesIntent

    data object CancelDelete : PreferencesIntent

    /** Clears [PreferencesState.error]. */
    data object DismissError : PreferencesIntent

    /** Sends [PreferencesEvent.OpenLicenses]. */
    data object OpenLicenses : PreferencesIntent

    data object Close : PreferencesIntent
}

sealed interface PreferencesEvent {
    data object OpenLicenses : PreferencesEvent

    /** Everything is deleted: the graph goes back to the server list, which is empty now. */
    data object DataDeleted : PreferencesEvent

    data object Close : PreferencesEvent
}

interface PreferencesPresenter : Presenter<PreferencesState, PreferencesIntent, PreferencesEvent>
