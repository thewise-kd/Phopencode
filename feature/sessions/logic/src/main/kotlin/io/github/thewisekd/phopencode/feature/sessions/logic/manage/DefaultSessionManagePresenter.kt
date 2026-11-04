package io.github.thewisekd.phopencode.feature.sessions.logic.manage

import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManagePresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P5.1): shows the initial state and ignores intents. P5.6 fills it in
 * without changing the constructor.
 *
 * @param sessionId The session to manage, from the route.
 * @param connection The server is the one of `ConnectionState.Connected`.
 */
@Suppress("unused")
class DefaultSessionManagePresenter(
    private val sessionId: SessionId,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
) : SessionManagePresenter {
    private val mutableState = MutableStateFlow(SessionManageState())

    override val state: StateFlow<SessionManageState> = mutableState.asStateFlow()

    override val events: Flow<SessionManageEvent> = emptyFlow()

    override fun onIntent(intent: SessionManageIntent) = Unit
}
