package io.github.thewisekd.phopencode.feature.git.logic.log

import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P9.3): shows the initial state and ignores intents. P9.8 fills it in
 * without changing the constructor; loading starts in [scope] after a `yield()`.
 *
 * @param directory The folder from the route, where git runs.
 * @param commit The hash from the route; checked before it reaches a command.
 * @param git Runs `git` on the active connection.
 */
@Suppress("unused")
class DefaultCommitDetailPresenter(
    private val directory: String,
    private val commit: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
) : CommitDetailPresenter {
    private val mutableState = MutableStateFlow(CommitDetailState(commit = commit))

    override val state: StateFlow<CommitDetailState> = mutableState.asStateFlow()

    override val events: Flow<CommitDetailEvent> = emptyFlow()

    override fun onIntent(intent: CommitDetailIntent) = Unit
}
