package io.github.thewisekd.phopencode.feature.files.logic.transfer

import io.github.thewisekd.phopencode.feature.files.logic.contract.LocalDocument
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferDirection
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferId
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferJob
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferQueue
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val FOLDER = "$ROOT/belgeler"

/** A queue that records what it was asked and shows the jobs a test sets. */
private class RecordingQueue : TransferQueue {
    val mutableJobs = MutableStateFlow<List<TransferJob>>(emptyList())

    override val jobs: StateFlow<List<TransferJob>> = mutableJobs

    val calls = mutableListOf<String>()
    val documents = mutableListOf<LocalDocument>()

    override fun upload(directory: String, folder: String, document: LocalDocument): TransferId {
        calls += "upload $directory $folder ${document.name}"
        documents += document
        return TransferId(calls.size.toLong())
    }

    override fun download(directory: String, path: String, document: LocalDocument): TransferId {
        calls += "download $directory $path ${document.name}"
        documents += document
        return TransferId(calls.size.toLong())
    }

    override fun answerOverwrite(id: TransferId, overwrite: Boolean) {
        calls += "answer ${id.value} $overwrite"
    }

    override fun cancel(id: TransferId) {
        calls += "cancel ${id.value}"
    }

    override fun clearFinished() {
        calls += "clear"
    }
}

private fun job(id: Long, status: TransferStatus = TransferStatus.Queued) =
    TransferJob(TransferId(id), TransferDirection.Upload, ROOT, "$FOLDER/$id.txt", "$id.txt", status = status)

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTransferPresenterTest {
    private fun TestScope.presenter(queue: TransferQueue): DefaultTransferPresenter =
        DefaultTransferPresenter(directory = ROOT, folder = FOLDER, queue = queue, scope = backgroundScope)

    @Test
    fun `starts with the folder and the queue's jobs, without waiting`() = runTest {
        val queue = RecordingQueue()
        queue.mutableJobs.value = listOf(job(1))

        val presenter = presenter(queue)

        assertEquals(TransferState(directory = ROOT, folder = FOLDER, jobs = listOf(job(1))), presenter.state.value)
        assertTrue(queue.calls.isEmpty())
    }

    @Test
    fun `follows the queue's jobs`() = runTest {
        val queue = RecordingQueue()
        val presenter = presenter(queue)
        runCurrent()

        queue.mutableJobs.value = listOf(job(1), job(2, TransferStatus.AwaitingOverwrite))
        runCurrent()

        assertEquals(listOf(job(1), job(2, TransferStatus.AwaitingOverwrite)), presenter.state.value.jobs)
        assertEquals(TransferId(2), presenter.state.value.overwritePrompt?.id)

        queue.mutableJobs.value = listOf(job(1, TransferStatus.Done))
        runCurrent()

        assertTrue(presenter.state.value.hasFinished)
        assertNull(presenter.state.value.overwritePrompt)
    }

    @Test
    fun `follows a real queue end to end`() = runTest {
        val transfers = FakeTransfers()
        val queue = DefaultTransferQueue(transfers, backgroundScope)
        val presenter = presenter(queue)

        presenter.onIntent(TransferIntent.Upload(listOf(FakeDocument("a.txt", ByteArray(3)))))
        runCurrent()

        val shown = presenter.state.value.jobs.single()
        assertEquals("$FOLDER/a.txt", shown.remotePath)
        assertEquals(TransferStatus.Done, shown.status)
    }

    @Test
    fun `uploads each picked document into the folder`() = runTest {
        val queue = RecordingQueue()
        val presenter = presenter(queue)

        presenter.onIntent(TransferIntent.Upload(listOf(FakeDocument("a.txt"), FakeDocument("b.txt"))))

        assertEquals(listOf("upload $ROOT $FOLDER a.txt", "upload $ROOT $FOLDER b.txt"), queue.calls)
    }

    @Test
    fun `a requested download waits for a place, then queues and clears`() = runTest {
        val queue = RecordingQueue()
        val presenter = presenter(queue)

        presenter.onIntent(TransferIntent.RequestDownload("$ROOT/src/main.kt"))
        assertEquals("$ROOT/src/main.kt", presenter.state.value.pendingDownload)
        assertTrue(queue.calls.isEmpty())

        val document = FakeDocument("main.kt")
        presenter.onIntent(TransferIntent.DownloadTo(document))

        assertNull(presenter.state.value.pendingDownload)
        assertEquals(listOf("download $ROOT $ROOT/src/main.kt main.kt"), queue.calls)
        assertTrue(queue.documents.single() === document)
    }

    @Test
    fun `a place without a pending download queues nothing`() = runTest {
        val queue = RecordingQueue()
        val presenter = presenter(queue)

        presenter.onIntent(TransferIntent.DownloadTo(FakeDocument("x")))

        assertTrue(queue.calls.isEmpty())
    }

    @Test
    fun `closing the picker clears the pending download`() = runTest {
        val queue = RecordingQueue()
        val presenter = presenter(queue)

        presenter.onIntent(TransferIntent.RequestDownload("$ROOT/a"))
        presenter.onIntent(TransferIntent.CancelDownload)
        presenter.onIntent(TransferIntent.DownloadTo(FakeDocument("a")))

        assertNull(presenter.state.value.pendingDownload)
        assertTrue(queue.calls.isEmpty())
    }

    @Test
    fun `a later request replaces the pending one`() = runTest {
        val queue = RecordingQueue()
        val presenter = presenter(queue)

        presenter.onIntent(TransferIntent.RequestDownload("$ROOT/a"))
        presenter.onIntent(TransferIntent.RequestDownload("$ROOT/b"))

        assertEquals("$ROOT/b", presenter.state.value.pendingDownload)
    }

    @Test
    fun `answers, cancels and clears go to the queue`() = runTest {
        val queue = RecordingQueue()
        val presenter = presenter(queue)

        presenter.onIntent(TransferIntent.AnswerOverwrite(TransferId(3), overwrite = true))
        presenter.onIntent(TransferIntent.AnswerOverwrite(TransferId(4), overwrite = false))
        presenter.onIntent(TransferIntent.Cancel(TransferId(5)))
        presenter.onIntent(TransferIntent.ClearFinished)

        assertEquals(listOf("answer 3 true", "answer 4 false", "cancel 5", "clear"), queue.calls)
    }

    @Test
    fun `close sends one event, delivered to a later collector`() = runTest {
        val queue = RecordingQueue()
        val presenter = presenter(queue)

        presenter.onIntent(TransferIntent.Close)
        val events = mutableListOf<TransferEvent>()
        backgroundScope.launch { presenter.events.toList(events) }
        runCurrent()

        assertEquals(listOf<TransferEvent>(TransferEvent.Close), events)
        assertFalse(queue.calls.isNotEmpty())
    }
}
