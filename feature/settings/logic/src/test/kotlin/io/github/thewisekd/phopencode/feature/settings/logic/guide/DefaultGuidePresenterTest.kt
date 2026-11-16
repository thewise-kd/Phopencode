package io.github.thewisekd.phopencode.feature.settings.logic.guide

import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideEvent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuidePage
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideState
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * [DefaultGuidePresenter] pages through the guide and, on Skip or Start, records that the guide was
 * seen, then always lets the user out: a store that fails or hangs never keeps them in the guide.
 * The settings store is its only way out, and only `markGuideSeen()` is ever called on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultGuidePresenterTest {
    /** Every call the store received, in order. */
    private val calls = mutableListOf<String>()

    private inner class FakeSettings : SettingsRepository {
        val stored = MutableStateFlow(AppSettings())
        var failure: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null

        override fun observe(): Flow<AppSettings> = error("The guide does not read the settings")

        override suspend fun setLanguage(language: AppLanguage) = error("Not called")

        override suspend fun setTheme(theme: ThemePreference) = error("Not called")

        override suspend fun setFileSizeLimit(bytes: Long) = error("Not called")

        override suspend fun markGuideSeen() {
            calls += "markGuideSeen"
            gate?.await()
            failure?.let { throw it }
            stored.value = stored.value.copy(guideSeen = true)
        }

        override suspend fun acceptPreviewNotice() = error("Not called")

        override suspend fun deleteAllData() = error("Not called")
    }

    private val settings = FakeSettings()

    /** A presenter with every event collected into the returned list. */
    private fun TestScope.started(
        scope: CoroutineScope = backgroundScope,
    ): Pair<DefaultGuidePresenter, MutableList<GuideEvent>> {
        val presenter = DefaultGuidePresenter(settings, scope)
        val events = mutableListOf<GuideEvent>()
        backgroundScope.launch { presenter.events.collect { events += it } }
        runCurrent()
        return presenter to events
    }

    private fun DefaultGuidePresenter.send(vararg intents: GuideIntent) = intents.forEach(::onIntent)

    @Test
    fun `next walks through every page in order and stops on the last`() = runTest {
        val (presenter, events) = started()

        val seen = mutableListOf(presenter.state.value.page)
        repeat(GuidePage.entries.size + 2) {
            presenter.send(GuideIntent.Next)
            seen += presenter.state.value.page
        }
        runCurrent()

        assertEquals(GuidePage.entries, seen.distinct())
        assertEquals(GuidePage.Done, presenter.state.value.page)
        assertEquals(emptyList(), events, "next on the last page does not finish: Start does")
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `back goes to the previous page`() = runTest {
        val (presenter, events) = started()

        presenter.send(GuideIntent.Next, GuideIntent.Next, GuideIntent.Back)
        runCurrent()

        assertEquals(GuidePage.OnYourServer, presenter.state.value.page)
        assertEquals(emptyList(), events)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `a swipe shows the page swiped to`() = runTest {
        val (presenter, _) = started()

        presenter.send(GuideIntent.ShowPage(GuidePage.LivePreview))

        assertEquals(GuidePage.LivePreview, presenter.state.value.page)
        assertEquals(7, presenter.state.value.position)
    }

    @Test
    fun `skip on any page records the guide as seen, then finishes`() = runTest {
        for (page in GuidePage.entries) {
            calls.clear()
            val (presenter, events) = started()
            presenter.send(GuideIntent.ShowPage(page), GuideIntent.Skip)
            assertTrue(presenter.state.value.finishing)
            runCurrent()

            assertEquals(listOf("markGuideSeen"), calls, "on $page")
            assertEquals(listOf<GuideEvent>(GuideEvent.Finished), events, "on $page")
        }
        assertTrue(settings.stored.value.guideSeen)
    }

    @Test
    fun `start on the last page records the guide as seen, then finishes`() = runTest {
        val (presenter, events) = started()

        presenter.send(GuideIntent.ShowPage(GuidePage.Done), GuideIntent.Start)
        runCurrent()

        assertEquals(listOf("markGuideSeen"), calls)
        assertEquals(listOf<GuideEvent>(GuideEvent.Finished), events)
        assertTrue(settings.stored.value.guideSeen)
    }

    @Test
    fun `back on the first page leaves the guide as skip does`() = runTest {
        val (presenter, events) = started()

        presenter.send(GuideIntent.Back)
        runCurrent()

        assertEquals(listOf("markGuideSeen"), calls)
        assertEquals(listOf<GuideEvent>(GuideEvent.Finished), events)
    }

    @Test
    fun `the event waits until the guide is recorded as seen`() = runTest {
        val gate = CompletableDeferred<Unit>()
        settings.gate = gate
        val (presenter, events) = started()

        presenter.send(GuideIntent.Skip)
        runCurrent()
        assertEquals(listOf("markGuideSeen"), calls)
        assertEquals(emptyList(), events, "leaving now would cancel the write with the destination")

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf<GuideEvent>(GuideEvent.Finished), events)
    }

    @Test
    fun `finishing happens once, and nothing else is taken meanwhile`() = runTest {
        val gate = CompletableDeferred<Unit>()
        settings.gate = gate
        val (presenter, events) = started()
        presenter.send(GuideIntent.Next)

        presenter.send(GuideIntent.Start, GuideIntent.Skip, GuideIntent.Next, GuideIntent.Back)
        presenter.send(GuideIntent.ShowPage(GuidePage.Done))
        gate.complete(Unit)
        runCurrent()

        assertEquals(GuideState(page = GuidePage.OnYourServer, finishing = true), presenter.state.value)
        assertEquals(listOf("markGuideSeen"), calls)
        assertEquals(listOf<GuideEvent>(GuideEvent.Finished), events)
    }

    @Test
    fun `a store that fails does not keep the user in the guide`() = runTest {
        settings.failure = IOException("disk full")
        val (presenter, events) = started()

        presenter.send(GuideIntent.Skip)
        runCurrent()

        assertEquals(listOf("markGuideSeen"), calls)
        assertEquals(listOf<GuideEvent>(GuideEvent.Finished), events)
        assertFalse(settings.stored.value.guideSeen)
    }

    @Test
    fun `a store that throws anything does not keep the user in the guide`() = runTest {
        settings.failure = IllegalStateException("broken")
        val (presenter, events) = started()

        presenter.send(GuideIntent.Start)
        runCurrent()

        assertEquals(listOf<GuideEvent>(GuideEvent.Finished), events)
    }

    @Test
    fun `a store that hangs lets the user out after a while`() = runTest {
        settings.gate = CompletableDeferred()
        val (presenter, events) = started()

        presenter.send(GuideIntent.Skip)
        advanceTimeBy(DefaultGuidePresenter.FINISH_TIMEOUT - 1.seconds)
        runCurrent()
        assertEquals(emptyList(), events)

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf<GuideEvent>(GuideEvent.Finished), events)
    }

    @Test
    fun `an event sent while nobody collects waits for the next collector`() = runTest {
        val presenter = DefaultGuidePresenter(settings, backgroundScope)
        presenter.send(GuideIntent.Skip)
        runCurrent()

        val events = mutableListOf<GuideEvent>()
        backgroundScope.launch { presenter.events.collect { events += it } }
        runCurrent()

        assertEquals(listOf<GuideEvent>(GuideEvent.Finished), events)
    }

    @Test
    fun `nothing is stored before the scope runs`() = runTest {
        val presenter = DefaultGuidePresenter(settings, backgroundScope)

        presenter.send(GuideIntent.Skip)

        assertEquals(emptyList(), calls)
        assertTrue(presenter.state.value.finishing)
    }

    @Test
    fun `the work stops with the destination's scope`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        settings.gate = CompletableDeferred()
        val (presenter, events) = started(scope)

        presenter.send(GuideIntent.Skip)
        runCurrent()
        scope.cancel()
        runCurrent()

        assertEquals(emptyList(), events)
    }

    @Test
    fun `printing the presenter shows its name only`() = runTest {
        assertEquals("DefaultGuidePresenter", DefaultGuidePresenter(settings, backgroundScope).toString())
    }
}
