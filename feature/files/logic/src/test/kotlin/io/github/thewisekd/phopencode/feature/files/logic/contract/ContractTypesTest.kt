package io.github.thewisekd.phopencode.feature.files.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractTypesTest {
    // Assembled at runtime, so that the source holds no secret-shaped literal.
    private val secretPath = listOf("/srv", "gizli", "token-42.env").joinToString("/")
    private val secretText = listOf("password", "=", "hunter", "2").joinToString("")
    private val root = "/srv/çalışma alanı"

    private fun assertMasked(value: Any, vararg hidden: String) {
        val printed = value.toString()
        hidden.forEach { assertFalse(it in printed, "$printed shows a hidden value") }
    }

    private fun file(path: String = "$root/main.kt") = FileEntry(path = path, type = FileEntryType.File, sizeBytes = 10)

    private fun folder(path: String = "$root/src") = FileEntry(path = path, type = FileEntryType.Directory)

    private class Document(override val name: String, private val bytes: ByteArray = ByteArray(0)) : LocalDocument {
        override val sizeBytes: Long = bytes.size.toLong()

        override fun openInput(): InputStream = ByteArrayInputStream(bytes)

        override fun openOutput(): OutputStream = ByteArrayOutputStream()

        override fun delete(): Boolean = true
    }

    @Test
    fun `the tree starts loading at the project's root`() {
        with(TreeState(directory = root)) {
            assertEquals(root, directory)
            assertEquals(root, folder)
            assertTrue(loading)
            assertFalse(refreshing)
            assertTrue(entries.isEmpty())
            assertFalse(showHidden)
            assertEquals(TreeSort.Name, sort)
            assertNull(error)
            assertTrue(isAtRoot)
            assertFalse(isEmpty)
        }
        assertFalse(TreeState(directory = root, folder = "$root/src").isAtRoot)
    }

    @Test
    fun `the tree is empty only once loaded without entries or error`() {
        assertTrue(TreeState(directory = root, loading = false).isEmpty)
        assertFalse(TreeState(directory = root, loading = false, entries = listOf(TreeEntry(file()))).isEmpty)
        assertFalse(TreeState(directory = root, loading = false, error = FilesFailure.NotFound).isEmpty)
    }

    @Test
    fun `a tree entry tells folders and hidden names apart`() {
        assertTrue(TreeEntry(folder()).isFolder)
        assertFalse(TreeEntry(file()).isFolder)
        assertTrue(TreeEntry(file("$root/.env")).isHidden)
        assertFalse(TreeEntry(file()).isHidden)
        assertEquals(GitBadge.Modified, TreeEntry(file(), GitBadge.Modified).badge)
        assertNull(TreeEntry(file()).badge)
    }

    @Test
    fun `badges and sorts are fixed`() {
        assertEquals(
            listOf(GitBadge.Added, GitBadge.Modified, GitBadge.Deleted, GitBadge.Renamed, GitBadge.Untracked, GitBadge.ContainsChanges),
            GitBadge.entries,
        )
        assertEquals(listOf(TreeSort.Name, TreeSort.Modified, TreeSort.Size), TreeSort.entries)
    }

    @Test
    fun `file operations start closed`() {
        with(FileOpsState()) {
            assertNull(menu)
            assertTrue(menuActions.isEmpty())
            assertNull(dialog)
            assertNull(busy)
            assertNull(error)
            assertFalse(canSubmit)
        }
    }

    @Test
    fun `a name is submitted only when it has no problem and nothing runs`() {
        val rename = FileOpsDialog.Rename(entry = file(), name = "new.kt")
        assertTrue(FileOpsState(dialog = rename).canSubmit)
        assertFalse(FileOpsState(dialog = rename.copy(name = "  ")).canSubmit)
        assertFalse(FileOpsState(dialog = rename.copy(problem = NameProblem.Separator)).canSubmit)
        assertFalse(FileOpsState(dialog = rename, busy = FileOp.Rename).canSubmit)
        assertTrue(FileOpsState(dialog = FileOpsDialog.NewFolder(folder = root, name = "docs")).canSubmit)
        assertTrue(FileOpsState(dialog = FileOpsDialog.ConfirmDelete(folder())).canSubmit)
        assertFalse(FileOpsState(dialog = FileOpsDialog.ConfirmDelete(folder()), busy = FileOp.Delete).canSubmit)
    }

    @Test
    fun `name problems, actions and operations are fixed`() {
        assertEquals(
            listOf(NameProblem.Blank, NameProblem.Separator, NameProblem.Reserved, NameProblem.TooLong, NameProblem.ControlCharacter, NameProblem.Taken),
            NameProblem.entries,
        )
        assertEquals(listOf(FileAction.Edit, FileAction.Download, FileAction.Rename, FileAction.Delete), FileAction.entries)
        assertEquals(listOf(FileOp.Rename, FileOp.Delete, FileOp.CreateFolder), FileOp.entries)
        val failed = FileOpError(FileOp.Delete, FilesFailure.NotEmpty)
        assertEquals(FileOp.Delete, failed.op)
        assertEquals(FilesFailure.NotEmpty, failed.reason)
    }

    @Test
    fun `an operation request names the folder it was made in`() {
        assertEquals(root, FileOpsRequest.Actions(file(), folder = root).folder)
        assertEquals(root, FileOpsRequest.NewFolder(folder = root).folder)
    }

    @Test
    fun `the viewer starts loading the file at its line`() {
        with(ViewerState(path = "src/main.kt", line = 42)) {
            assertEquals("src/main.kt", path)
            assertEquals(42, line)
            assertTrue(loading)
            assertNull(content)
            assertEquals(ViewerMode.Rendered, mode)
            assertNull(error)
            assertFalse(canEdit)
        }
        assertNull(ViewerState(path = "a").line)
    }

    @Test
    fun `only loaded text can be edited`() {
        val text = ViewerContent.Text("fun main() {}")
        assertTrue(ViewerState(path = "a", loading = false, content = text).canEdit)
        assertTrue(ViewerState(path = "a", loading = false, content = text.copy(markdown = true)).canEdit)
        assertFalse(ViewerState(path = "a", loading = false, content = text.copy(malformed = true)).canEdit)
        assertFalse(ViewerState(path = "a", loading = false, content = ViewerContent.Binary(10)).canEdit)
        assertFalse(ViewerState(path = "a", loading = false, content = ViewerContent.TooLarge(10, 5, canOpenAnyway = true)).canEdit)
        assertFalse(ViewerState(path = "a", loading = false, content = ViewerContent.Image(byteArrayOf(1), ImageFormat.Png)).canEdit)
    }

    @Test
    fun `images compare by their bytes`() {
        val image = ViewerContent.Image(byteArrayOf(1, 2, 3), ImageFormat.Png)
        assertEquals(image, ViewerContent.Image(byteArrayOf(1, 2, 3), ImageFormat.Png))
        assertEquals(image.hashCode(), ViewerContent.Image(byteArrayOf(1, 2, 3), ImageFormat.Png).hashCode())
        assertNotEquals(image, ViewerContent.Image(byteArrayOf(1, 2, 4), ImageFormat.Png))
        assertNotEquals(image, ViewerContent.Image(byteArrayOf(1, 2, 3), ImageFormat.Jpeg))
        assertEquals(listOf(ImageFormat.Png, ImageFormat.Jpeg, ImageFormat.Gif, ImageFormat.Webp, ImageFormat.Bmp), ImageFormat.entries)
    }

    @Test
    fun `the editor starts loading, clean`() {
        with(EditorState(path = "src/main.kt")) {
            assertTrue(loading)
            assertEquals("", text)
            assertFalse(dirty)
            assertEquals(LineEnding.Lf, lineEnding)
            assertFalse(saving)
            assertFalse(conflict)
            assertFalse(confirmingLeave)
            assertNull(blocked)
            assertNull(loadError)
            assertNull(saveError)
            assertFalse(canSave)
        }
        assertEquals(listOf(LineEnding.Lf, LineEnding.CrLf, LineEnding.Cr), LineEnding.entries)
        assertEquals(listOf(EditorBlock.Binary, EditorBlock.TooLarge, EditorBlock.Malformed), EditorBlock.entries)
    }

    @Test
    fun `the editor saves only loaded, editable, changed text`() {
        val loaded = EditorState(path = "a", loading = false, text = "x", dirty = true)
        assertTrue(loaded.canSave)
        assertFalse(loaded.copy(dirty = false).canSave)
        assertFalse(loaded.copy(saving = true).canSave)
        assertFalse(loaded.copy(blocked = EditorBlock.Binary).canSave)
        assertFalse(loaded.copy(loadError = FilesFailure.NotFound).canSave)
        // A failed save may be tried again.
        assertTrue(loaded.copy(saveError = FilesFailure.Unreachable).canSave)
    }

    @Test
    fun `transfers start with an empty queue in the folder`() {
        with(TransferState(directory = root, folder = "$root/docs")) {
            assertEquals("$root/docs", folder)
            assertTrue(jobs.isEmpty())
            assertNull(pendingDownload)
            assertNull(overwritePrompt)
            assertFalse(hasFinished)
        }
    }

    @Test
    fun `a transfer asks about overwriting one file at a time`() {
        val waiting = TransferJob(TransferId(1), TransferDirection.Upload, root, "$root/a.txt", "a.txt", status = TransferStatus.AwaitingOverwrite)
        val done = waiting.copy(id = TransferId(2), status = TransferStatus.Done)
        val state = TransferState(directory = root, folder = root, jobs = listOf(done, waiting, waiting.copy(id = TransferId(3))))

        assertEquals(TransferId(1), state.overwritePrompt?.id)
        assertTrue(state.hasFinished)
    }

    @Test
    fun `a transfer reports its progress when the size is known`() {
        val job = TransferJob(TransferId(1), TransferDirection.Download, root, "$root/a", "a", bytesDone = 25, totalBytes = 100)
        assertEquals(0.25f, job.fraction)
        assertNull(job.copy(totalBytes = null).fraction)
        assertEquals(0f, job.copy(bytesDone = 0, totalBytes = 0).fraction)
        assertEquals(1f, job.copy(bytesDone = 200).fraction)
    }

    @Test
    fun `a finished transfer is complete, even of an empty file`() {
        val job = TransferJob(TransferId(1), TransferDirection.Upload, root, "$root/a", "a", status = TransferStatus.Done)
        assertEquals(1f, job.copy(bytesDone = 0, totalBytes = 0).fraction)
        assertEquals(1f, job.copy(bytesDone = 0, totalBytes = null).fraction)
        assertEquals(1f, job.copy(bytesDone = 10, totalBytes = 100).fraction)
    }

    @Test
    fun `finished transfers are told apart`() {
        assertFalse(TransferStatus.Queued.isFinished)
        assertFalse(TransferStatus.Running.isFinished)
        assertFalse(TransferStatus.AwaitingOverwrite.isFinished)
        assertTrue(TransferStatus.Done.isFinished)
        assertTrue(TransferStatus.Cancelled.isFinished)
        assertTrue(TransferStatus.Failed(FilesFailure.Unreachable).isFinished)
    }

    @Test
    fun `search starts empty, by name`() {
        with(SearchState(directory = root)) {
            assertEquals("", query)
            assertEquals(SearchMode.Names, mode)
            assertFalse(searching)
            assertFalse(searched)
            assertTrue(results.isEmpty())
            assertFalse(truncated)
            assertNull(tool)
            assertNull(error)
            assertFalse(canSearch)
            assertFalse(isEmpty)
        }
        assertTrue(SearchState(directory = root, query = "main").canSearch)
        assertFalse(SearchState(directory = root, query = "  ").canSearch)
        assertFalse(SearchState(directory = root, query = "main", searching = true).canSearch)
        assertTrue(SearchState(directory = root, query = "x", searched = true).isEmpty)
        assertFalse(SearchState(directory = root, query = "x", searched = true, error = FilesFailure.ToolMissing).isEmpty)
        assertEquals(listOf(ContentSearchTool.Ripgrep, ContentSearchTool.Grep), ContentSearchTool.entries)
    }

    @Test
    fun `paths and file contents do not print`() {
        val entry = file(secretPath)
        assertMasked(TreeState(directory = secretPath, folder = secretPath, entries = listOf(TreeEntry(entry))), secretPath, "token")
        assertMasked(TreeEntry(entry), secretPath, "token")
        assertMasked(TreeIntent.Open(TreeEntry(entry)), secretPath, "token")
        assertMasked(TreeIntent.OpenFolder(secretPath), secretPath, "token")
        assertMasked(TreeIntent.ShowActions(TreeEntry(entry)), secretPath, "token")
        assertMasked(TreeEvent.OpenFile(secretPath), secretPath, "token")
        assertMasked(TreeEvent.OpenTransfer(secretPath), secretPath, "token")
        assertMasked(TreeEvent.OpenActions(FileOpsRequest.Actions(entry, secretPath)), secretPath, "token")
        assertMasked(FileOpsRequest.NewFolder(secretPath), secretPath, "token")
        assertMasked(
            FileOpsState(menu = FileOpsRequest.Actions(entry, secretPath), dialog = FileOpsDialog.Rename(entry, secretText)),
            secretPath,
            secretText,
            "token",
        )
        assertMasked(FileOpsDialog.NewFolder(secretPath, secretText), secretPath, secretText)
        assertMasked(FileOpsDialog.ConfirmDelete(entry), secretPath, "token")
        assertMasked(FileOpsIntent.NameChanged(secretText), secretText)
        assertMasked(FileOpsEvent.Changed(secretPath), secretPath, "token")
        assertMasked(FileOpsEvent.Download(secretPath, secretPath), secretPath, "token")
        assertMasked(FileOpsEvent.OpenEditor(secretPath), secretPath, "token")
        assertMasked(
            ViewerState(path = secretPath, content = ViewerContent.Text(secretText)),
            secretPath,
            secretText,
            "token",
        )
        assertMasked(ViewerContent.Image(secretText.toByteArray(), ImageFormat.Png), secretText)
        assertMasked(EditorState(path = secretPath, text = secretText), secretPath, secretText, "token")
        assertMasked(EditorIntent.TextChanged(secretText), secretText)
        val job = TransferJob(TransferId(1), TransferDirection.Upload, secretPath, secretPath, secretText)
        assertMasked(job, secretPath, secretText, "token")
        assertMasked(
            TransferState(directory = secretPath, folder = secretPath, jobs = listOf(job), pendingDownload = secretPath),
            secretPath,
            secretText,
            "token",
        )
        assertMasked(TransferIntent.Upload(listOf(Document(secretText))), secretText)
        assertMasked(TransferIntent.RequestDownload(secretPath), secretPath, "token")
        assertMasked(TransferIntent.DownloadTo(Document(secretText)), secretText)
        val hit = ContentHit(secretPath, 3, secretText)
        assertMasked(hit, secretPath, secretText, "token")
        assertMasked(SearchResult.Name(entry), secretPath, "token")
        assertMasked(SearchResult.Content(hit), secretPath, secretText, "token")
        assertMasked(
            SearchState(directory = secretPath, query = secretText, results = listOf(SearchResult.Content(hit))),
            secretPath,
            secretText,
            "token",
        )
        assertMasked(SearchIntent.QueryChanged(secretText), secretText)
        assertMasked(SearchIntent.Open(SearchResult.Content(hit)), secretPath, secretText, "token")
        assertMasked(SearchEvent.OpenFile(secretPath, 3), secretPath, "token")
        assertMasked(SearchEvent.OpenFolder(secretPath), secretPath, "token")
        assertMasked(FilesException(FilesFailure.NotFound), secretPath, "token")
    }

    @Test
    fun `masked values still compare by content`() {
        val entry = file(secretPath)
        assertEquals(TreeIntent.OpenFolder(secretPath), TreeIntent.OpenFolder(secretPath))
        assertEquals(TreeEntry(entry), TreeEntry(file(secretPath)))
        assertEquals(EditorIntent.TextChanged(secretText), EditorIntent.TextChanged(secretText))
        assertEquals(SearchIntent.QueryChanged(secretText), SearchIntent.QueryChanged(secretText))
        assertEquals(ContentHit(secretPath, 3, secretText), ContentHit(secretPath, 3, secretText))
        assertEquals(SearchEvent.OpenFile(secretPath, 3), SearchEvent.OpenFile(secretPath, 3))
    }

    @Test
    fun `api errors map to failures a screen can word`() {
        assertEquals(FilesFailure.Unauthorized, FilesFailure.of(ApiError.Unauthorized))
        assertEquals(FilesFailure.PermissionDenied, FilesFailure.of(ApiError.Forbidden))
        assertEquals(FilesFailure.NotFound, FilesFailure.of(ApiError.NotFound))
        assertEquals(FilesFailure.Rejected, FilesFailure.of(ApiError.Conflict()))
        assertEquals(FilesFailure.Rejected, FilesFailure.of(ApiError.BadRequest()))
        assertEquals(FilesFailure.ServerFailed, FilesFailure.of(ApiError.Server(500)))
        assertEquals(FilesFailure.Unreachable, FilesFailure.of(ApiError.Network))
        assertEquals(FilesFailure.Unreachable, FilesFailure.of(ApiError.Timeout))
        assertEquals(FilesFailure.NotConnected, FilesFailure.of(ApiError.Closed))
        assertEquals(FilesFailure.Unknown, FilesFailure.of(ApiError.VersionTooOld("2.1")))
        assertEquals(FilesFailure.Unknown, FilesFailure.of(ApiError.MalformedResponse))
        assertEquals(FilesFailure.Unknown, FilesFailure.of(ApiError.Unexpected()))
    }

    @Test
    fun `sftp failures keep their meaning`() {
        assertEquals(FilesFailure.NotFound, FilesFailure.of(SftpFailure.NotFound))
        assertEquals(FilesFailure.PermissionDenied, FilesFailure.of(SftpFailure.PermissionDenied))
        assertEquals(FilesFailure.AlreadyExists, FilesFailure.of(SftpFailure.AlreadyExists))
        assertEquals(FilesFailure.NotEmpty, FilesFailure.of(SftpFailure.NotEmpty))
        assertEquals(FilesFailure.PathRejected, FilesFailure.of(SftpFailure.PathRejected))
        assertEquals(FilesFailure.TooLarge, FilesFailure.of(SftpFailure.TooLarge))
        assertEquals(FilesFailure.ServerFailed, FilesFailure.of(SftpFailure.Failed))
    }

    @Test
    fun `ssh errors map to failures a screen can word`() {
        assertEquals(FilesFailure.Unreachable, FilesFailure.of(SshException.ConnectionFailed()))
        assertEquals(FilesFailure.NotConnected, FilesFailure.of(SshException.Disconnected()))
        assertEquals(FilesFailure.Unauthorized, FilesFailure.of(SshException.AuthenticationFailed()))
        assertEquals(FilesFailure.Unauthorized, FilesFailure.of(SshException.HostKeyRejected(HostKey("ssh-ed25519", ByteArray(32)))))
        assertEquals(FilesFailure.Unreachable, FilesFailure.of(SshException.Timeout()))
        assertEquals(FilesFailure.TooLarge, FilesFailure.of(SshException.OutputLimitExceeded(10)))
        assertEquals(FilesFailure.NotEmpty, FilesFailure.of(SshException.Sftp(SftpFailure.NotEmpty)))
    }

    @Test
    fun `a files exception carries its failure and no detail`() {
        val cause = IllegalStateException(secretPath)
        val error = FilesException(FilesFailure.PathRejected, cause)
        assertEquals(FilesFailure.PathRejected, error.failure)
        assertEquals(cause, error.cause)
        assertFalse(secretPath in error.message.orEmpty())
    }
}
