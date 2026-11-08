package io.github.thewisekd.phopencode

import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update

/**
 * A [SettingsRepository] for the theme tests. It starts with [initial] stored, or, given null,
 * with nothing read yet: [observe] then emits nothing until a setter runs, like a store whose
 * first read has not finished. [fail] makes [observe] throw, like a store whose read broke.
 */
class ThemeSettingsFake(initial: AppSettings? = AppSettings()) : SettingsRepository {
    /** Null: nothing read yet; an [AppSettings]: the stored value; an [IOException]: a failed read. */
    private val state = MutableStateFlow<Any?>(initial)

    override fun observe(): Flow<AppSettings> = state.transform {
        when (it) {
            is AppSettings -> emit(it)
            is IOException -> throw it
        }
    }

    fun fail() {
        state.value = IOException("settings unreadable")
    }

    private fun change(transform: (AppSettings) -> AppSettings) {
        state.update { transform(it as? AppSettings ?: AppSettings()) }
    }

    override suspend fun setLanguage(language: AppLanguage) = change { it.copy(language = language) }

    override suspend fun setTheme(theme: ThemePreference) = change { it.copy(theme = theme) }

    override suspend fun setFileSizeLimit(bytes: Long) = change { it.copy(fileSizeLimitBytes = bytes) }

    override suspend fun deleteAllData() {
        state.value = AppSettings()
    }

    companion object {
        fun storing(theme: ThemePreference) = ThemeSettingsFake(AppSettings(theme = theme))
    }
}
