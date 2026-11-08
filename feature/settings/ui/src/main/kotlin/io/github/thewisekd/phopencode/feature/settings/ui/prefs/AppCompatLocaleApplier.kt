package io.github.thewisekd.phopencode.feature.settings.ui.prefs

import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.feature.settings.logic.contract.LocaleApplier

/**
 * Stub from the contract (P10.1), kept beside the screen it serves: applies nothing. P10.9 fills it
 * in without changing the constructor, with `AppCompatDelegate.setApplicationLocales` (`tr`, `en`,
 * or an empty list for [AppLanguage.SYSTEM]). `SettingsModule` makes one for the app.
 */
class AppCompatLocaleApplier : LocaleApplier {
    override fun apply(language: AppLanguage) = Unit
}
