package io.github.thewisekd.phopencode.core.files

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.api.FakeApiControl
import io.github.thewisekd.phopencode.core.testing.api.FakeFsApi
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteFiles
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSftpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class FileServiceTest {
    private val root = "/home/u/project"
    private val files = FakeRemoteFiles()
    private val sftp = FakeSftpClient(root, files)
    private val exec = FakeRemoteExec()
    private val control = FakeApiControl()
    private val fs = FakeFsApi(control)
    private val service: FileService = RemoteFileService(sftp, exec, fs)

    private val hostileNames = listOf(
        "\$(touch pwned).txt",
        "`id`.txt",
        "-rf",
        "a b.txt",
        "it's \"q\".txt",
        "new\nline.txt",
        "çğıöşü İI.txt",
        "a:1:b.txt",
    )

    private suspend fun failure(block: suspend () -> Unit): FileError {
        val thrown = try {
            block()
            null
        } catch (e: FileException) {
            e
        }
        return assertIs<FileException>(thrown, "Expected a FileException").error
    }

    init {
        files.addDirectory(root)
    }

    @Test
    fun `lists a folder with hostile names unchanged`() = runTest {
        hostileNames.forEach { files.addFile("$root/d/$it", it.encodeToByteArray()) }
        val names = service.list("d").map { it.name }.toSet()
        assertEquals(hostileNames.toSet(), names)
        assertTrue(service.list("$root/d").all { it.path.startsWith("$root/d/") })
    }

    @Test
    fun `reads hostile names byte for byte`() = runTest {
        hostileNames.forEach { name ->
            val bytes = byteArrayOf(0, -1, 10, 13) + name.encodeToByteArray()
            files.addFile("$root/$name", bytes)
            val content = service.read(name)
            assertContentEquals(bytes, content.bytes, name)
            assertEquals("$root/$name", content.entry.path)
        }
        assertTrue(exec.executions.isEmpty(), "No shell is needed to read")
    }

    @Test
    fun `a file above the limit is reported too large without being read`() = runTest {
        files.addFile("$root/big.bin", ByteArray(2048))
        val before = sftp.requests
        val error = failure { service.read("big.bin", maxBytes = 1024) }
        assertEquals(FileError.TooLarge(sizeBytes = 2048, limitBytes = 1024), error)
        assertEquals(1, sftp.requests - before, "Only the stat reached the server")
        assertContentEquals(ByteArray(1024), service.read("big.bin", maxBytes = 2048).bytes.copyOf(1024))
    }

    @Test
    fun `a file that grows past the limit after the stat is still too large`() = runTest {
        val grower = object : io.github.thewisekd.phopencode.core.ssh.api.SftpClient by sftp {
            override suspend fun read(path: String, maxBytes: Long): ByteArray = throw SshException.Sftp(SftpFailure.TooLarge)
        }
        files.addFile("$root/f", ByteArray(10))
        val error = failure { RemoteFileService(grower, exec, fs).read("f", maxBytes = 100) }
        assertEquals(FileError.TooLarge(sizeBytes = null, limitBytes = 100), error)
    }

    @Test
    fun `reading a folder or a missing file fails clearly`() = runTest {
        files.addDirectory("$root/dir")
        assertEquals(FileError.NotAFile, failure { service.read("dir") })
        assertEquals(FileError.NotFound, failure { service.read("missing") })
        assertEquals(FileError.NotADirectory, failure { files.addFile("$root/plain", byteArrayOf(1)); service.list("plain") })
    }

    @Test
    fun `cancellation during the not-a-folder check is not swallowed`() = runTest {
        val cancelling = object : io.github.thewisekd.phopencode.core.ssh.api.SftpClient by sftp {
            override suspend fun list(path: String): List<FileEntry> = throw SshException.Sftp(SftpFailure.Failed)

            override suspend fun stat(path: String): FileEntry = throw kotlinx.coroutines.CancellationException("stop")
        }
        assertFailsWith<kotlinx.coroutines.CancellationException> { RemoteFileService(cancelling, exec, fs).list("x") }
    }

    @Test
    fun `paths outside the root or with dot segments never reach the server`() = runTest {
        val before = sftp.requests
        listOf("../x", "/etc/passwd", "a/../../b", "/home/u/projectx/a", "a\u0000b").forEach { path ->
            assertEquals(FileError.PathRejected, failure { service.read(path) }, path)
            assertEquals(FileError.PathRejected, failure { service.delete(path) }, path)
            assertEquals(FileError.PathRejected, failure { service.write(path, byteArrayOf(1)) }, path)
            assertEquals(FileError.PathRejected, failure { service.move("ok", path) }, path)
        }
        assertEquals(before, sftp.requests)
    }

    @Test
    fun `write replaces atomically and keeps the mode`() = runTest {
        files.addFile("$root/run.sh", "old".encodeToByteArray(), mode = 0b111_101_000)
        service.write("run.sh", "new".encodeToByteArray(), FileWriteOptions(overwrite = true))
        assertContentEquals("new".encodeToByteArray(), files.content("$root/run.sh"))
        assertEquals(0b111_101_000, files.mode("$root/run.sh"))
        assertEquals(setOf("run.sh"), files.names(root), "No temporary file remains")
    }

    @Test
    fun `write refuses to replace without overwrite`() = runTest {
        files.addFile("$root/a", "old".encodeToByteArray())
        assertEquals(FileError.AlreadyExists, failure { service.write("a", "new".encodeToByteArray()) })
        assertContentEquals("old".encodeToByteArray(), files.content("$root/a"))
    }

    @Test
    fun `a failed upload leaves the old file and no temporary file`() = runTest {
        files.addFile("$root/a", "old".encodeToByteArray())
        val broken = object : InputStream() {
            var sent = false

            override fun read(): Int = throw IOException("broken")

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent) throw IOException("broken")
                sent = true
                b[off] = 1
                return 1
            }
        }
        val error = failure { service.upload("a", broken, FileWriteOptions(overwrite = true)) }
        assertEquals(FileError.Failed, error)
        assertContentEquals("old".encodeToByteArray(), files.content("$root/a"))
        assertEquals(setOf("a"), files.names(root))
    }

    @Test
    fun `write with an expected time refuses a file changed since`() = runTest {
        val changing = object : io.github.thewisekd.phopencode.core.ssh.api.SftpClient by sftp {
            override suspend fun stat(path: String): FileEntry =
                sftp.stat(path).copy(modifiedAt = Instant.fromEpochSeconds(200))
        }
        files.addFile("$root/a", "old".encodeToByteArray())
        val withTimes = RemoteFileService(changing, exec, fs)
        val stale = FileWriteOptions(overwrite = true, expectedModifiedAt = Instant.fromEpochSeconds(100))
        assertEquals(FileError.Conflict, failure { withTimes.write("a", "new".encodeToByteArray(), stale) })
        assertContentEquals("old".encodeToByteArray(), files.content("$root/a"))

        val fresh = stale.copy(expectedModifiedAt = Instant.fromEpochSeconds(200))
        withTimes.write("a", "new".encodeToByteArray(), fresh)
        assertContentEquals("new".encodeToByteArray(), files.content("$root/a"))

        assertEquals(FileError.Conflict, failure { withTimes.write("gone", byteArrayOf(1), fresh) })
        assertFalse(files.exists("$root/gone"))
    }

    @Test
    fun `upload and download stream hostile names`() = runTest {
        hostileNames.forEach { name ->
            val bytes = ByteArray(100_000) { (it % 251).toByte() }
            var progress = 0L
            service.upload(name, ByteArrayInputStream(bytes)) { progress = it }
            assertEquals(bytes.size.toLong(), progress)
            val sink = ByteArrayOutputStream()
            assertEquals(bytes.size.toLong(), service.download("$root/$name", sink))
            assertContentEquals(bytes, sink.toByteArray(), name)
        }
    }

    @Test
    fun `delete removes a symlink, never its target`() = runTest {
        files.addFile("/home/u/outside/keep", byteArrayOf(1))
        files.addSymlink("$root/link", "/home/u/outside")
        service.delete("link", recursive = true)
        assertFalse(files.exists("$root/link"))
        assertTrue(files.exists("/home/u/outside/keep"))
    }

    @Test
    fun `delete reports clear errors and refuses the root`() = runTest {
        files.addFile("$root/full/x", byteArrayOf(1))
        assertEquals(FileError.NotEmpty, failure { service.delete("full") })
        assertTrue(files.exists("$root/full/x"))
        assertEquals(FileError.NotFound, failure { service.delete("missing") })
        assertEquals(FileError.RootProtected, failure { service.delete(root, recursive = true) })
        assertEquals(FileError.RootProtected, failure { service.delete("", recursive = true) })
        service.delete("full", recursive = true)
        assertFalse(files.exists("$root/full"))
    }

    @Test
    fun `hostile names are deleted exactly`() = runTest {
        hostileNames.forEach { files.addFile("$root/$it", byteArrayOf(1)) }
        files.addFile("$root/keep", byteArrayOf(1))
        hostileNames.forEach { service.delete(it) }
        assertEquals(setOf("keep"), files.names(root))
    }

    @Test
    fun `move renames hostile names and reports clashes`() = runTest {
        hostileNames.forEach { name ->
            files.addFile("$root/src/$name", name.encodeToByteArray())
            service.move("src/$name", "dst/$name".also { files.addDirectory("$root/dst") })
            assertContentEquals(name.encodeToByteArray(), files.content("$root/dst/$name"))
        }
        files.addFile("$root/a", byteArrayOf(1))
        files.addFile("$root/b", byteArrayOf(2))
        assertEquals(FileError.AlreadyExists, failure { service.move("a", "b") })
        service.move("a", "b", overwrite = true)
        assertContentEquals(byteArrayOf(1), files.content("$root/b"))
        assertEquals(FileError.NotFound, failure { service.move("a", "c") })
    }

    @Test
    fun `move refuses a folder into itself and the root`() = runTest {
        files.addDirectory("$root/dir")
        assertEquals(FileError.MoveIntoItself, failure { service.move("dir", "dir/sub") })
        assertEquals(FileError.RootProtected, failure { service.move(root, "x") })
        assertTrue(files.exists("$root/dir"))
    }

    @Test
    fun `create folder`() = runTest {
        service.createDirectory("a/b c", parents = true)
        assertTrue(files.exists("$root/a/b c"))
        assertEquals(FileError.AlreadyExists, failure { service.createDirectory("a") })
    }

    @Test
    fun `server failures map to clear errors`() = runTest {
        files.addFile("$root/a", byteArrayOf(1))
        files.deny("$root/a")
        assertEquals(FileError.PermissionDenied, failure { service.read("a") })
        sftp.failNext(SshException.Timeout())
        assertEquals(FileError.Timeout, failure { service.list("") })
        sftp.failNext(SshException.Sftp(SftpFailure.Failed))
        assertEquals(FileError.Failed, failure { service.list("") })
        sftp.disconnect()
        assertEquals(FileError.Disconnected, failure { service.list("") })
    }

    @Test
    fun `name search goes through the API with absolute paths`() = runTest {
        val dir = "$root/src"
        files.addDirectory(dir)
        fs.seed(
            Location(dir),
            listOf(
                FileEntry("main.kt", FileEntryType.File),
                FileEntry("a b/main 2.kt", FileEntryType.File),
                FileEntry("main", FileEntryType.Directory),
                FileEntry("../escape/main", FileEntryType.File),
            ),
        )
        val result = service.findByName("src", "main", limit = 10)
        assertEquals(
            listOf("$dir/main.kt", "$dir/a b/main 2.kt", "$dir/main"),
            result.entries.map { it.path },
        )
        assertFalse(result.truncated)
        assertEquals(listOf("fs.find"), control.calls)
        assertTrue(exec.executions.isEmpty(), "Name search never runs a shell")

        val files = service.findByName("src", "main", type = FileSearchType.File, limit = 10).entries
        assertTrue(files.all { it.type == FileEntryType.File })
    }

    @Test
    fun `name search checks the folder over SFTP before asking the API`() = runTest {
        files.addFile("$root/plain", byteArrayOf(1))
        val refusing = object : io.github.thewisekd.phopencode.core.ssh.api.SftpClient by sftp {
            override suspend fun stat(path: String): FileEntry = throw SshException.Sftp(SftpFailure.PathRejected)
        }
        assertEquals(FileError.PathRejected, failure { RemoteFileService(refusing, exec, fs).findByName("link", "x") })
        assertEquals(FileError.NotADirectory, failure { service.findByName("plain", "x") })
        assertEquals(FileError.NotFound, failure { service.findByName("missing", "x") })
        assertTrue(control.calls.isEmpty())
    }

    @Test
    fun `name search is limited and says so`() = runTest {
        fs.seed(Location(root), (1..5).map { FileEntry("f$it", FileEntryType.File) })
        val result = service.findByName("", "f", limit = 3)
        assertEquals(3, result.entries.size)
        assertTrue(result.truncated)
        val all = service.findByName("", "f", limit = 5)
        assertEquals(5, all.entries.size)
        assertFalse(all.truncated)
    }

    @Test
    fun `name search errors carry the API error`() = runTest {
        control.failNext(ApiError.Unauthorized)
        assertEquals(FileError.Api(ApiError.Unauthorized), failure { service.findByName("", "x") })
        assertTrue(service.findByName("", "  ").entries.isEmpty())
        assertEquals(FileError.PathRejected, failure { service.findByName("../x", "x") })
    }

    @Test
    fun `printed forms hold no path or content`() = runTest {
        val marker = "private-name"
        files.addFile("$root/$marker", marker.encodeToByteArray())
        val content = service.read(marker)
        assertFalse(marker in content.toString())
        assertFalse(marker in ContentMatch("$root/$marker", 1, marker).toString())
        assertFalse(marker in ContentQuery(marker).toString())
        assertFalse(marker in NameSearchResult(listOf(FileEntry(marker, FileEntryType.File)), false).toString())
        assertFalse(marker in failure { service.read("$marker/x") }.toString())
        val thrown = assertFailsWith<FileException> { service.read("../$marker") }
        assertFalse(marker in thrown.toString())
        assertFalse(marker in service.toString())
        assertNull(thrown.message?.takeIf { marker in it })
    }
}
