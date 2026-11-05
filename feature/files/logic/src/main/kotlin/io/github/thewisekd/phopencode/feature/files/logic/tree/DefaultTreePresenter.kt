package io.github.thewisekd.phopencode.feature.files.logic.tree

import io.github.thewisekd.phopencode.feature.files.logic.contract.FileListing
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreePresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P8.2): shows the initial state and ignores intents. P8.4 fills it in
 * without changing the constructor; loading starts in [scope] after a `yield()`.
 *
 * @param directory The project's folder, from the route; the tree starts there.
 * @param files Lists folders.
 * @param git The git badges.
 */
@Suppress("unused")
class DefaultTreePresenter(
    private val directory: String,
    private val files: FileListing,
    private val git: GitChanges,
    private val scope: CoroutineScope,
) : TreePresenter {
    private val mutableState = MutableStateFlow(TreeState(directory = directory))

    override val state: StateFlow<TreeState> = mutableState.asStateFlow()

    override val events: Flow<TreeEvent> = emptyFlow()

    override fun onIntent(intent: TreeIntent) = Unit
}
