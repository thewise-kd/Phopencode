package io.github.thewisekd.phopencode.feature.sessions.logic.list

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListPresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P5.1): shows the initial state and ignores intents. P5.2 fills it in
 * without changing the constructor.
 *
 * @param directory The folder to list, from the route; null lists every folder.
 * @param connection The server is the one of `ConnectionState.Connected`; its client changes with
 *   each new connection (a larger `generation`), and the list reloads on it.
 * @param pageSize `SessionQuery.limit` of each page.
 */
@Suppress("unused")
class DefaultSessionListPresenter(
    private val directory: String?,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : SessionListPresenter {
    private val mutableState = MutableStateFlow(SessionListState(directory = directory))

    override val state: StateFlow<SessionListState> = mutableState.asStateFlow()

    override val events: Flow<SessionListEvent> = emptyFlow()

    override fun onIntent(intent: SessionListIntent) = Unit

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 30
    }
}
