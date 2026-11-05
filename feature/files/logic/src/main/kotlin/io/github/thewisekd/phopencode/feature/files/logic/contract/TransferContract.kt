package io.github.thewisekd.phopencode.feature.files.logic.contract

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.StateFlow

/**
 * Upload and download, `FilesRoute.Transfer` (P8.12 logic, P8.13 screen and service).
 *
 * The transfers themselves run in the app-wide [TransferQueue], not in the screen: they go on when
 * the screen closes, and P8.13's foreground service shows [TransferQueue.jobs] in a notification
 * while any is active. The screen shows every job of the queue and adds to it.
 *
 * Uploading: the screen opens the system file picker and sends [TransferIntent.Upload] with the
 * picked documents; each goes to [folder] under its own name. Downloading: [pendingDownload] is the
 * remote file waiting for a place; the screen opens the system's create-document picker named
 * after it, then sends [TransferIntent.DownloadTo] or [TransferIntent.CancelDownload].
 *
 * @property directory The project's folder, from the route.
 * @property folder Where uploads go, from the route's `path`.
 * @property jobs Every job of the queue, oldest first.
 * @property pendingDownload A file to download, waiting for the user to pick where.
 */
data class TransferState(
    val directory: String,
    val folder: String,
    val jobs: List<TransferJob> = emptyList(),
    val pendingDownload: String? = null,
) {
    /** The job whose overwrite prompt shows: the oldest waiting one. */
    val overwritePrompt: TransferJob?
        get() = jobs.firstOrNull { it.status == TransferStatus.AwaitingOverwrite }

    /** Whether [TransferIntent.ClearFinished] would remove anything. */
    val hasFinished: Boolean
        get() = jobs.any { it.status.isFinished }

    override fun toString(): String =
        "TransferState(directory=***, folder=***, jobs=$jobs, pendingDownload=${pendingDownload?.let { "***" }})"
}

/**
 * A local file the user picked with the system picker, opened by the ui side (a `content://`
 * document). Implementations hold no open stream between calls.
 */
interface LocalDocument {
    /** The display name: an upload's remote file name, and what a download row shows. */
    val name: String

    /** Null when the provider does not tell. */
    val sizeBytes: Long?

    /** A new stream on each call; the caller closes it. */
    fun openInput(): InputStream

    /** A new stream on each call, replacing the content; the caller closes it. */
    fun openOutput(): OutputStream

    /** Removes the document, as after a failed or cancelled download. False when it could not. */
    fun delete(): Boolean
}

/** A job's ID in its [TransferQueue], increasing in the order jobs were added. */
data class TransferId(val value: Long)

enum class TransferDirection {
    Upload,
    Download,
}

sealed interface TransferStatus {
    /** Done, cancelled or failed: nothing more happens to the job. */
    val isFinished: Boolean
        get() = false

    data object Queued : TransferStatus

    data object Running : TransferStatus

    /** An upload found a file of the same name; it waits for [TransferQueue.answerOverwrite]. */
    data object AwaitingOverwrite : TransferStatus

    data object Done : TransferStatus {
        override val isFinished: Boolean
            get() = true
    }

    /** No partial file remains: an upload left the remote file unchanged, a download was deleted. */
    data object Cancelled : TransferStatus {
        override val isFinished: Boolean
            get() = true
    }

    /** As [Cancelled], no partial file remains. */
    data class Failed(val reason: FilesFailure) : TransferStatus {
        override val isFinished: Boolean
            get() = true
    }
}

/**
 * @property directory The project's folder the job works in.
 * @property remotePath The file on the server: the upload's target or the download's source.
 * @property localName The local document's name.
 * @property totalBytes Null until known.
 */
data class TransferJob(
    val id: TransferId,
    val direction: TransferDirection,
    val directory: String,
    val remotePath: String,
    val localName: String,
    val bytesDone: Long = 0,
    val totalBytes: Long? = null,
    val status: TransferStatus = TransferStatus.Queued,
) {
    /**
     * Between 0 and 1; null while the size is not known. [TransferStatus.Done] is always 1, an empty
     * file included; before that, an empty file is at 0.
     */
    val fraction: Float?
        get() {
            if (status == TransferStatus.Done) return 1f
            val total = totalBytes ?: return null
            if (total <= 0) return 0f
            return (bytesDone.toFloat() / total).coerceIn(0f, 1f)
        }

    override fun toString(): String =
        "TransferJob(id=${id.value}, direction=$direction, directory=***, remotePath=***, localName=***, " +
            "bytesDone=$bytesDone, totalBytes=$totalBytes, status=$status)"
}

/**
 * The app's transfers, one at a time in the order added, on the app's scope through
 * [FileTransfers]. One instance for the app (P8.12 fills `DefaultTransferQueue`); the transfer
 * presenter and P8.13's foreground service share it.
 */
interface TransferQueue {
    /** Every job not yet cleared, oldest first. */
    val jobs: StateFlow<List<TransferJob>>

    /** Queues uploading [document] into [folder] under its own name. */
    fun upload(directory: String, folder: String, document: LocalDocument): TransferId

    /** Queues downloading [path] into [document]. */
    fun download(directory: String, path: String, document: LocalDocument): TransferId

    /** Answers [TransferStatus.AwaitingOverwrite]: replace the file, or cancel the job. */
    fun answerOverwrite(id: TransferId, overwrite: Boolean)

    /** Stops a queued or running job; it ends as [TransferStatus.Cancelled]. */
    fun cancel(id: TransferId)

    /** Removes the finished jobs. */
    fun clearFinished()
}

sealed interface TransferIntent {
    /** Queues each document for upload into [TransferState.folder]. */
    data class Upload(val documents: List<LocalDocument>) : TransferIntent {
        override fun toString(): String = "Upload(${documents.size})"
    }

    /**
     * Sets [TransferState.pendingDownload]. Forwarded by the graph from the file operations'
     * [FileOpsEvent.Download].
     */
    data class RequestDownload(val path: String) : TransferIntent {
        override fun toString(): String = "RequestDownload(***)"
    }

    /** Queues [TransferState.pendingDownload] into [document] and clears it. */
    data class DownloadTo(val document: LocalDocument) : TransferIntent {
        override fun toString(): String = "DownloadTo(***)"
    }

    /** The picker was closed without a place: clears [TransferState.pendingDownload]. */
    data object CancelDownload : TransferIntent

    data class AnswerOverwrite(val id: TransferId, val overwrite: Boolean) : TransferIntent

    data class Cancel(val id: TransferId) : TransferIntent

    data object ClearFinished : TransferIntent

    data object Close : TransferIntent
}

sealed interface TransferEvent {
    data object Close : TransferEvent
}

interface TransferPresenter : Presenter<TransferState, TransferIntent, TransferEvent>
