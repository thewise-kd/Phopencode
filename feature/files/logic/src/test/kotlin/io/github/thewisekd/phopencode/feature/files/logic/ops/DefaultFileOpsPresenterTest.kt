package io.github.thewisekd.phopencode.feature.files.logic.ops

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileAction
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOp
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpError
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsDialog
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsRequest
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.NameProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val ROOT = "/srv/çalışma alanı"
private const val FOLDER = "$ROOT/src"

private fun file(name: String = "main kodu.kt", folder: String = FOLDER) =
    FileEntry(path = "$folder/$name", type = FileEntryType.File)

private fun folder(name: String = "eski klasör", parent: String = FOLDER) =
    FileEntry(path = "$parent/$name", type = FileEntryType.Directory)

private fun symlink(name: String = "bağ", parent: String = FOLDER) =
    FileEntry(path = "$parent/$name", type = FileEntryType.Symlink)

/** A pretend server that records every change asked of it. */
private class FakeFileChanges : FileChanges {
    val calls = mutableListOf<String>()

    var failure: Throwable? = null

    /** Completes before a call answers, when set: lets a test act while an operation is on its way. */
    var gate: CompletableDeferred<Unit>? = null

    /** Whether the call waits for [gate] even when cancelled, and then answers normally. */
    var ignoresCancellation = false

    private suspend fun answer() {
        gate?.let { if (ignoresCancellation) withContext(NonCancellable) { it.await() } else it.await() }
        failure?.let { throw it }
    }

    override suspend fun delete(directory: String, path: String, recursive: Boolean) {
        assertEquals(ROOT, directory)
        calls += "delete $path recursive=$recursive"
        answer()
    }

    override suspend fun move(directory: String, from: String, to: String) {
        assertEquals(ROOT, directory)
        calls += "move $from -> $to"
        answer()
    }

    override suspend fun createFolder(directory: String, path: String) {
        assertEquals(ROOT, directory)
        calls += "mkdir $path"
        answer()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultFileOpsPresenterTest {
    private fun TestScope.ops(files: FakeFileChanges): DefaultFileOpsPresenter =
        DefaultFileOpsPresenter(ROOT, files, backgroundScope)

    private fun TestScope.collect(presenter: DefaultFileOpsPresenter): MutableList<FileOpsEvent> {
        val events = mutableListOf<FileOpsEvent>()
        backgroundScope.launch { presenter.events.toList(events) }
        return events
    }

    private fun DefaultFileOpsPresenter.menu(entry: FileEntry, folder: String = FOLDER) =
        onIntent(FileOpsIntent.Open(FileOpsRequest.Actions(entry, folder)))

    // Opening

    @Test
    fun `nothing runs before the scope does`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)
        runCurrent()

        assertEquals(FileOpsState(), presenter.state.value)
        assertTrue(files.calls.isEmpty())
    }

    @Test
    fun `a file's menu offers every action`() = runTest {
        val presenter = ops(FakeFileChanges())
        presenter.menu(file())

        val state = presenter.state.value
        assertEquals(FileOpsRequest.Actions(file(), FOLDER), state.menu)
        assertEquals(listOf(FileAction.Edit, FileAction.Download, FileAction.Rename, FileAction.Delete), state.menuActions)
        assertNull(state.dialog)
    }

    @Test
    fun `a folder's or a symlink's menu offers rename and delete`() = runTest {
        val presenter = ops(FakeFileChanges())
        presenter.menu(folder())
        assertEquals(listOf(FileAction.Rename, FileAction.Delete), presenter.state.value.menuActions)

        presenter.menu(symlink())
        assertEquals(listOf(FileAction.Rename, FileAction.Delete), presenter.state.value.menuActions)
    }

    @Test
    fun `opening replaces whatever was open`() = runTest {
        val presenter = ops(FakeFileChanges())
        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Rename))
        assertIs<FileOpsDialog.Rename>(presenter.state.value.dialog)

        presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.NewFolder(FOLDER)))
        assertEquals(FileOpsState(dialog = FileOpsDialog.NewFolder(FOLDER)), presenter.state.value)

        presenter.menu(folder())
        assertNull(presenter.state.value.dialog)
        assertEquals(folder(), presenter.state.value.menu?.entry)
    }

    @Test
    fun `cancel closes the menu and the dialog`() = runTest {
        val presenter = ops(FakeFileChanges())
        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Cancel)
        assertEquals(FileOpsState(), presenter.state.value)

        presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.NewFolder(FOLDER)))
        presenter.onIntent(FileOpsIntent.Cancel)
        assertEquals(FileOpsState(), presenter.state.value)
    }

    // Edit and download

    @Test
    fun `edit and download send their event and close the menu`() = runTest {
        val presenter = ops(FakeFileChanges())
        val events = collect(presenter)

        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Edit))
        assertEquals(FileOpsState(), presenter.state.value)

        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Download))
        assertEquals(FileOpsState(), presenter.state.value)
        runCurrent()

        assertEquals(
            listOf(FileOpsEvent.OpenEditor(file().path), FileOpsEvent.Download(file().path, FOLDER)),
            events,
        )
    }

    @Test
    fun `events wait for a collector`() = runTest {
        val presenter = ops(FakeFileChanges())
        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Edit))

        val events = collect(presenter)
        runCurrent()
        assertEquals(listOf<FileOpsEvent>(FileOpsEvent.OpenEditor(file().path)), events)
    }

    @Test
    fun `an action the menu does not offer is ignored`() = runTest {
        val presenter = ops(FakeFileChanges())
        val events = collect(presenter)

        presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
        assertEquals(FileOpsState(), presenter.state.value)

        presenter.menu(folder())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Edit))
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Download))
        runCurrent()

        assertTrue(events.isEmpty())
        assertEquals(folder(), presenter.state.value.menu?.entry)
    }

    // Rename

    @Test
    fun `rename starts with the entry's name and moves within its folder, trimmed`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)
        val events = collect(presenter)

        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Rename))
        val dialog = presenter.state.value.dialog
        assertEquals(FileOpsDialog.Rename(file(), "main kodu.kt"), dialog)
        assertNull(presenter.state.value.menu)
        assertTrue(presenter.state.value.menuActions.isEmpty())

        presenter.onIntent(FileOpsIntent.NameChanged("  yeni ad.kt "))
        assertTrue(presenter.state.value.canSubmit)
        presenter.onIntent(FileOpsIntent.Submit)
        assertEquals(FileOp.Rename, presenter.state.value.busy)
        assertTrue(files.calls.isEmpty())

        runCurrent()
        assertEquals(listOf("move $FOLDER/main kodu.kt -> $FOLDER/yeni ad.kt"), files.calls)
        assertEquals(FileOpsState(), presenter.state.value)
        assertEquals(listOf<FileOpsEvent>(FileOpsEvent.Changed(FOLDER)), events)
    }

    @Test
    fun `rename keeps a relative path relative`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)
        presenter.menu(FileEntry("src/a.kt", FileEntryType.File))
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Rename))
        presenter.onIntent(FileOpsIntent.NameChanged("b.kt"))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()

        presenter.menu(FileEntry("top.kt", FileEntryType.File))
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Rename))
        presenter.onIntent(FileOpsIntent.NameChanged("other.kt"))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()

        assertEquals(listOf("move src/a.kt -> src/b.kt", "move top.kt -> other.kt"), files.calls)
    }

    @Test
    fun `renaming a folder moves the folder`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)
        presenter.menu(folder())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Rename))
        presenter.onIntent(FileOpsIntent.NameChanged("yeni klasör"))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()

        assertEquals(listOf("move $FOLDER/eski klasör -> $FOLDER/yeni klasör"), files.calls)
    }

    @Test
    fun `renaming to the same name sends nothing and closes`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)
        val events = collect(presenter)
        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Rename))
        presenter.onIntent(FileOpsIntent.NameChanged(" main kodu.kt "))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()

        assertTrue(files.calls.isEmpty())
        assertTrue(events.isEmpty())
        assertEquals(FileOpsState(), presenter.state.value)
    }

    @Test
    fun `every invalid name shows its problem and is never sent`() = runTest {
        val cases = listOf(
            "" to NameProblem.Blank,
            "   " to NameProblem.Blank,
            "." to NameProblem.Reserved,
            ".." to NameProblem.Reserved,
            "a/b" to NameProblem.Separator,
            "../dışarı" to NameProblem.Separator,
            "a\u0000b" to NameProblem.ControlCharacter,
            "a\nb" to NameProblem.ControlCharacter,
            "a".repeat(256) to NameProblem.TooLong,
            "ş".repeat(128) to NameProblem.TooLong,
        )
        for ((name, problem) in cases) {
            val files = FakeFileChanges()
            val presenter = ops(files)
            val events = collect(presenter)

            presenter.menu(file())
            presenter.onIntent(FileOpsIntent.Choose(FileAction.Rename))
            presenter.onIntent(FileOpsIntent.NameChanged(name))
            assertEquals(problem, (presenter.state.value.dialog as FileOpsDialog.Rename).problem, "rename $problem")
            assertFalse(presenter.state.value.canSubmit)
            presenter.onIntent(FileOpsIntent.Submit)

            presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.NewFolder(FOLDER)))
            presenter.onIntent(FileOpsIntent.NameChanged(name))
            assertEquals(problem, (presenter.state.value.dialog as FileOpsDialog.NewFolder).problem, "mkdir $problem")
            assertFalse(presenter.state.value.canSubmit)
            presenter.onIntent(FileOpsIntent.Submit)
            runCurrent()

            assertTrue(files.calls.isEmpty(), "sent $problem")
            assertTrue(events.isEmpty())
            assertNull(presenter.state.value.busy)
        }
    }

    @Test
    fun `fixing the name clears its problem`() = runTest {
        val presenter = ops(FakeFileChanges())
        presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.NewFolder(FOLDER)))
        presenter.onIntent(FileOpsIntent.NameChanged("a/"))
        assertEquals(NameProblem.Separator, (presenter.state.value.dialog as FileOpsDialog.NewFolder).problem)

        presenter.onIntent(FileOpsIntent.NameChanged("a"))
        assertEquals(FileOpsDialog.NewFolder(FOLDER, "a"), presenter.state.value.dialog)
    }

    @Test
    fun `a name already taken shows as taken, not as an error`() = runTest {
        val files = FakeFileChanges().apply { failure = FilesException(FilesFailure.AlreadyExists) }
        val presenter = ops(files)
        val events = collect(presenter)

        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Rename))
        presenter.onIntent(FileOpsIntent.NameChanged("var.kt"))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()

        val state = presenter.state.value
        assertEquals(FileOpsDialog.Rename(file(), "var.kt", NameProblem.Taken), state.dialog)
        assertNull(state.busy)
        assertNull(state.error)
        assertFalse(state.canSubmit)
        assertTrue(events.isEmpty())

        presenter.onIntent(FileOpsIntent.NameChanged("var2.kt"))
        assertTrue(presenter.state.value.canSubmit)
    }

    @Test
    fun `a failed rename keeps the dialog and shows the error`() = runTest {
        val files = FakeFileChanges().apply { failure = FilesException(FilesFailure.PermissionDenied) }
        val presenter = ops(files)
        val events = collect(presenter)

        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Rename))
        presenter.onIntent(FileOpsIntent.NameChanged("b.kt"))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()

        val state = presenter.state.value
        assertEquals(FileOpsDialog.Rename(file(), "b.kt"), state.dialog)
        assertEquals(FileOpError(FileOp.Rename, FilesFailure.PermissionDenied), state.error)
        assertNull(state.busy)
        assertTrue(state.canSubmit)
        assertTrue(events.isEmpty())

        presenter.onIntent(FileOpsIntent.DismissError)
        assertNull(presenter.state.value.error)
        assertEquals(FileOpsDialog.Rename(file(), "b.kt"), presenter.state.value.dialog)
    }

    @Test
    fun `an unexpected failure is unknown`() = runTest {
        val files = FakeFileChanges().apply { failure = IllegalStateException("/secret/path") }
        val presenter = ops(files)
        presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.NewFolder(FOLDER)))
        presenter.onIntent(FileOpsIntent.NameChanged("x"))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()

        assertEquals(FileOpError(FileOp.CreateFolder, FilesFailure.Unknown), presenter.state.value.error)
    }

    @Test
    fun `a call cancelled under a live presenter ends as a failure that can be retried`() = runTest {
        val files = FakeFileChanges().apply { failure = CancellationException("connection call cancelled") }
        val presenter = ops(files)
        val events = collect(presenter)
        presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.NewFolder(FOLDER)))
        presenter.onIntent(FileOpsIntent.NameChanged("x"))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()

        val state = presenter.state.value
        assertEquals(listOf("mkdir $FOLDER/x"), files.calls)
        assertNull(state.busy)
        assertEquals(FileOpError(FileOp.CreateFolder, FilesFailure.Unknown), state.error)
        assertEquals(FileOpsDialog.NewFolder(FOLDER, "x"), state.dialog)
        assertTrue(state.canSubmit)
        assertTrue(events.isEmpty())

        // The dialog takes input again, and a retry reaches the server.
        presenter.onIntent(FileOpsIntent.NameChanged("y"))
        files.failure = null
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()
        assertEquals(listOf("mkdir $FOLDER/x", "mkdir $FOLDER/y"), files.calls)
        assertEquals(FileOpsState(), presenter.state.value)
        assertEquals(listOf<FileOpsEvent>(FileOpsEvent.Changed(FOLDER)), events)
    }

    @Test
    fun `a delete cancelled under a live presenter still refreshes the folder`() = runTest {
        val files = FakeFileChanges().apply { failure = CancellationException("connection call cancelled") }
        val presenter = ops(files)
        val events = collect(presenter)
        presenter.menu(folder())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        runCurrent()

        assertEquals(FileOpError(FileOp.Delete, FilesFailure.Unknown), presenter.state.value.error)
        assertNull(presenter.state.value.busy)
        assertEquals(listOf<FileOpsEvent>(FileOpsEvent.Changed(FOLDER)), events)
    }

    @Test
    fun `a result that arrives after the scope was cancelled is dropped`() = runTest {
        for (failure in listOf(null, FilesException(FilesFailure.PermissionDenied), IllegalStateException())) {
            for (deleteFolder in listOf(true, false)) {
                val gate = CompletableDeferred<Unit>()
                val files = FakeFileChanges().apply {
                    this.gate = gate
                    this.failure = failure
                    ignoresCancellation = true
                }
                val job = Job()
                val presenter = DefaultFileOpsPresenter(ROOT, files, backgroundScope + job)
                val events = collect(presenter)
                val states = mutableListOf<FileOpsState>()
                backgroundScope.launch { presenter.state.toList(states) }

                if (deleteFolder) {
                    presenter.menu(folder())
                    presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
                    presenter.onIntent(FileOpsIntent.ConfirmDelete)
                } else {
                    presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.NewFolder(FOLDER)))
                    presenter.onIntent(FileOpsIntent.NameChanged("x"))
                    presenter.onIntent(FileOpsIntent.Submit)
                }
                runCurrent()
                assertEquals(1, files.calls.size)
                val before = presenter.state.value
                val published = states.size

                job.cancel()
                runCurrent()
                gate.complete(Unit)
                runCurrent()

                val case = "failure=$failure delete=$deleteFolder"
                assertEquals(before, presenter.state.value, case)
                assertEquals(published, states.size, case)
                assertTrue(events.isEmpty(), case)
            }
        }
    }

    @Test
    fun `cancelling the presenter's scope publishes nothing more`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val files = FakeFileChanges().apply { this.gate = gate }
        val job = Job()
        val presenter = DefaultFileOpsPresenter(ROOT, files, backgroundScope + job)
        val events = collect(presenter)
        val states = mutableListOf<FileOpsState>()
        backgroundScope.launch { presenter.state.toList(states) }

        presenter.menu(folder())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        runCurrent()
        assertEquals(listOf("delete ${folder().path} recursive=true"), files.calls)
        val before = presenter.state.value
        val published = states.size

        job.cancel()
        gate.complete(Unit)
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(before, presenter.state.value)
        assertEquals(FileOp.Delete, presenter.state.value.busy)
        assertEquals(published, states.size)
        assertTrue(events.isEmpty())
    }

    // New folder

    @Test
    fun `a new folder is created in the folder shown`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)
        val events = collect(presenter)

        presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.NewFolder(ROOT)))
        assertFalse(presenter.state.value.canSubmit)
        presenter.onIntent(FileOpsIntent.NameChanged(" belgeler "))
        presenter.onIntent(FileOpsIntent.Submit)
        assertEquals(FileOp.CreateFolder, presenter.state.value.busy)
        runCurrent()

        assertEquals(listOf("mkdir $ROOT/belgeler"), files.calls)
        assertEquals(FileOpsState(), presenter.state.value)
        assertEquals(listOf<FileOpsEvent>(FileOpsEvent.Changed(ROOT)), events)
    }

    @Test
    fun `a new folder at the file system root has one slash`() = runTest {
        val files = FakeFileChanges()
        val presenter = DefaultFileOpsPresenter("/", object : FileChanges by files {
            override suspend fun createFolder(directory: String, path: String) {
                files.calls += "mkdir $path"
            }
        }, backgroundScope)
        presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.NewFolder("/")))
        presenter.onIntent(FileOpsIntent.NameChanged("tmp2"))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()

        assertEquals(listOf("mkdir /tmp2"), files.calls)
    }

    @Test
    fun `submit without a name dialog does nothing`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)
        presenter.onIntent(FileOpsIntent.Submit)
        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Submit)
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()

        assertTrue(files.calls.isEmpty())
    }

    // Delete

    @Test
    fun `deleting a file asks first, then deletes it alone`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)
        val events = collect(presenter)

        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
        runCurrent()
        assertEquals(FileOpsState(dialog = FileOpsDialog.ConfirmDelete(file())), presenter.state.value)
        assertTrue(files.calls.isEmpty())

        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        assertEquals(FileOp.Delete, presenter.state.value.busy)
        runCurrent()

        assertEquals(listOf("delete ${file().path} recursive=false"), files.calls)
        assertEquals(FileOpsState(), presenter.state.value)
        assertEquals(listOf<FileOpsEvent>(FileOpsEvent.Changed(FOLDER)), events)
    }

    @Test
    fun `deleting a folder needs confirmation before the recursive delete`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)

        presenter.menu(folder())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
        runCurrent()
        assertEquals(FileOpsDialog.ConfirmDelete(folder()), presenter.state.value.dialog)
        assertTrue(files.calls.isEmpty())

        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        runCurrent()
        assertEquals(listOf("delete ${folder().path} recursive=true"), files.calls)
    }

    @Test
    fun `a cancelled folder delete never reaches the server`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)

        presenter.menu(folder())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
        presenter.onIntent(FileOpsIntent.Cancel)
        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        runCurrent()

        assertTrue(files.calls.isEmpty())
        assertEquals(FileOpsState(), presenter.state.value)
    }

    @Test
    fun `confirm without its dialog deletes nothing`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)

        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        presenter.menu(folder())
        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Rename))
        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        runCurrent()

        assertTrue(files.calls.isEmpty())
    }

    @Test
    fun `a symlink is deleted alone, never its target's content`() = runTest {
        val files = FakeFileChanges()
        val presenter = ops(files)
        presenter.menu(symlink())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        runCurrent()

        assertEquals(listOf("delete ${symlink().path} recursive=false"), files.calls)
    }

    @Test
    fun `a failed delete shows the error and still refreshes the folder`() = runTest {
        val files = FakeFileChanges().apply { failure = FilesException(FilesFailure.PermissionDenied) }
        val presenter = ops(files)
        val events = collect(presenter)

        presenter.menu(folder())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        runCurrent()

        val state = presenter.state.value
        assertEquals(FileOpError(FileOp.Delete, FilesFailure.PermissionDenied), state.error)
        assertEquals(FileOpsDialog.ConfirmDelete(folder()), state.dialog)
        assertNull(state.busy)
        // Part of the content may be gone already.
        assertEquals(listOf<FileOpsEvent>(FileOpsEvent.Changed(FOLDER)), events)
    }

    // While busy

    @Test
    fun `the dialog takes no input while its operation runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val files = FakeFileChanges().apply { this.gate = gate }
        val presenter = ops(files)

        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Rename))
        presenter.onIntent(FileOpsIntent.NameChanged("b.kt"))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()

        presenter.onIntent(FileOpsIntent.NameChanged("c.kt"))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()
        val busy = presenter.state.value
        assertEquals(FileOp.Rename, busy.busy)
        assertEquals(FileOpsDialog.Rename(file(), "b.kt"), busy.dialog)
        assertFalse(busy.canSubmit)
        assertEquals(1, files.calls.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(FileOpsState(), presenter.state.value)
        assertEquals(listOf("move ${file().path} -> $FOLDER/b.kt"), files.calls)
    }

    @Test
    fun `a second confirm while deleting is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val files = FakeFileChanges().apply { this.gate = gate }
        val presenter = ops(files)

        presenter.menu(folder())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        runCurrent()
        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, files.calls.size)
    }

    @Test
    fun `cancel does not stop the operation, and its end leaves a newer dialog alone`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val files = FakeFileChanges().apply { this.gate = gate }
        val presenter = ops(files)
        val events = collect(presenter)

        presenter.menu(file())
        presenter.onIntent(FileOpsIntent.Choose(FileAction.Delete))
        presenter.onIntent(FileOpsIntent.ConfirmDelete)
        runCurrent()
        presenter.onIntent(FileOpsIntent.Cancel)
        assertEquals(FileOpsState(busy = FileOp.Delete), presenter.state.value)

        presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.NewFolder(FOLDER)))
        presenter.onIntent(FileOpsIntent.NameChanged("yeni"))
        assertFalse(presenter.state.value.canSubmit)

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("delete ${file().path} recursive=false"), files.calls)
        assertEquals(FileOpsState(dialog = FileOpsDialog.NewFolder(FOLDER, "yeni")), presenter.state.value)
        assertTrue(presenter.state.value.canSubmit)
        assertEquals(listOf<FileOpsEvent>(FileOpsEvent.Changed(FOLDER)), events)
    }

    @Test
    fun `a new operation clears the last error`() = runTest {
        val files = FakeFileChanges().apply { failure = FilesException(FilesFailure.Unreachable) }
        val presenter = ops(files)
        presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.NewFolder(FOLDER)))
        presenter.onIntent(FileOpsIntent.NameChanged("x"))
        presenter.onIntent(FileOpsIntent.Submit)
        runCurrent()
        assertEquals(FileOpError(FileOp.CreateFolder, FilesFailure.Unreachable), presenter.state.value.error)

        files.failure = null
        presenter.onIntent(FileOpsIntent.Submit)
        assertNull(presenter.state.value.error)
        runCurrent()
        assertEquals(FileOpsState(), presenter.state.value)
    }
}
