package io.github.thewisekd.phopencode.feature.git.logic.status

import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P9.3): shows the initial state and ignores intents. P9.4 fills it in
 * without changing the constructor; loading starts in [scope] after a `yield()`.
 *
 * @param directory The folder from the route, where git runs.
 * @param git Runs `git` on the active connection.
 */
@Suppress("unused")
class DefaultStatusPresenter(
    private val directory: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
) : StatusPresenter {
    private val mutableState = MutableStateFlow(StatusState(directory = directory))

    override val state: StateFlow<StatusState> = mutableState.asStateFlow()

    override val events: Flow<StatusEvent> = emptyFlow()

    override fun onIntent(intent: StatusIntent) = Unit
}
