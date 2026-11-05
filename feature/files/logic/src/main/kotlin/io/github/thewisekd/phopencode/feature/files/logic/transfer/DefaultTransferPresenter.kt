package io.github.thewisekd.phopencode.feature.files.logic.transfer

import io.github.thewisekd.phopencode.feature.files.logic.contract.FileTransfers
import io.github.thewisekd.phopencode.feature.files.logic.contract.LocalDocument
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferId
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferJob
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferQueue
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P8.2): shows the initial state and ignores intents. P8.12 fills it in
 * without changing the constructor; it follows [queue]'s jobs in [scope], starting with a
 * `yield()`.
 *
 * @param directory The project's folder, from the route.
 * @param folder Where uploads go, from the route's `path`.
 * @param queue The app's one queue, which runs the transfers.
 */
@Suppress("unused")
class DefaultTransferPresenter(
    private val directory: String,
    private val folder: String,
    private val queue: TransferQueue,
    private val scope: CoroutineScope,
) : TransferPresenter {
    private val mutableState = MutableStateFlow(TransferState(directory = directory, folder = folder))

    override val state: StateFlow<TransferState> = mutableState.asStateFlow()

    override val events: Flow<TransferEvent> = emptyFlow()

    override fun onIntent(intent: TransferIntent) = Unit
}

/**
 * Stub from the contract (P8.2), kept beside the presenter it serves: holds no jobs and queues
 * nothing. P8.12 fills it in without changing the constructor. `FilesModule` makes one for the app.
 *
 * @param transfers Moves the bytes.
 * @param scope The app's scope, so that transfers outlive the screen; each job starts with a
 *   `yield()`.
 */
@Suppress("unused")
class DefaultTransferQueue(
    private val transfers: FileTransfers,
    private val scope: CoroutineScope,
) : TransferQueue {
    private val mutableJobs = MutableStateFlow<List<TransferJob>>(emptyList())

    override val jobs: StateFlow<List<TransferJob>> = mutableJobs.asStateFlow()

    override fun upload(directory: String, folder: String, document: LocalDocument): TransferId = TransferId(0)

    override fun download(directory: String, path: String, document: LocalDocument): TransferId = TransferId(0)

    override fun answerOverwrite(id: TransferId, overwrite: Boolean) = Unit

    override fun cancel(id: TransferId) = Unit

    override fun clearFinished() = Unit
}
