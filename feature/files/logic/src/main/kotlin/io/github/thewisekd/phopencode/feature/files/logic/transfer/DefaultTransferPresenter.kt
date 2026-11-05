package io.github.thewisekd.phopencode.feature.files.logic.transfer

import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileTransfers
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.LocalDocument
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferDirection
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferId
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferJob
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferQueue
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferStatus
import java.io.Closeable
import java.io.FileNotFoundException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The transfer screen (P8.12): shows every job of [queue] and adds to it; the rules of the jobs
 * themselves are [DefaultTransferQueue]'s.
 *
 * [TransferState.jobs] starts as the queue's jobs at construction and follows them in [scope], after
 * a `yield()`. [TransferState.pendingDownload] is the presenter's own: set by
 * [TransferIntent.RequestDownload], taken and cleared by [TransferIntent.DownloadTo], cleared by
 * [TransferIntent.CancelDownload]. A place picked with no download pending queues nothing.
 *
 * Nothing here logs; paths and names are user data.
 *
 * @param directory The project's folder, from the route.
 * @param folder Where uploads go, from the route's `path`.
 * @param queue The app's one queue, which runs the transfers.
 */
class DefaultTransferPresenter(
    private val directory: String,
    private val folder: String,
    private val queue: TransferQueue,
    private val scope: CoroutineScope,
) : TransferPresenter {
    private val lock = Any()

    private val mutableState = MutableStateFlow(
        TransferState(directory = directory, folder = folder, jobs = queue.jobs.value),
    )

    override val state: StateFlow<TransferState> = mutableState.asStateFlow()

    private val eventChannel = Channel<TransferEvent>(Channel.UNLIMITED)

    override val events: Flow<TransferEvent> = eventChannel.receiveAsFlow()

    init {
        scope.launch {
            yield()
            queue.jobs.collect { jobs -> change { it.copy(jobs = jobs) } }
        }
    }

    override fun onIntent(intent: TransferIntent) {
        when (intent) {
            is TransferIntent.Upload -> intent.documents.forEach { queue.upload(directory, folder, it) }
            is TransferIntent.RequestDownload -> change { it.copy(pendingDownload = intent.path) }
            is TransferIntent.DownloadTo -> downloadTo(intent.document)
            TransferIntent.CancelDownload -> change { it.copy(pendingDownload = null) }
            is TransferIntent.AnswerOverwrite -> queue.answerOverwrite(intent.id, intent.overwrite)
            is TransferIntent.Cancel -> queue.cancel(intent.id)
            TransferIntent.ClearFinished -> queue.clearFinished()
            TransferIntent.Close -> synchronized(lock) { eventChannel.trySend(TransferEvent.Close) }
        }
    }

    private fun downloadTo(document: LocalDocument) {
        // The queue is called outside the lock: it has its own, and its jobs come back through it.
        val path = synchronized(lock) {
            val pending = mutableState.value.pendingDownload ?: return
            mutableState.value = mutableState.value.copy(pendingDownload = null)
            pending
        }
        queue.download(directory, path, document)
    }

    private inline fun change(transform: (TransferState) -> TransferState) {
        synchronized(lock) { mutableState.value = transform(mutableState.value) }
    }
}

/**
 * The app's one transfer queue (P8.12), on the app's [scope] so that transfers outlive the screen.
 *
 * - Order: one job runs at a time, the oldest [TransferStatus.Queued] one. A job waiting for its
 *   overwrite answer does not hold up the ones after it; answered yes, it is queued again in its
 *   place, ahead of every newer job, and uploads with `overwrite`.
 * - Progress: [TransferJob.bytesDone] follows the port's `onProgress`. An upload's
 *   [TransferJob.totalBytes] is [LocalDocument.sizeBytes]; a download's is its stat's size, taken
 *   before any byte is written. When it ends [TransferStatus.Done], both are the bytes moved.
 * - Overwrite: an upload never replaces a file unasked. It asks the port without `overwrite`, and
 *   [FilesFailure.AlreadyExists] becomes [TransferStatus.AwaitingOverwrite]. There is no separate
 *   existence check that could race with the upload: the port's refusal is the check.
 * - No partial file remains:
 *   - Upload: [FileTransfers.upload] streams into a temporary file and renames it over the target
 *     only at the end; on any failure or cancellation the target is unchanged and the temporary
 *     file is gone. The queue relies on that and itself creates nothing on the server, so it
 *     deletes nothing there: an existing file is never touched by a failed or cancelled job.
 *   - Download: the local document is the one the user picked for this download. On a failure or
 *     a cancellation, also of a job that never started, its stream is closed and it is deleted;
 *     if the provider will not delete it, it is emptied instead so no partial content remains.
 * - Cancel: a queued or waiting job ends [TransferStatus.Cancelled] at once; a running one is
 *   cancelled in the port and ends [TransferStatus.Cancelled] when the port has stopped and the
 *   cleanup is done. A job whose port call had already succeeded ends [TransferStatus.Done], as
 *   that is what the files are. [cancelAll] cancels every job not finished, atomically: all are
 *   marked cancelled before any running coroutine is stopped, so none can start in between.
 * - An upload whose document name is not a plain file name (empty, `.`, `..`, with a `/` or a NUL)
 *   fails with [FilesFailure.PathRejected] without a request.
 * - A port failure is [TransferStatus.Failed] with its [FilesFailure]; a local document that is
 *   gone is [FilesFailure.NotFound], one the app may not use [FilesFailure.PermissionDenied], any
 *   other exception [FilesFailure.Unknown]. Cancellation is never wrapped.
 * - Closing the local stream: an upload's outcome is the port's, so a failing close after the port
 *   returned leaves it [TransferStatus.Done]. A download's close failing after a complete transfer
 *   fails it, as the provider may not have stored every byte, and the document is removed.
 * - When [scope] ends, the running job ends [TransferStatus.Cancelled] with the same cleanup, and
 *   every queued or waiting job, and any added later, is cancelled in one pass, without recursion.
 *   The queue does not wait for a running job to notice: on the first job added it registers a
 *   completion handler on [scope]'s `Job`, which cancels whatever is left, also when only jobs
 *   awaiting an overwrite answer remain.
 * - Where the local cleanup runs: deleting a document is blocking I/O. A running job removes its
 *   own partial file in its coroutine. Every other removal, after [cancel], [cancelAll], the end of
 *   [scope] or a run that never began, starts from a caller's thread, possibly the main thread, or
 *   from a completion handler on any thread; it is handed to [cleanupContext] in a coroutine of its
 *   own, not tied to [scope], so it still runs after [scope] has ended. The job shows
 *   [TransferStatus.Cancelled] at once; its document goes when that coroutine runs.
 *
 * Nothing runs in the constructor; each job starts in [scope] with a `yield()`. Nothing here logs;
 * paths, names and contents are user data.
 *
 * @param transfers Moves the bytes.
 * @param scope The app's scope.
 * @param cleanupContext Where removals that do not run in a job's own coroutine run; tests pass a
 *   test dispatcher.
 */
class DefaultTransferQueue(
    private val transfers: FileTransfers,
    private val scope: CoroutineScope,
    private val cleanupContext: CoroutineContext = Dispatchers.IO,
) : TransferQueue {
    /**
     * @property job What [jobs] shows.
     * @property document The picked local document.
     * @property overwrite The user answered yes to replacing the upload's target.
     * @property attempt Increases with each start, so that a stale run cannot change the job.
     * @property runner The coroutine of the current attempt, while [TransferStatus.Running].
     */
    private class Entry(
        val job: TransferJob,
        val document: LocalDocument,
        val overwrite: Boolean = false,
        val attempt: Int = 0,
        val runner: Job? = null,
    ) {
        fun copy(
            job: TransferJob = this.job,
            overwrite: Boolean = this.overwrite,
            attempt: Int = this.attempt,
            runner: Job? = this.runner,
        ): Entry = Entry(job, document, overwrite, attempt, runner)

        override fun toString(): String = "Entry(job=$job, overwrite=$overwrite, attempt=$attempt)"
    }

    /** How a run ended. */
    private sealed interface Outcome {
        data class Done(val bytes: Long) : Outcome

        data object AwaitingOverwrite : Outcome

        data class Failed(val reason: FilesFailure) : Outcome

        data object Cancelled : Outcome
    }

    private val lock = Any()

    private var entries: List<Entry> = emptyList()

    private var nextId = 1L

    /** Whether the handler on [scope]'s `Job` is registered. */
    private var watching = false

    private val mutableJobs = MutableStateFlow<List<TransferJob>>(emptyList())

    override val jobs: StateFlow<List<TransferJob>> = mutableJobs.asStateFlow()

    override fun upload(directory: String, folder: String, document: LocalDocument): TransferId {
        val name = document.name
        val valid = isPlainName(name)
        val id = add { id ->
            TransferJob(
                id = id,
                direction = TransferDirection.Upload,
                directory = directory,
                remotePath = join(folder, name),
                localName = name,
                totalBytes = document.sizeBytes,
                status = if (valid) TransferStatus.Queued else TransferStatus.Failed(FilesFailure.PathRejected),
            ) to document
        }
        watchScope()
        startNext()
        return id
    }

    override fun download(directory: String, path: String, document: LocalDocument): TransferId {
        val id = add { id ->
            TransferJob(
                id = id,
                direction = TransferDirection.Download,
                directory = directory,
                remotePath = path,
                localName = document.name,
            ) to document
        }
        watchScope()
        startNext()
        return id
    }

    override fun answerOverwrite(id: TransferId, overwrite: Boolean) {
        synchronized(lock) {
            val entry = find(id) ?: return
            if (entry.job.status != TransferStatus.AwaitingOverwrite) return
            val status = if (overwrite) TransferStatus.Queued else TransferStatus.Cancelled
            replace(entry.copy(job = entry.job.copy(status = status, bytesDone = 0), overwrite = overwrite))
        }
        startNext()
    }

    override fun cancel(id: TransferId) {
        val cancelled = synchronized(lock) {
            val entry = find(id) ?: return
            when (entry.job.status) {
                TransferStatus.Queued, TransferStatus.AwaitingOverwrite -> {
                    replace(entry.copy(job = entry.job.copy(status = TransferStatus.Cancelled)))
                    entry
                }
                TransferStatus.Running -> {
                    // The run ends the job when the port has stopped and the cleanup is done.
                    entry.runner?.cancel()
                    return
                }
                else -> return
            }
        }
        removePartialLater(listOf(cancelled))
    }

    override fun clearFinished() {
        synchronized(lock) {
            entries = entries.filterNot { it.job.status.isFinished }
            publish()
        }
    }

    // Running

    /**
     * Registers, once and only when the first job is added, a handler that cancels what is left
     * when [scope] ends. Registered outside [lock]: on a scope that has already ended, the handler
     * runs at once, in this call.
     */
    private fun watchScope() {
        val scopeJob = synchronized(lock) {
            if (watching) return
            watching = true
            scope.coroutineContext[Job]
        } ?: return
        scopeJob.invokeOnCompletion { drain() }
    }

    /**
     * Starts the oldest queued job when none runs. Once [scope] has ended, cancels every queued job
     * instead, in one pass: a coroutine launched there would never run.
     */
    private fun startNext() {
        if (!scope.isActive) {
            drain()
            return
        }
        val (id, attempt, runner) = synchronized(lock) {
            if (entries.any { it.job.status == TransferStatus.Running }) return
            val next = entries.firstOrNull { it.job.status == TransferStatus.Queued } ?: return
            val attempt = next.attempt + 1
            val runner = scope.launch(start = CoroutineStart.LAZY) { run(next.job.id, attempt) }
            replace(
                next.copy(
                    job = next.job.copy(status = TransferStatus.Running, bytesDone = 0),
                    attempt = attempt,
                    runner = runner,
                ),
            )
            Triple(next.job.id, attempt, runner)
        }
        // A run that never got to its own cleanup, as when the scope ended just now, is ended here;
        // its startNext then finds the scope ended and drains, so this never recurses deeper.
        runner.invokeOnCompletion { cause -> abandoned(id, attempt, cause) }
        runner.start()
    }

    /**
     * Marks every queued and waiting job cancelled in one lock section, so nothing can be picked
     * between them, then hands the removal of their partial files to [cleanupContext]. Running jobs
     * are left to their own run, which ends them with their cleanup. Idempotent and never blocks:
     * a second call finds nothing to cancel.
     *
     * @return The running jobs' coroutines, for [cancelAllJobs] to cancel.
     */
    private fun drain(): List<Job> {
        val (stopped, runners) = synchronized(lock) {
            val stopped = mutableListOf<Entry>()
            val runners = mutableListOf<Job>()
            entries = entries.map { entry ->
                when (entry.job.status) {
                    TransferStatus.Queued, TransferStatus.AwaitingOverwrite -> {
                        stopped += entry
                        entry.copy(job = entry.job.copy(status = TransferStatus.Cancelled))
                    }
                    TransferStatus.Running -> {
                        entry.runner?.let { runners += it }
                        entry
                    }
                    else -> entry
                }
            }
            if (stopped.isNotEmpty()) publish()
            stopped to runners
        }
        removePartialLater(stopped)
        return runners
    }

    /**
     * [cancelAll] for this queue, atomic with respect to scheduling: every unfinished job is marked
     * cancelled first, under one lock section, so stopping the running one cannot start another,
     * not even an older overwrite retry; only then are the running coroutines cancelled.
     */
    internal fun cancelAllJobs() {
        drain().forEach { it.cancel() }
    }

    private suspend fun run(id: TransferId, attempt: Int) {
        val entry = synchronized(lock) { find(id) } ?: return
        val outcome = try {
            yield()
            when (entry.job.direction) {
                TransferDirection.Upload -> upload(entry, attempt)
                TransferDirection.Download -> download(entry, attempt)
            }
        } catch (e: CancellationException) {
            finish(entry, attempt, Outcome.Cancelled)
            throw e
        } catch (e: FilesException) {
            Outcome.Failed(e.failure)
        } catch (e: FileNotFoundException) {
            Outcome.Failed(FilesFailure.NotFound)
        } catch (e: SecurityException) {
            Outcome.Failed(FilesFailure.PermissionDenied)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Outcome.Failed(FilesFailure.Unknown)
        }
        finish(entry, attempt, outcome)
    }

    /**
     * The outcome is the port's alone. The local stream is only read: once the port returned, the
     * server holds the whole file, so a failure closing the stream afterwards is ignored and the
     * job is done; after a port failure it is ignored too, so that the port's reason shows.
     */
    private suspend fun upload(entry: Entry, attempt: Int): Outcome {
        val job = entry.job
        var sent = 0L
        val input = entry.document.openInput()
        return try {
            transfers.upload(job.directory, job.remotePath, input, entry.overwrite) { bytes ->
                sent = bytes
                progress(job.id, attempt, bytes, total = null)
            }
            Outcome.Done(sent)
        } catch (e: FilesException) {
            if (e.failure == FilesFailure.AlreadyExists && !entry.overwrite) Outcome.AwaitingOverwrite else throw e
        } finally {
            closeQuietly(input)
        }
    }

    private suspend fun download(entry: Entry, attempt: Int): Outcome {
        val job = entry.job
        val stat = transfers.stat(job.directory, job.remotePath)
        if (stat.type == FileEntryType.Directory) throw FilesException(FilesFailure.Unsupported)
        progress(job.id, attempt, bytes = 0, total = stat.sizeBytes)
        val sink = entry.document.openOutput()
        val written = try {
            transfers.download(job.directory, job.remotePath, sink) { bytes ->
                progress(job.id, attempt, bytes, total = null)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // The port's failure or the cancellation is the outcome; the stream only has to go.
            closeQuietly(sink)
            throw e
        }
        // Not quiet: a provider may flush or sync on close, so a failing close after a complete
        // transfer means the local file may be incomplete. That fails the job, and it is removed.
        sink.close()
        return Outcome.Done(written)
    }

    private fun closeQuietly(stream: Closeable) {
        try {
            stream.close()
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // Nothing to do: the outcome is already decided by the transfer itself.
        }
    }

    private fun progress(id: TransferId, attempt: Int, bytes: Long, total: Long?) {
        synchronized(lock) {
            val entry = find(id) ?: return
            if (entry.attempt != attempt || entry.job.status != TransferStatus.Running) return
            replace(entry.copy(job = entry.job.copy(bytesDone = bytes, totalBytes = total ?: entry.job.totalBytes)))
        }
    }

    /**
     * Cleans up after [outcome], records it, and starts the next job.
     *
     * @param later Whether the cleanup goes to [cleanupContext], when not called from the job's
     *   own coroutine.
     */
    private fun finish(entry: Entry, attempt: Int, outcome: Outcome, later: Boolean = false) {
        if (outcome !is Outcome.Done && outcome != Outcome.AwaitingOverwrite) {
            if (later) removePartialLater(listOf(entry)) else removePartial(entry)
        }
        synchronized(lock) {
            val current = find(entry.job.id) ?: return@synchronized
            if (current.attempt != attempt || current.job.status != TransferStatus.Running) return@synchronized
            val job = current.job
            val next = when (outcome) {
                is Outcome.Done -> job.copy(status = TransferStatus.Done, bytesDone = outcome.bytes, totalBytes = outcome.bytes)
                Outcome.AwaitingOverwrite -> job.copy(status = TransferStatus.AwaitingOverwrite, bytesDone = 0)
                is Outcome.Failed -> job.copy(status = TransferStatus.Failed(outcome.reason))
                Outcome.Cancelled -> job.copy(status = TransferStatus.Cancelled)
            }
            replace(current.copy(job = next, runner = null))
        }
        startNext()
    }

    /** Ends an attempt whose coroutine completed without recording how, as one never started. */
    private fun abandoned(id: TransferId, attempt: Int, cause: Throwable?) {
        val entry = synchronized(lock) { find(id) } ?: return
        if (entry.attempt != attempt || entry.job.status != TransferStatus.Running) return
        val outcome = if (cause is CancellationException) Outcome.Cancelled else Outcome.Failed(FilesFailure.Unknown)
        finish(entry, attempt, outcome, later = true)
    }

    /** [removePartial] for each of [stopped], in a coroutine of its own on [cleanupContext]. */
    private fun removePartialLater(stopped: List<Entry>) {
        val downloads = stopped.filter { it.job.direction == TransferDirection.Download }
        if (downloads.isEmpty()) return
        CoroutineScope(cleanupContext).launch {
            for (entry in downloads) removePartial(entry)
        }
    }

    /**
     * Leaves no partial file of [entry]. An upload created nothing outside the port, which already
     * removed its temporary file; a download's picked document is deleted, or emptied if that fails.
     */
    private fun removePartial(entry: Entry) {
        if (entry.job.direction != TransferDirection.Download) return
        val document = entry.document
        val deleted = try {
            document.delete()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            false
        }
        if (deleted) return
        try {
            document.openOutput().close()
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // Nothing more can be done from here; the job's status already says it did not finish.
        }
    }

    // The model

    private inline fun add(make: (TransferId) -> Pair<TransferJob, LocalDocument>): TransferId =
        synchronized(lock) {
            val id = TransferId(nextId++)
            val (job, document) = make(id)
            entries = entries + Entry(job, document)
            publish()
            id
        }

    private fun find(id: TransferId): Entry? = entries.firstOrNull { it.job.id == id }

    /** Replaces the entry of the same ID and publishes; call it holding [lock]. */
    private fun replace(entry: Entry) {
        entries = entries.map { if (it.job.id == entry.job.id) entry else it }
        publish()
    }

    private fun publish() {
        mutableJobs.value = entries.map { it.job }
    }

    private companion object {
        fun isPlainName(name: String): Boolean =
            name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name

        fun join(folder: String, name: String): String {
            val base = folder.trimEnd('/')
            return "$base/$name"
        }
    }
}
