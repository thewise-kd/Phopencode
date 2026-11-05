package io.github.thewisekd.phopencode.feature.files.logic.viewer

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.ImageFormat
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerMode
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
import kotlin.test.Test
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

private const val ROOT = "/srv/project"
private const val PATH = "src/Main.kt"
private const val WARN = 100L
private const val MAX = 1000L

private val T1 = Instant.fromEpochSeconds(1_700_000_000)
private val T2 = Instant.fromEpochSeconds(1_700_000_100)

private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)

/** One file the test changes by hand; every call is recorded, and a gate can hold a call. */
private class FakeFile : FileContent {
    var content: ByteArray = "fun main() {}\n".toByteArray()
    var modifiedAt: Instant? = T1
    var type: FileEntryType = FileEntryType.File

    /** Overrides the path `stat` reports; null reports the asked one. */
    var statPath: String? = null

    /** Overrides the size `stat` reports; null reports the content's, [unknownSize] none. */
    var statSize: Long? = null
    var unknownSize = false

    var statFailure: FilesFailure? = null
    var readFailure: FilesFailure? = null
    var readError: Throwable? = null

    /** When set, `read` waits for it before answering. */
    var readGate: CompletableDeferred<Unit>? = null

    val calls = mutableListOf<String>()

    override suspend fun stat(directory: String, path: String): FileEntry {
        calls += "stat $directory $path"
        statFailure?.let { throw FilesException(it) }
        return FileEntry(
            path = statPath ?: "$directory/$path",
            type = type,
            sizeBytes = if (unknownSize) null else statSize ?: content.size.toLong(),
            modifiedAt = modifiedAt,
        )
    }

    override suspend fun read(directory: String, path: String, maxBytes: Long): ByteArray {
        calls += "read $directory $path $maxBytes"
        readGate?.await()
        readError?.let { throw it }
        readFailure?.let { throw FilesException(it) }
        if (content.size > maxBytes) throw FilesException(FilesFailure.TooLarge)
        return content.copyOf()
    }

    override suspend fun write(directory: String, path: String, content: ByteArray): FileEntry =
        error("The viewer never writes")

    val reads: List<String> get() = calls.filter { it.startsWith("read") }
    val stats: List<String> get() = calls.filter { it.startsWith("stat") }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultViewerPresenterTest {
    private val file = FakeFile()

    private fun TestScope.presenter(path: String = PATH, line: Int? = null) = DefaultViewerPresenter(
        directory = ROOT,
        path = path,
        line = line,
        files = file,
        scope = backgroundScope,
        warnBytes = WARN,
        maxBytes = MAX,
    )

    private fun TestScope.events(presenter: DefaultViewerPresenter): List<ViewerEvent> {
        val events = mutableListOf<ViewerEvent>()
        backgroundScope.launch { presenter.events.toList(events) }
        runCurrent()
        return events
    }

    @Test
    fun `nothing is called before the scope runs`() = runTest {
        val presenter = presenter(line = 7)

        assertEquals(ViewerState(path = PATH, line = 7), presenter.state.value)
        assertTrue(file.calls.isEmpty())
    }

    @Test
    fun `a small text file is stat-ed, then read up to the warning limit`() = runTest {
        val presenter = presenter(line = 3)
        runCurrent()

        assertEquals(listOf("stat $ROOT $PATH", "read $ROOT $PATH $WARN"), file.calls)
        assertEquals(
            ViewerState(path = PATH, line = 3, loading = false, content = ViewerContent.Text("fun main() {}\n")),
            presenter.state.value,
        )
        assertTrue(presenter.state.value.canEdit)
    }

    @Test
    fun `markdown is flagged and its mode can change`() = runTest {
        file.content = "# Title\n".toByteArray()
        val presenter = presenter(path = "README.md")
        runCurrent()

        assertEquals(ViewerContent.Text("# Title\n", markdown = true), presenter.state.value.content)
        assertEquals(ViewerMode.Rendered, presenter.state.value.mode)

        presenter.onIntent(ViewerIntent.SetMode(ViewerMode.Raw))
        assertEquals(ViewerMode.Raw, presenter.state.value.mode)
        presenter.onIntent(ViewerIntent.SetMode(ViewerMode.Rendered))
        assertEquals(ViewerMode.Rendered, presenter.state.value.mode)
    }

    @Test
    fun `an image is previewed and cannot be edited`() = runTest {
        file.content = PNG
        val presenter = presenter(path = "logo.txt")
        runCurrent()

        assertEquals(ViewerContent.Image(PNG, ImageFormat.Png), presenter.state.value.content)
        assertFalse(presenter.state.value.canEdit)
    }

    @Test
    fun `a binary file shows its size`() = runTest {
        file.content = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0, 0, 0, 0)
        val presenter = presenter(path = "app.kt")
        runCurrent()

        assertEquals(ViewerContent.Binary(8), presenter.state.value.content)
        assertFalse(presenter.state.value.canEdit)
    }

    @Test
    fun `malformed text shows, but the editor is not offered`() = runTest {
        file.content = byteArrayOf(0x61, 0xFF.toByte(), 0x62)
        val presenter = presenter()
        val events = events(presenter)
        runCurrent()

        assertEquals(ViewerContent.Text("a�b", malformed = true), presenter.state.value.content)
        assertFalse(presenter.state.value.canEdit)
        presenter.onIntent(ViewerIntent.Edit)
        runCurrent()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `edit opens the editor for clean text`() = runTest {
        val presenter = presenter()
        presenter.onIntent(ViewerIntent.Edit)
        val events = events(presenter)
        assertTrue(presenter.state.value.canEdit)
        assertTrue(events.isEmpty(), "Not while loading: events wait for a collector, so none was sent")

        presenter.onIntent(ViewerIntent.Edit)
        runCurrent()
        assertEquals(listOf<ViewerEvent>(ViewerEvent.OpenEditor), events)
    }

    @Test
    fun `close sends close`() = runTest {
        val presenter = presenter()
        val events = events(presenter)
        presenter.onIntent(ViewerIntent.Close)
        runCurrent()
        assertEquals(listOf<ViewerEvent>(ViewerEvent.Close), events)
    }

    @Test
    fun `a file above the warning limit is not read until opened anyway`() = runTest {
        file.content = ByteArray(500) { 'a'.code.toByte() }
        val presenter = presenter()
        runCurrent()

        assertEquals(listOf("stat $ROOT $PATH"), file.calls)
        assertEquals(
            ViewerState(
                path = PATH,
                loading = false,
                content = ViewerContent.TooLarge(500, WARN, canOpenAnyway = true),
            ),
            presenter.state.value,
        )

        presenter.onIntent(ViewerIntent.OpenAnyway)
        assertTrue(presenter.state.value.loading)
        assertNull(presenter.state.value.content)
        runCurrent()

        assertEquals(listOf("read $ROOT $PATH $MAX"), file.reads)
        assertEquals(1, file.stats.size, "The size is known: no second stat")
        assertEquals(ViewerContent.Text("a".repeat(500)), presenter.state.value.content)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `a file at the warning limit is read`() = runTest {
        file.content = ByteArray(WARN.toInt()) { 'b'.code.toByte() }
        val presenter = presenter()
        runCurrent()

        assertEquals(ViewerContent.Text("b".repeat(WARN.toInt())), presenter.state.value.content)
    }

    @Test
    fun `a file above the hard limit is never read`() = runTest {
        file.statSize = 5000
        val presenter = presenter()
        runCurrent()

        val tooLarge = ViewerContent.TooLarge(5000, MAX, canOpenAnyway = false)
        assertEquals(tooLarge, presenter.state.value.content)

        presenter.onIntent(ViewerIntent.OpenAnyway)
        runCurrent()
        assertTrue(file.reads.isEmpty())
        assertEquals(tooLarge, presenter.state.value.content)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `open anyway does nothing unless the file is too large`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(ViewerIntent.OpenAnyway)
        runCurrent()

        assertEquals(1, file.reads.size)
    }

    @Test
    fun `a file that grew past the limit between stat and read shows as too large`() = runTest {
        file.statSize = 10
        file.content = ByteArray(300) { 'c'.code.toByte() }
        val presenter = presenter()
        runCurrent()

        val content = presenter.state.value.content as ViewerContent.TooLarge
        assertEquals(WARN, content.limitBytes)
        assertTrue(content.sizeBytes > WARN)
        assertTrue(content.canOpenAnyway)
        assertNull(presenter.state.value.error)

        presenter.onIntent(ViewerIntent.OpenAnyway)
        runCurrent()
        assertEquals(ViewerContent.Text("c".repeat(300)), presenter.state.value.content)
    }

    @Test
    fun `a file of unknown size is read up to the warning limit`() = runTest {
        file.unknownSize = true
        file.content = ByteArray(300) { 'd'.code.toByte() }
        val presenter = presenter()
        runCurrent()

        assertEquals(listOf("read $ROOT $PATH $WARN"), file.reads)
        val content = presenter.state.value.content as ViewerContent.TooLarge
        assertTrue(content.canOpenAnyway)

        presenter.onIntent(ViewerIntent.OpenAnyway)
        runCurrent()
        assertEquals(ViewerContent.Text("d".repeat(300)), presenter.state.value.content)
    }

    @Test
    fun `a file past the hard limit after open anyway cannot be opened again`() = runTest {
        file.statSize = 500
        file.content = ByteArray(2000)
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(ViewerIntent.OpenAnyway)
        runCurrent()

        val content = presenter.state.value.content as ViewerContent.TooLarge
        assertEquals(MAX, content.limitBytes)
        assertFalse(content.canOpenAnyway)
    }

    @Test
    fun `a failed stat shows the failure, and retry reads again`() = runTest {
        file.statFailure = FilesFailure.Unreachable
        val presenter = presenter()
        runCurrent()

        assertEquals(
            ViewerState(path = PATH, loading = false, error = FilesFailure.Unreachable),
            presenter.state.value,
        )
        assertTrue(file.reads.isEmpty())

        file.statFailure = null
        presenter.onIntent(ViewerIntent.Retry)
        assertTrue(presenter.state.value.loading)
        assertNull(presenter.state.value.error)
        runCurrent()

        assertEquals(ViewerContent.Text("fun main() {}\n"), presenter.state.value.content)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a failed read shows the failure`() = runTest {
        file.readFailure = FilesFailure.PermissionDenied
        val presenter = presenter()
        runCurrent()

        assertEquals(FilesFailure.PermissionDenied, presenter.state.value.error)
        assertNull(presenter.state.value.content)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `an unexpected error shows as unknown`() = runTest {
        file.readError = IllegalStateException("boom")
        val presenter = presenter()
        runCurrent()

        assertEquals(FilesFailure.Unknown, presenter.state.value.error)
    }

    @Test
    fun `a folder is not read`() = runTest {
        file.type = FileEntryType.Directory
        val presenter = presenter()
        runCurrent()

        assertTrue(file.reads.isEmpty())
        assertEquals(FilesFailure.Unsupported, presenter.state.value.error)
    }

    @Test
    fun `retry does nothing without an error`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(ViewerIntent.Retry)
        runCurrent()

        assertEquals(1, file.reads.size)
    }

    @Test
    fun `refresh reads nothing when the file is unchanged`() = runTest {
        val presenter = presenter()
        runCurrent()
        val before = presenter.state.value

        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(2, file.stats.size)
        assertEquals(1, file.reads.size)
        assertEquals(before, presenter.state.value)
    }

    @Test
    fun `refresh reads again when the modification time changed`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(ViewerIntent.SetMode(ViewerMode.Raw))

        file.content = "fun main() { println() }\n".toByteArray()
        file.modifiedAt = T2
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(2, file.reads.size)
        assertEquals(ViewerContent.Text("fun main() { println() }\n"), presenter.state.value.content)
        assertEquals(ViewerMode.Raw, presenter.state.value.mode)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `refresh reads again when the size changed`() = runTest {
        val presenter = presenter()
        runCurrent()

        file.content = "x".toByteArray()
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(ViewerContent.Text("x"), presenter.state.value.content)
    }

    private fun TestScope.refreshWithout(size: Boolean, modifiedAt: Boolean) {
        file.unknownSize = !size
        if (!modifiedAt) file.modifiedAt = null
        val presenter = presenter()
        runCurrent()
        assertEquals(ViewerContent.Text("fun main() {}\n"), presenter.state.value.content)

        // Same size and the same (unknown) stamp: nothing proves the content is unchanged.
        file.content = "fun main() {1}".toByteArray()
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(2, file.reads.size)
        assertEquals(ViewerContent.Text("fun main() {1}"), presenter.state.value.content)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `refresh reads again when the size is unknown`() = runTest {
        refreshWithout(size = false, modifiedAt = true)
    }

    @Test
    fun `refresh reads again when the modification time is unknown`() = runTest {
        refreshWithout(size = true, modifiedAt = false)
    }

    @Test
    fun `refresh reads again when neither size nor modification time is known`() = runTest {
        refreshWithout(size = false, modifiedAt = false)
    }

    @Test
    fun `refresh reads again when the new stat lost the modification time`() = runTest {
        val presenter = presenter()
        runCurrent()

        file.modifiedAt = null
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(2, file.reads.size)
    }

    @Test
    fun `refresh of an unprovable file keeps the content shown while it reads`() = runTest {
        file.modifiedAt = null
        val presenter = presenter()
        runCurrent()
        val shown = presenter.state.value

        file.readGate = CompletableDeferred()
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()
        assertEquals(shown, presenter.state.value)

        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()
        assertEquals(2, file.stats.size, "A refresh while one runs is ignored")

        file.readGate!!.complete(Unit)
        runCurrent()
        assertEquals(2, file.reads.size)
        assertEquals(shown, presenter.state.value)
    }

    @Test
    fun `refresh of an unprovable file opened anyway keeps it open`() = runTest {
        file.modifiedAt = null
        file.content = ByteArray(400) { 'h'.code.toByte() }
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(ViewerIntent.OpenAnyway)
        runCurrent()

        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(listOf("read $ROOT $PATH $MAX", "read $ROOT $PATH $MAX"), file.reads)
        assertEquals(ViewerContent.Text("h".repeat(400)), presenter.state.value.content)
    }

    @Test
    fun `refresh of a file that became a folder with the same size and time shows unsupported`() = runTest {
        val presenter = presenter()
        runCurrent()
        assertTrue(presenter.state.value.canEdit)

        file.type = FileEntryType.Directory
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(1, file.reads.size, "A folder is never read")
        assertEquals(
            ViewerState(path = PATH, loading = false, error = FilesFailure.Unsupported),
            presenter.state.value,
        )
        assertFalse(presenter.state.value.canEdit)
    }

    @Test
    fun `refresh reads again when the type changed with the same size and time`() = runTest {
        for (type in listOf(FileEntryType.Symlink, FileEntryType.Unknown("socket"))) {
            val local = FakeFile()
            val presenter = DefaultViewerPresenter(ROOT, PATH, null, local, backgroundScope, WARN, MAX)
            runCurrent()

            local.type = type
            local.content = "fun main() {1}".toByteArray()
            presenter.onIntent(ViewerIntent.Refresh)
            runCurrent()

            assertEquals(2, local.reads.size, "$type")
            assertEquals(ViewerContent.Text("fun main() {1}"), presenter.state.value.content, "$type")
        }
    }

    @Test
    fun `refresh reads again when one unknown type became another`() = runTest {
        file.type = FileEntryType.Unknown("fifo")
        val presenter = presenter()
        runCurrent()

        file.type = FileEntryType.Unknown("socket")
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(2, file.reads.size)
    }

    @Test
    fun `refresh reads again when the stat names another path`() = runTest {
        val presenter = presenter()
        runCurrent()

        file.statPath = "$ROOT/elsewhere.kt"
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(2, file.reads.size)
    }

    @Test
    fun `refresh keeps the content shown while it checks`() = runTest {
        val presenter = presenter()
        runCurrent()
        val shown = presenter.state.value

        file.modifiedAt = T2
        file.readGate = CompletableDeferred()
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()
        assertEquals(shown, presenter.state.value)

        file.readGate!!.complete(Unit)
        runCurrent()
        assertEquals(2, file.reads.size)
    }

    @Test
    fun `refresh of a file that grew past the limit shows the warning`() = runTest {
        val presenter = presenter()
        runCurrent()

        file.content = ByteArray(400) { 'e'.code.toByte() }
        file.modifiedAt = T2
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(ViewerContent.TooLarge(400, WARN, canOpenAnyway = true), presenter.state.value.content)
        assertEquals(1, file.reads.size)
    }

    @Test
    fun `refresh after open anyway keeps the file open`() = runTest {
        file.content = ByteArray(400) { 'f'.code.toByte() }
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(ViewerIntent.OpenAnyway)
        runCurrent()

        file.content = ByteArray(450) { 'g'.code.toByte() }
        file.modifiedAt = T2
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(ViewerContent.Text("g".repeat(450)), presenter.state.value.content)
        assertEquals(listOf("read $ROOT $PATH $MAX", "read $ROOT $PATH $MAX"), file.reads)
    }

    @Test
    fun `refresh of a deleted file shows not found`() = runTest {
        val presenter = presenter()
        runCurrent()

        file.statFailure = FilesFailure.NotFound
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(FilesFailure.NotFound, presenter.state.value.error)
        assertNull(presenter.state.value.content)
        assertFalse(presenter.state.value.canEdit)
    }

    @Test
    fun `refresh while loading changes nothing`() = runTest {
        file.readGate = CompletableDeferred()
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()
        assertEquals(1, file.stats.size)

        file.readGate!!.complete(Unit)
        runCurrent()
        assertEquals(ViewerContent.Text("fun main() {}\n"), presenter.state.value.content)
    }

    @Test
    fun `refresh after an error reads again, since nothing is loaded`() = runTest {
        file.statFailure = FilesFailure.Unreachable
        val presenter = presenter()
        runCurrent()

        file.statFailure = null
        presenter.onIntent(ViewerIntent.Refresh)
        runCurrent()

        assertEquals(ViewerContent.Text("fun main() {}\n"), presenter.state.value.content)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `printing the state shows neither path nor content`() = runTest {
        file.content = "secret-ish content".toByteArray()
        val presenter = presenter(path = "private/notes.txt")
        runCurrent()

        val printed = presenter.state.value.toString()
        assertFalse(printed.contains("private"))
        assertFalse(printed.contains("secret-ish"))
    }
}
