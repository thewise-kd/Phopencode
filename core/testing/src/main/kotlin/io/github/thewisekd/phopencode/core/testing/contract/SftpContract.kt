package io.github.thewisekd.phopencode.core.testing.contract

import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SftpWriteOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/** The tree an SFTP client works on, from the server's side. Paths are relative to [root]. */
interface SftpHarness {
    val client: SftpClient

    /** The client's root, absolute. */
    val root: String

    /** An absolute folder outside [root], holding [OUTSIDE_FILE]. */
    val outside: String

    /** @param mode Null is the server's default. */
    suspend fun givenFile(path: String, content: ByteArray, mode: Int? = null)

    suspend fun givenFolder(path: String)

    suspend fun givenSymlink(path: String, target: String)

    /** The server refuses this client access to [path]. */
    suspend fun givenDenied(path: String)

    /** The bytes of a file, or null if it is missing. A symlink at [path] is not followed. */
    suspend fun content(path: String): ByteArray?

    /** The bytes of [OUTSIDE_FILE]. */
    suspend fun outsideContent(): ByteArray

    /** Whether anything is at [path], without following a symlink. */
    suspend fun exists(path: String): Boolean

    /** The permission bits of a file. */
    suspend fun mode(path: String): Int

    /** The names in a folder, as the server sees them. */
    suspend fun names(path: String): Set<String>

    /** The names in [outside], as the server sees them. */
    suspend fun outsideNames(): Set<String>

    /** A client of the same connection with another root. */
    suspend fun clientAt(root: String): SftpClient

    /** Ends the connection. Not suspending, so that a stream can do it during a transfer. */
    fun disconnect()

    companion object {
        const val OUTSIDE_FILE: String = "outside.txt"
    }
}

private val BYTES = byteArrayOf(0, 1, -1, 127, -128, 10, 13)
private val OLD = "old".encodeToByteArray()
private val NEW = "new".encodeToByteArray()
private const val MODE_644 = 0b110_100_100
private const val MODE_600 = SftpWriteOptions.OWNER_ONLY
private const val MODE_640 = 0b110_100_000

/** Big enough for a real client to go through several network writes. */
private val LARGE = ByteArray(1024 * 1024) { it.toByte() }

private val BAD_SEGMENTS = listOf("../x", "a/../b", "a/..", "..", "./x", "a/./b", ".", "a\u0000b")

private class Tracked(source: InputStream) : FilterInputStream(source) {
    var closed = false

    override fun close() {
        closed = true
        super.close()
    }
}

private class TrackedSink(sink: OutputStream) : FilterOutputStream(sink) {
    var closed = false

    override fun close() {
        closed = true
        super.close()
    }
}

/** Gives [good] bytes and then fails. */
private class FailingSource(private val good: Int) : InputStream() {
    private var served = 0

    override fun read(): Int {
        if (served >= good) throw IOException("source failed")
        served++
        return 'x'.code
    }
}

private fun source(bytes: ByteArray) = ByteArrayInputStream(bytes)

/** Gives one byte, and ends the connection on the next read. */
private class DisconnectingSource(private val harness: SftpHarness) : InputStream() {
    private var served = false

    override fun read(): Int {
        if (served) {
            harness.disconnect()
            throw IOException("connection ended")
        }
        served = true
        return 'x'.code
    }
}

/** [path] holds [bytes], and nothing else is in the root: no temporary file stayed behind. */
private suspend fun SftpHarness.expectUntouched(path: String, bytes: ByteArray) {
    expectThat(content(path).contentEquals(bytes), "$path must hold the expected bytes")
    expectEqual(setOf(path), names(""), "names in the root")
}

/** The promises on [SftpClient]. */
fun sftpClientContract(newHarness: suspend () -> SftpHarness): List<ContractCase> = contractCases {
    pathRules(newHarness)
    reading(newHarness)
    writing(newHarness)
    changing(newHarness)
    connection(newHarness)
}

private fun ContractCases.pathRules(newHarness: suspend () -> SftpHarness) {
    BAD_SEGMENTS.forEach { path ->
        case("the path <${path.replace('\u0000', '0')}> is rejected") {
            val harness = newHarness()
            expectSftpFailure(SftpFailure.PathRejected, "stat") { harness.client.stat(path) }
            expectSftpFailure(SftpFailure.PathRejected, "write") {
                harness.client.writeAtomic(path, source(NEW))
            }
        }
    }
    case("an absolute path outside the root is rejected") {
        val harness = newHarness()
        expectSftpFailure(SftpFailure.PathRejected, "read") {
            harness.client.read("${harness.outside}/${SftpHarness.OUTSIDE_FILE}", 1024)
        }
    }
    case("a sibling folder that starts like the root is outside it") {
        val harness = newHarness()
        expectSftpFailure(SftpFailure.PathRejected, "list") { harness.client.list("${harness.root}-sibling") }
    }
    case("relative and absolute paths inside the root reach the same file") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        expectThat(harness.client.read("a.txt", 1024).contentEquals(BYTES), "relative path")
        expectThat(harness.client.read("${harness.root}/a.txt", 1024).contentEquals(BYTES), "absolute path")
    }
    case("a change through a symlink that leaves the root is rejected, and changes nothing") {
        val harness = newHarness()
        val before = harness.outsideContent()
        harness.givenSymlink("out", harness.outside)
        val client = harness.client
        val outsideFile = "out/${SftpHarness.OUTSIDE_FILE}"
        val options = SftpWriteOptions(overwrite = true)
        expectSftpFailure(SftpFailure.PathRejected, "write") { client.writeAtomic(outsideFile, source(NEW), options) }
        expectSftpFailure(SftpFailure.PathRejected, "delete") { client.delete(outsideFile) }
        expectSftpFailure(SftpFailure.PathRejected, "mkdir") { client.mkdir("out/new") }
        expectSftpFailure(SftpFailure.PathRejected, "mkdir with parents") { client.mkdir("out/new/deep", parents = true) }
        expectSftpFailure(SftpFailure.PathRejected, "rename from") { client.rename(outsideFile, "in.txt") }
        harness.givenFile("in.txt", BYTES)
        expectSftpFailure(SftpFailure.PathRejected, "rename to") { client.rename("in.txt", "out/in.txt") }
        expectThat(harness.outsideContent().contentEquals(before), "The outside file must be unchanged")
        expectEqual(setOf(SftpHarness.OUTSIDE_FILE), harness.outsideNames(), "The outside folder must be unchanged")
        expectThat(harness.content("in.txt").contentEquals(BYTES), "file inside the root")
    }
    case("a write to a symlink that points outside the root is rejected") {
        val harness = newHarness()
        val before = harness.outsideContent()
        harness.givenSymlink("link", "${harness.outside}/${SftpHarness.OUTSIDE_FILE}")
        expectSftpFailure(SftpFailure.PathRejected, "write") {
            harness.client.writeAtomic("link", source(NEW), SftpWriteOptions(overwrite = true))
        }
        expectThat(harness.outsideContent().contentEquals(before), "The outside file must be unchanged")
        expectEqual(setOf("link"), harness.names(""), "names")
    }
    case("reporting a symlink that points outside the root is allowed") {
        val harness = newHarness()
        harness.givenSymlink("link", harness.outside)
        expectEqual<FileEntryType>(FileEntryType.Symlink, harness.client.lstat("link").type, "lstat")
        expectEqual(harness.outside, harness.client.readLink("link"), "readLink")
        expectEqual<FileEntryType>(FileEntryType.Symlink, harness.client.list("").single().type, "list")
        harness.client.delete("link")
        expectThat(!harness.exists("link"), "The link must be gone")
        expectEqual(setOf(SftpHarness.OUTSIDE_FILE), harness.outsideNames(), "The target must stay")
    }
    case("a root of slash gives the whole server, with the same segment rules") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        val whole = harness.clientAt("/")
        expectThat(whole.read("${harness.root}/a.txt", 1024).contentEquals(BYTES), "absolute path")
        expectSftpFailure(SftpFailure.PathRejected, "segment rule") { whole.stat("/a/../b") }
    }
}

private fun ContractCases.reading(newHarness: suspend () -> SftpHarness) {
    case("list gives the direct children, and reports a symlink as a symlink") {
        val harness = newHarness()
        harness.givenFolder("folder")
        harness.givenFile("folder/deep.txt", BYTES)
        harness.givenFile("a.txt", BYTES)
        harness.givenSymlink("link", "a.txt")
        val types = harness.client.list("").associate { it.name to it.type }
        expectEqual(
            mapOf<String, FileEntryType>(
                "a.txt" to FileEntryType.File,
                "folder" to FileEntryType.Directory,
                "link" to FileEntryType.Symlink,
            ),
            types,
            "entries",
        )
    }
    case("a missing path fails with NotFound") {
        val client = newHarness().client
        expectSftpFailure(SftpFailure.NotFound, "list") { client.list("missing") }
        expectSftpFailure(SftpFailure.NotFound, "stat") { client.stat("missing") }
        expectSftpFailure(SftpFailure.NotFound, "lstat") { client.lstat("missing") }
        expectSftpFailure(SftpFailure.NotFound, "readLink") { client.readLink("missing") }
        expectSftpFailure(SftpFailure.NotFound, "read") { client.read("missing", 1024) }
        expectSftpFailure(SftpFailure.NotFound, "download") { client.download("missing", ByteArrayOutputStream()) }
    }
    case("a server refusal fails with PermissionDenied") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        harness.givenDenied("a.txt")
        expectSftpFailure(SftpFailure.PermissionDenied, "read") { harness.client.read("a.txt", 1024) }
    }
    case("stat follows symlinks, and lstat does not") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        harness.givenSymlink("link", "a.txt")
        val followed = harness.client.stat("link")
        expectEqual<FileEntryType>(FileEntryType.File, followed.type, "stat")
        expectEqual(BYTES.size.toLong(), followed.sizeBytes, "size")
        expectEqual<FileEntryType>(FileEntryType.Symlink, harness.client.lstat("link").type, "lstat")
    }
    case("readLink gives the target as stored, which may be relative") {
        val harness = newHarness()
        harness.givenSymlink("link", "../somewhere/else")
        expectEqual("../somewhere/else", harness.client.readLink("link"), "target")
    }
    case("read gives a file byte for byte, and a limit equal to the size is fine") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        expectThat(harness.client.read("a.txt", BYTES.size.toLong()).contentEquals(BYTES), "bytes")
    }
    case("read above the limit fails with TooLarge") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        expectSftpFailure(SftpFailure.TooLarge, "read") { harness.client.read("a.txt", BYTES.size - 1L) }
    }
    case("download streams byte for byte, reports the running total, and returns it") {
        val harness = newHarness()
        harness.givenFile("large.bin", LARGE)
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val total = harness.client.download("large.bin", sink) { progress += it }
        expectEqual(LARGE.size.toLong(), total, "total")
        expectThat(sink.toByteArray().contentEquals(LARGE), "bytes")
        expectEqual(LARGE.size.toLong(), progress.last(), "last progress")
        expectEqual(progress.sorted(), progress, "progress order")
    }
    case("download leaves the sink open") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        val sink = TrackedSink(ByteArrayOutputStream())
        harness.client.download("a.txt", sink)
        expectThat(!sink.closed, "The sink belongs to the caller")
    }
    case("cancelling stops a download") {
        val harness = newHarness()
        harness.givenFile("large.bin", LARGE)
        val sink = ByteArrayOutputStream()
        val running = start {
            val job = currentCoroutineContext().job
            harness.client.download("large.bin", sink) { job.cancel() }
        }
        expectCancelled(running)
        expectThat(sink.size() < LARGE.size, "Cancelling must stop the transfer")
    }
}

private suspend fun expectCancelled(running: Running<*>) {
    val failure = running.failure("cancelled transfer")
    expectThat(failure is CancellationException, "Cancelling must not become <$failure>")
}

private fun ContractCases.writing(newHarness: suspend () -> SftpHarness) {
    case("writeAtomic creates a file byte for byte, reports the total, and leaves no temporary file") {
        val harness = newHarness()
        val progress = mutableListOf<Long>()
        harness.client.writeAtomic("large.bin", source(LARGE)) { progress += it }
        expectThat(harness.content("large.bin").contentEquals(LARGE), "bytes")
        expectEqual(LARGE.size.toLong(), progress.last(), "last progress")
        expectEqual(setOf("large.bin"), harness.names(""), "names")
    }
    case("without overwrite an existing file fails with AlreadyExists") {
        val harness = newHarness()
        harness.givenFile("a.txt", OLD)
        expectSftpFailure(SftpFailure.AlreadyExists, "write") { harness.client.writeAtomic("a.txt", source(NEW)) }
        harness.expectUntouched("a.txt", OLD)
    }
    case("overwrite replaces an existing file") {
        val harness = newHarness()
        harness.givenFile("a.txt", OLD)
        harness.client.writeAtomic("a.txt", source(NEW), SftpWriteOptions(overwrite = true))
        harness.expectUntouched("a.txt", NEW)
    }
    case("a missing parent fails with NotFound") {
        val harness = newHarness()
        expectSftpFailure(SftpFailure.NotFound, "write") { harness.client.writeAtomic("missing/a.txt", source(NEW)) }
    }
    case("the source stays open") {
        val harness = newHarness()
        val tracked = Tracked(source(NEW))
        harness.client.writeAtomic("a.txt", tracked)
        expectThat(!tracked.closed, "The source belongs to the caller")
    }
    case("a failing source leaves the path unchanged and no temporary file") {
        val harness = newHarness()
        harness.givenFile("a.txt", OLD)
        expectSftpFailure(SftpFailure.Failed, "write") {
            harness.client.writeAtomic("a.txt", FailingSource(2), SftpWriteOptions(overwrite = true))
        }
        harness.expectUntouched("a.txt", OLD)
    }
    case("a failing source leaves nothing behind for a new file") {
        val harness = newHarness()
        expectSftpFailure(SftpFailure.Failed, "write") { harness.client.writeAtomic("a.txt", FailingSource(2)) }
        expectEqual(emptySet(), harness.names(""), "names")
    }
    case("cancelling leaves the path unchanged and no temporary file") {
        val harness = newHarness()
        harness.givenFile("a.txt", OLD)
        val running = start {
            val job = currentCoroutineContext().job
            harness.client.writeAtomic("a.txt", source(LARGE), SftpWriteOptions(overwrite = true)) { job.cancel() }
        }
        expectCancelled(running)
        harness.expectUntouched("a.txt", OLD)
    }
    case("null permissions keep the mode of the replaced file") {
        val harness = newHarness()
        harness.givenFile("a.txt", OLD, MODE_640)
        harness.client.writeAtomic("a.txt", source(NEW), SftpWriteOptions(overwrite = true))
        expectEqual(MODE_640, harness.mode("a.txt"), "mode")
    }
    case("permissions set the mode of the file") {
        val harness = newHarness()
        harness.client.writeAtomic("new.txt", source(NEW), SftpWriteOptions(permissions = MODE_600))
        expectEqual(MODE_600, harness.mode("new.txt"), "mode of a new file")
        harness.givenFile("a.txt", OLD, MODE_644)
        harness.client.writeAtomic("a.txt", source(NEW), SftpWriteOptions(overwrite = true, permissions = MODE_600))
        expectEqual(MODE_600, harness.mode("a.txt"), "mode of a replaced file")
    }
    case("a symlink stays a symlink: the file it points at is replaced") {
        val harness = newHarness()
        harness.givenFile("a.txt", OLD)
        harness.givenSymlink("link", "a.txt")
        harness.client.writeAtomic("link", source(NEW), SftpWriteOptions(overwrite = true))
        expectEqual<FileEntryType>(FileEntryType.Symlink, harness.client.lstat("link").type, "link")
        expectThat(harness.content("a.txt").contentEquals(NEW), "The target must be replaced")
    }
}

private fun ContractCases.changing(newHarness: suspend () -> SftpHarness) {
    case("delete removes a file") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        harness.client.delete("a.txt")
        expectThat(!harness.exists("a.txt"), "The file must be gone")
    }
    case("delete removes a symlink and never its target") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        harness.givenSymlink("link", "a.txt")
        harness.client.delete("link")
        expectEqual(setOf("a.txt"), harness.names(""), "names")
    }
    case("delete of a missing path fails with NotFound") {
        expectSftpFailure(SftpFailure.NotFound, "delete") { newHarness().client.delete("missing") }
    }
    case("delete removes an empty folder") {
        val harness = newHarness()
        harness.givenFolder("folder")
        harness.client.delete("folder")
        expectThat(!harness.exists("folder"), "The folder must be gone")
    }
    case("delete of a folder with content fails with NotEmpty unless recursive") {
        val harness = newHarness()
        harness.givenFolder("folder")
        harness.givenFile("folder/a.txt", BYTES)
        expectSftpFailure(SftpFailure.NotEmpty, "delete") { harness.client.delete("folder") }
        expectThat(harness.exists("folder/a.txt"), "The content must stay")
        harness.client.delete("folder", recursive = true)
        expectThat(!harness.exists("folder"), "The folder must be gone")
    }
    case("a recursive delete does not follow symlinks") {
        val harness = newHarness()
        harness.givenFolder("kept")
        harness.givenFile("kept/a.txt", BYTES)
        harness.givenFolder("folder")
        harness.givenSymlink("folder/link", "../kept")
        harness.client.delete("folder", recursive = true)
        expectThat(!harness.exists("folder"), "The folder must be gone")
        expectThat(harness.exists("kept/a.txt"), "The link's target must stay")
    }
    case("rename moves a file") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        harness.client.rename("a.txt", "b.txt")
        harness.expectUntouched("b.txt", BYTES)
    }
    case("rename of a missing path fails with NotFound") {
        expectSftpFailure(SftpFailure.NotFound, "rename") { newHarness().client.rename("missing", "b.txt") }
    }
    case("rename onto an existing path fails with AlreadyExists unless overwrite") {
        val harness = newHarness()
        harness.givenFile("a.txt", NEW)
        harness.givenFile("b.txt", OLD)
        expectSftpFailure(SftpFailure.AlreadyExists, "rename") { harness.client.rename("a.txt", "b.txt") }
        expectThat(harness.content("b.txt").contentEquals(OLD), "The target must stay")
        harness.client.rename("a.txt", "b.txt", overwrite = true)
        harness.expectUntouched("b.txt", NEW)
    }
    case("mkdir creates a folder") {
        val harness = newHarness()
        harness.client.mkdir("folder")
        expectEqual<FileEntryType>(FileEntryType.Directory, harness.client.stat("folder").type, "type")
    }
    case("mkdir fails with NotFound on a missing parent unless parents") {
        val harness = newHarness()
        expectSftpFailure(SftpFailure.NotFound, "mkdir") { harness.client.mkdir("a/b/c") }
        harness.client.mkdir("a/b/c", parents = true)
        expectEqual<FileEntryType>(FileEntryType.Directory, harness.client.stat("a/b/c").type, "type")
    }
    case("mkdir fails with AlreadyExists on an existing folder unless parents") {
        val harness = newHarness()
        harness.givenFolder("folder")
        expectSftpFailure(SftpFailure.AlreadyExists, "mkdir") { harness.client.mkdir("folder") }
        harness.client.mkdir("folder", parents = true)
    }
}

private fun ContractCases.connection(newHarness: suspend () -> SftpHarness) {
    case("root reports the folder the client is confined to") {
        val harness = newHarness()
        expectEqual(harness.root, harness.client.root, "root")
    }
    case("close can be called twice") {
        val client = newHarness().client
        client.close()
        client.close()
    }
    case("a closed client leaves the connection usable") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        harness.client.close()
        val next = harness.clientAt(harness.root)
        expectThat(next.read("a.txt", 1024).contentEquals(BYTES), "bytes")
    }
    case("the end of the connection fails calls with Disconnected") {
        val harness = newHarness()
        harness.givenFile("a.txt", BYTES)
        harness.disconnect()
        expectThrows<SshException.Disconnected>("read") { harness.client.read("a.txt", 1024) }
    }
    case("a connection that ends during a write leaves no change behind on the path") {
        val harness = newHarness()
        harness.givenFile("a.txt", OLD)
        val disconnected = expectThrows<SshException>("write") {
            harness.client.writeAtomic("a.txt", DisconnectingSource(harness), SftpWriteOptions(overwrite = true))
        }
        expectThat(disconnected is SshException.Disconnected, "Write failed with <$disconnected>")
        expectThat(harness.content("a.txt").contentEquals(OLD), "The file must be unchanged")
    }
}
