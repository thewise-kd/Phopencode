package io.github.thewisekd.phopencode.feature.git.logic.branch

import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P9.3): shows the initial state and ignores intents. P9.12 fills it in
 * without changing the constructor; loading starts in [scope] after a `yield()`.
 *
 * @param directory The folder from the route, where git runs.
 * @param git Runs `git` on the active connection.
 */
@Suppress("unused")
class DefaultBranchesPresenter(
    private val directory: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
) : BranchesPresenter {
    private val mutableState = MutableStateFlow(BranchesState(directory = directory))

    override val state: StateFlow<BranchesState> = mutableState.asStateFlow()

    override val events: Flow<BranchEvent> = emptyFlow()

    override fun onIntent(intent: BranchIntent) = Unit
}
