package io.github.thewisekd.phopencode.feature.settings.logic.contract

import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.feature.settings.logic.prefs.DefaultPreferencesPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

/**
 * The presenter is built with the constructor the contract fixes, as `SettingsModule` builds it, and
 * shows the initial state before anything has run. P10.8 fills it in; this holds for it too. Every
 * dependency fails if called: nothing may be read, saved, applied or disconnected before the scope
 * runs.
 */
class PresenterStubsTest {
    private val settings = object : SettingsRepository {
        override fun observe(): Flow<AppSettings> = error("Not called before the scope runs")

        override suspend fun setLanguage(language: AppLanguage) = error("Not called")

        override suspend fun setTheme(theme: ThemePreference) = error("Not called")

        override suspend fun setFileSizeLimit(bytes: Long) = error("Not called")

        override suspend fun deleteAllData() = error("Not called")
    }

    @Test
    fun `preferences start loading`() = runTest {
        val presenter: PreferencesPresenter = DefaultPreferencesPresenter(
            settings = settings,
            locales = LocaleApplier { error("Not called before the scope runs") },
            disconnector = Disconnector { error("Not called before the scope runs") },
            scope = backgroundScope,
        )

        assertEquals(PreferencesState(), presenter.state.value)
    }
}
