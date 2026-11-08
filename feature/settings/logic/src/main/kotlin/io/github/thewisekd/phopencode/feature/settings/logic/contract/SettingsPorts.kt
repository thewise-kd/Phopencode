package io.github.thewisekd.phopencode.feature.settings.logic.contract

import io.github.thewisekd.phopencode.core.storage_api.AppLanguage

/**
 * Puts a language on screen at once, without a restart: the app's per-app locale. The settings
 * presenter calls it after storing the choice, and only from its scope.
 *
 * The implementation is Android's (`prefs/AppCompatLocaleApplier` in the settings ui module, P10.9);
 * tests pass a recording one. [AppLanguage.SYSTEM] clears the app's own locale, so the system's
 * applies.
 */
fun interface LocaleApplier {
    fun apply(language: AppLanguage)
}

/**
 * Ends the active connection, if any: deleting all data removes the secrets and the profile it was
 * made with. `SettingsModule` binds it to `ConnectionManager.disconnect`.
 */
fun interface Disconnector {
    suspend fun disconnect()
}
