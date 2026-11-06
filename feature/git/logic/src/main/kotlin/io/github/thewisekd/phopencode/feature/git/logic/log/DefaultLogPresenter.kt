package io.github.thewisekd.phopencode.feature.git.logic.log

import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
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
 * @param git Runs `git` on the active connection.
 * @param pageSize Commits per page.
 */
@Suppress("unused")
class DefaultLogPresenter(
    private val directory: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : LogPresenter {
    private val mutableState = MutableStateFlow(LogState(directory = directory))

    override val state: StateFlow<LogState> = mutableState.asStateFlow()

    override val events: Flow<LogEvent> = emptyFlow()

    override fun onIntent(intent: LogIntent) = Unit

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
    }
}
