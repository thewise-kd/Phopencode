package io.github.thewisekd.phopencode.feature.git.logic.remote

import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemotePresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P9.3): shows the initial state and ignores intents. P9.14 fills it in
 * without changing the constructor; loading starts in [scope] after a `yield()`. Fetch, pull and
 * push run in [scope] too, so leaving the screen for good cancels a running one.
 *
 * @param directory The folder from the route, where git runs.
 * @param git Runs `git` on the active connection.
 */
@Suppress("unused")
class DefaultRemotePresenter(
    private val directory: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
) : RemotePresenter {
    private val mutableState = MutableStateFlow(RemoteState(directory = directory))

    override val state: StateFlow<RemoteState> = mutableState.asStateFlow()

    override val events: Flow<RemoteEvent> = emptyFlow()

    override fun onIntent(intent: RemoteIntent) = Unit
}
