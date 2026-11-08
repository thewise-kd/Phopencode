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
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesPresenter
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The preferences (P10.8): language, theme, file size limit, and deleting the app's data.
 *
 * - The state follows [SettingsRepository.observe], collected in [scope] after a `yield()`. Until
 *   its first value the screen is loading and every change is ignored; a store that cannot be read
 *   leaves it loading rather than showing defaults that a change would then overwrite.
 * - Every write, and the deletion, runs in one worker in [scope], one at a time, in the order the
 *   intents came, so the last change made is the one stored. A failed write sets
 *   [PreferencesError.SaveFailed]; a successful one clears the error.
 * - While observing works, the stored language, theme and limit in the state come from it alone, so
 *   a write that returns late never hides a newer stored value. The presenter also keeps the
 *   settings as it knows them stored: the first observed value, with each later successful write
 *   or deletion on top, in order (later observed values may be late copies of older writes, so
 *   they never change it). When observing has failed or finished, the state shows that snapshot at
 *   once, so a write stored but never observed still shows; after that, a successful write puts its
 *   value in the state itself, and a deletion the defaults, so the screen stays right (the contract has no error for a failed read, so that failure itself
 *   is not shown).
 * - Every port call in the worker is guarded: an exception that is not the scope's own cancellation
 *   becomes the contract's error, and the worker goes on with the next item.
 * - Observing has ended once it failed, finished, or was cancelled from upstream while [scope] is
 *   still active; only the cancellation of [scope] itself is rethrown.
 * - A language is applied with [LocaleApplier] only once stored, and it is exactly the language
 *   written (the system's after a deletion), never one read back from observing; see "Single
 *   writer". If storing worked but applying did not, the state shows the stored language (the state follows what was stored) with
 *   [PreferencesError.SaveFailed], since the choice did not take effect; choosing it again retries.
 *   The theme is only stored: the app applies it from the stored settings.
 * - The limit field takes whole mebibytes in ASCII digits, spaces around them ignored. A valid
 *   value is stored in bytes; anything else sets a [FileSizeProblem] and stores nothing. The field
 *   follows the stored limit until the user types in it, and again after all data is deleted.
 * - Deleting needs [PreferencesIntent.RequestDelete], then [PreferencesIntent.ConfirmDelete]. The
 *   worker ends the connection with [Disconnector]; only if that worked does it call
 *   [SettingsRepository.deleteAllData], which removes what the app stored on the phone and nothing
 *   else. The state shows the defaults, the language goes back to the system's, then
 *   [PreferencesEvent.DataDeleted] is sent; if only that last locale call fails, the data is still
 *   gone and the event is still sent. Any failure before that sets [PreferencesError.DeleteFailed] and returns to [DataDeletion.Idle], so asking again
 *   finishes the job.
 *
 * Single writer: [SettingsRepository] is written only by this presenter, through its one serial
 * worker. The worker stores and applies one choice fully before the next, so the last choice made
 * is the last one written and the last one applied; that is why the written language is applied,
 * not what observing shows at that moment, which may still be an earlier write of this same
 * worker. The state and the applied locale are correct under that assumption. Interleavings with
 * another concurrent writer of the settings are out of scope (accepted for P10.8).
 *
 * The three ports are the only things this class reaches: nothing is sent to a server, and no
 * process is stopped. The state holds preferences only; nothing here logs.
 *
 * @param settings The stored preferences.
 * @param locales Puts a stored language on screen at once.
 * @param disconnector Ends the active connection before all data is deleted.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultPreferencesPresenter(
    private val settings: SettingsRepository,
    private val locales: LocaleApplier,
    private val disconnector: Disconnector,
    private val scope: CoroutineScope,
) : PreferencesPresenter {
    private val mutableState = MutableStateFlow(PreferencesState())

    override val state: StateFlow<PreferencesState> = mutableState.asStateFlow()

    /** Unlimited, so `trySend` never drops an event, [PreferencesEvent.DataDeleted] above all. */
    private val eventChannel = Channel<PreferencesEvent>(Channel.UNLIMITED)

    override val events: Flow<PreferencesEvent> = eventChannel.receiveAsFlow()

    /** Guards [edited] and every state change that reads the state first. */
    private val lock = Any()

    /** Guarded by [lock]: whether the user typed in the limit field, so it no longer follows the store. */
    private var edited = false

    /** Guarded by [lock]: whether [SettingsRepository.observe] failed or finished, so it shows no more changes. */
    private var observationEnded = false

    /**
     * Guarded by [lock]: the settings as this presenter, the single writer, knows them to be stored:
     * the first observed value, then each successful write or deletion on top, in order. Later
     * observed values never change it, since they may be late copies of older writes. Null until
     * observing delivers its first value.
     */
    private var known: AppSettings? = null

    private val work = Channel<Work>(Channel.UNLIMITED)

    init {
        scope.launch {
            yield()
            read()
        }
        scope.launch {
            yield()
            for (item in work) run(item)
        }
    }

    override fun onIntent(intent: PreferencesIntent) {
        when (intent) {
            is PreferencesIntent.SetLanguage -> enqueue(Work.Language(intent.language))
            is PreferencesIntent.SetTheme -> enqueue(Work.Theme(intent.theme))
            is PreferencesIntent.EditFileSizeLimit -> editLimit(intent.text)
            PreferencesIntent.RequestDelete -> synchronized(lock) {
                mutableState.update {
                    if (it.canEdit && it.deletion == DataDeletion.Idle) it.copy(deletion = DataDeletion.Confirming) else it
                }
            }
            PreferencesIntent.CancelDelete -> synchronized(lock) {
                mutableState.update {
                    if (it.deletion == DataDeletion.Confirming) it.copy(deletion = DataDeletion.Idle) else it
                }
            }
            PreferencesIntent.ConfirmDelete -> synchronized(lock) {
                val current = mutableState.value
                if (current.canEdit && current.deletion == DataDeletion.Confirming) {
                    mutableState.value = current.copy(deletion = DataDeletion.Deleting)
                    work.trySend(Work.DeleteAll)
                }
            }
            PreferencesIntent.DismissError -> synchronized(lock) { mutableState.update { it.copy(error = null) } }
            PreferencesIntent.OpenLicenses -> eventChannel.trySend(PreferencesEvent.OpenLicenses)
            PreferencesIntent.Close -> eventChannel.trySend(PreferencesEvent.Close)
        }
    }

    private fun enqueue(item: Work) {
        synchronized(lock) {
            if (mutableState.value.canEdit) work.trySend(item)
        }
    }

    private fun editLimit(text: String) {
        synchronized(lock) {
            if (!mutableState.value.canEdit) return
            edited = true
            val parsed = parseMib(text)
            mutableState.update { it.copy(fileSizeLimitText = text, fileSizeProblem = parsed.problem) }
            parsed.mib?.let { work.trySend(Work.Limit(it * PreferencesState.BYTES_PER_MIB)) }
        }
    }

    private suspend fun read() {
        try {
            settings.observe().collect { stored ->
                synchronized(lock) {
                    // Only the first value: after it, the store holds exactly this presenter's writes.
                    if (known == null) known = stored
                    mutableState.update { show(it.copy(loading = false), stored) }
                }
            }
        } catch (e: CancellationException) {
            // Rethrown only when this presenter's own scope is cancelled; an upstream one ends observing.
            currentCoroutineContext().ensureActive()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Unreadable: stay loading, so no change can overwrite settings never shown.
        }
        // Failed or finished: show the writes it never showed; from now on, writes show themselves.
        synchronized(lock) {
            observationEnded = true
            known?.let { stored -> mutableState.update { show(it, stored) } }
        }
    }

    /** Under [lock]: [state] showing [stored]; the limit field follows it unless the user typed. */
    private fun show(state: PreferencesState, stored: AppSettings): PreferencesState =
        state.copy(
            language = stored.language,
            theme = stored.theme,
            fileSizeLimitBytes = stored.fileSizeLimitBytes,
            fileSizeLimitText = if (edited) state.fileSizeLimitText else mibText(stored.fileSizeLimitBytes),
        )

    private suspend fun run(item: Work) {
        when (item) {
            is Work.Language -> {
                val stored = save({ settings.setLanguage(item.language) }) { it.copy(language = item.language) }
                // Exactly what was written: see "Single writer" in the class comment.
                if (stored && failsWith { locales.apply(item.language) }) {
                    synchronized(lock) { mutableState.update { it.copy(error = PreferencesError.SaveFailed) } }
                }
            }
            is Work.Theme -> save({ settings.setTheme(item.theme) }) { it.copy(theme = item.theme) }
            is Work.Limit -> save({ settings.setFileSizeLimit(item.bytes) }) { it.copy(fileSizeLimitBytes = item.bytes) }
            Work.DeleteAll -> deleteAll()
        }
    }

    /**
     * Runs one write; if it was stored, records it in [known] with [change], shows it once observing
     * has ended, and returns true.
     */
    private suspend fun save(write: suspend () -> Unit, change: (AppSettings) -> AppSettings): Boolean {
        val failed = failsWith { write() }
        synchronized(lock) {
            if (failed) {
                mutableState.update { it.copy(error = PreferencesError.SaveFailed) }
            } else {
                val stored = known?.let(change)
                known = stored
                mutableState.update {
                    // While observing works, the stored settings come from it alone; see the class comment.
                    val shown = if (observationEnded && stored != null) show(it, stored) else it
                    shown.copy(error = null)
                }
            }
        }
        return !failed
    }

    private suspend fun deleteAll() {
        val failed = failsWith {
            disconnector.disconnect()
            settings.deleteAllData()
        }
        if (failed) {
            synchronized(lock) {
                mutableState.update { it.copy(deletion = DataDeletion.Idle, error = PreferencesError.DeleteFailed) }
            }
            return
        }
        val defaults = AppSettings()
        synchronized(lock) {
            edited = false
            known = defaults
            mutableState.update {
                // While observing works, the stored settings come from it alone; see the class comment.
                val shown = if (observationEnded) show(it, defaults) else it
                shown.copy(
                    fileSizeLimitText = mibText(shown.fileSizeLimitBytes),
                    fileSizeProblem = null,
                    deletion = DataDeletion.Idle,
                    error = null,
                )
            }
        }
        // The data is gone either way; a locale that could not be reset does not undo that.
        failsWith { locales.apply(defaults.language) }
        eventChannel.trySend(PreferencesEvent.DataDeleted)
    }

    /**
     * Runs [block]; true if it threw. A cancellation that is not this coroutine's own, such as a
     * timeout inside a port, is a failure too; this coroutine's own cancellation is rethrown.
     */
    private suspend fun failsWith(block: suspend () -> Unit): Boolean =
        try {
            block()
            false
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            true
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            true
        }

    override fun toString(): String = "DefaultPreferencesPresenter"

    private sealed interface Work {
        data class Language(val language: AppLanguage) : Work

        data class Theme(val theme: ThemePreference) : Work

        data class Limit(val bytes: Long) : Work

        data object DeleteAll : Work
    }

    private class ParsedMib(val mib: Long?, val problem: FileSizeProblem?)

    private companion object {
        val WHOLE_NUMBER = Regex("[+-]?[0-9]+")

        fun mibText(bytes: Long): String = (bytes / PreferencesState.BYTES_PER_MIB).toString()

        fun parseMib(text: String): ParsedMib {
            val trimmed = text.trim()
            val problem = when {
                trimmed.isEmpty() -> FileSizeProblem.Blank
                !WHOLE_NUMBER.matches(trimmed) -> FileSizeProblem.NotANumber
                else -> {
                    val value = trimmed.toLongOrNull()
                    when {
                        value == null -> if (trimmed.startsWith('-')) FileSizeProblem.TooSmall else FileSizeProblem.TooLarge
                        value < PreferencesState.MIN_FILE_SIZE_LIMIT_MIB -> FileSizeProblem.TooSmall
                        value > PreferencesState.MAX_FILE_SIZE_LIMIT_MIB -> FileSizeProblem.TooLarge
                        else -> return ParsedMib(value, null)
                    }
                }
            }
            return ParsedMib(null, problem)
        }
    }
}
