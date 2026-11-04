package io.github.thewisekd.phopencode.feature.chat.logic.status

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P6.1): shows the initial state and ignores intents. P6.13 fills it in
 * without changing the constructor.
 *
 * @param session The chat's session, whose availability, sync phase and status the banner shows.
 * @param connection Only for [StatusIntent.Reconnect]; the server stays the one [session] is bound
 *   to.
 */
@Suppress("unused")
class DefaultStatusPresenter(
    private val session: ChatSession,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
) : StatusPresenter {
    private val mutableState = MutableStateFlow(StatusState())

    override val state: StateFlow<StatusState> = mutableState.asStateFlow()

    override val events: Flow<StatusEvent> = emptyFlow()

    override fun onIntent(intent: StatusIntent) = Unit
}
