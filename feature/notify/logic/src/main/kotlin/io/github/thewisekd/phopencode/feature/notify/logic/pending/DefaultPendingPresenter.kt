package io.github.thewisekd.phopencode.feature.notify.logic.pending

import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyConnection
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingEvent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P11.7): shows the initial state and ignores intents. P11.11 fills it in
 * without changing the constructor; following the connection starts in [scope] after a `yield()`.
 *
 * @param connection The client of the active connection.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultPendingPresenter(
    private val connection: NotifyConnection,
    private val scope: CoroutineScope,
) : PendingPresenter {
    private val mutableState = MutableStateFlow(PendingState())

    override val state: StateFlow<PendingState> = mutableState.asStateFlow()

    override val events: Flow<PendingEvent> = emptyFlow()

    override fun onIntent(intent: PendingIntent) = Unit
}
