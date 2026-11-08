package io.github.thewisekd.phopencode.feature.settings.logic.prefs

import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.feature.settings.logic.contract.DataDeletion
import io.github.thewisekd.phopencode.feature.settings.logic.contract.Disconnector
import io.github.thewisekd.phopencode.feature.settings.logic.contract.FileSizeProblem
import io.github.thewisekd.phopencode.feature.settings.logic.contract.LocaleApplier
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesError
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesEvent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * [DefaultPreferencesPresenter] stores the language, theme and file size limit through the settings
 * store, applies a stored language at once, and deletes the app's own data only after a confirmation,
 * ending the connection first. Its only ways out are the three ports it is given: nothing reaches the
 * server, no process is stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPreferencesPresenterTest {
    private val mib = PreferencesState.BYTES_PER_MIB

    /** Every call any port received, in order, across the store, the locale applier and the disconnector. */
    private val calls = mutableListOf<String>()

    private inner class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
        val stored = MutableStateFlow(initial)
        var observed = 0
        var saveFailure: Throwable? = null
        var deleteFailure: Throwable? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var deleteGate: CompletableDeferred<Unit>? = null

        override fun observe(): Flow<AppSettings> {
            observed++
            return stored
        }

        private suspend fun save(call: String, change: (AppSettings) -> AppSettings) {
            calls += call
            callGates[call]?.await()
            saveGate?.await()
            saveFailure?.let { throw it }
            stored.value = change(stored.value)
            returnGate?.await()
        }

        /** Holds the one write named here, such as `setLanguage(TURKISH)`, before it is stored. */
        val callGates = mutableMapOf<String, CompletableDeferred<Unit>>()

        /** Holds a write after it was stored, before it returns. */
        var returnGate: CompletableDeferred<Unit>? = null

        /** Holds the deletion after it was done, before it returns. */
        var deleteReturnGate: CompletableDeferred<Unit>? = null

        override suspend fun setLanguage(language: AppLanguage) =
            save("setLanguage($language)") { it.copy(language = language) }

        override suspend fun setTheme(theme: ThemePreference) = save("setTheme($theme)") { it.copy(theme = theme) }

        override suspend fun setFileSizeLimit(bytes: Long) =
            save("setFileSizeLimit($bytes)") { it.copy(fileSizeLimitBytes = bytes) }

        override suspend fun deleteAllData() {
            calls += "deleteAllData"
            deleteGate?.await()
            deleteFailure?.let { throw it }
            stored.value = AppSettings()
            deleteReturnGate?.await()
        }
    }

    private var disconnectFailure: Throwable? = null

    private var applyFailure: Throwable? = null

    private val locales = LocaleApplier {
        calls += "apply($it)"
        applyFailure?.let { failure -> throw failure }
    }

    private val disconnector = Disconnector {
        calls += "disconnect"
        disconnectFailure?.let { throw it }
    }

    private fun TestScope.presenter(settings: FakeSettings): DefaultPreferencesPresenter =
        DefaultPreferencesPresenter(settings, locales, disconnector, backgroundScope)

    /** A presenter whose settings are read, with every event collected into the returned list. */
    private fun TestScope.loaded(
        settings: FakeSettings,
    ): Pair<DefaultPreferencesPresenter, MutableList<PreferencesEvent>> {
        val presenter = presenter(settings)
        val events = mutableListOf<PreferencesEvent>()
        backgroundScope.launch { presenter.events.collect { events += it } }
        runCurrent()
        return presenter to events
    }

    @Test
    fun `shows the initial state until the scope runs, then the stored settings`() = runTest {
        val settings = FakeSettings(AppSettings(AppLanguage.TURKISH, ThemePreference.DARK, 7 * mib))
        val presenter = presenter(settings)

        assertEquals(PreferencesState(), presenter.state.value)
        assertEquals(0, settings.observed)

        runCurrent()

        assertEquals(
            PreferencesState(
                loading = false,
                language = AppLanguage.TURKISH,
                theme = ThemePreference.DARK,
                fileSizeLimitBytes = 7 * mib,
                fileSizeLimitText = "7",
            ),
            presenter.state.value,
        )
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `ignores changes before the settings are read`() = runTest {
        val settings = FakeSettings()
        val presenter = presenter(settings)

        presenter.onIntent(PreferencesIntent.SetLanguage(AppLanguage.ENGLISH))
        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.DARK))
        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("5"))
        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()

        assertEquals(emptyList(), calls)
        assertEquals(AppSettings(), settings.stored.value)
        assertEquals(DataDeletion.Idle, presenter.state.value.deletion)
        assertEquals("1", presenter.state.value.fileSizeLimitText)
    }

    @Test
    fun `each language is stored, then applied, and the state follows what was stored`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)

        for (language in listOf(AppLanguage.TURKISH, AppLanguage.ENGLISH, AppLanguage.SYSTEM)) {
            calls.clear()
            presenter.onIntent(PreferencesIntent.SetLanguage(language))
            runCurrent()

            assertEquals(listOf("setLanguage($language)", "apply($language)"), calls)
            assertEquals(language, settings.stored.value.language)
            assertEquals(language, presenter.state.value.language)
            assertNull(presenter.state.value.error)
        }
    }

    @Test
    fun `a language that could not be stored is not applied`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)
        settings.saveFailure = IOException("disk full")

        presenter.onIntent(PreferencesIntent.SetLanguage(AppLanguage.TURKISH))
        runCurrent()

        assertEquals(listOf("setLanguage(TURKISH)"), calls)
        assertEquals(AppLanguage.SYSTEM, presenter.state.value.language)
        assertEquals(PreferencesError.SaveFailed, presenter.state.value.error)

        presenter.onIntent(PreferencesIntent.DismissError)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `the language changes only when the store says so`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)
        settings.saveGate = CompletableDeferred()

        presenter.onIntent(PreferencesIntent.SetLanguage(AppLanguage.ENGLISH))
        runCurrent()
        assertEquals(AppLanguage.SYSTEM, presenter.state.value.language)
        assertEquals(listOf("setLanguage(ENGLISH)"), calls)

        settings.saveGate!!.complete(Unit)
        runCurrent()
        assertEquals(AppLanguage.ENGLISH, presenter.state.value.language)
        assertEquals(listOf("setLanguage(ENGLISH)", "apply(ENGLISH)"), calls)
    }

    @Test
    fun `the theme is stored and shown, and never touches the locale`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)

        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.DARK))
        runCurrent()

        assertEquals(listOf("setTheme(DARK)"), calls)
        assertEquals(ThemePreference.DARK, settings.stored.value.theme)
        assertEquals(ThemePreference.DARK, presenter.state.value.theme)

        settings.saveFailure = IOException("disk full")
        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.LIGHT))
        runCurrent()

        assertEquals(ThemePreference.DARK, presenter.state.value.theme)
        assertEquals(PreferencesError.SaveFailed, presenter.state.value.error)
    }

    @Test
    fun `a valid limit is stored in bytes`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)

        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("25"))
        assertEquals("25", presenter.state.value.fileSizeLimitText)
        runCurrent()

        assertEquals(listOf("setFileSizeLimit(${25 * mib})"), calls)
        assertEquals(25 * mib, presenter.state.value.fileSizeLimitBytes)
        assertEquals("25", presenter.state.value.fileSizeLimitText)
        assertNull(presenter.state.value.fileSizeProblem)
    }

    @Test
    fun `both ends of the range are stored`() = runTest {
        val settings = FakeSettings(AppSettings(fileSizeLimitBytes = 10 * mib))
        val (presenter, _) = loaded(settings)

        presenter.onIntent(PreferencesIntent.EditFileSizeLimit(PreferencesState.MIN_FILE_SIZE_LIMIT_MIB.toString()))
        runCurrent()
        assertEquals(PreferencesState.MIN_FILE_SIZE_LIMIT_MIB * mib, settings.stored.value.fileSizeLimitBytes)

        presenter.onIntent(PreferencesIntent.EditFileSizeLimit(PreferencesState.MAX_FILE_SIZE_LIMIT_MIB.toString()))
        runCurrent()
        assertEquals(PreferencesState.MAX_FILE_SIZE_LIMIT_MIB * mib, settings.stored.value.fileSizeLimitBytes)
        assertNull(presenter.state.value.fileSizeProblem)
    }

    @Test
    fun `surrounding spaces are ignored`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)

        presenter.onIntent(PreferencesIntent.EditFileSizeLimit(" 12 "))
        runCurrent()

        assertEquals(12 * mib, settings.stored.value.fileSizeLimitBytes)
        assertEquals(" 12 ", presenter.state.value.fileSizeLimitText)
        assertNull(presenter.state.value.fileSizeProblem)
    }

    @Test
    fun `a bad limit stores nothing and says why`() = runTest {
        val cases = listOf(
            "" to FileSizeProblem.Blank,
            "   " to FileSizeProblem.Blank,
            "abc" to FileSizeProblem.NotANumber,
            "1.5" to FileSizeProblem.NotANumber,
            "1,5" to FileSizeProblem.NotANumber,
            "5MB" to FileSizeProblem.NotANumber,
            "1e3" to FileSizeProblem.NotANumber,
            "٥" to FileSizeProblem.NotANumber, // Arabic-Indic five: only ASCII digits count.
            "0" to FileSizeProblem.TooSmall,
            "-3" to FileSizeProblem.TooSmall,
            "101" to FileSizeProblem.TooLarge,
            "99999999999999999999999" to FileSizeProblem.TooLarge,
            "9223372036854775807" to FileSizeProblem.TooLarge,
        )
        val settings = FakeSettings(AppSettings(fileSizeLimitBytes = 3 * mib))
        val (presenter, _) = loaded(settings)

        for ((text, problem) in cases) {
            presenter.onIntent(PreferencesIntent.EditFileSizeLimit(text))
            runCurrent()

            assertEquals(problem, presenter.state.value.fileSizeProblem, "for '$text'")
            assertEquals(text, presenter.state.value.fileSizeLimitText)
            assertEquals(3 * mib, presenter.state.value.fileSizeLimitBytes)
        }
        assertEquals(emptyList(), calls)
        assertEquals(3 * mib, settings.stored.value.fileSizeLimitBytes)
    }

    @Test
    fun `editing to a valid value clears the problem`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)

        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("500"))
        assertEquals(FileSizeProblem.TooLarge, presenter.state.value.fileSizeProblem)

        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("50"))
        assertNull(presenter.state.value.fileSizeProblem)
        runCurrent()
        assertEquals(50 * mib, settings.stored.value.fileSizeLimitBytes)
    }

    @Test
    fun `the field follows the stored limit until the user types`() = runTest {
        val settings = FakeSettings(AppSettings(fileSizeLimitBytes = 4 * mib))
        val (presenter, _) = loaded(settings)
        assertEquals("4", presenter.state.value.fileSizeLimitText)

        settings.stored.value = settings.stored.value.copy(fileSizeLimitBytes = 8 * mib)
        runCurrent()
        assertEquals("8", presenter.state.value.fileSizeLimitText)

        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("0"))
        settings.stored.value = settings.stored.value.copy(fileSizeLimitBytes = 9 * mib)
        runCurrent()
        assertEquals("0", presenter.state.value.fileSizeLimitText)
        assertEquals(9 * mib, presenter.state.value.fileSizeLimitBytes)
        assertEquals(FileSizeProblem.TooSmall, presenter.state.value.fileSizeProblem)
    }

    @Test
    fun `a limit that could not be stored is a save failure`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)
        settings.saveFailure = IOException("disk full")

        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("20"))
        runCurrent()

        assertEquals(PreferencesError.SaveFailed, presenter.state.value.error)
        assertEquals(AppSettings.DEFAULT_FILE_SIZE_LIMIT_BYTES, presenter.state.value.fileSizeLimitBytes)
        assertNull(presenter.state.value.fileSizeProblem)
    }

    @Test
    fun `changes are stored one at a time, in the order they were made`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)
        settings.saveGate = CompletableDeferred()

        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("2"))
        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("20"))
        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.LIGHT))
        runCurrent()
        assertEquals(listOf("setFileSizeLimit(${2 * mib})"), calls)

        settings.saveGate!!.complete(Unit)
        runCurrent()

        assertEquals(
            listOf("setFileSizeLimit(${2 * mib})", "setFileSizeLimit(${20 * mib})", "setTheme(LIGHT)"),
            calls,
        )
        assertEquals(20 * mib, settings.stored.value.fileSizeLimitBytes)
        assertEquals(ThemePreference.LIGHT, settings.stored.value.theme)
    }

    @Test
    fun `a later success clears an earlier save failure`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)
        settings.saveFailure = IOException("disk full")
        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.DARK))
        runCurrent()
        assertEquals(PreferencesError.SaveFailed, presenter.state.value.error)

        settings.saveFailure = null
        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.DARK))
        runCurrent()
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `confirming without asking first deletes nothing`() = runTest {
        val settings = FakeSettings()
        val (presenter, events) = loaded(settings)

        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()

        assertEquals(emptyList(), calls)
        assertEquals(DataDeletion.Idle, presenter.state.value.deletion)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `cancelling the confirmation deletes nothing`() = runTest {
        val settings = FakeSettings()
        val (presenter, events) = loaded(settings)

        presenter.onIntent(PreferencesIntent.RequestDelete)
        assertEquals(DataDeletion.Confirming, presenter.state.value.deletion)
        presenter.onIntent(PreferencesIntent.CancelDelete)
        assertEquals(DataDeletion.Idle, presenter.state.value.deletion)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()

        assertEquals(emptyList(), calls)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `deleting ends the connection first, then deletes the app's data, then reports it`() = runTest {
        val settings = FakeSettings(AppSettings(AppLanguage.TURKISH, ThemePreference.DARK, 30 * mib))
        val (presenter, events) = loaded(settings)
        settings.deleteGate = CompletableDeferred()

        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        assertEquals(DataDeletion.Deleting, presenter.state.value.deletion)
        assertFalse(presenter.state.value.canEdit)
        runCurrent()

        assertEquals(listOf("disconnect", "deleteAllData"), calls)
        assertEquals(emptyList(), events)

        settings.deleteGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf("disconnect", "deleteAllData", "apply(SYSTEM)"), calls)
        assertEquals(listOf<PreferencesEvent>(PreferencesEvent.DataDeleted), events)
        val state = presenter.state.value
        assertEquals(DataDeletion.Idle, state.deletion)
        assertNull(state.error)
        assertEquals(AppLanguage.SYSTEM, state.language)
        assertEquals(ThemePreference.SYSTEM, state.theme)
        assertEquals(AppSettings.DEFAULT_FILE_SIZE_LIMIT_BYTES, state.fileSizeLimitBytes)
    }

    @Test
    fun `after deleting, the field follows the defaults again`() = runTest {
        val settings = FakeSettings(AppSettings(fileSizeLimitBytes = 30 * mib))
        val (presenter, _) = loaded(settings)
        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("40"))
        runCurrent()

        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()

        assertEquals("1", presenter.state.value.fileSizeLimitText)
        assertNull(presenter.state.value.fileSizeProblem)
    }

    @Test
    fun `nothing is deleted when the connection could not be ended`() = runTest {
        val settings = FakeSettings(AppSettings(language = AppLanguage.ENGLISH))
        val (presenter, events) = loaded(settings)
        disconnectFailure = IllegalStateException("still connected")

        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()

        assertEquals(listOf("disconnect"), calls)
        assertEquals(PreferencesError.DeleteFailed, presenter.state.value.error)
        assertEquals(DataDeletion.Idle, presenter.state.value.deletion)
        assertEquals(AppLanguage.ENGLISH, settings.stored.value.language)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `a failed deletion says so, and asking again finishes it`() = runTest {
        val settings = FakeSettings()
        val (presenter, events) = loaded(settings)
        settings.deleteFailure = IOException("could not delete")

        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()

        assertEquals(listOf("disconnect", "deleteAllData"), calls)
        assertEquals(PreferencesError.DeleteFailed, presenter.state.value.error)
        assertEquals(DataDeletion.Idle, presenter.state.value.deletion)
        assertTrue(presenter.state.value.canEdit)
        assertEquals(emptyList(), events)

        settings.deleteFailure = null
        calls.clear()
        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()

        assertEquals(listOf("disconnect", "deleteAllData", "apply(SYSTEM)"), calls)
        assertNull(presenter.state.value.error)
        assertEquals(listOf<PreferencesEvent>(PreferencesEvent.DataDeleted), events)
    }

    @Test
    fun `nothing changes while deleting`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)
        settings.deleteGate = CompletableDeferred()
        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()

        presenter.onIntent(PreferencesIntent.SetLanguage(AppLanguage.TURKISH))
        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.DARK))
        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("9"))
        presenter.onIntent(PreferencesIntent.CancelDelete)
        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        assertEquals(DataDeletion.Deleting, presenter.state.value.deletion)
        assertEquals("1", presenter.state.value.fileSizeLimitText)

        settings.deleteGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf("disconnect", "deleteAllData", "apply(SYSTEM)"), calls)
        assertEquals(AppSettings(), settings.stored.value)
    }

    @Test
    fun `changes made before confirming are stored before the deletion`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)
        settings.saveGate = CompletableDeferred()

        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.DARK))
        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()
        assertEquals(listOf("setTheme(DARK)"), calls)

        settings.saveGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf("setTheme(DARK)", "disconnect", "deleteAllData", "apply(SYSTEM)"), calls)
        assertEquals(AppSettings(), settings.stored.value)
    }

    @Test
    fun `licenses and close are events`() = runTest {
        val settings = FakeSettings()
        val (presenter, events) = loaded(settings)

        presenter.onIntent(PreferencesIntent.OpenLicenses)
        presenter.onIntent(PreferencesIntent.Close)
        runCurrent()

        assertEquals(listOf(PreferencesEvent.OpenLicenses, PreferencesEvent.Close), events)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `an event sent while nobody collects waits for the next collector`() = runTest {
        val settings = FakeSettings()
        val presenter = presenter(settings)
        runCurrent()

        presenter.onIntent(PreferencesIntent.Close)
        val events = mutableListOf<PreferencesEvent>()
        backgroundScope.launch { presenter.events.collect { events += it } }
        runCurrent()

        assertEquals(listOf<PreferencesEvent>(PreferencesEvent.Close), events)
    }

    @Test
    fun `no event is dropped while nobody collects, the deletion's included`() = runTest {
        val settings = FakeSettings()
        val presenter = presenter(settings)
        runCurrent()

        repeat(500) {
            presenter.onIntent(PreferencesIntent.OpenLicenses)
            presenter.onIntent(PreferencesIntent.Close)
        }
        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()
        assertEquals(listOf("disconnect", "deleteAllData", "apply(SYSTEM)"), calls)

        val events = mutableListOf<PreferencesEvent>()
        backgroundScope.launch { presenter.events.collect { events += it } }
        runCurrent()

        assertEquals(1001, events.size)
        assertEquals(500, events.count { it == PreferencesEvent.OpenLicenses })
        assertEquals(500, events.count { it == PreferencesEvent.Close })
        assertEquals(PreferencesEvent.DataDeleted, events.last())
    }

    @Test
    fun `a quick second language is applied last, even if the first one is observed during its write`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)
        val turkishGate = CompletableDeferred<Unit>()
        settings.callGates["setLanguage(TURKISH)"] = turkishGate

        presenter.onIntent(PreferencesIntent.SetLanguage(AppLanguage.ENGLISH))
        presenter.onIntent(PreferencesIntent.SetLanguage(AppLanguage.TURKISH))
        runCurrent()
        // English's observation arrived only while Turkish's write waits.
        assertEquals(AppLanguage.ENGLISH, presenter.state.value.language)

        turkishGate.complete(Unit)
        runCurrent()

        assertEquals(
            listOf("setLanguage(ENGLISH)", "apply(ENGLISH)", "setLanguage(TURKISH)", "apply(TURKISH)"),
            calls,
        )
        assertEquals(AppLanguage.TURKISH, presenter.state.value.language)
    }

    @Test
    fun `a deletion right after a language choice resets the locale last, even if the choice is observed during it`() = runTest {
        val settings = FakeSettings()
        val (presenter, events) = loaded(settings)
        val gate = CompletableDeferred<Unit>()
        settings.deleteGate = gate

        presenter.onIntent(PreferencesIntent.SetLanguage(AppLanguage.ENGLISH))
        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()
        // English's observation arrived only while the deletion waits.
        assertEquals(AppLanguage.ENGLISH, presenter.state.value.language)

        gate.complete(Unit)
        runCurrent()

        assertEquals(
            listOf("setLanguage(ENGLISH)", "apply(ENGLISH)", "disconnect", "deleteAllData", "apply(SYSTEM)"),
            calls,
        )
        assertEquals(AppLanguage.SYSTEM, presenter.state.value.language)
        assertEquals(listOf<PreferencesEvent>(PreferencesEvent.DataDeleted), events)
    }

    @Test
    fun `an upstream cancellation of observing, with the scope alive, still lets saves show`() = runTest {
        val stored = MutableStateFlow(AppSettings())
        val settings = object : SettingsRepository {
            override fun observe(): Flow<AppSettings> = kotlinx.coroutines.flow.flow {
                emit(stored.value)
                throw kotlinx.coroutines.CancellationException("upstream gave up")
            }

            override suspend fun setLanguage(language: AppLanguage) {
                stored.value = stored.value.copy(language = language)
            }

            override suspend fun setTheme(theme: ThemePreference) {
                stored.value = stored.value.copy(theme = theme)
            }

            override suspend fun setFileSizeLimit(bytes: Long) {
                stored.value = stored.value.copy(fileSizeLimitBytes = bytes)
            }

            override suspend fun deleteAllData() {
                stored.value = AppSettings()
            }
        }
        val presenter = DefaultPreferencesPresenter(settings, locales, disconnector, backgroundScope)
        runCurrent()
        assertFalse(presenter.state.value.loading)

        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.DARK))
        presenter.onIntent(PreferencesIntent.SetLanguage(AppLanguage.TURKISH))
        runCurrent()

        assertEquals(ThemePreference.DARK, presenter.state.value.theme)
        assertEquals(AppLanguage.TURKISH, presenter.state.value.language)
        assertEquals(listOf("apply(TURKISH)"), calls)
    }

    @Test
    fun `writes stored before observing fails, but never observed, still show`() = runTest {
        var stored = AppSettings(fileSizeLimitBytes = 4 * mib)
        val breakObserving = CompletableDeferred<Unit>()
        val settings = object : SettingsRepository {
            override fun observe(): Flow<AppSettings> = kotlinx.coroutines.flow.flow {
                emit(stored)
                breakObserving.await()
                throw IOException("observation broke before showing the writes")
            }

            override suspend fun setLanguage(language: AppLanguage) {
                stored = stored.copy(language = language)
            }

            override suspend fun setTheme(theme: ThemePreference) {
                stored = stored.copy(theme = theme)
            }

            override suspend fun setFileSizeLimit(bytes: Long) {
                stored = stored.copy(fileSizeLimitBytes = bytes)
            }

            override suspend fun deleteAllData() {
                stored = AppSettings()
            }
        }
        val presenter = DefaultPreferencesPresenter(settings, locales, disconnector, backgroundScope)
        runCurrent()

        presenter.onIntent(PreferencesIntent.SetLanguage(AppLanguage.TURKISH))
        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.DARK))
        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("12"))
        runCurrent()
        assertEquals(AppLanguage.SYSTEM, presenter.state.value.language)

        breakObserving.complete(Unit)
        runCurrent()

        assertEquals(AppLanguage.TURKISH, presenter.state.value.language)
        assertEquals(ThemePreference.DARK, presenter.state.value.theme)
        assertEquals(12 * mib, presenter.state.value.fileSizeLimitBytes)
        assertEquals("12", presenter.state.value.fileSizeLimitText)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `an older write observed late, just before observing fails, does not replace a newer write`() = runTest {
        var stored = AppSettings()
        val late = kotlinx.coroutines.channels.Channel<AppSettings>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val settings = object : SettingsRepository {
            override fun observe(): Flow<AppSettings> = kotlinx.coroutines.flow.flow {
                emit(stored)
                for (value in late) emit(value)
                throw IOException("observation broke after a late value")
            }

            override suspend fun setLanguage(language: AppLanguage) = error("Not called")

            override suspend fun setTheme(theme: ThemePreference) {
                stored = stored.copy(theme = theme)
            }

            override suspend fun setFileSizeLimit(bytes: Long) = error("Not called")

            override suspend fun deleteAllData() = error("Not called")
        }
        val presenter = DefaultPreferencesPresenter(settings, locales, disconnector, backgroundScope)
        runCurrent()

        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.DARK))
        runCurrent()
        val afterFirst = stored
        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.LIGHT))
        runCurrent()

        late.send(afterFirst)
        late.close()
        runCurrent()

        assertEquals(ThemePreference.LIGHT, presenter.state.value.theme)
    }

    @Test
    fun `a deletion done before observing fails, but never observed, still shows the defaults`() = runTest {
        var stored = AppSettings(AppLanguage.ENGLISH, ThemePreference.DARK, 9 * mib)
        val breakObserving = CompletableDeferred<Unit>()
        val settings = object : SettingsRepository {
            override fun observe(): Flow<AppSettings> = kotlinx.coroutines.flow.flow {
                emit(stored)
                breakObserving.await()
                throw IOException("observation broke before showing the deletion")
            }

            override suspend fun setLanguage(language: AppLanguage) = error("Not called")

            override suspend fun setTheme(theme: ThemePreference) = error("Not called")

            override suspend fun setFileSizeLimit(bytes: Long) = error("Not called")

            override suspend fun deleteAllData() {
                stored = AppSettings()
            }
        }
        val presenter = DefaultPreferencesPresenter(settings, locales, disconnector, backgroundScope)
        runCurrent()

        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()
        breakObserving.complete(Unit)
        runCurrent()

        val state = presenter.state.value
        assertEquals(AppLanguage.SYSTEM, state.language)
        assertEquals(ThemePreference.SYSTEM, state.theme)
        assertEquals(AppSettings.DEFAULT_FILE_SIZE_LIMIT_BYTES, state.fileSizeLimitBytes)
        assertEquals("1", state.fileSizeLimitText)
    }

    @Test
    fun `a settings store that cannot be read does not crash the scope`() = runTest {
        val broken = object : SettingsRepository {
            override fun observe(): Flow<AppSettings> = kotlinx.coroutines.flow.flow { throw IOException("unreadable") }

            override suspend fun setLanguage(language: AppLanguage) = error("Not called")

            override suspend fun setTheme(theme: ThemePreference) = error("Not called")

            override suspend fun setFileSizeLimit(bytes: Long) = error("Not called")

            override suspend fun deleteAllData() = error("Not called")
        }
        val presenter = DefaultPreferencesPresenter(broken, locales, disconnector, backgroundScope)
        runCurrent()

        assertTrue(presenter.state.value.loading)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `changes still show after the store stops being observable`() = runTest {
        val stored = MutableStateFlow(AppSettings())
        val settings = object : SettingsRepository {
            override fun observe(): Flow<AppSettings> = kotlinx.coroutines.flow.flow {
                emit(stored.value)
                throw IOException("observation broke")
            }

            override suspend fun setLanguage(language: AppLanguage) {
                stored.value = stored.value.copy(language = language)
            }

            override suspend fun setTheme(theme: ThemePreference) {
                stored.value = stored.value.copy(theme = theme)
            }

            override suspend fun setFileSizeLimit(bytes: Long) {
                stored.value = stored.value.copy(fileSizeLimitBytes = bytes)
            }

            override suspend fun deleteAllData() {
                stored.value = AppSettings()
            }
        }
        val presenter = DefaultPreferencesPresenter(settings, locales, disconnector, backgroundScope)
        runCurrent()
        assertFalse(presenter.state.value.loading)

        presenter.onIntent(PreferencesIntent.SetLanguage(AppLanguage.TURKISH))
        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.DARK))
        presenter.onIntent(PreferencesIntent.EditFileSizeLimit("33"))
        runCurrent()

        assertEquals(AppLanguage.TURKISH, presenter.state.value.language)
        assertEquals(ThemePreference.DARK, presenter.state.value.theme)
        assertEquals(33 * mib, presenter.state.value.fileSizeLimitBytes)
        assertNull(presenter.state.value.error)

        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()

        val state = presenter.state.value
        assertEquals(AppLanguage.SYSTEM, state.language)
        assertEquals(ThemePreference.SYSTEM, state.theme)
        assertEquals(AppSettings.DEFAULT_FILE_SIZE_LIMIT_BYTES, state.fileSizeLimitBytes)
        assertEquals("1", state.fileSizeLimitText)
    }

    @Test
    fun `while observing works, a write that returns late does not hide a newer stored value`() = runTest {
        val settings = FakeSettings()
        val (presenter, _) = loaded(settings)

        /** [intent]'s write is stored, then [newer] is stored and observed, then the write returns. */
        fun writeReturningLate(intent: PreferencesIntent, newer: (AppSettings) -> AppSettings) {
            val gate = CompletableDeferred<Unit>()
            settings.returnGate = gate
            presenter.onIntent(intent)
            runCurrent()
            settings.stored.value = newer(settings.stored.value)
            runCurrent()
            gate.complete(Unit)
            runCurrent()
        }

        writeReturningLate(PreferencesIntent.SetLanguage(AppLanguage.TURKISH)) { it.copy(language = AppLanguage.ENGLISH) }
        assertEquals(AppLanguage.ENGLISH, presenter.state.value.language)

        writeReturningLate(PreferencesIntent.SetTheme(ThemePreference.DARK)) { it.copy(theme = ThemePreference.LIGHT) }
        assertEquals(ThemePreference.LIGHT, presenter.state.value.theme)

        writeReturningLate(PreferencesIntent.EditFileSizeLimit("5")) { it.copy(fileSizeLimitBytes = 6 * mib) }
        assertEquals(6 * mib, presenter.state.value.fileSizeLimitBytes)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `while observing works, a deletion that returns late does not hide a newer stored value`() = runTest {
        val settings = FakeSettings(AppSettings(theme = ThemePreference.DARK))
        val (presenter, events) = loaded(settings)
        val gate = CompletableDeferred<Unit>()
        settings.deleteReturnGate = gate

        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()
        settings.stored.value = settings.stored.value.copy(theme = ThemePreference.LIGHT, fileSizeLimitBytes = 2 * mib)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(ThemePreference.LIGHT, presenter.state.value.theme)
        assertEquals(2 * mib, presenter.state.value.fileSizeLimitBytes)
        assertEquals("2", presenter.state.value.fileSizeLimitText)
        assertEquals(listOf<PreferencesEvent>(PreferencesEvent.DataDeleted), events)
    }

    @Test
    fun `a language stored but not applied is an error, and later work still runs`() = runTest {
        val settings = FakeSettings()
        val (presenter, events) = loaded(settings)
        applyFailure = IllegalStateException("no activity")

        presenter.onIntent(PreferencesIntent.SetLanguage(AppLanguage.TURKISH))
        runCurrent()

        assertEquals(listOf("setLanguage(TURKISH)", "apply(TURKISH)"), calls)
        assertEquals(AppLanguage.TURKISH, presenter.state.value.language)
        assertEquals(PreferencesError.SaveFailed, presenter.state.value.error)

        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.DARK))
        runCurrent()
        assertEquals(ThemePreference.DARK, settings.stored.value.theme)
        assertNull(presenter.state.value.error)

        presenter.onIntent(PreferencesIntent.RequestDelete)
        presenter.onIntent(PreferencesIntent.ConfirmDelete)
        runCurrent()

        assertEquals(
            listOf("setLanguage(TURKISH)", "apply(TURKISH)", "setTheme(DARK)", "disconnect", "deleteAllData", "apply(SYSTEM)"),
            calls,
        )
        assertEquals(AppSettings(), settings.stored.value)
        assertEquals(DataDeletion.Idle, presenter.state.value.deletion)
        assertNull(presenter.state.value.error)
        assertEquals(listOf<PreferencesEvent>(PreferencesEvent.DataDeleted), events)

        presenter.onIntent(PreferencesIntent.SetTheme(ThemePreference.LIGHT))
        runCurrent()
        assertEquals(ThemePreference.LIGHT, settings.stored.value.theme)
    }

    @Test
    fun `printing the presenter shows its name only`() = runTest {
        assertEquals("DefaultPreferencesPresenter", presenter(FakeSettings()).toString())
    }
}
