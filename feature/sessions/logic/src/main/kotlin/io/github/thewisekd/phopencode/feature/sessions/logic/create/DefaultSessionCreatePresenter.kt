package io.github.thewisekd.phopencode.feature.sessions.logic.create

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreatePresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P5.1): shows the initial state and ignores intents. P5.4 fills it in
 * without changing the constructor.
 *
 * @param directory The folder to preselect, from the route.
 * @param skillId The skill to attach to the first prompt, from the route; kept in the state.
 * @param connection The server is the one of `ConnectionState.Connected`, whose
 *   `server.profileId` names the recent folders to offer.
 * @param recentFolders The active server's recent folders; a created session's folder is added.
 */
@Suppress("unused")
class DefaultSessionCreatePresenter(
    private val directory: String?,
    private val skillId: String?,
    private val connection: ConnectionManager,
    private val recentFolders: RecentFoldersStore,
    private val scope: CoroutineScope,
) : SessionCreatePresenter {
    private val mutableState = MutableStateFlow(SessionCreateState(directory = directory.orEmpty(), skillId = skillId))

    override val state: StateFlow<SessionCreateState> = mutableState.asStateFlow()

    override val events: Flow<SessionCreateEvent> = emptyFlow()

    override fun onIntent(intent: SessionCreateIntent) = Unit
}
