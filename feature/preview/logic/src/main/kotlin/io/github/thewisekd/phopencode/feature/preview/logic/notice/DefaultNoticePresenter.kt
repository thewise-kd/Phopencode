package io.github.thewisekd.phopencode.feature.preview.logic.notice

import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticePhase
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The first-visit notice's logic (P13.24); the rules are in [NoticeState]'s contract.
 *
 * The setting is read once per visit: a later change elsewhere does not matter while the port
 * picker is open, and the notice's own confirmation hides it directly. A read that fails shows the
 * notice rather than skipping it. Nothing is logged.
 *
 * @param settings Where the confirmation is read and stored, across restarts.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultNoticePresenter(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : NoticePresenter {
    private val mutableState = MutableStateFlow(NoticeState())

    override val state: StateFlow<NoticeState> = mutableState.asStateFlow()

    /** Unlimited, so no event is ever dropped while nobody collects. */
    private val eventChannel = Channel<NoticeEvent>(Channel.UNLIMITED)

    override val events: Flow<NoticeEvent> = eventChannel.receiveAsFlow()

    /** Guards read-modify-write of [mutableState], so a check and the change it allows are one step. */
    private val lock = Any()

    init {
        scope.launch {
            yield()
            val accepted = try {
                settings.observe().first().previewNoticeAccepted
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                false
            }
            edit { current ->
                if (current.phase == NoticePhase.Checking) {
                    current.copy(phase = if (accepted) NoticePhase.Hidden else NoticePhase.Showing)
                } else {
                    current
                }
            }
        }
    }

    override fun onIntent(intent: NoticeIntent) {
        when (intent) {
            NoticeIntent.Accept -> accept()
            NoticeIntent.NotNow -> leave()
        }
    }

    private inline fun edit(change: (NoticeState) -> NoticeState) {
        synchronized(lock) { mutableState.value = change(mutableState.value) }
    }

    /**
     * Leaves without confirming, only from [NoticePhase.Showing]: while the confirmation is being
     * stored the user waits for its outcome, so leaving never races an acceptance; once hidden there
     * is nothing to leave. Leaving moves to the final [NoticePhase.Left], so a second tap sends no
     * second event and a later accept stores nothing. The check, the move and the event are one
     * step under [lock], as [accept]'s is.
     */
    private fun leave() {
        synchronized(lock) {
            val current = mutableState.value
            if (current.phase == NoticePhase.Showing) {
                mutableState.value = current.copy(phase = NoticePhase.Left)
                eventChannel.trySend(NoticeEvent.Leave)
            }
        }
    }

    /** Stores the confirmation; only from [NoticePhase.Showing], so one store runs at a time. */
    private fun accept() {
        val started = synchronized(lock) {
            val current = mutableState.value
            if (current.phase == NoticePhase.Showing) {
                mutableState.value = current.copy(phase = NoticePhase.Saving)
                true
            } else {
                false
            }
        }
        if (!started) return
        scope.launch {
            yield()
            val stored = try {
                settings.acceptPreviewNotice()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                false
            }
            edit {
                if (stored) NoticeState(phase = NoticePhase.Hidden) else NoticeState(NoticePhase.Showing, saveFailed = true)
            }
        }
    }
}
