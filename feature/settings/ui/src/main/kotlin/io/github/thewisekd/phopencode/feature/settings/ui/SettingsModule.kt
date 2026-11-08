package io.github.thewisekd.phopencode.feature.settings.ui

import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.feature.settings.logic.contract.Disconnector
import io.github.thewisekd.phopencode.feature.settings.logic.contract.LocaleApplier
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesPresenter
import io.github.thewisekd.phopencode.feature.settings.logic.prefs.DefaultPreferencesPresenter
import io.github.thewisekd.phopencode.feature.settings.ui.prefs.AppCompatLocaleApplier
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The settings feature's definitions, which the app loads at startup. The settings store and the
 * connection manager come from the app's core modules.
 *
 * - [LocaleApplier] is the app's one [AppCompatLocaleApplier].
 * - [Disconnector] ends the connection manager's connection.
 * - [PreferencesPresenter] is a factory under its contract interface, built per destination with two
 *   parameters: the destination's route and its coroutine scope (see `rememberPresenter` in
 *   SettingsGraph). The scope is a parameter, never `get()`: the app binds no bare scope.
 * - The licenses screen has no presenter.
 */
val settingsModule: Module = module {
    single<LocaleApplier> { AppCompatLocaleApplier() }
    single<Disconnector> {
        val connection = get<ConnectionManager>()
        Disconnector { connection.disconnect() }
    }

    factory<PreferencesPresenter> { (_: SettingsRoute.Preferences, scope: CoroutineScope) ->
        DefaultPreferencesPresenter(settings = get(), locales = get(), disconnector = get(), scope = scope)
    }
}
