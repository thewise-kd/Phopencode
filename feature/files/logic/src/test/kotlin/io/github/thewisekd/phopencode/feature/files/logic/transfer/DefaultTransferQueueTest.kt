package io.github.thewisekd.phopencode.feature.files.logic.transfer

import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileTransfers
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.LocalDocument
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferDirection
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferId
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferJob
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferQueue
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferStatus
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTransferQueueTest {
    private fun TestScope.queue(transfers: FakeTransfers): DefaultTransferQueue =
        DefaultTransferQueue(transfers = transfers, scope = backgroundScope, cleanupContext = inline())

    /** Runs the queue's cleanup in place, so that a test sees it at once. */
    private fun TestScope.inline() = UnconfinedTestDispatcher(testScheduler)

    private fun DefaultTransferQueue.job(id: TransferId): TransferJob = jobs.value.single { it.id == id }

    private fun DefaultTransferQueue.status(id: TransferId): TransferStatus = job(id).status

    // Queue

    @Test
    fun `nothing reaches the port or the document before the scope runs`() = runTest {
        val transfers = FakeTransfers()
        val queue = queue(transfers)
        val document = FakeDocument("a.txt", utf8("hello"))

        val id = queue.upload(ROOT, "$ROOT/docs", document)

        assertEquals(
            listOf(
                TransferJob(
                    id = id,
                    direction = TransferDirection.Upload,
                    directory = ROOT,
                    remotePath = "$ROOT/docs/a.txt",
                    localName = "a.txt",
                    totalBytes = 5,
                    status = TransferStatus.Running,
                ),
            ),
            queue.jobs.value,
        )
        assertTrue(transfers.calls.isEmpty())
        assertTrue(document.events.isEmpty())
    }

    @Test
    fun `ids increase and jobs stay oldest first`() = runTest {
        val queue = queue(FakeTransfers())

        val first = queue.upload(ROOT, ROOT, FakeDocument("a"))
        val second = queue.download(ROOT, "$ROOT/b", FakeDocument("b"))
        val third = queue.upload(ROOT, ROOT, FakeDocument("c"))

        assertTrue(first.value < second.value && second.value < third.value)
        assertEquals(listOf(first, second, third), queue.jobs.value.map { it.id })
        assertEquals(
            listOf(TransferDirection.Upload, TransferDirection.Download, TransferDirection.Upload),
            queue.jobs.value.map { it.direction },
        )
    }

    @Test
    fun `runs one job at a time in the order added`() = runTest {
        val transfers = FakeTransfers()
        val gate = CompletableDeferred<Unit>()
        transfers.gate = gate
        val queue = queue(transfers)

        val first = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("first file")))
        val second = queue.upload(ROOT, ROOT, FakeDocument("b.txt", utf8("second")))
        runCurrent()

        assertEquals(TransferStatus.Running, queue.status(first))
        assertEquals(TransferStatus.Queued, queue.status(second))
        assertEquals(listOf("upload"), transfers.calls)

        gate.complete(Unit)
        runCurrent()

        assertEquals(TransferStatus.Done, queue.status(first))
        assertEquals(TransferStatus.Done, queue.status(second))
        assertEquals(listOf("$ROOT/a.txt", "$ROOT/b.txt"), transfers.uploads.map { it.first })
    }

    @Test
    fun `a job added after the others finished runs too`() = runTest {
        val transfers = FakeTransfers()
        val queue = queue(transfers)

        val first = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("1")))
        runCurrent()
        val second = queue.upload(ROOT, ROOT, FakeDocument("b.txt", utf8("2")))
        runCurrent()

        assertEquals(TransferStatus.Done, queue.status(first))
        assertEquals(TransferStatus.Done, queue.status(second))
    }

    // Upload

    @Test
    fun `an upload reports progress and ends done with the file on the server`() = runTest {
        val transfers = FakeTransfers(chunk = 4)
        val gate = CompletableDeferred<Unit>()
        transfers.gate = gate
        val queue = queue(transfers)
        val document = FakeDocument("notlar.md", utf8("0123456789"))

        val id = queue.upload(ROOT, "$ROOT/docs", document)
        runCurrent()

        with(queue.job(id)) {
            assertEquals(TransferStatus.Running, status)
            assertEquals(4, bytesDone)
            assertEquals(10, totalBytes)
            assertEquals(0.4f, fraction)
        }

        gate.complete(Unit)
        runCurrent()

        with(queue.job(id)) {
            assertEquals(TransferStatus.Done, status)
            assertEquals(10, bytesDone)
            assertEquals(1f, fraction)
        }
        assertContentEquals(utf8("0123456789"), transfers.files["$ROOT/docs/notlar.md"])
        assertEquals(listOf("$ROOT/docs/notlar.md" to false), transfers.uploads)
        assertEquals(0, document.openStreams)
        assertTrue(transfers.temporaries.isEmpty())
    }

    @Test
    fun `an upload of unknown size learns it at the end`() = runTest {
        val transfers = FakeTransfers()
        val queue = queue(transfers)
        val document = FakeDocument("a.txt", utf8("abcdef"), sizeBytes = null)

        val id = queue.upload(ROOT, ROOT, document)
        assertNull(queue.job(id).totalBytes)
        runCurrent()

        assertEquals(TransferStatus.Done, queue.status(id))
        assertEquals(6, queue.job(id).bytesDone)
    }

    @Test
    fun `uploads into the root folder without a double slash`() = runTest {
        val transfers = FakeTransfers()
        val queue = queue(transfers)

        val id = queue.upload("/", "/", FakeDocument("a.txt"))

        assertEquals("/a.txt", queue.job(id).remotePath)
        val trailing = queue.upload(ROOT, "$ROOT/docs/", FakeDocument("b.txt"))
        assertEquals("$ROOT/docs/b.txt", queue.job(trailing).remotePath)
    }

    @Test
    fun `an upload whose name is not a plain file name fails without a request`() = runTest {
        val transfers = FakeTransfers()
        val queue = queue(transfers)

        val ids = listOf("", ".", "..", "../x", "a/b", "a\u0000b", " ").map {
            queue.upload(ROOT, ROOT, FakeDocument(it, utf8("x")))
        }
        runCurrent()

        ids.forEach { assertEquals(TransferStatus.Failed(FilesFailure.PathRejected), queue.status(it)) }
        assertTrue(transfers.calls.isEmpty())
    }

    @Test
    fun `an existing target waits for the overwrite answer while the next job runs`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a.txt"] = utf8("old")
        val queue = queue(transfers)

        val waiting = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("new content")))
        val next = queue.upload(ROOT, ROOT, FakeDocument("b.txt", utf8("b")))
        runCurrent()

        with(queue.job(waiting)) {
            assertEquals(TransferStatus.AwaitingOverwrite, status)
            assertEquals(0, bytesDone)
        }
        assertEquals(TransferStatus.Done, queue.status(next))
        assertContentEquals(utf8("old"), transfers.files["$ROOT/a.txt"])
        assertTrue(transfers.temporaries.isEmpty())
    }

    @Test
    fun `answering yes overwrites the file`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a.txt"] = utf8("old")
        val queue = queue(transfers)
        val document = FakeDocument("a.txt", utf8("new content"))

        val id = queue.upload(ROOT, ROOT, document)
        runCurrent()
        queue.answerOverwrite(id, overwrite = true)
        assertEquals(TransferStatus.Running, queue.status(id))
        assertNull(queue.jobs.value.firstOrNull { it.status == TransferStatus.AwaitingOverwrite })
        runCurrent()

        assertEquals(TransferStatus.Done, queue.status(id))
        assertContentEquals(utf8("new content"), transfers.files["$ROOT/a.txt"])
        assertEquals(listOf("$ROOT/a.txt" to false, "$ROOT/a.txt" to true), transfers.uploads)
        assertEquals(0, document.openStreams)
    }

    @Test
    fun `answering no cancels the job and leaves the file`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a.txt"] = utf8("old")
        val queue = queue(transfers)

        val id = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("new")))
        runCurrent()
        queue.answerOverwrite(id, overwrite = false)
        runCurrent()

        assertEquals(TransferStatus.Cancelled, queue.status(id))
        assertContentEquals(utf8("old"), transfers.files["$ROOT/a.txt"])
        assertEquals(1, transfers.uploads.size)
    }

    @Test
    fun `an overwrite answer for a job not waiting does nothing`() = runTest {
        val transfers = FakeTransfers()
        val gate = CompletableDeferred<Unit>()
        transfers.gate = gate
        val queue = queue(transfers)

        val id = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("12345678")))
        runCurrent()
        queue.answerOverwrite(id, overwrite = false)
        queue.answerOverwrite(TransferId(999), overwrite = true)

        assertEquals(TransferStatus.Running, queue.status(id))
        gate.complete(Unit)
        runCurrent()
        assertEquals(TransferStatus.Done, queue.status(id))
    }

    @Test
    fun `a cancelled upload leaves the server file unchanged and the next job runs`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a.txt"] = utf8("old")
        transfers.gate = CompletableDeferred()
        val queue = queue(transfers)
        val document = FakeDocument("a.txt", utf8("much newer content"))

        val id = queue.upload(ROOT, ROOT, document)
        runCurrent()
        queue.answerOverwrite(id, overwrite = true)
        val next = queue.upload(ROOT, ROOT, FakeDocument("b.txt", utf8("b")))
        runCurrent()
        assertEquals(TransferStatus.Running, queue.status(id))
        assertEquals(1, transfers.temporaries.size)

        queue.cancel(id)
        transfers.gate = null
        runCurrent()

        assertEquals(TransferStatus.Cancelled, queue.status(id))
        assertContentEquals(utf8("old"), transfers.files["$ROOT/a.txt"])
        assertTrue(transfers.temporaries.isEmpty())
        assertEquals(0, document.openStreams)
        assertEquals(TransferStatus.Done, queue.status(next))
    }

    @Test
    fun `a cancelled new upload leaves no file on the server`() = runTest {
        val transfers = FakeTransfers()
        transfers.gate = CompletableDeferred()
        val queue = queue(transfers)

        val id = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("content")))
        runCurrent()
        queue.cancel(id)
        runCurrent()

        assertEquals(TransferStatus.Cancelled, queue.status(id))
        assertTrue(transfers.files.isEmpty())
    }

    @Test
    fun `a failed upload leaves the server file unchanged`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a.txt"] = utf8("old")
        transfers.failMidway = FilesException(FilesFailure.Unreachable)
        val queue = queue(transfers)
        val document = FakeDocument("a.txt", utf8("new content"))

        val id = queue.upload(ROOT, ROOT, document)
        runCurrent()
        queue.answerOverwrite(id, overwrite = true)
        runCurrent()

        assertEquals(TransferStatus.Failed(FilesFailure.Unreachable), queue.status(id))
        assertContentEquals(utf8("old"), transfers.files["$ROOT/a.txt"])
        assertTrue(transfers.temporaries.isEmpty())
        assertEquals(0, document.openStreams)
        assertFalse(document.deleted)
    }

    @Test
    fun `an upload whose document cannot be read fails and the next job runs`() = runTest {
        val transfers = FakeTransfers()
        val queue = queue(transfers)
        val gone = FakeDocument("a.txt", utf8("x")).apply { openFailure = FileNotFoundException("gone") }
        val broken = FakeDocument("b.txt", utf8("x")).apply { openFailure = IOException("broken") }

        val first = queue.upload(ROOT, ROOT, gone)
        val second = queue.upload(ROOT, ROOT, broken)
        val third = queue.upload(ROOT, ROOT, FakeDocument("c.txt", utf8("c")))
        runCurrent()

        assertEquals(TransferStatus.Failed(FilesFailure.NotFound), queue.status(first))
        assertEquals(TransferStatus.Failed(FilesFailure.Unknown), queue.status(second))
        assertEquals(TransferStatus.Done, queue.status(third))
        assertFalse(gone.deleted)
    }

    @Test
    fun `an unexpected exception from the port fails the job as unknown`() = runTest {
        val transfers = FakeTransfers()
        transfers.failMidway = IllegalStateException("boom")
        val queue = queue(transfers)

        val id = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("12345678")))
        runCurrent()

        assertEquals(TransferStatus.Failed(FilesFailure.Unknown), queue.status(id))
        assertTrue(transfers.files.isEmpty())
    }

    // Download

    @Test
    fun `a download reports progress and ends done with the whole file`() = runTest {
        val transfers = FakeTransfers(chunk = 4)
        transfers.files["$ROOT/src/main.kt"] = utf8("fun main() {}")
        val gate = CompletableDeferred<Unit>()
        transfers.gate = gate
        val queue = queue(transfers)
        val document = FakeDocument("main.kt", sizeBytes = null)

        val id = queue.download(ROOT, "src/main.kt", document)
        with(queue.job(id)) {
            assertEquals(TransferDirection.Download, direction)
            assertEquals("src/main.kt", remotePath)
            assertEquals("main.kt", localName)
            assertNull(totalBytes)
        }
        runCurrent()

        with(queue.job(id)) {
            assertEquals(TransferStatus.Running, status)
            assertEquals(4, bytesDone)
            assertEquals(13, totalBytes)
        }

        gate.complete(Unit)
        runCurrent()

        with(queue.job(id)) {
            assertEquals(TransferStatus.Done, status)
            assertEquals(13, bytesDone)
        }
        assertContentEquals(utf8("fun main() {}"), document.content)
        assertFalse(document.deleted)
        assertEquals(0, document.openStreams)
        assertEquals(listOf("stat", "download"), transfers.calls)
    }

    @Test
    fun `a cancelled download deletes the partial local file`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/big.bin"] = utf8("0123456789")
        transfers.gate = CompletableDeferred()
        val queue = queue(transfers)
        val document = FakeDocument("big.bin")

        val id = queue.download(ROOT, "$ROOT/big.bin", document)
        runCurrent()
        assertContentEquals(utf8("0123"), document.content)

        queue.cancel(id)
        runCurrent()

        assertEquals(TransferStatus.Cancelled, queue.status(id))
        assertTrue(document.deleted)
        assertNull(document.content)
        assertEquals(listOf("openOutput", "closeOutput", "delete"), document.events)
        assertContentEquals(utf8("0123456789"), transfers.files["$ROOT/big.bin"])
    }

    @Test
    fun `a failed download deletes the partial local file`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/big.bin"] = utf8("0123456789")
        transfers.failMidway = FilesException(FilesFailure.NotConnected)
        val queue = queue(transfers)
        val document = FakeDocument("big.bin")

        val id = queue.download(ROOT, "$ROOT/big.bin", document)
        runCurrent()

        assertEquals(TransferStatus.Failed(FilesFailure.NotConnected), queue.status(id))
        assertTrue(document.deleted)
        assertEquals(listOf("openOutput", "closeOutput", "delete"), document.events)
    }

    @Test
    fun `a download whose stat fails deletes the picked file and writes nothing`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a"] = utf8("a")
        transfers.statFailure = FilesFailure.PermissionDenied
        val queue = queue(transfers)
        val document = FakeDocument("a")

        val id = queue.download(ROOT, "$ROOT/a", document)
        runCurrent()

        assertEquals(TransferStatus.Failed(FilesFailure.PermissionDenied), queue.status(id))
        assertEquals(listOf("delete"), document.events)
        assertEquals(listOf("stat"), transfers.calls)
    }

    @Test
    fun `a download of a folder fails without reading`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/src"] = ByteArray(0)
        transfers.statType = FileEntryType.Directory
        val queue = queue(transfers)
        val document = FakeDocument("src")

        val id = queue.download(ROOT, "$ROOT/src", document)
        runCurrent()

        assertEquals(TransferStatus.Failed(FilesFailure.Unsupported), queue.status(id))
        assertTrue(document.deleted)
        assertEquals(listOf("stat"), transfers.calls)
    }

    @Test
    fun `a download whose local file cannot be deleted is emptied instead`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a"] = utf8("secret data")
        transfers.failMidway = FilesException(FilesFailure.Unreachable)
        val queue = queue(transfers)
        val document = FakeDocument("a").apply { deleteSucceeds = false }

        val id = queue.download(ROOT, "$ROOT/a", document)
        runCurrent()

        assertEquals(TransferStatus.Failed(FilesFailure.Unreachable), queue.status(id))
        assertContentEquals(ByteArray(0), document.content)
        assertEquals(0, document.openStreams)
    }

    @Test
    fun `a download whose local file cannot be opened fails and is removed`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a"] = utf8("a")
        val queue = queue(transfers)
        val document = FakeDocument("a").apply { openFailure = IOException("no") }

        val id = queue.download(ROOT, "$ROOT/a", document)
        runCurrent()

        assertEquals(TransferStatus.Failed(FilesFailure.Unknown), queue.status(id))
        assertTrue(document.deleted)
    }

    @Test
    fun `an empty download is done at full fraction`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/empty"] = ByteArray(0)
        val queue = queue(transfers)
        val document = FakeDocument("empty")

        val id = queue.download(ROOT, "$ROOT/empty", document)
        runCurrent()

        assertEquals(TransferStatus.Done, queue.status(id))
        assertEquals(1f, queue.job(id).fraction)
        assertContentEquals(ByteArray(0), document.content)
        assertFalse(document.deleted)
    }

    // Cancel

    @Test
    fun `cancelling a queued job never starts it, and a queued download's file goes`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a"] = utf8("a")
        val gate = CompletableDeferred<Unit>()
        transfers.gate = gate
        val queue = queue(transfers)
        val download = FakeDocument("a")

        val running = queue.upload(ROOT, ROOT, FakeDocument("x.txt", utf8("12345678")))
        val queuedUpload = queue.upload(ROOT, ROOT, FakeDocument("y.txt", utf8("y")))
        val queuedDownload = queue.download(ROOT, "$ROOT/a", download)
        runCurrent()

        queue.cancel(queuedUpload)
        queue.cancel(queuedDownload)
        assertEquals(TransferStatus.Cancelled, queue.status(queuedUpload))
        assertEquals(TransferStatus.Cancelled, queue.status(queuedDownload))
        assertTrue(download.deleted)

        gate.complete(Unit)
        runCurrent()

        assertEquals(TransferStatus.Done, queue.status(running))
        assertEquals(listOf("$ROOT/x.txt"), transfers.uploads.map { it.first })
        assertFalse("download" in transfers.calls)
    }

    @Test
    fun `cancelling a job waiting for the overwrite answer leaves the file`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a.txt"] = utf8("old")
        val queue = queue(transfers)

        val id = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("new")))
        runCurrent()
        queue.cancel(id)
        runCurrent()

        assertEquals(TransferStatus.Cancelled, queue.status(id))
        assertContentEquals(utf8("old"), transfers.files["$ROOT/a.txt"])
    }

    @Test
    fun `cancelling a finished or unknown job does nothing`() = runTest {
        val transfers = FakeTransfers()
        val queue = queue(transfers)

        val id = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("a")))
        runCurrent()
        queue.cancel(id)
        queue.cancel(TransferId(999))

        assertEquals(TransferStatus.Done, queue.status(id))
        assertContentEquals(utf8("a"), transfers.files["$ROOT/a.txt"])
    }

    @Test
    fun `cancelling all stops every job not finished and removes partial files`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a"] = utf8("0123456789")
        transfers.files["$ROOT/b.txt"] = utf8("old")
        val queue = queue(transfers)
        val running = FakeDocument("a")
        val queued = FakeDocument("c")

        val done = queue.upload(ROOT, ROOT, FakeDocument("z.txt", utf8("z")))
        runCurrent()
        transfers.gate = CompletableDeferred()
        val first = queue.download(ROOT, "$ROOT/a", running)
        val waiting = queue.upload(ROOT, ROOT, FakeDocument("b.txt", utf8("new")))
        val third = queue.download(ROOT, "$ROOT/a", queued)
        runCurrent()
        assertEquals(TransferStatus.Running, queue.status(first))

        queue.cancelAll()
        runCurrent()

        assertEquals(TransferStatus.Done, queue.status(done))
        listOf(first, waiting, third).forEach { assertEquals(TransferStatus.Cancelled, queue.status(it)) }
        assertTrue(running.deleted)
        assertTrue(queued.deleted)
        assertContentEquals(utf8("old"), transfers.files["$ROOT/b.txt"])
        assertEquals(1, transfers.calls.count { it == "download" })
    }

    @Test
    fun `when the app's scope ends, running and queued jobs end cancelled without partial files`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a"] = utf8("0123456789")
        transfers.gate = CompletableDeferred()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val queue = DefaultTransferQueue(transfers, scope, inline())
        val running = FakeDocument("a")

        val first = queue.download(ROOT, "$ROOT/a", running)
        runCurrent()
        scope.cancel()
        runCurrent()

        assertEquals(TransferStatus.Cancelled, queue.status(first))
        assertTrue(running.deleted)

        val late = FakeDocument("late")
        val second = queue.download(ROOT, "$ROOT/a", late)
        runCurrent()

        assertEquals(TransferStatus.Cancelled, queue.status(second))
        assertTrue(late.deleted)
    }

    @Test
    fun `progress after a job ended changes nothing`() = runTest {
        val transfers = FakeTransfers()
        var progress: ((Long) -> Unit)? = null
        val capturing = object : FileTransfers by transfers {
            override suspend fun upload(
                directory: String,
                path: String,
                source: InputStream,
                overwrite: Boolean,
                onProgress: (Long) -> Unit,
            ) {
                progress = onProgress
                transfers.upload(directory, path, source, overwrite, onProgress)
            }
        }
        val queue = DefaultTransferQueue(capturing, backgroundScope, inline())

        val id = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("abcdef")))
        runCurrent()
        progress!!.invoke(2)

        assertEquals(TransferStatus.Done, queue.status(id))
        assertEquals(6, queue.job(id).bytesDone)
    }

    // Closing the local stream

    @Test
    fun `an upload the port finished stays done when the local stream fails to close`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a.txt"] = utf8("old")
        val queue = queue(transfers)
        val document = FakeDocument("a.txt", utf8("new content")).apply { closeFailure = IOException("close") }

        val id = queue.upload(ROOT, ROOT, document)
        runCurrent()
        queue.answerOverwrite(id, overwrite = true)
        runCurrent()

        assertEquals(TransferStatus.Done, queue.status(id))
        assertEquals(11, queue.job(id).bytesDone)
        assertContentEquals(utf8("new content"), transfers.files["$ROOT/a.txt"])
        assertEquals(0, document.openStreams)
    }

    @Test
    fun `a failed upload whose local stream also fails to close keeps the port's failure`() = runTest {
        val transfers = FakeTransfers()
        transfers.failMidway = FilesException(FilesFailure.Unreachable)
        val queue = queue(transfers)
        val document = FakeDocument("a.txt", utf8("12345678")).apply { closeFailure = IOException("close") }

        val id = queue.upload(ROOT, ROOT, document)
        runCurrent()

        assertEquals(TransferStatus.Failed(FilesFailure.Unreachable), queue.status(id))
        assertTrue(transfers.files.isEmpty())
    }

    @Test
    fun `a download whose local stream fails to close may be incomplete, so it fails and is removed`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a"] = utf8("0123456789")
        val queue = queue(transfers)
        val document = FakeDocument("a").apply { closeFailure = IOException("flush") }

        val id = queue.download(ROOT, "$ROOT/a", document)
        runCurrent()

        assertEquals(TransferStatus.Failed(FilesFailure.Unknown), queue.status(id))
        assertTrue(document.deleted)
        assertEquals(listOf("openOutput", "closeOutput", "delete"), document.events)
    }

    @Test
    fun `a failed download whose local stream also fails to close keeps the port's failure`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a"] = utf8("0123456789")
        transfers.failMidway = FilesException(FilesFailure.NotConnected)
        val queue = queue(transfers)
        val document = FakeDocument("a").apply { closeFailure = IOException("flush") }

        val id = queue.download(ROOT, "$ROOT/a", document)
        runCurrent()

        assertEquals(TransferStatus.Failed(FilesFailure.NotConnected), queue.status(id))
        assertTrue(document.deleted)
    }

    // Large backlogs and atomic cancel-all

    @Test
    fun `when the scope ends with a large backlog, every queued job is cancelled without deep recursion`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a"] = utf8("0123456789")
        transfers.gate = CompletableDeferred()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val queue = DefaultTransferQueue(transfers, scope, inline())
        val running = FakeDocument("a")
        queue.download(ROOT, "$ROOT/a", running)
        val backlog = List(10_000) { FakeDocument("d$it") }
        backlog.forEach { queue.download(ROOT, "$ROOT/a", it) }
        runCurrent()

        scope.cancel()
        runCurrent()

        assertTrue(queue.jobs.value.all { it.status == TransferStatus.Cancelled })
        assertTrue(running.deleted)
        assertTrue(backlog.all { it.deleted })
        assertEquals(1, transfers.calls.count { it == "download" })
    }

    @Test
    fun `jobs added after the scope ended are cancelled at once`() = runTest {
        val transfers = FakeTransfers()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val queue = DefaultTransferQueue(transfers, scope, inline())
        scope.cancel()

        val upload = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("a")))
        val document = FakeDocument("b")
        val download = queue.download(ROOT, "$ROOT/b", document)
        runCurrent()

        assertEquals(TransferStatus.Cancelled, queue.status(upload))
        assertEquals(TransferStatus.Cancelled, queue.status(download))
        assertTrue(document.deleted)
        assertTrue(transfers.calls.isEmpty())
    }

    @Test
    fun `cancelling all never lets an older queued retry start when a newer running job stops`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a.txt"] = utf8("old")
        // Unconfined: a cancelled job ends, and the next one would start and finish, right away.
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val queue = DefaultTransferQueue(transfers, scope, inline())

        val retry = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("new")))
        assertEquals(TransferStatus.AwaitingOverwrite, queue.status(retry))
        transfers.gate = CompletableDeferred()
        val newer = queue.upload(ROOT, ROOT, FakeDocument("b.txt", utf8("12345678")))
        assertEquals(TransferStatus.Running, queue.status(newer))
        queue.answerOverwrite(retry, overwrite = true)
        assertEquals(TransferStatus.Queued, queue.status(retry))
        transfers.gate = null

        queue.cancelAll()
        runCurrent()

        assertEquals(TransferStatus.Cancelled, queue.status(retry))
        assertEquals(TransferStatus.Cancelled, queue.status(newer))
        assertContentEquals(utf8("old"), transfers.files["$ROOT/a.txt"])
        assertFalse("$ROOT/b.txt" in transfers.files)
        assertEquals(listOf("$ROOT/a.txt" to false, "$ROOT/b.txt" to false), transfers.uploads)
    }

    @Test
    fun `when the scope ends with only jobs awaiting an overwrite answer, they end cancelled`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a.txt"] = utf8("old a")
        transfers.files["$ROOT/b.txt"] = utf8("old b")
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val queue = DefaultTransferQueue(transfers, scope, inline())

        val first = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("new")))
        val second = queue.upload(ROOT, ROOT, FakeDocument("b.txt", utf8("new")))
        runCurrent()
        assertEquals(TransferStatus.AwaitingOverwrite, queue.status(first))
        assertEquals(TransferStatus.AwaitingOverwrite, queue.status(second))

        scope.cancel()
        runCurrent()

        assertEquals(TransferStatus.Cancelled, queue.status(first))
        assertEquals(TransferStatus.Cancelled, queue.status(second))
        assertNull(queue.jobs.value.firstOrNull { !it.status.isFinished })
        assertContentEquals(utf8("old a"), transfers.files["$ROOT/a.txt"])
        assertContentEquals(utf8("old b"), transfers.files["$ROOT/b.txt"])
    }

    @Test
    fun `when the scope ends with only queued jobs that never began, they end cancelled and their files go`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a"] = utf8("a")
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        // Cleanup on its own dispatcher, as on Dispatchers.IO in the app: it runs when that does.
        val queue = DefaultTransferQueue(transfers, scope, StandardTestDispatcher(testScheduler))
        val documents = List(3) { FakeDocument("d$it") }

        documents.forEach { queue.download(ROOT, "$ROOT/a", it) }
        scope.cancel()
        runCurrent()

        assertTrue(queue.jobs.value.all { it.status == TransferStatus.Cancelled })
        assertTrue(documents.all { it.deleted })
        assertTrue(transfers.calls.isEmpty())
    }

    @Test
    fun `cancelling from the caller's thread hands the file removal to the cleanup dispatcher`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/a"] = utf8("a")
        transfers.gate = CompletableDeferred()
        val queue = DefaultTransferQueue(transfers, backgroundScope, StandardTestDispatcher(testScheduler))
        queue.upload(ROOT, ROOT, FakeDocument("x.txt", utf8("12345678")))
        val document = FakeDocument("a")
        val queued = queue.download(ROOT, "$ROOT/a", document)
        runCurrent()

        queue.cancel(queued)

        assertEquals(TransferStatus.Cancelled, queue.status(queued))
        assertTrue(document.events.isEmpty())

        runCurrent()

        assertTrue(document.deleted)
    }

    @Test
    fun `cancelling all through the interface cancels every job of another queue`() = runTest {
        val cancelled = mutableListOf<Long>()
        val jobs = listOf(
            TransferJob(TransferId(1), TransferDirection.Upload, ROOT, "$ROOT/a", "a", status = TransferStatus.Done),
            TransferJob(TransferId(2), TransferDirection.Upload, ROOT, "$ROOT/b", "b", status = TransferStatus.Running),
            TransferJob(TransferId(3), TransferDirection.Upload, ROOT, "$ROOT/c", "c"),
        )
        val other = object : TransferQueue {
            override val jobs: StateFlow<List<TransferJob>> = MutableStateFlow(jobs)

            override fun upload(directory: String, folder: String, document: LocalDocument) = TransferId(0)

            override fun download(directory: String, path: String, document: LocalDocument) = TransferId(0)

            override fun answerOverwrite(id: TransferId, overwrite: Boolean) = Unit

            override fun cancel(id: TransferId) {
                cancelled += id.value
            }

            override fun clearFinished() = Unit
        }

        other.cancelAll()

        assertEquals(listOf(3L, 2L), cancelled)
    }

    // Clear

    @Test
    fun `clearing removes finished jobs only`() = runTest {
        val transfers = FakeTransfers()
        transfers.files["$ROOT/exists.txt"] = utf8("old")
        val queue = queue(transfers)

        val done = queue.upload(ROOT, ROOT, FakeDocument("a.txt", utf8("a")))
        val failed = queue.upload(ROOT, ROOT, FakeDocument("..", utf8("a")))
        val waiting = queue.upload(ROOT, ROOT, FakeDocument("exists.txt", utf8("new")))
        runCurrent()
        transfers.gate = CompletableDeferred()
        val running = queue.upload(ROOT, ROOT, FakeDocument("r.txt", utf8("12345678")))
        val queued = queue.upload(ROOT, ROOT, FakeDocument("q.txt", utf8("q")))
        val cancelled = queue.upload(ROOT, ROOT, FakeDocument("c.txt", utf8("c")))
        runCurrent()
        queue.cancel(cancelled)

        queue.clearFinished()

        assertEquals(listOf(waiting, running, queued), queue.jobs.value.map { it.id })
        assertFalse(queue.jobs.value.any { it.id == done || it.id == failed })
    }
}
