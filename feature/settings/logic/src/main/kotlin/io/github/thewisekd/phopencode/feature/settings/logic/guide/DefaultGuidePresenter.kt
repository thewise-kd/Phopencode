package io.github.thewisekd.phopencode.feature.settings.logic.guide

import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideEvent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuidePage
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuidePresenter
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * The usage guide (P14.1); see `GuideContract.kt` for the rules.
 *
 * Paging is plain state. Finishing writes `guideSeen` in [scope] and sends [GuideEvent.Finished] only
 * after the write returned, failed or took longer than [FINISH_TIMEOUT]: the write runs in the
 * destination's scope, which leaving the guide would cancel. A failed write is not shown; the guide
 * then opens again on a later launch, which is the safe side.
 *
 * The settings store is the only thing this class reaches, and only through `markGuideSeen()`.
 *
 * @param settings Records that the guide was seen.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultGuidePresenter(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : GuidePresenter {
    private val mutableState = MutableStateFlow(GuideState())

    override val state: StateFlow<GuideState> = mutableState.asStateFlow()

    /** Unlimited, so `trySend` never drops [GuideEvent.Finished]. */
    private val eventChannel = Channel<GuideEvent>(Channel.UNLIMITED)

    override val events: Flow<GuideEvent> = eventChannel.receiveAsFlow()

    override fun onIntent(intent: GuideIntent) {
        when (intent) {
            GuideIntent.Next -> move(+1)
            GuideIntent.Back -> if (mutableState.value.isFirst) finish() else move(-1)
            is GuideIntent.ShowPage -> mutableState.update { if (it.finishing) it else it.copy(page = intent.page) }
            GuideIntent.Skip, GuideIntent.Start -> finish()
        }
    }

    private fun move(step: Int) {
        mutableState.update {
            val index = it.page.ordinal + step
            if (it.finishing || index !in GuidePage.entries.indices) it else it.copy(page = GuidePage.entries[index])
        }
    }

    private fun finish() {
        var started = false
        mutableState.update {
            started = !it.finishing
            it.copy(finishing = true)
        }
        if (!started) return
        scope.launch {
            yield()
            withTimeoutOrNull(FINISH_TIMEOUT) { markSeen() }
            eventChannel.trySend(GuideEvent.Finished)
        }
    }

    /**
     * Stores `guideSeen`, dropping any failure: a cancellation that is not this coroutine's own, such
     * as a timeout inside the store, is a failure too; this coroutine's own cancellation is rethrown.
     */
    private suspend fun markSeen() {
        try {
            settings.markGuideSeen()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Not stored: the guide opens again on a later launch.
        }
    }

    override fun toString(): String = "DefaultGuidePresenter"

    companion object {
        /** How long finishing waits for the store before it lets the user out anyway. */
        val FINISH_TIMEOUT: Duration = 5.seconds
    }
}
