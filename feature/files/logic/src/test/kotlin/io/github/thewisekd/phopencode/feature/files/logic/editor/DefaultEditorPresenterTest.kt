package io.github.thewisekd.phopencode.feature.files.logic.editor

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorBlock
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.LineEnding
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val ROOT = "/srv/çalışma alanı"
private const val FILE = "src/main kodu.kt"

/** One file on a pretend server, with the calls that reached it. */
private class FakeFileContent(bytes: ByteArray?, var modifiedSeconds: Long = 1_000) : FileContent {
    var bytes: ByteArray? = bytes

    val calls = mutableListOf<String>()

    val writes = mutableListOf<ByteArray>()

    var statFailure: FilesFailure? = null
    var readFailure: FilesFailure? = null
    var writeFailure: FilesFailure? = null

    /** Size reported by stat, when set; otherwise the real size. */
    var reportedSize: Long? = null

    /** Size the write's answer reports, when set: someone else wrote right after us. */
    var writeReportsSize: Long? = null

    /** Completes before a stat runs, when set: lets a test act while a save's check is on its way. */
    var statGate: CompletableDeferred<Unit>? = null

    /** Completes before a write runs, when set: lets a test act while a save is on its way. */
    var writeGate: CompletableDeferred<Unit>? = null

    private fun entry(): FileEntry {
        val content = bytes ?: throw FilesException(FilesFailure.NotFound)
        return FileEntry(
            path = "$ROOT/$FILE",
            type = FileEntryType.File,
            sizeBytes = reportedSize ?: content.size.toLong(),
            modifiedAt = Instant.fromEpochSeconds(modifiedSeconds),
        )
    }

    /** Someone else changes the file on the server. */
    fun changeOnServer(content: ByteArray) {
        bytes = content
        modifiedSeconds += 7
    }

    override suspend fun stat(directory: String, path: String): FileEntry {
        calls += "stat"
        assertEquals(ROOT, directory)
        assertEquals(FILE, path)
        statGate?.await()
        statFailure?.let { throw FilesException(it) }
        return entry()
    }

    override suspend fun read(directory: String, path: String, maxBytes: Long): ByteArray {
        calls += "read"
        assertEquals(ROOT, directory)
        assertEquals(FILE, path)
        readFailure?.let { throw FilesException(it) }
        val content = bytes ?: throw FilesException(FilesFailure.NotFound)
        if (content.size > maxBytes) throw FilesException(FilesFailure.TooLarge)
        return content.copyOf()
    }

    override suspend fun write(directory: String, path: String, content: ByteArray): FileEntry {
        calls += "write"
        assertEquals(ROOT, directory)
        assertEquals(FILE, path)
        writeGate?.await()
        writeFailure?.let { throw FilesException(it) }
        writes += content.copyOf()
        bytes = content.copyOf()
        modifiedSeconds += 3
        val written = entry()
        return writeReportsSize?.let { written.copy(sizeBytes = it) } ?: written
    }
}

private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultEditorPresenterTest {
    private fun TestScope.editor(files: FakeFileContent, maxBytes: Long = 1024): DefaultEditorPresenter =
        DefaultEditorPresenter(ROOT, FILE, files, backgroundScope, maxBytes)

    private fun TestScope.loaded(files: FakeFileContent, maxBytes: Long = 1024): DefaultEditorPresenter {
        val presenter = editor(files, maxBytes)
        runCurrent()
        return presenter
    }

    // Loading

    @Test
    fun `nothing runs before the scope does`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = editor(files)

        assertEquals(EditorState(path = FILE), presenter.state.value)
        assertTrue(files.calls.isEmpty())
    }

    @Test
    fun `loads the file as clean text`() = runTest {
        val files = FakeFileContent(utf8("fun main() {}\n"))
        val presenter = loaded(files)

        val state = presenter.state.value
        assertFalse(state.loading)
        assertEquals("fun main() {}\n", state.text)
        assertFalse(state.dirty)
        assertEquals(LineEnding.Lf, state.lineEnding)
        assertNull(state.blocked)
        assertNull(state.loadError)
        assertFalse(state.canSave)
    }

    @Test
    fun `CRLF files show with LF line endings`() = runTest {
        val presenter = loaded(FakeFileContent(utf8("a\r\nb\r\n")))

        assertEquals("a\nb\n", presenter.state.value.text)
        assertEquals(LineEnding.CrLf, presenter.state.value.lineEnding)
    }

    @Test
    fun `CR files show with LF line endings`() = runTest {
        val presenter = loaded(FakeFileContent(utf8("a\rb\r")))

        assertEquals("a\nb\n", presenter.state.value.text)
        assertEquals(LineEnding.Cr, presenter.state.value.lineEnding)
    }

    @Test
    fun `the first line ending names the file's`() = runTest {
        val presenter = loaded(FakeFileContent(utf8("a\r\nb\nc\rd")))

        assertEquals("a\nb\nc\nd", presenter.state.value.text)
        assertEquals(LineEnding.CrLf, presenter.state.value.lineEnding)
    }

    @Test
    fun `a file without line endings is LF`() = runTest {
        val presenter = loaded(FakeFileContent(utf8("one line")))

        assertEquals("one line", presenter.state.value.text)
        assertEquals(LineEnding.Lf, presenter.state.value.lineEnding)
    }

    @Test
    fun `an empty file loads`() = runTest {
        val presenter = loaded(FakeFileContent(ByteArray(0)))

        assertEquals("", presenter.state.value.text)
        assertFalse(presenter.state.value.loading)
        assertNull(presenter.state.value.blocked)
    }

    @Test
    fun `a file above the limit is blocked without being read`() = runTest {
        val files = FakeFileContent(ByteArray(2048) { 'a'.code.toByte() })
        val presenter = loaded(files)

        assertEquals(EditorBlock.TooLarge, presenter.state.value.blocked)
        assertFalse(presenter.state.value.loading)
        assertEquals("", presenter.state.value.text)
        assertEquals(listOf("stat"), files.calls)
        assertFalse(presenter.state.value.canSave)
    }

    @Test
    fun `a file that grew past the limit between stat and read is blocked`() = runTest {
        val files = FakeFileContent(utf8("small")).apply { reportedSize = 5; readFailure = FilesFailure.TooLarge }
        val presenter = loaded(files)

        assertEquals(EditorBlock.TooLarge, presenter.state.value.blocked)
        assertNull(presenter.state.value.loadError)
    }

    @Test
    fun `a binary file is blocked`() = runTest {
        val presenter = loaded(FakeFileContent(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00, 0x00)))

        assertEquals(EditorBlock.Binary, presenter.state.value.blocked)
        assertEquals("", presenter.state.value.text)
        assertFalse(presenter.state.value.canSave)
    }

    @Test
    fun `a file that is not valid UTF-8 is blocked`() = runTest {
        // "café" in Latin-1.
        val presenter = loaded(FakeFileContent(byteArrayOf(0x63, 0x61, 0x66, 0xe9.toByte(), 0x0a)))

        assertEquals(EditorBlock.Malformed, presenter.state.value.blocked)
        assertEquals("", presenter.state.value.text)
    }

    @Test
    fun `a blocked file takes no edits and saves nothing`() = runTest {
        val files = FakeFileContent(byteArrayOf(0x00, 0x01))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("text"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        assertEquals("", presenter.state.value.text)
        assertFalse(presenter.state.value.dirty)
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `a failed load shows its failure and retries`() = runTest {
        val files = FakeFileContent(utf8("x\n")).apply { readFailure = FilesFailure.Unreachable }
        val presenter = loaded(files)

        assertEquals(FilesFailure.Unreachable, presenter.state.value.loadError)
        assertFalse(presenter.state.value.loading)
        assertFalse(presenter.state.value.canSave)

        files.readFailure = null
        presenter.onIntent(EditorIntent.Retry)
        assertTrue(presenter.state.value.loading)
        runCurrent()

        assertNull(presenter.state.value.loadError)
        assertEquals("x\n", presenter.state.value.text)
    }

    @Test
    fun `retry does nothing without a load error`() = runTest {
        val files = FakeFileContent(utf8("x\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.Retry)
        runCurrent()

        assertEquals(listOf("stat", "read"), files.calls)
    }

    // Dirty state

    @Test
    fun `an edit makes the file dirty and undoing it makes it clean`() = runTest {
        val presenter = loaded(FakeFileContent(utf8("a\n")))

        presenter.onIntent(EditorIntent.TextChanged("ab\n"))
        assertTrue(presenter.state.value.dirty)
        assertTrue(presenter.state.value.canSave)
        assertEquals("ab\n", presenter.state.value.text)

        presenter.onIntent(EditorIntent.TextChanged("a\n"))
        assertFalse(presenter.state.value.dirty)
        assertFalse(presenter.state.value.canSave)
    }

    @Test
    fun `typed CR and CRLF line endings become LF`() = runTest {
        val presenter = loaded(FakeFileContent(utf8("a\n")))

        presenter.onIntent(EditorIntent.TextChanged("a\r\nb\rc"))

        assertEquals("a\nb\nc", presenter.state.value.text)
    }

    @Test
    fun `edits before the file loaded are ignored`() = runTest {
        val presenter = editor(FakeFileContent(utf8("a\n")))

        presenter.onIntent(EditorIntent.TextChanged("lost"))
        runCurrent()

        assertEquals("a\n", presenter.state.value.text)
        assertFalse(presenter.state.value.dirty)
    }

    // Saving

    @Test
    fun `save checks the server then writes atomically through the port`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("a\nb\n"))
        presenter.onIntent(EditorIntent.Save)
        assertTrue(presenter.state.value.saving)
        assertFalse(presenter.state.value.canSave)
        runCurrent()

        assertEquals(listOf("stat", "read", "stat", "write"), files.calls)
        assertContentEquals(utf8("a\nb\n"), files.writes.single())
        assertFalse(presenter.state.value.saving)
        assertFalse(presenter.state.value.dirty)
        assertNull(presenter.state.value.saveError)
    }

    @Test
    fun `save does nothing while clean`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        assertEquals(listOf("stat", "read"), files.calls)
    }

    @Test
    fun `a second save while one is on its way is ignored`() = runTest {
        val files = FakeFileContent(utf8("a\n")).apply { writeGate = CompletableDeferred() }
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("b\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.Save)
        files.writeGate!!.complete(Unit)
        runCurrent()

        assertEquals(1, files.writes.size)
    }

    @Test
    fun `edits made while saving stay dirty after the save`() = runTest {
        val files = FakeFileContent(utf8("a\n")).apply { writeGate = CompletableDeferred() }
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("b\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.TextChanged("bc\n"))
        files.writeGate!!.complete(Unit)
        runCurrent()

        assertContentEquals(utf8("b\n"), files.writes.single())
        assertEquals("bc\n", presenter.state.value.text)
        assertTrue(presenter.state.value.dirty)

        presenter.onIntent(EditorIntent.TextChanged("b\n"))
        assertFalse(presenter.state.value.dirty)
    }

    @Test
    fun `a failed write keeps the edits and can be saved again`() = runTest {
        val files = FakeFileContent(utf8("a\n")).apply { writeFailure = FilesFailure.PermissionDenied }
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("edited\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        val failed = presenter.state.value
        assertEquals(FilesFailure.PermissionDenied, failed.saveError)
        assertEquals("edited\n", failed.text)
        assertTrue(failed.dirty)
        assertFalse(failed.saving)
        assertTrue(failed.canSave)
        assertContentEquals(utf8("a\n"), files.bytes)

        presenter.onIntent(EditorIntent.DismissError)
        assertNull(presenter.state.value.saveError)
        assertEquals("edited\n", presenter.state.value.text)

        files.writeFailure = null
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        assertContentEquals(utf8("edited\n"), files.bytes)
        assertFalse(presenter.state.value.dirty)
    }

    @Test
    fun `a failed check before the write keeps the edits`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)
        files.statFailure = FilesFailure.Unreachable

        presenter.onIntent(EditorIntent.TextChanged("edited\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        assertEquals(FilesFailure.Unreachable, presenter.state.value.saveError)
        assertEquals("edited\n", presenter.state.value.text)
        assertTrue(presenter.state.value.dirty)
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `an unexpected error from a port keeps the edits`() = runTest {
        val files = object : FileContent by FakeFileContent(utf8("a\n")) {
            override suspend fun write(directory: String, path: String, content: ByteArray): FileEntry =
                throw IllegalStateException("boom")
        }
        val presenter = DefaultEditorPresenter(ROOT, FILE, files, backgroundScope)
        runCurrent()

        presenter.onIntent(EditorIntent.TextChanged("edited\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        assertEquals(FilesFailure.Unknown, presenter.state.value.saveError)
        assertEquals("edited\n", presenter.state.value.text)
        assertTrue(presenter.state.value.dirty)
    }

    @Test
    fun `saves in a row check against the last save, not the load`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("b\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.TextChanged("c\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        assertFalse(presenter.state.value.conflict)
        assertEquals(2, files.writes.size)
        assertContentEquals(utf8("c\n"), files.bytes)
    }

    // Conflicts

    @Test
    fun `a file changed on the server is not overwritten silently`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        files.changeOnServer(utf8("theirs\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        val state = presenter.state.value
        assertTrue(state.conflict)
        assertFalse(state.saving)
        assertTrue(state.dirty)
        assertEquals("mine\n", state.text)
        assertTrue(files.writes.isEmpty())
        assertContentEquals(utf8("theirs\n"), files.bytes)
    }

    @Test
    fun `a size change with the same mtime is a conflict too`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        files.bytes = utf8("theirs, same second\n")
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        assertTrue(presenter.state.value.conflict)
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `a file deleted on the server is a conflict`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        files.bytes = null
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        assertTrue(presenter.state.value.conflict)
        assertNull(presenter.state.value.saveError)
        assertEquals("mine\n", presenter.state.value.text)

        presenter.onIntent(EditorIntent.Overwrite)
        runCurrent()
        assertContentEquals(utf8("mine\n"), files.bytes)
    }

    @Test
    fun `overwrite writes over the server's version after the conflict`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        files.changeOnServer(utf8("theirs\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.Overwrite)
        assertTrue(presenter.state.value.saving)
        assertFalse(presenter.state.value.conflict)
        runCurrent()

        assertContentEquals(utf8("mine\n"), files.bytes)
        assertFalse(presenter.state.value.dirty)
        assertFalse(presenter.state.value.conflict)

        // The overwrite's own mtime is the new base: the next save goes through.
        presenter.onIntent(EditorIntent.TextChanged("mine again\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        assertFalse(presenter.state.value.conflict)
        assertContentEquals(utf8("mine again\n"), files.bytes)
    }

    @Test
    fun `overwrite does nothing without a conflict`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        files.changeOnServer(utf8("theirs\n"))
        presenter.onIntent(EditorIntent.Overwrite)
        runCurrent()

        assertTrue(files.writes.isEmpty())
        assertContentEquals(utf8("theirs\n"), files.bytes)
    }

    @Test
    fun `a failed overwrite keeps the edits`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        files.changeOnServer(utf8("theirs\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        files.writeFailure = FilesFailure.Unreachable
        presenter.onIntent(EditorIntent.Overwrite)
        runCurrent()

        assertEquals(FilesFailure.Unreachable, presenter.state.value.saveError)
        assertEquals("mine\n", presenter.state.value.text)
        assertTrue(presenter.state.value.dirty)
    }

    @Test
    fun `reload drops the edits and loads the server's version`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        files.changeOnServer(utf8("theirs\r\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.Reload)
        runCurrent()

        val state = presenter.state.value
        assertEquals("theirs\n", state.text)
        assertEquals(LineEnding.CrLf, state.lineEnding)
        assertFalse(state.dirty)
        assertFalse(state.conflict)
        assertFalse(state.loading)

        presenter.onIntent(EditorIntent.TextChanged("next\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        assertFalse(presenter.state.value.conflict)
        assertContentEquals(utf8("next\r\n"), files.bytes)
    }

    @Test
    fun `reload does nothing without a conflict`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        presenter.onIntent(EditorIntent.Reload)
        runCurrent()

        assertEquals("mine\n", presenter.state.value.text)
        assertEquals(listOf("stat", "read"), files.calls)
    }

    @Test
    fun `keeping on editing after a conflict checks again on the next save`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        files.changeOnServer(utf8("theirs\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.DismissConflict)

        assertFalse(presenter.state.value.conflict)
        assertEquals("mine\n", presenter.state.value.text)
        assertTrue(presenter.state.value.dirty)

        presenter.onIntent(EditorIntent.TextChanged("mine 2\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        assertTrue(presenter.state.value.conflict)
        assertTrue(files.writes.isEmpty())
    }

    // Line endings

    private fun TestScope.roundTrip(original: String, edit: (String) -> String): ByteArray {
        val files = FakeFileContent(utf8(original))
        val presenter = loaded(files)
        presenter.onIntent(EditorIntent.TextChanged(edit(presenter.state.value.text)))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        assertNull(presenter.state.value.saveError)
        assertFalse(presenter.state.value.conflict)
        return files.writes.single()
    }

    @Test
    fun `CRLF is put back on every line`() = runTest {
        val written = roundTrip("a\r\nb\r\n") { it + "c\n" }

        assertEquals("a\r\nb\r\nc\r\n", written.decodeToString())
    }

    @Test
    fun `CR is put back on every line`() = runTest {
        val written = roundTrip("a\rb\r") { "x\n$it" }

        assertEquals("x\ra\rb\r", written.decodeToString())
    }

    @Test
    fun `LF stays LF`() = runTest {
        val written = roundTrip("a\nb\n") { it.replace("b", "B") }

        assertEquals("a\nB\n", written.decodeToString())
    }

    @Test
    fun `an absent final newline stays absent`() = runTest {
        val written = roundTrip("a\r\nb") { it.replace("a", "A") }

        assertEquals("A\r\nb", written.decodeToString())
    }

    @Test
    fun `a final newline stays`() = runTest {
        val written = roundTrip("a\r\nb\r\n") { it.replace("a", "A") }

        assertEquals("A\r\nb\r\n", written.decodeToString())
    }

    @Test
    fun `a file without line endings gets LF on new lines`() = runTest {
        val written = roundTrip("a") { it + "\nb" }

        assertEquals("a\nb", written.decodeToString())
    }

    @Test
    fun `mixed line endings keep each untouched line's ending`() = runTest {
        val written = roundTrip("one\r\ntwo\nthree\rfour\r\nfive") { it.replace("three", "THREE") }

        assertEquals("one\r\ntwo\nTHREE\rfour\r\nfive", written.decodeToString())
    }

    @Test
    fun `mixed line endings survive inserted and deleted lines`() = runTest {
        val inserted = roundTrip("one\r\ntwo\nthree\r\n") { it.replace("two\n", "two\nnew\n") }
        assertEquals("one\r\ntwo\nnew\r\nthree\r\n", inserted.decodeToString())
    }

    @Test
    fun `mixed line endings survive a deleted line`() = runTest {
        val deleted = roundTrip("one\nmiddle\r\nthree\nfour\r\n") { it.replace("middle\n", "") }

        assertEquals("one\nthree\nfour\r\n", deleted.decodeToString())
    }

    @Test
    fun `separated edits with an insertion and a deletion keep every untouched line's ending`() = runTest {
        val written = roundTrip("l1\r\nl2\nl3\rl4\r\nl5\nl6\r\nl7\nl8\r") {
            "l1\nL2\nl3\nl4\nnew\nl5\nl7\nL8\n"
        }

        assertEquals("l1\r\nL2\nl3\rl4\r\nnew\r\nl5\nl7\nL8\r", written.decodeToString())
    }

    @Test
    fun `an untouched mixed file saves byte for byte`() = runTest {
        val original = "﻿é\r\n\r\nb\n\rc\r\r\nd"
        val files = FakeFileContent(utf8(original))
        val presenter = loaded(files)
        val text = presenter.state.value.text

        presenter.onIntent(EditorIntent.TextChanged(text + "x"))
        presenter.onIntent(EditorIntent.TextChanged(text.dropLast(1) + "D"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        assertEquals("﻿é\r\n\r\nb\n\rc\r\r\nD", files.writes.single().decodeToString())
    }

    @Test
    fun `endings follow the last save, not the load`() = runTest {
        val files = FakeFileContent(utf8("a\r\nb\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("x\na\nb\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.TextChanged("x\na\nB\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        assertEquals("x\r\na\r\nB\n", files.writes[1].decodeToString())
    }

    // A save on its way

    @Test
    fun `undoing back to the loaded text while a save is on its way does not count as clean`() = runTest {
        val files = FakeFileContent(utf8("a\n")).apply { writeGate = CompletableDeferred() }
        val presenter = loaded(files)
        val events = mutableListOf<EditorEvent>()
        backgroundScope.launch { presenter.events.toList(events) }

        presenter.onIntent(EditorIntent.TextChanged("b\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.TextChanged("a\n"))
        assertTrue(presenter.state.value.dirty)

        presenter.onIntent(EditorIntent.Back)
        runCurrent()
        assertTrue(presenter.state.value.confirmingLeave)
        assertTrue(events.isEmpty())

        presenter.onIntent(EditorIntent.CancelLeave)
        files.writeGate!!.complete(Unit)
        runCurrent()

        // The server now holds "b": the text shown differs from it.
        assertContentEquals(utf8("b\n"), files.bytes)
        assertTrue(presenter.state.value.dirty)
        assertFalse(presenter.state.value.saving)
    }

    @Test
    fun `back while a save is on its way asks first`() = runTest {
        val files = FakeFileContent(utf8("a\n")).apply { writeGate = CompletableDeferred() }
        val presenter = loaded(files)
        val events = mutableListOf<EditorEvent>()
        backgroundScope.launch { presenter.events.toList(events) }

        presenter.onIntent(EditorIntent.TextChanged("b\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.Back)
        runCurrent()

        assertTrue(presenter.state.value.confirmingLeave)
        assertTrue(events.isEmpty())

        files.writeGate!!.complete(Unit)
        runCurrent()
        assertFalse(presenter.state.value.dirty)
    }

    @Test
    fun `back after a save that matched the text closes at once`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)
        val events = mutableListOf<EditorEvent>()
        backgroundScope.launch { presenter.events.toList(events) }

        presenter.onIntent(EditorIntent.TextChanged("b\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.Back)
        runCurrent()

        assertEquals(listOf<EditorEvent>(EditorEvent.Close), events)
    }

    // After the write

    @Test
    fun `a write the server reports with another size shows a conflict and stays dirty`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        // Someone else's write lands between ours and the server's answer.
        files.writeReportsSize = 99
        presenter.onIntent(EditorIntent.Save)
        runCurrent()

        assertEquals(1, files.writes.size)

        assertTrue(presenter.state.value.conflict)
        assertTrue(presenter.state.value.dirty)
        assertEquals("mine\n", presenter.state.value.text)
    }

    @Test
    fun `after a suspicious write, undoing back to the sent text never makes the editor clean`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)
        val events = mutableListOf<EditorEvent>()
        backgroundScope.launch { presenter.events.toList(events) }

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        files.writeReportsSize = 99
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        files.writeReportsSize = null

        presenter.onIntent(EditorIntent.TextChanged("mine, more\n"))
        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        assertTrue(presenter.state.value.dirty)
        presenter.onIntent(EditorIntent.TextChanged("a\n"))
        assertTrue(presenter.state.value.dirty)

        presenter.onIntent(EditorIntent.DismissConflict)
        assertTrue(presenter.state.value.dirty)

        presenter.onIntent(EditorIntent.Back)
        runCurrent()
        assertTrue(presenter.state.value.confirmingLeave)
        assertTrue(events.isEmpty())
        presenter.onIntent(EditorIntent.CancelLeave)

        // A save checks again and stops at the conflict; only the overwrite verifies.
        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        assertTrue(presenter.state.value.conflict)
        assertTrue(presenter.state.value.dirty)
        assertEquals(1, files.writes.size)

        presenter.onIntent(EditorIntent.Overwrite)
        runCurrent()
        assertFalse(presenter.state.value.conflict)
        assertFalse(presenter.state.value.dirty)

        presenter.onIntent(EditorIntent.Back)
        runCurrent()
        assertEquals(listOf<EditorEvent>(EditorEvent.Close), events)
    }

    @Test
    fun `a reload after a suspicious write leaves a clean editor`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("mine\n"))
        files.writeReportsSize = 99
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        files.writeReportsSize = null
        presenter.onIntent(EditorIntent.Reload)
        runCurrent()

        assertEquals("mine\n", presenter.state.value.text)
        assertFalse(presenter.state.value.dirty)
        assertFalse(presenter.state.value.conflict)
    }

    @Test
    fun `a save whose check fails after the text was undone leaves the editor clean`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)
        files.statGate = CompletableDeferred()
        files.statFailure = FilesFailure.Unreachable

        presenter.onIntent(EditorIntent.TextChanged("b\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.TextChanged("a\n"))
        assertTrue(presenter.state.value.dirty)

        files.statGate!!.complete(Unit)
        runCurrent()

        assertEquals(FilesFailure.Unreachable, presenter.state.value.saveError)
        assertFalse(presenter.state.value.saving)
        assertFalse(presenter.state.value.dirty)
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `a failed write after the text was undone leaves the editor clean`() = runTest {
        val files = FakeFileContent(utf8("a\n")).apply {
            writeGate = CompletableDeferred()
            writeFailure = FilesFailure.PermissionDenied
        }
        val presenter = loaded(files)

        presenter.onIntent(EditorIntent.TextChanged("b\n"))
        presenter.onIntent(EditorIntent.Save)
        runCurrent()
        presenter.onIntent(EditorIntent.TextChanged("a\n"))
        files.writeGate!!.complete(Unit)
        runCurrent()

        assertEquals(FilesFailure.PermissionDenied, presenter.state.value.saveError)
        assertFalse(presenter.state.value.dirty)
        assertContentEquals(utf8("a\n"), files.bytes)
    }

    // Leaving

    @Test
    fun `back closes at once when clean`() = runTest {
        val presenter = loaded(FakeFileContent(utf8("a\n")))
        val events = mutableListOf<EditorEvent>()
        backgroundScope.launch { presenter.events.toList(events) }

        presenter.onIntent(EditorIntent.Back)
        runCurrent()

        assertEquals(listOf<EditorEvent>(EditorEvent.Close), events)
        assertFalse(presenter.state.value.confirmingLeave)
    }

    @Test
    fun `back asks first when dirty, and cancel keeps the edits`() = runTest {
        val presenter = loaded(FakeFileContent(utf8("a\n")))
        val events = mutableListOf<EditorEvent>()
        backgroundScope.launch { presenter.events.toList(events) }

        presenter.onIntent(EditorIntent.TextChanged("b\n"))
        presenter.onIntent(EditorIntent.Back)
        runCurrent()
        assertTrue(presenter.state.value.confirmingLeave)
        assertTrue(events.isEmpty())

        presenter.onIntent(EditorIntent.CancelLeave)
        runCurrent()
        assertFalse(presenter.state.value.confirmingLeave)
        assertEquals("b\n", presenter.state.value.text)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `confirming the leave closes without saving`() = runTest {
        val files = FakeFileContent(utf8("a\n"))
        val presenter = loaded(files)
        val events = mutableListOf<EditorEvent>()
        backgroundScope.launch { presenter.events.toList(events) }

        presenter.onIntent(EditorIntent.TextChanged("b\n"))
        presenter.onIntent(EditorIntent.Back)
        presenter.onIntent(EditorIntent.ConfirmLeave)
        runCurrent()

        assertEquals(listOf<EditorEvent>(EditorEvent.Close), events)
        assertFalse(presenter.state.value.confirmingLeave)
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `confirm leave without the question does nothing`() = runTest {
        val presenter = loaded(FakeFileContent(utf8("a\n")))
        val events = mutableListOf<EditorEvent>()
        backgroundScope.launch { presenter.events.toList(events) }

        presenter.onIntent(EditorIntent.ConfirmLeave)
        runCurrent()

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a close sent while nobody collects waits for the collector`() = runTest {
        val presenter = loaded(FakeFileContent(utf8("a\n")))

        presenter.onIntent(EditorIntent.Back)
        val events = mutableListOf<EditorEvent>()
        backgroundScope.launch { presenter.events.toList(events) }
        runCurrent()

        assertEquals(listOf<EditorEvent>(EditorEvent.Close), events)
    }

    @Test
    fun `the state never prints the path or the text`() = runTest {
        val presenter = loaded(FakeFileContent(utf8("secret text\n")))

        val printed = presenter.state.value.toString()
        assertFalse(printed.contains("secret"))
        assertFalse(printed.contains("kodu"))
    }
}
