package io.github.thewisekd.phopencode.core.ssh.sftp.read

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import io.github.thewisekd.phopencode.core.ssh.connect.password
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.sftp.common.SftpConstants
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout

// A blocked sshj thread ignores coroutine cancellation; this ends such a test instead of the build.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SftpReaderTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private val folders = mutableListOf<Path>()

    private val server = SftpReadServer().closeLater()
    private val root: Path = tempFolder("sftp-read-root")
    private val outside: Path = tempFolder("sftp-read-outside")

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    private fun tempFolder(prefix: String): Path = Files.createTempDirectory(prefix).toRealPath().also { folders.add(it) }

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
        folders.forEach { folder ->
            folder.toFile().walkTopDown().forEach { runCatching { it.setReadable(true); it.setExecutable(true) } }
            folder.toFile().deleteRecursively()
        }
    }

    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(TEST_TIMEOUT) { body() } }

    private suspend fun session(): SshjSession =
        SshjConnector().connect(server.target, password(), ACCEPT, SshConnectOptions()).closeLater() as SshjSession

    private suspend fun reader(at: String = root.toString(), session: SshjSession? = null): SftpReader =
        openSftpReader(session ?: session(), at).closeLater()

    private fun file(relative: String, content: ByteArray): Path =
        root.resolve(relative).also { it.parent.createDirectories(); it.writeBytes(content) }

    private fun link(relative: String, target: String): Path =
        root.resolve(relative).createSymbolicLinkPointingTo(root.fileSystem.getPath(target))

    private suspend fun assertFailure(expected: SftpFailure, what: String, block: suspend () -> Unit) {
        val failure = assertFailsWith<SshException.Sftp>(what) { block() }
        assertEquals(expected, failure.failure, what)
    }

    /**
     * [actual] is the caller's own [expected], untranslated. kotlinx.coroutines' stack-trace
     * recovery (on while assertions are enabled, as in tests) may hand back a copy made at the
     * dispatcher switch: same class and message, with the original as its cause.
     */
    private fun assertCallerOwn(expected: Throwable, actual: Throwable) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        assertTrue(actual === expected || actual.cause === expected, "<$actual> must be the caller's own exception")
    }

    private fun assumeNotRoot() {
        assumeTrue(System.getProperty("user.name") != "root", "root ignores file permissions")
    }

    // Listing

    @Test
    fun `list gives the direct children with type, size and time, and no dot entries`() = test {
        val mtime = Instant.fromEpochSeconds(1_700_000_000)
        file("a.txt", BYTES).also { Files.setLastModifiedTime(it, FileTime.fromMillis(mtime.toEpochMilliseconds())) }
        file("folder/deep.txt", BYTES)
        link("link", "a.txt")
        val entries = reader().list("").associateBy { it.name }
        assertEquals(setOf("a.txt", "folder", "link"), entries.keys)
        val a = entries.getValue("a.txt")
        assertEquals(FileEntry("$root/a.txt", FileEntryType.File, BYTES.size.toLong(), mtime), a)
        assertEquals(FileEntryType.Directory, entries.getValue("folder").type)
        assertEquals("$root/folder", entries.getValue("folder").path)
        assertEquals(FileEntryType.Symlink, entries.getValue("link").type)
    }

    @Test
    fun `list of a subfolder, by relative and absolute path`() = test {
        file("folder/deep.txt", BYTES)
        val client = reader()
        assertEquals(listOf("$root/folder/deep.txt"), client.list("folder").map { it.path })
        assertEquals(listOf("$root/folder/deep.txt"), client.list("$root/folder").map { it.path })
    }

    @Test
    fun `list of an empty folder is empty`() = test {
        root.resolve("empty").createDirectory()
        assertEquals(emptyList(), reader().list("empty"))
    }

    @Test
    fun `list of a large folder gives every entry`() = test {
        repeat(300) { file("many/f$it.txt", BYTES) }
        assertEquals((0 until 300).map { "f$it.txt" }.toSet(), reader().list("many").map { it.name }.toSet())
    }

    // stat, lstat, readLink

    @Test
    fun `stat follows symlinks, and lstat does not`() = test {
        file("a.txt", BYTES)
        link("link", "a.txt")
        val client = reader()
        val followed = client.stat("link")
        assertEquals(FileEntryType.File, followed.type)
        assertEquals(BYTES.size.toLong(), followed.sizeBytes)
        assertEquals("$root/link", followed.path)
        val link = client.lstat("link")
        assertEquals(FileEntryType.Symlink, link.type)
        assertEquals("$root/link", link.path)
    }

    @Test
    fun `stat of a folder and of the root`() = test {
        root.resolve("folder").createDirectory()
        val client = reader()
        assertEquals(FileEntryType.Directory, client.stat("folder").type)
        val top = client.stat("")
        assertEquals(root.toString(), top.path)
        assertEquals(FileEntryType.Directory, top.type)
        assertNotNull(top.modifiedAt)
    }

    @Test
    fun `readLink gives the target as stored, which may be relative`() = test {
        link("rel", "../somewhere/else")
        link("abs", "/somewhere/else")
        val client = reader()
        assertEquals("../somewhere/else", client.readLink("rel"))
        assertEquals("/somewhere/else", client.readLink("abs"))
    }

    @Test
    fun `a symlink pointing outside the root can be reported`() = test {
        link("out", outside.toString())
        val client = reader()
        assertEquals(FileEntryType.Symlink, client.lstat("out").type)
        assertEquals(outside.toString(), client.readLink("out"))
        assertEquals(FileEntryType.Symlink, client.list("").single().type)
    }

    @Test
    fun `a dangling symlink is listed and lstat works, stat fails with NotFound`() = test {
        link("dangling", "missing-target")
        val client = reader()
        assertEquals(FileEntryType.Symlink, client.lstat("dangling").type)
        assertEquals(listOf("dangling"), client.list("").map { it.name })
        assertFailure(SftpFailure.NotFound, "stat") { client.stat("dangling") }
    }

    // read

    @Test
    fun `read gives a binary file byte for byte`() = test {
        val every = ByteArray(256) { it.toByte() }
        file("all.bin", every)
        assertContentEquals(every, reader().read("all.bin", 1024))
    }

    @Test
    fun `read gives an empty file, even with a limit of zero`() = test {
        file("empty", ByteArray(0))
        val client = reader()
        assertContentEquals(ByteArray(0), client.read("empty", 0))
        assertContentEquals(ByteArray(0), client.read("empty", 10))
    }

    @Test
    fun `read with a limit equal to the size is fine, one below fails with TooLarge`() = test {
        file("a.bin", BYTES)
        val client = reader()
        assertContentEquals(BYTES, client.read("a.bin", BYTES.size.toLong()))
        assertFailure(SftpFailure.TooLarge, "limit below size") { client.read("a.bin", BYTES.size - 1L) }
    }

    @Test
    fun `read gives a large file byte for byte`() = test {
        file("large.bin", LARGE)
        assertContentEquals(LARGE, reader().read("large.bin", LARGE.size.toLong()))
    }

    @Test
    fun `read above the limit does not read the rest of the file`() = test {
        file("large.bin", LARGE)
        val client = reader()
        val before = server.bytesRead()
        assertFailure(SftpFailure.TooLarge, "read") { client.read("large.bin", 100) }
        assertTrue(server.bytesRead() - before < LARGE.size / 2, "Read ${server.bytesRead() - before} bytes")
    }

    @Test
    fun `read follows a symlink`() = test {
        file("a.bin", BYTES)
        link("link", "a.bin")
        assertContentEquals(BYTES, reader().read("link", 1024))
    }

    @Test
    fun `a negative limit is a programming error`() = test {
        file("a.bin", BYTES)
        val client = reader()
        assertFailsWith<IllegalArgumentException> { client.read("a.bin", -1) }
    }

    @Test
    fun `read of a folder fails with a typed error`() = test {
        root.resolve("folder").createDirectory()
        assertFailsWith<SshException.Sftp> { reader().read("folder", 1024) }
    }

    // download

    @Test
    fun `download streams byte for byte, reports the running total, and returns it`() = test {
        file("large.bin", LARGE)
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val total = reader().download("large.bin", sink) { progress += it }
        assertEquals(LARGE.size.toLong(), total)
        assertContentEquals(LARGE, sink.toByteArray())
        assertEquals(LARGE.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.size > 1, "Progress must come during the transfer")
    }

    @Test
    fun `download of an empty file writes nothing and returns zero`() = test {
        file("empty", ByteArray(0))
        val sink = ByteArrayOutputStream()
        assertEquals(0, reader().download("empty", sink))
        assertEquals(0, sink.size())
    }

    @Test
    fun `download leaves the sink open`() = test {
        file("a.bin", BYTES)
        val sink = TrackedSink(ByteArrayOutputStream())
        reader().download("a.bin", sink)
        assertFalse(sink.closed)
    }

    @Test
    fun `cancelling stops a download`() = test {
        file("large.bin", LARGE)
        val client = reader()
        val sink = ByteArrayOutputStream()
        var thrown: Throwable? = null
        coroutineScope {
            launch {
                val job = currentCoroutineContext().job
                try {
                    client.download("large.bin", sink) { job.cancel() }
                } catch (failure: Throwable) {
                    thrown = failure
                    throw failure
                }
            }
        }
        assertTrue(thrown is CancellationException, "Cancelling must not become <$thrown>")
        assertTrue(sink.size() < LARGE.size, "Cancelling must stop the transfer")
        // The reader stays usable.
        assertContentEquals(LARGE, client.read("large.bin", LARGE.size.toLong()))
    }

    @Test
    fun `cancelling a listing sends no further READDIR and closes the folder handle`() = test {
        repeat(MANY_ENTRIES) { file("many/${LONG_NAME}_$it.txt", ByteArray(0)) }
        val client = reader()
        val before = server.requests(SftpConstants.SSH_FXP_READDIR)
        assertEquals(MANY_ENTRIES, client.list("many").size)
        val fullListing = server.requests(SftpConstants.SSH_FXP_READDIR) - before
        assertTrue(fullListing >= 3, "The folder must take several batches, took $fullListing")

        var thrown: Throwable? = null
        val start = server.requests(SftpConstants.SSH_FXP_READDIR)
        coroutineScope {
            val listing = launch {
                try {
                    client.list("many")
                } catch (failure: Throwable) {
                    thrown = failure
                    throw failure
                }
            }
            // Cancelled while the server is still answering the first batch.
            server.onRequest = { type -> if (type == SftpConstants.SSH_FXP_READDIR) listing.cancel() }
        }
        server.onRequest = {}
        assertTrue(thrown is CancellationException, "Cancelling must not become <$thrown>")
        assertEquals(1, server.requests(SftpConstants.SSH_FXP_READDIR) - start, "READDIR requests after cancelling")
        eventually("folder handle closed") { server.openHandles() == 0 }
        // The reader stays usable.
        assertEquals(FileEntryType.Directory, client.stat("many").type)
    }

    @Test
    fun `a failing sink surfaces its own exception and the file handle is closed`() = test {
        file("large.bin", LARGE)
        val client = reader()
        val own = IOException("the caller's disk is full")
        val sink = object : OutputStream() {
            override fun write(b: Int) = throw own

            override fun write(b: ByteArray, off: Int, len: Int) = throw own
        }
        val failure = assertFailsWith<IOException> { client.download("large.bin", sink) }
        assertCallerOwn(own, failure)
        eventually("file handle closed") { server.openHandles() == 0 }
        // The reader stays usable.
        assertContentEquals(LARGE, client.read("large.bin", LARGE.size.toLong()))
    }

    @Test
    fun `a failing progress callback surfaces its own exception and the file handle is closed`() = test {
        file("large.bin", LARGE)
        val client = reader()
        val own = IOException("the caller's progress failed")
        val failure = assertFailsWith<IOException> {
            client.download("large.bin", ByteArrayOutputStream()) { throw own }
        }
        assertCallerOwn(own, failure)
        val other = IllegalStateException("the caller's bug")
        assertCallerOwn(other, assertFailsWith<IllegalStateException> {
            client.download("large.bin", ByteArrayOutputStream()) { throw other }
        })
        eventually("file handles closed") { server.openHandles() == 0 }
    }

    @Test
    fun `a sink failure on a lost connection is still the sink's own exception`() = test {
        file("large.bin", LARGE)
        val session = session()
        val client = reader(session = session)
        val own = IOException("the caller's sink failed")
        val sink = object : OutputStream() {
            override fun write(b: Int) = throw own

            override fun write(b: ByteArray, off: Int, len: Int) {
                session.close()
                throw own
            }
        }
        assertCallerOwn(own, assertFailsWith<IOException> { client.download("large.bin", sink) })
    }

    // Names

    @Test
    fun `Turkish names and names with spaces are listed, read and reported exactly`() = test {
        val folder = "klasör adı ÇĞİÖŞÜ"
        val name = "çğıİöşü dosya adı.txt"
        val content = "İçerik: ğüşıöç".encodeToByteArray()
        file("$folder/$name", content)
        file("  baş ve son boşluk  ", BYTES)
        val client = reader()
        assertEquals(setOf(folder, "  baş ve son boşluk  "), client.list("").map { it.name }.toSet())
        val entry = client.list(folder).single()
        assertEquals(name, entry.name)
        assertEquals("$root/$folder/$name", entry.path)
        assertContentEquals(content, client.read("$folder/$name", 1024))
        assertContentEquals(content, client.read("$root/$folder/$name", 1024))
        assertContentEquals(content, client.read(entry.path, 1024))
        assertEquals(content.size.toLong(), client.stat("$folder/$name").sizeBytes)
        assertContentEquals(BYTES, client.read("  baş ve son boşluk  ", 1024))
        val sink = ByteArrayOutputStream()
        client.download("$folder/$name", sink)
        assertContentEquals(content, sink.toByteArray())
    }

    @Test
    fun `a root with a Turkish name and spaces`() = test {
        val turkish = root.resolve("kök dizin ış").createDirectory()
        Files.write(turkish.resolve("ö.txt"), BYTES)
        val client = reader(turkish.toString())
        assertEquals(turkish.toString(), client.root)
        assertEquals(listOf("ö.txt"), client.list("").map { it.name })
        assertContentEquals(BYTES, client.read("ö.txt", 1024))
    }

    // Failures

    @Test
    fun `a missing path fails with NotFound`() = test {
        val client = reader()
        assertFailure(SftpFailure.NotFound, "list") { client.list("missing") }
        assertFailure(SftpFailure.NotFound, "stat") { client.stat("missing") }
        assertFailure(SftpFailure.NotFound, "lstat") { client.lstat("missing") }
        assertFailure(SftpFailure.NotFound, "readLink") { client.readLink("missing") }
        assertFailure(SftpFailure.NotFound, "read") { client.read("missing", 1024) }
        assertFailure(SftpFailure.NotFound, "download") { client.download("missing", ByteArrayOutputStream()) }
        assertFailure(SftpFailure.NotFound, "missing parent") { client.read("missing/a.txt", 1024) }
    }

    @Test
    fun `a server refusal fails with PermissionDenied`() = test {
        assumeNotRoot()
        val denied = file("denied.txt", BYTES)
        Files.setPosixFilePermissions(denied, PosixFilePermissions.fromString("---------"))
        val closed = root.resolve("closed").createDirectory()
        Files.write(closed.resolve("inner.txt"), BYTES)
        Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("---------"))
        val client = reader()
        assertFailure(SftpFailure.PermissionDenied, "read") { client.read("denied.txt", 1024) }
        assertFailure(SftpFailure.PermissionDenied, "download") { client.download("denied.txt", ByteArrayOutputStream()) }
        assertFailure(SftpFailure.PermissionDenied, "list") { client.list("closed") }
    }

    @Test
    fun `failures quote no path and no server text`() = test {
        val secretName = "gizli-dosya-adı"
        val client = reader()
        val failure = assertFailsWith<SshException.Sftp> { client.read(secretName, 1024) }
        var current: Throwable? = failure
        while (current != null) {
            assertFalse(current.message.orEmpty().contains("gizli"), "Message of ${current.javaClass.name}")
            assertFalse(current.toString().contains(root.toString()), "Text of ${current.javaClass.name}")
            current = current.cause
        }
    }

    // Path rules

    @Test
    fun `rejected paths fail with PathRejected before any request`() = test {
        file("a.txt", BYTES)
        Files.write(outside.resolve("outside.txt"), BYTES)
        val client = reader()
        client.stat("a.txt")
        val before = server.requests()
        val bad = listOf("../x", "a/../b", "a/..", "..", "./x", "a/./b", ".", "a\u0000b") +
            listOf("$outside/outside.txt", "$root-sibling", "/", "$root/../${outside.fileName}/outside.txt")
        bad.forEach { path ->
            val shown = path.replace('\u0000', '0')
            assertFailure(SftpFailure.PathRejected, "list <$shown>") { client.list(path) }
            assertFailure(SftpFailure.PathRejected, "stat <$shown>") { client.stat(path) }
            assertFailure(SftpFailure.PathRejected, "lstat <$shown>") { client.lstat(path) }
            assertFailure(SftpFailure.PathRejected, "readLink <$shown>") { client.readLink(path) }
            assertFailure(SftpFailure.PathRejected, "read <$shown>") { client.read(path, 1024) }
            assertFailure(SftpFailure.PathRejected, "download <$shown>") { client.download(path, ByteArrayOutputStream()) }
        }
        assertEquals(before, server.requests(), "No request may reach the server")
    }

    @Test
    fun `relative and absolute paths inside the root reach the same file`() = test {
        file("a.txt", BYTES)
        val client = reader()
        assertContentEquals(BYTES, client.read("a.txt", 1024))
        assertContentEquals(BYTES, client.read("$root/a.txt", 1024))
        assertContentEquals(BYTES, client.read("$root//a.txt", 1024))
    }

    @Test
    fun `a root of slash gives the whole server, with the same segment rules`() = test {
        file("a.txt", BYTES)
        val whole = reader("/")
        assertEquals("/", whole.root)
        assertContentEquals(BYTES, whole.read("$root/a.txt", 1024))
        assertFailure(SftpFailure.PathRejected, "segment rule") { whole.stat("/a/../b") }
    }

    @Test
    fun `the root is reported normalised`() = test {
        assertEquals(root.toString(), reader("$root/").root)
    }

    @Test
    fun `a malformed root is rejected before a channel opens`() = test {
        val session = session()
        val before = server.requests()
        listOf("relative", "", "$root/../x", "/a\u0000").forEach { bad ->
            assertFailure(SftpFailure.PathRejected, "root") { openSftpReader(session, bad) }
        }
        assertEquals(before, server.requests())
    }

    // Lifecycle

    @Test
    fun `close can be called twice and leaves the session usable`() = test {
        file("a.txt", BYTES)
        val session = session()
        val first = reader(session = session)
        first.close()
        first.close()
        assertTrue(session.connected.value)
        assertContentEquals(BYTES, reader(session = session).read("a.txt", 1024))
    }

    @Test
    fun `a closed reader refuses calls without reaching the server`() = test {
        file("a.txt", BYTES)
        val client = reader()
        client.close()
        val before = server.requests()
        assertFailsWith<IllegalStateException> { client.read("a.txt", 1024) }
        assertEquals(before, server.requests())
    }

    @Test
    fun `closing the session closes the reader, and calls fail with Disconnected`() = test {
        file("a.txt", BYTES)
        val session = session()
        val client = reader(session = session)
        session.close()
        assertFailsWith<SshException.Disconnected> { client.read("a.txt", 1024) }
        assertFailsWith<SshException.Disconnected> { client.list("") }
    }

    @Test
    fun `a connection dropped by the server fails calls with Disconnected`() = test {
        file("a.txt", BYTES)
        val session = session()
        val client = reader(session = session)
        assertContentEquals(BYTES, client.read("a.txt", 1024))
        server.dropAll()
        eventually("session lost") { !session.connected.value }
        assertFailsWith<SshException.Disconnected> { client.read("a.txt", 1024) }
    }

    @Test
    fun `a connection dropped by the server during a download fails it with Disconnected`() = test {
        file("large.bin", LARGE)
        repeat(DROP_ROUNDS) {
            val client = reader()
            var dropped = false
            assertFailsWith<SshException.Disconnected> {
                client.download("large.bin", ByteArrayOutputStream()) {
                    if (!dropped) {
                        dropped = true
                        server.dropAll()
                    }
                }
            }
        }
    }

    @Test
    fun `a session closed during a download fails it with Disconnected`() = test {
        file("large.bin", LARGE)
        repeat(DROP_ROUNDS) {
            val session = session()
            val client = reader(session = session)
            assertFailsWith<SshException.Disconnected> {
                client.download("large.bin", ByteArrayOutputStream()) { session.close() }
            }
        }
    }

    @Test
    fun `opening on a closed session fails with Disconnected`() = test {
        val session = session()
        session.close()
        assertFailsWith<SshException.Disconnected> { openSftpReader(session, root.toString()) }
    }

    @Test
    fun `the SFTP channel closes with the reader`() = test {
        val session = session()
        val client = reader(session = session)
        client.list("")
        client.close()
        // A second reader on the same session still works after the first is gone.
        reader(session = session).list("")
        assertTrue(session.connected.value)
    }

    private class TrackedSink(sink: OutputStream) : FilterOutputStream(sink) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        val TEST_TIMEOUT = 45.seconds

        /** A race shows up only now and then, so the disconnect tests run several times. */
        const val DROP_ROUNDS = 10

        /** With [LONG_NAME], enough for the server to answer READDIR in many batches. */
        const val MANY_ENTRIES = 600
        val LONG_NAME = "uzun-dosya-adı-".repeat(4)
        val ACCEPT = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }
        val BYTES = byteArrayOf(0, 1, -1, 127, -128, 10, 13)

        /** Big enough for several network reads; not a multiple of any usual chunk size. */
        val LARGE = ByteArray(1024 * 1024 + 7) { (it * 31 + 7).toByte() }

        suspend fun eventually(what: String, within: Duration = 5.seconds, condition: () -> Boolean) {
            val started = TimeSource.Monotonic.markNow()
            while (!condition()) {
                if (started.elapsedNow() > within) throw AssertionError("Not within $within: $what")
                delay(20.milliseconds)
            }
        }
    }
}
