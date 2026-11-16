package io.github.thewisekd.phopencode.feature.preview.ui.notice

import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeState
import io.github.thewisekd.phopencode.feature.preview.logic.notice.DefaultNoticePresenter
import io.github.thewisekd.phopencode.feature.preview.ui.previewModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame

/** Settings that only hold the notice flag; nothing else is used here. */
private class NoticeOnlySettings : SettingsRepository {
    val settings = MutableStateFlow(AppSettings())

    override fun observe(): Flow<AppSettings> = settings

    override suspend fun setLanguage(language: AppLanguage) = error("Not used")

    override suspend fun setTheme(theme: ThemePreference) = error("Not used")

    override suspend fun setFileSizeLimit(bytes: Long) = error("Not used")

    override suspend fun markGuideSeen() = error("Not used")

    override suspend fun acceptPreviewNotice() {
        settings.value = settings.value.copy(previewNoticeAccepted = true)
    }

    override suspend fun deleteAllData() = error("Not used")
}

/**
 * `previewModule` builds the notice's presenter per destination from the scope the screen passes,
 * on the app's settings. Plain JVM: nothing here runs Android code.
 */
class NoticeBindingTest {
    /** Nothing it is given runs until a test advances it, so the state read is the initial one. */
    private val screenScope = CoroutineScope(Job() + StandardTestDispatcher())

    @After
    fun stop() {
        stopKoin()
        screenScope.cancel()
    }

    @Test
    fun `the notice presenter is built per destination on the app's settings`() {
        val koin = startKoin {
            modules(previewModule, module { single<SettingsRepository> { NoticeOnlySettings() } })
        }.koin

        val first = koin.get<NoticePresenter> { parametersOf(screenScope) }
        val second = koin.get<NoticePresenter> { parametersOf(screenScope) }

        assertIs<DefaultNoticePresenter>(first)
        assertEquals(NoticeState(), first.state.value)
        assertNotSame(first, second)
    }
}
