package io.github.thewisekd.phopencode.core.files

import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.testing.api.FakeApiControl
import io.github.thewisekd.phopencode.core.testing.api.FakeFsApi
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The service over a real SSH connection: the default [SshjConnector] against an embedded server
 * whose SFTP and commands work on a temporary folder of this machine.
 */
class FileServiceIntegrationTest {
    private val server = FilesSshServer()
    private val project: Path = server.home.resolve("project").createDirectories()
    private var session: SshSession? = null
    private var sftp: SftpClient? = null

    private val hostileNames = listOf(
        "\$(touch pwned).txt",
        "`touch pwned`.txt",
        "-rf",
        "a b.txt",
        "it's \"q\".txt",
        "new\nline.txt",
        "çğıöşü İI.txt",
        "a:1:b.txt",
    )

    @AfterTest
    fun tearDown() {
        runCatching { sftp?.close() }
        runCatching { session?.close() }
        server.close()
    }

    private suspend fun service(): FileService {
        val opened = SshjConnector().connect(server.target, filesPassword(), server.verifier)
        session = opened
        val client = opened.openSftp(project.toString())
        sftp = client
        return RemoteFileService(client, opened.exec, FakeFsApi())
    }

    private fun noInjection() {
        assertTrue(server.home.toFile().walk().none { it.name == "pwned" }, "A name ran as a command")
    }

    private fun names(): Set<String> = project.listDirectoryEntries().map { it.name }.toSet()

    private suspend fun error(block: suspend () -> Unit): FileError =
        assertIs<FileException>(runCatching { block() }.exceptionOrNull()).error

    @Test
    fun `hostile names survive write, read, list, move and delete`() = runBlocking {
        val service = service()
        hostileNames.forEach { name ->
            val bytes = "content of $name\n".encodeToByteArray()
            service.write(name, bytes)
            assertContentEquals(bytes, project.resolve(name).readBytes(), name)
            assertContentEquals(bytes, service.read(name).bytes, name)
        }
        assertEquals(hostileNames.toSet(), service.list("").map { it.name }.toSet())
        assertEquals(hostileNames.toSet(), names(), "No temporary file remains")

        service.createDirectory("moved")
        hostileNames.forEach { service.move(it, "moved/$it") }
        assertEquals(setOf("moved"), names())
        hostileNames.forEach { service.delete("moved/$it") }
        assertTrue(project.resolve("moved").listDirectoryEntries().isEmpty())
        noInjection()
    }

    @Test
    fun `saving keeps the file mode and leaves no temporary file`() = runBlocking {
        val script = project.resolve("run.sh")
        script.writeText("old\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"))
        val service = service()
        val before = service.read("run.sh")
        service.write("run.sh", "new\n".encodeToByteArray(), FileWriteOptions(overwrite = true, expectedModifiedAt = before.entry.modifiedAt))
        assertEquals("new\n", script.readBytes().decodeToString())
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(script)))
        assertEquals(setOf("run.sh"), names())
    }

    @Test
    fun `too large is reported without loading`() = runBlocking {
        project.resolve("big").writeBytes(ByteArray(4096))
        val service = service()
        assertEquals(FileError.TooLarge(4096, 1000), error { service.read("big", maxBytes = 1000) })
    }

    @Test
    fun `delete does not follow a symlink out of the root`() = runBlocking {
        val outside = server.home.resolve("outside").createDirectories()
        outside.resolve("keep").writeText("x")
        Files.createSymbolicLink(project.resolve("link"), outside)
        val service = service()
        assertEquals(FileError.PathRejected, error { service.read("link/keep") })
        assertEquals(FileError.PathRejected, error { service.delete("link/keep") })
        service.delete("link", recursive = true)
        assertFalse(project.resolve("link").exists())
        assertTrue(outside.resolve("keep").exists())
    }

    @Test
    fun `name search refuses a folder symlinked out of the root without asking the API`() = runBlocking {
        val outside = server.home.resolve("outside-search").createDirectories()
        outside.resolve("target.txt").writeText("x")
        Files.createSymbolicLink(project.resolve("escape"), outside)
        val control = FakeApiControl()
        val fs = FakeFsApi(control).apply {
            seed(Location(project.resolve("escape").toString()), listOf(FileEntry("target.txt", FileEntryType.File)))
        }
        val opened = SshjConnector().connect(server.target, filesPassword(), server.verifier)
        session = opened
        val client = opened.openSftp(project.toString())
        sftp = client
        val service = RemoteFileService(client, opened.exec, fs)

        assertEquals(FileError.PathRejected, error { service.findByName("escape", "target") })
        assertEquals(emptyList(), control.calls)
    }

    @Test
    fun `content search over SSH with hostile names`() = runBlocking {
        val dir = project.resolve("-rf \$(touch pwned) 'q'").createDirectories()
        hostileNames.forEach { dir.resolve(it).writeText("one\nfind: me\n") }
        val service = service()
        val result = service.searchContent(dir.toString(), ContentQuery("find: me"))
        assertEquals(hostileNames.map { "$dir/$it" }.toSet(), result.matches.map { it.path }.toSet())
        assertTrue(result.matches.all { it.line == 2 })
        noInjection()
    }
}
