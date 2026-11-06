package io.github.thewisekd.phopencode.feature.git.logic.diff

import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P9.3): shows the initial state and ignores intents. P9.6 fills it in
 * without changing the constructor; loading starts in [scope] after a `yield()`.
 *
 * @param directory The folder from the route, where git runs.
 * @param target The diff to show, from the route (`DiffTarget.of`).
 * @param git Runs `git` on the active connection.
 * @param maxBytes Above this much diff output, the content is `DiffContent.TooLarge`.
 */
@Suppress("unused")
class DefaultDiffPresenter(
    private val directory: String,
    private val target: DiffTarget,
    private val git: GitConnection,
    private val scope: CoroutineScope,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : DiffPresenter {
    private val mutableState = MutableStateFlow(DiffState(target = target))

    override val state: StateFlow<DiffState> = mutableState.asStateFlow()

    override val events: Flow<DiffEvent> = emptyFlow()

    override fun onIntent(intent: DiffIntent) = Unit

    companion object {
        /** 1 MiB. */
        const val DEFAULT_MAX_BYTES: Long = 1L shl 20
    }
}
