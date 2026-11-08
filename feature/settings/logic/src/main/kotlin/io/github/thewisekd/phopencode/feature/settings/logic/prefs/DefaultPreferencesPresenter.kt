package io.github.thewisekd.phopencode.feature.settings.logic.prefs

import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.feature.settings.logic.contract.Disconnector
import io.github.thewisekd.phopencode.feature.settings.logic.contract.LocaleApplier
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesEvent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesPresenter
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P10.1): shows the initial state and ignores intents. P10.8 fills it in
 * without changing the constructor; reading the settings starts in [scope] after a `yield()`.
 *
 * @param settings The stored preferences.
 * @param locales Puts a stored language on screen at once.
 * @param disconnector Ends the active connection before all data is deleted.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultPreferencesPresenter(
    private val settings: SettingsRepository,
    private val locales: LocaleApplier,
    private val disconnector: Disconnector,
    private val scope: CoroutineScope,
) : PreferencesPresenter {
    private val mutableState = MutableStateFlow(PreferencesState())

    override val state: StateFlow<PreferencesState> = mutableState.asStateFlow()

    override val events: Flow<PreferencesEvent> = emptyFlow()

    override fun onIntent(intent: PreferencesIntent) = Unit
}
