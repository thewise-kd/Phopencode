package io.github.thewisekd.phopencode.feature.files.logic.ops

import io.github.thewisekd.phopencode.feature.files.logic.contract.FileChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P8.2): shows the initial state and ignores intents. P8.10 fills it in
 * without changing the constructor; operations run in [scope], each starting with a `yield()`.
 *
 * @param directory The project's folder, from the tree's route.
 * @param files Deletes, renames and creates folders.
 */
@Suppress("unused")
class DefaultFileOpsPresenter(
    private val directory: String,
    private val files: FileChanges,
    private val scope: CoroutineScope,
) : FileOpsPresenter {
    private val mutableState = MutableStateFlow(FileOpsState())

    override val state: StateFlow<FileOpsState> = mutableState.asStateFlow()

    override val events: Flow<FileOpsEvent> = emptyFlow()

    override fun onIntent(intent: FileOpsIntent) = Unit
}
