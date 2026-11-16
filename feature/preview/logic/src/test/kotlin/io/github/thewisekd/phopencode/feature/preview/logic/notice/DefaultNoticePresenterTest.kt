package io.github.thewisekd.phopencode.feature.preview.logic.notice

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.testing.storage.FakeSettingsRepository
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticePhase
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeState
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Settings whose reads and stores a test controls; counts the stores. */
private class ScriptedSettings(
    private val read: () -> Flow<AppSettings>,
    private val store: suspend () -> Unit = {},
) : SettingsRepository {
    var accepts = 0
        private set

    override fun observe(): Flow<AppSettings> = read()

    override suspend fun setLanguage(language: io.github.thewisekd.phopencode.core.storage_api.AppLanguage) = error("Not called")

    override suspend fun setTheme(theme: io.github.thewisekd.phopencode.core.storage_api.ThemePreference) = error("Not called")

    override suspend fun setFileSizeLimit(bytes: Long) = error("Not called")

    override suspend fun markGuideSeen() = error("Not called")

    override suspend fun acceptPreviewNotice() {
        accepts++
        store()
    }

    override suspend fun deleteAllData() = error("Not called")
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultNoticePresenterTest {
    private val settings = FakeSettingsRepository()

    private fun TestScope.presenter(repository: SettingsRepository = settings) =
        DefaultNoticePresenter(settings = repository, scope = backgroundScope)

    @Test
    fun `it starts checking and blocks the screen before the scope runs`() = runTest {
        val presenter = presenter()

        assertEquals(NoticeState(), presenter.state.value)
        assertEquals(NoticePhase.Checking, presenter.state.value.phase)
        assertTrue(presenter.state.value.blocksScreen)
        assertFalse(presenter.state.value.dialogShown)
    }

    @Test
    fun `a notice never confirmed shows`() = runTest {
        val presenter = presenter()
        runCurrent()

        assertEquals(NoticePhase.Showing, presenter.state.value.phase)
        assertTrue(presenter.state.value.dialogShown)
        assertTrue(presenter.state.value.blocksScreen)
    }

    @Test
    fun `a notice confirmed before stays hidden`() = runTest {
        settings.acceptPreviewNotice()
        val presenter = presenter()
        runCurrent()

        assertEquals(NoticePhase.Hidden, presenter.state.value.phase)
        assertFalse(presenter.state.value.blocksScreen)
        assertFalse(presenter.state.value.dialogShown)
    }

    @Test
    fun `a setting that cannot be read shows the notice`() = runTest {
        val broken = ScriptedSettings(read = { flow { throw IOException("unreadable") } })
        val presenter = presenter(broken)
        runCurrent()

        assertEquals(NoticePhase.Showing, presenter.state.value.phase)
    }

    @Test
    fun `accepting stores the confirmation and hides the notice`() = runTest {
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(NoticeIntent.Accept)
        runCurrent()

        assertTrue(settings.observe().first().previewNoticeAccepted)
        assertEquals(NoticeState(phase = NoticePhase.Hidden), presenter.state.value)
    }

    @Test
    fun `after accepting a new visit, as after a restart, does not show it`() = runTest {
        val first = presenter()
        runCurrent()
        first.onIntent(NoticeIntent.Accept)
        runCurrent()

        val second = presenter()
        runCurrent()

        assertEquals(NoticePhase.Hidden, second.state.value.phase)
    }

    @Test
    fun `while storing the dialog stays and a second accept stores nothing more`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = ScriptedSettings(read = { settings.observe() }, store = { gate.await() })
        val presenter = presenter(slow)
        runCurrent()

        presenter.onIntent(NoticeIntent.Accept)
        runCurrent()
        assertEquals(NoticePhase.Saving, presenter.state.value.phase)
        assertTrue(presenter.state.value.dialogShown)
        presenter.onIntent(NoticeIntent.Accept)
        runCurrent()
        assertEquals(1, slow.accepts)

        gate.complete(Unit)
        runCurrent()
        assertEquals(NoticePhase.Hidden, presenter.state.value.phase)
    }

    @Test
    fun `while storing not now is ignored, so leaving never races an acceptance`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = ScriptedSettings(read = { settings.observe() }, store = { gate.await() })
        val presenter = presenter(slow)
        runCurrent()

        presenter.events.test {
            presenter.onIntent(NoticeIntent.Accept)
            runCurrent()
            presenter.onIntent(NoticeIntent.NotNow)
            runCurrent()
            expectNoEvents()
            assertEquals(NoticePhase.Saving, presenter.state.value.phase)

            gate.complete(Unit)
            runCurrent()
            assertEquals(NoticePhase.Hidden, presenter.state.value.phase)
            presenter.onIntent(NoticeIntent.NotNow)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `after a failed store not now leaves again`() = runTest {
        val presenter = presenter()
        runCurrent()
        settings.unavailable = true
        presenter.onIntent(NoticeIntent.Accept)
        runCurrent()

        presenter.events.test {
            presenter.onIntent(NoticeIntent.NotNow)
            assertEquals(NoticeEvent.Leave, awaitItem())
        }
        assertFalse(settings.observe().first().previewNoticeAccepted)
    }

    @Test
    fun `a failed store keeps the dialog and the user can try again`() = runTest {
        val presenter = presenter()
        runCurrent()
        settings.unavailable = true

        presenter.onIntent(NoticeIntent.Accept)
        runCurrent()

        assertEquals(NoticeState(phase = NoticePhase.Showing, saveFailed = true), presenter.state.value)
        assertFalse(settings.observe().first().previewNoticeAccepted)

        settings.unavailable = false
        presenter.onIntent(NoticeIntent.Accept)
        runCurrent()

        assertEquals(NoticeState(phase = NoticePhase.Hidden), presenter.state.value)
        assertTrue(settings.observe().first().previewNoticeAccepted)
    }

    @Test
    fun `a store that throws something other than IO also keeps the dialog`() = runTest {
        val odd = ScriptedSettings(read = { settings.observe() }, store = { throw IllegalStateException("odd") })
        val presenter = presenter(odd)
        runCurrent()

        presenter.onIntent(NoticeIntent.Accept)
        runCurrent()

        assertEquals(NoticeState(phase = NoticePhase.Showing, saveFailed = true), presenter.state.value)
    }

    @Test
    fun `accept before the setting is known stores nothing`() = runTest {
        val counting = ScriptedSettings(read = { settings.observe() })
        val presenter = presenter(counting)

        presenter.onIntent(NoticeIntent.Accept)
        runCurrent()

        assertEquals(0, counting.accepts)
        assertEquals(NoticePhase.Showing, presenter.state.value.phase)
    }

    @Test
    fun `not now leaves without storing, so the next visit shows it again`() = runTest {
        val counting = ScriptedSettings(read = { settings.observe() })
        val presenter = presenter(counting)
        runCurrent()

        presenter.events.test {
            presenter.onIntent(NoticeIntent.NotNow)
            assertEquals(NoticeEvent.Leave, awaitItem())
        }
        runCurrent()

        assertEquals(0, counting.accepts)
        assertFalse(settings.observe().first().previewNoticeAccepted)
        val next = presenter(counting)
        runCurrent()
        assertEquals(NoticePhase.Showing, next.state.value.phase)
    }

    @Test
    fun `not now leaves once, and a second not now or back sends nothing more`() = runTest {
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            presenter.onIntent(NoticeIntent.NotNow)
            assertEquals(NoticeEvent.Leave, awaitItem())
            assertEquals(NoticePhase.Left, presenter.state.value.phase)

            presenter.onIntent(NoticeIntent.NotNow)
            presenter.onIntent(NoticeIntent.NotNow)
            runCurrent()
            expectNoEvents()
        }
        assertEquals(NoticePhase.Left, presenter.state.value.phase)
    }

    @Test
    fun `accept after not now stores nothing`() = runTest {
        val counting = ScriptedSettings(read = { settings.observe() })
        val presenter = presenter(counting)
        runCurrent()

        presenter.onIntent(NoticeIntent.NotNow)
        presenter.onIntent(NoticeIntent.Accept)
        runCurrent()

        assertEquals(0, counting.accepts)
        assertFalse(settings.observe().first().previewNoticeAccepted)
        assertEquals(NoticePhase.Left, presenter.state.value.phase)
    }

    @Test
    fun `after leaving the list stays blocked and the answers wait`() = runTest {
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(NoticeIntent.NotNow)

        assertTrue(presenter.state.value.blocksScreen)
        assertTrue(presenter.state.value.dialogShown)
    }

    @Test
    fun `a leave sent while nobody collects waits for the collector`() = runTest {
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(NoticeIntent.NotNow)

        presenter.events.test {
            assertEquals(NoticeEvent.Leave, awaitItem())
        }
    }

    @Test
    fun `nothing is shown or blocked once the notice is hidden, and intents then do nothing`() = runTest {
        settings.acceptPreviewNotice()
        val counting = ScriptedSettings(read = { settings.observe() })
        val presenter = presenter(counting)
        runCurrent()

        presenter.onIntent(NoticeIntent.Accept)
        runCurrent()

        assertEquals(0, counting.accepts)
        assertEquals(NoticePhase.Hidden, presenter.state.value.phase)
    }
}
