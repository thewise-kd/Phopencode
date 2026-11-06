package io.github.thewisekd.phopencode.feature.git.logic.commit

import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P9.3): shows the initial state and ignores intents. P9.10 fills it in
 * without changing the constructor; loading starts in [scope] after a `yield()`.
 *
 * @param directory The folder from the route, where git runs.
 * @param git Runs `git` on the active connection.
 */
@Suppress("unused")
class DefaultCommitPresenter(
    private val directory: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
) : CommitPresenter {
    private val mutableState = MutableStateFlow(CommitState(directory = directory))

    override val state: StateFlow<CommitState> = mutableState.asStateFlow()

    override val events: Flow<CommitEvent> = emptyFlow()

    override fun onIntent(intent: CommitIntent) = Unit
}
