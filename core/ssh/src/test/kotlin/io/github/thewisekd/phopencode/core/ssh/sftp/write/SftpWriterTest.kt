package io.github.thewisekd.phopencode.core.ssh.sftp.write

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SftpWriteOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import io.github.thewisekd.phopencode.core.ssh.connect.password
import io.github.thewisekd.phopencode.core.ssh.sftp.RealPaths
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SftpWriterTest {
    private val closeables = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    // The first test in the JVM pays for class loading, key generation and the first handshake,
    // which took over 20 s on a loaded Raspberry Pi; the JUnit limit above still ends a hang.
    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(50.seconds) { body() } }

    private lateinit var server: TestSftpServer

    /** The local folder behind the writer's root, `/work`. */
    private lateinit var work: Path

    private suspend fun writer(
        openSshExtensions: String? = null,
        vetoMove: (Path, Path) -> Boolean = { _, _ -> false },
        root: String = "/work",
        hooks: WriteHooks = WriteHooks(),
    ): SftpWriter {
        server = TestSftpServer(openSshExtensions, vetoMove).closeLater()
        work = server.home.resolve("work").createDirectory()
        server.home.resolve("outside").createDirectory().resolve("secret.txt").writeText("outside")
        val session = session()
        return openSftpWriter(session, root, hooks).closeLater()
    }

    private suspend fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for: $what")
            delay(20)
        }
    }

    private suspend fun session(): SshjSession =
        (SshjConnector().connect(server.target, password(), ACCEPT) as SshjSession).closeLater()

    private suspend fun SftpWriter.write(path: String, text: String, options: SftpWriteOptions = SftpWriteOptions()) =
        writeAtomic(path, ByteArrayInputStream(text.toByteArray()), options)

    private suspend fun assertFailure(expected: SftpFailure, block: suspend () -> Unit) {
        val error = assertFailsWith<SshException.Sftp> { block() }
        assertEquals(expected, error.failure)
    }

    /** Fails a test that leaves a temporary, probe or backup file behind. */
    private fun assertNoLeftovers(dir: Path = work) {
        val left = Files.walk(dir).use { paths -> paths.filter { it.name.startsWith(".phopencode-") }.toList() }
        assertEquals(emptyList(), left)
    }

    private fun Path.mode(): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(this))

    private fun Path.setMode(mode: String) {
        Files.setPosixFilePermissions(this, PosixFilePermissions.fromString(mode))
    }

    // Atomic write

    @Test
    fun `writes a new file`() = test {
        val writer = writer()
        writer.write("new.txt", "hello")
        assertEquals("hello", work.resolve("new.txt").readText())
        assertNoLeftovers()
    }

    @Test
    fun `an existing file is not replaced without overwrite`() = test {
        val writer = writer()
        work.resolve("a.txt").writeText("old")
        assertFailure(SftpFailure.AlreadyExists) { writer.write("a.txt", "new") }
        assertEquals("old", work.resolve("a.txt").readText())
        assertNoLeftovers()
    }

    @Test
    fun `overwrite replaces through posix-rename and syncs the data`() = test {
        val writer = writer()
        work.resolve("a.txt").writeText("old content that is longer")
        writer.write("a.txt", "new", SftpWriteOptions(overwrite = true))
        assertEquals("new", work.resolve("a.txt").readText())
        assertTrue("posix-rename@openssh.com" in server.extensions)
        assertTrue("fsync@openssh.com" in server.extensions)
        assertNoLeftovers()
    }

    @Test
    fun `a folder at the target is never replaced`() = test {
        val writer = writer()
        work.resolve("dir").createDirectory()
        assertFailure(SftpFailure.AlreadyExists) { writer.write("dir", "x", SftpWriteOptions(overwrite = true)) }
        assertTrue(work.resolve("dir").isDirectory())
        assertNoLeftovers()
    }

    @Test
    fun `while the data streams the target is untouched and the temporary file is owner-only`() = test {
        val writer = writer()
        val target = work.resolve("a.txt")
        target.writeText("old")
        target.setMode("rw-r--r--")
        var seenTemp = false
        val source = object : InputStream() {
            private var sent = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent == 1) {
                    // The first chunk is on the server by now.
                    assertEquals("old", target.readText())
                    val temps = work.listDirectoryEntries(".phopencode-*")
                    assertEquals(1, temps.size)
                    assertEquals("rw-------", temps.single().mode())
                    seenTemp = true
                }
                if (sent == 2) return -1
                sent++
                b.fill('x'.code.toByte(), off, off + len)
                return len
            }
        }
        writer.writeAtomic("a.txt", source, SftpWriteOptions(overwrite = true))
        assertTrue(seenTemp)
        assertTrue(target.readText().all { it == 'x' })
        assertNoLeftovers()
    }

    @Test
    fun `streams a large upload byte for byte with progress`() = test {
        val writer = writer()
        val size = 6L * 1024 * 1024 + 123
        val progress = mutableListOf<Long>()
        val expected = MessageDigest.getInstance("SHA-256")
        val source = PatternStream(size, expected)
        writer.writeAtomic("big.bin", source, onProgress = { progress += it })
        val actual = MessageDigest.getInstance("SHA-256").digest(work.resolve("big.bin").readBytes())
        assertContentEquals(expected.digest(), actual)
        assertEquals(size, progress.last())
        assertTrue(progress.size > 10)
        assertEquals(progress.sorted(), progress)
        assertFalse(source.closed, "the source belongs to the caller")
    }

    @Test
    fun `an empty source gives an empty file`() = test {
        val writer = writer()
        writer.writeAtomic("empty.txt", ByteArrayInputStream(ByteArray(0)))
        assertEquals(0, Files.size(work.resolve("empty.txt")))
    }

    @Test
    fun `a failing source leaves the target and no temporary file`() = test {
        val writer = writer()
        work.resolve("a.txt").writeText("old")
        val source = object : InputStream() {
            private var calls = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (calls++ > 2) throw IOException("source broke")
                return len
            }
        }
        val error = assertFailsWith<IOException> { writer.writeAtomic("a.txt", source, SftpWriteOptions(overwrite = true)) }
        assertEquals("source broke", error.message)
        assertEquals("old", work.resolve("a.txt").readText())
        assertNoLeftovers()
    }

    @Test
    fun `cancelling stops the upload and removes the temporary file`() = test {
        val writer = writer()
        work.resolve("a.txt").writeText("old")
        val firstChunk = CountDownLatch(1)
        val never = CountDownLatch(1)
        val source = object : InputStream() {
            private var calls = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (calls++ == 0) return len
                firstChunk.countDown()
                never.await() // Interrupted by the cancellation.
                return len
            }
        }
        val upload = async(Dispatchers.Default) {
            writer.writeAtomic("a.txt", source, SftpWriteOptions(overwrite = true))
        }
        runInterruptible(Dispatchers.IO) { assertTrue(firstChunk.await(10, TimeUnit.SECONDS)) }
        upload.cancel()
        assertFailsWith<CancellationException> { upload.await() }
        assertEquals("old", work.resolve("a.txt").readText())
        assertNoLeftovers()
    }

    @Test
    fun `cancelled right after the temporary file opens, its handle is closed and the file removed`() = test {
        lateinit var caller: Job
        val writer = writer(hooks = WriteHooks(afterTempOpened = { caller.cancel() }))
        work.resolve("a.txt").writeText("old")
        withContext(Dispatchers.Default) {
            val upload = async(start = CoroutineStart.LAZY) {
                writer.write("a.txt", "new", SftpWriteOptions(overwrite = true))
            }
            caller = upload
            upload.start()
            assertFailsWith<CancellationException> { upload.await() }
        }
        assertEquals("old", work.resolve("a.txt").readText())
        eventually("handle closed") { server.openHandles() == 0 }
        assertNoLeftovers()
        assertEquals(1, server.openSubsystems())
    }

    @Test
    fun `cancelled after the rename, the write is reported done`() = test {
        lateinit var caller: Job
        val writer = writer(hooks = WriteHooks(afterCommit = { caller.cancel() }))
        work.resolve("a.txt").writeText("old")
        var returned = false
        withContext(Dispatchers.Default) {
            val upload = async(start = CoroutineStart.LAZY) {
                writer.write("a.txt", "new", SftpWriteOptions(overwrite = true))
                returned = true
            }
            caller = upload
            upload.start()
            assertFailsWith<CancellationException> { upload.await() }
        }
        assertTrue(returned, "the write happened, so it must not report a cancellation")
        assertEquals("new", work.resolve("a.txt").readText())
        assertNoLeftovers()
    }

    // Permissions

    @Test
    fun `replacing keeps the mode of the replaced file`() = test {
        val writer = writer()
        work.resolve("a.txt").writeText("old")
        work.resolve("a.txt").setMode("rwxr-x---")
        writer.write("a.txt", "new", SftpWriteOptions(overwrite = true))
        assertEquals("rwxr-x---", work.resolve("a.txt").mode())
    }

    @Test
    fun `given permissions win`() = test {
        val writer = writer()
        work.resolve("a.txt").writeText("old")
        work.resolve("a.txt").setMode("rw-rw-rw-")
        writer.write("a.txt", "secret", SftpWriteOptions(overwrite = true, permissions = SftpWriteOptions.OWNER_ONLY))
        assertEquals("rw-------", work.resolve("a.txt").mode())
        writer.write("b.txt", "x", SftpWriteOptions(permissions = 0b111_101_000))
        assertEquals("rwxr-x---", work.resolve("b.txt").mode())
    }

    @Test
    fun `a new file gets the server's default mode`() = test {
        val writer = writer()
        writer.write("new.txt", "x")
        val reference = Files.createFile(server.home.resolve("reference"))
        assertEquals(reference.mode(), work.resolve("new.txt").mode())
        assertNoLeftovers()
    }

    // Without posix-rename and fsync

    @Test
    fun `without posix-rename overwrite moves the old file aside and leaves nothing behind`() = test {
        val writer = writer(openSshExtensions = "statvfs@openssh.com=2")
        work.resolve("a.txt").writeText("old")
        writer.write("a.txt", "new", SftpWriteOptions(overwrite = true))
        assertEquals("new", work.resolve("a.txt").readText())
        assertFalse("posix-rename@openssh.com" in server.extensions)
        assertFalse("fsync@openssh.com" in server.extensions)
        assertNoLeftovers()
        writer.write("b.txt", "fresh", SftpWriteOptions(overwrite = true))
        assertEquals("fresh", work.resolve("b.txt").readText())
        assertNoLeftovers()
    }

    @Test
    fun `without posix-rename a failed swap puts the old file back`() = test {
        // Only the move of the new data onto the target fails.
        val writer = writer(openSshExtensions = "statvfs@openssh.com=2", vetoMove = { from, to ->
            from.name.endsWith(".tmp") && to.name == "a.txt"
        })
        work.resolve("a.txt").writeText("old")
        assertFailsWith<SshException.Sftp> { writer.write("a.txt", "new", SftpWriteOptions(overwrite = true)) }
        assertEquals("old", work.resolve("a.txt").readText())
        assertNoLeftovers()
    }

    @Test
    fun `a failed rename onto the target leaves it and removes the temporary file`() = test {
        val writer = writer(vetoMove = { from, _ -> from.name.endsWith(".tmp") })
        work.resolve("a.txt").writeText("old")
        assertFailsWith<SshException.Sftp> { writer.write("a.txt", "new", SftpWriteOptions(overwrite = true)) }
        assertEquals("old", work.resolve("a.txt").readText())
        assertNoLeftovers()
    }

    // Symlinks

    @Test
    fun `a symlink target stays a symlink and its file is replaced`() = test {
        val writer = writer()
        work.resolve("real.txt").writeText("old")
        work.resolve("dir").createDirectory()
        work.resolve("dir/link.txt").createSymbolicLinkPointingTo(Path.of("../real.txt"))
        writer.write("dir/link.txt", "new", SftpWriteOptions(overwrite = true))
        assertTrue(work.resolve("dir/link.txt").isSymbolicLink())
        assertEquals("new", work.resolve("real.txt").readText())
        assertNoLeftovers()
    }

    @Test
    fun `a symlink to a missing file inside the root creates that file`() = test {
        val writer = writer()
        work.resolve("link.txt").createSymbolicLinkPointingTo(Path.of("made.txt"))
        writer.write("link.txt", "new", SftpWriteOptions(overwrite = true))
        assertTrue(work.resolve("link.txt").isSymbolicLink())
        assertEquals("new", work.resolve("made.txt").readText())
    }

    @Test
    fun `writing through a symlink that leaves the root is rejected`() = test {
        val writer = writer()
        work.resolve("link.txt").createSymbolicLinkPointingTo(Path.of("../outside/secret.txt"))
        assertFailure(SftpFailure.PathRejected) { writer.write("link.txt", "x", SftpWriteOptions(overwrite = true)) }
        work.resolve("dirlink").createSymbolicLinkPointingTo(Path.of("../outside"))
        assertFailure(SftpFailure.PathRejected) { writer.write("dirlink/secret.txt", "x", SftpWriteOptions(overwrite = true)) }
        assertFailure(SftpFailure.PathRejected) { writer.write("dirlink/new.txt", "x") }
        assertFailure(SftpFailure.PathRejected) { writer.mkdir("dirlink/new") }
        assertFailure(SftpFailure.PathRejected) { writer.mkdir("dirlink/new/deeper", parents = true) }
        assertFailure(SftpFailure.PathRejected) { writer.rename("dirlink/secret.txt", "taken.txt") }
        assertFailure(SftpFailure.PathRejected) { writer.delete("dirlink/secret.txt") }
        assertEquals("outside", server.home.resolve("outside/secret.txt").readText())
        assertEquals(listOf("secret.txt"), server.home.resolve("outside").listDirectoryEntries().map { it.name })
        assertNoLeftovers(server.home)
    }

    @Test
    fun `a dot-dot in a symlink target is resolved after the symlink before it`() = test {
        val writer = writer()
        work.resolve("sub/deep").createDirectories()
        work.resolve("sub/x").writeText("old")
        work.resolve("x").writeText("keep")
        work.resolve("a").createSymbolicLinkPointingTo(Path.of("sub/deep"))
        // The kernel reads b as sub/deep/../x, that is sub/x; text would give x.
        work.resolve("b").createSymbolicLinkPointingTo(Path.of("a/../x"))
        writer.write("b", "new", SftpWriteOptions(overwrite = true))
        assertEquals("new", work.resolve("b").readText())
        assertEquals("new", work.resolve("sub/x").readText())
        assertEquals("keep", work.resolve("x").readText())
        assertTrue(work.resolve("b").isSymbolicLink())
        assertNoLeftovers()
    }

    @Test
    fun `a symlink target that leaves the root through dot-dot is rejected`() = test {
        val writer = writer()
        work.resolve("sub").createDirectory()
        work.resolve("out").createSymbolicLinkPointingTo(Path.of("../outside"))
        work.resolve("one").createSymbolicLinkPointingTo(Path.of("sub"))
        // By text this stays inside (work/work/y); really it is outside/../work/y, through outside.
        work.resolve("sneaky").createSymbolicLinkPointingTo(Path.of("out/../work/y"))
        // By text and really, this leaves the root: sub/.. is work, and work/.. is above it.
        work.resolve("up").createSymbolicLinkPointingTo(Path.of("one/../../outside/secret.txt"))
        assertFailure(SftpFailure.PathRejected) { writer.write("sneaky", "x", SftpWriteOptions(overwrite = true)) }
        assertFailure(SftpFailure.PathRejected) { writer.write("up", "x", SftpWriteOptions(overwrite = true)) }
        assertFailure(SftpFailure.PathRejected) { writer.mkdir("sneaky/z", parents = true) }
        assertEquals("outside", server.home.resolve("outside/secret.txt").readText())
        assertFalse(work.resolve("work").exists())
        assertFalse(server.home.resolve("work/y").exists())
        assertNoLeftovers(server.home)
    }

    @Test
    fun `a file in the middle of a symlink target is not a folder to step back from`() = test {
        val writer = writer()
        work.resolve("regular").writeText("file")
        work.resolve("victim").writeText("keep")
        work.resolve("sub").createDirectory()
        work.resolve("sub/x").writeText("keep")
        // The kernel fails these (ENOTDIR); dropping regular/.. as text would reach victim and sub.
        work.resolve("b").createSymbolicLinkPointingTo(Path.of("regular/../victim"))
        work.resolve("c").createSymbolicLinkPointingTo(Path.of("regular/../sub"))
        assertFailure(SftpFailure.NotFound) { writer.write("b", "new", SftpWriteOptions(overwrite = true)) }
        assertFailure(SftpFailure.NotFound) { writer.mkdir("c/new") }
        assertFailure(SftpFailure.NotFound) { writer.mkdir("c/new", parents = true) }
        assertFailure(SftpFailure.NotFound) { writer.rename("c/x", "moved") }
        assertFailure(SftpFailure.NotFound) { writer.delete("c/x") }
        assertFailure(SftpFailure.NotFound) { writer.write("c/x", "new", SftpWriteOptions(overwrite = true)) }
        assertEquals("keep", work.resolve("victim").readText())
        assertEquals("keep", work.resolve("sub/x").readText())
        assertFalse(work.resolve("sub/new").exists())
        assertFalse(work.resolve("moved").exists())
        assertNoLeftovers()
    }

    @Test
    fun `a chain of exactly the symlink limit resolves, one more fails`() = test {
        val writer = writer()
        work.resolve("real.txt").writeText("old")
        fun chain(prefix: String, links: Int) {
            for (i in 1..links) {
                val target = if (i == links) "real.txt" else "$prefix${i + 1}"
                work.resolve("$prefix$i").createSymbolicLinkPointingTo(Path.of(target))
            }
        }
        chain("ok", RealPaths.MAX_SYMLINKS)
        chain("long", RealPaths.MAX_SYMLINKS + 1)
        writer.write("ok1", "new", SftpWriteOptions(overwrite = true))
        assertEquals("new", work.resolve("real.txt").readText())
        assertFailure(SftpFailure.Failed) { writer.write("long1", "newer", SftpWriteOptions(overwrite = true)) }
        assertEquals("new", work.resolve("real.txt").readText())
        assertNoLeftovers()
    }

    @Test
    fun `parents may pass through a symlink whose target has a dot-dot`() = test {
        val writer = writer()
        work.resolve("sub/deep").createDirectories()
        work.resolve("a").createSymbolicLinkPointingTo(Path.of("sub/deep"))
        work.resolve("b").createSymbolicLinkPointingTo(Path.of("a/.."))
        writer.mkdir("b/made", parents = true)
        assertTrue(work.resolve("sub/made").isDirectory())
        assertFalse(work.resolve("made").exists())
    }

    // Delete

    @Test
    fun `deletes a file and an empty folder`() = test {
        val writer = writer()
        work.resolve("a.txt").writeText("x")
        work.resolve("empty").createDirectory()
        writer.delete("a.txt")
        writer.delete("empty")
        assertFalse(work.resolve("a.txt").exists())
        assertFalse(work.resolve("empty").exists())
    }

    @Test
    fun `a folder with content needs recursive`() = test {
        val writer = writer()
        work.resolve("dir/sub").createDirectories()
        work.resolve("dir/sub/a.txt").writeText("x")
        assertFailure(SftpFailure.NotEmpty) { writer.delete("dir") }
        assertTrue(work.resolve("dir/sub/a.txt").exists())
        writer.delete("dir", recursive = true)
        assertFalse(work.resolve("dir").exists())
    }

    @Test
    fun `deleting a symlink removes only the link`() = test {
        val writer = writer()
        work.resolve("out").createSymbolicLinkPointingTo(Path.of("../outside"))
        work.resolve("file").createSymbolicLinkPointingTo(Path.of("../outside/secret.txt"))
        writer.delete("out", recursive = true)
        writer.delete("file")
        assertFalse(Files.exists(work.resolve("out"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(work.resolve("file"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("outside", server.home.resolve("outside/secret.txt").readText())
    }

    @Test
    fun `recursive delete does not follow symlinks`() = test {
        val writer = writer()
        work.resolve("dir/sub").createDirectories()
        work.resolve("dir/sub/link").createSymbolicLinkPointingTo(Path.of("../../../outside"))
        work.resolve("dir/filelink").createSymbolicLinkPointingTo(Path.of("../../outside/secret.txt"))
        writer.delete("dir", recursive = true)
        assertFalse(work.resolve("dir").exists())
        assertEquals("outside", server.home.resolve("outside/secret.txt").readText())
    }

    @Test
    fun `deleting what is missing fails with NotFound`() = test {
        val writer = writer()
        assertFailure(SftpFailure.NotFound) { writer.delete("missing.txt") }
        assertFailure(SftpFailure.NotFound) { writer.delete("missing/a.txt") }
    }

    @Test
    fun `the root itself cannot be deleted, renamed or written`() = test {
        val writer = writer()
        assertFailure(SftpFailure.PathRejected) { writer.delete("", recursive = true) }
        assertFailure(SftpFailure.PathRejected) { writer.delete("/work", recursive = true) }
        assertFailure(SftpFailure.PathRejected) { writer.rename("/work", "x") }
        assertFailure(SftpFailure.PathRejected) { writer.write("/work", "x", SftpWriteOptions(overwrite = true)) }
        assertTrue(work.isDirectory())
    }

    // Rename

    @Test
    fun `renames a file and a folder`() = test {
        val writer = writer()
        work.resolve("a.txt").writeText("a")
        work.resolve("dir").createDirectory()
        writer.rename("a.txt", "dir/b.txt")
        writer.rename("dir", "moved")
        assertEquals("a", work.resolve("moved/b.txt").readText())
        assertFalse(work.resolve("a.txt").exists())
    }

    @Test
    fun `rename does not replace without overwrite`() = test {
        val writer = writer()
        work.resolve("a.txt").writeText("a")
        work.resolve("b.txt").writeText("b")
        assertFailure(SftpFailure.AlreadyExists) { writer.rename("a.txt", "b.txt") }
        assertEquals("a", work.resolve("a.txt").readText())
        assertEquals("b", work.resolve("b.txt").readText())
        writer.rename("a.txt", "b.txt", overwrite = true)
        assertEquals("a", work.resolve("b.txt").readText())
        assertFalse(work.resolve("a.txt").exists())
    }

    @Test
    fun `rename with overwrite works without posix-rename`() = test {
        val writer = writer(openSshExtensions = "statvfs@openssh.com=2")
        work.resolve("a.txt").writeText("a")
        work.resolve("b.txt").writeText("b")
        writer.rename("a.txt", "b.txt", overwrite = true)
        assertEquals("a", work.resolve("b.txt").readText())
        assertFalse(work.resolve("a.txt").exists())
        assertNoLeftovers()
    }

    @Test
    fun `renaming what is missing fails with NotFound`() = test {
        val writer = writer()
        assertFailure(SftpFailure.NotFound) { writer.rename("missing.txt", "b.txt") }
    }

    @Test
    fun `renaming a symlink moves the link`() = test {
        val writer = writer()
        work.resolve("link").createSymbolicLinkPointingTo(Path.of("../outside/secret.txt"))
        writer.rename("link", "moved")
        assertTrue(work.resolve("moved").isSymbolicLink())
    }

    // Mkdir

    @Test
    fun `creates a folder`() = test {
        val writer = writer()
        writer.mkdir("dir")
        assertTrue(work.resolve("dir").isDirectory())
        assertFailure(SftpFailure.AlreadyExists) { writer.mkdir("dir") }
        assertFailure(SftpFailure.NotFound) { writer.mkdir("missing/dir") }
    }

    @Test
    fun `creates parents and accepts an existing folder`() = test {
        val writer = writer()
        work.resolve("a").createDirectory()
        writer.mkdir("a/b/c d/ğüşiöç", parents = true)
        assertTrue(work.resolve("a/b/c d/ğüşiöç").isDirectory())
        writer.mkdir("a/b", parents = true)
        work.resolve("file").writeText("x")
        assertFailure(SftpFailure.AlreadyExists) { writer.mkdir("file", parents = true) }
        assertFailure(SftpFailure.AlreadyExists) { writer.mkdir("file/sub", parents = true) }
    }

    @Test
    fun `parents may pass through a symlinked folder inside the root`() = test {
        val writer = writer()
        work.resolve("real").createDirectory()
        work.resolve("alias").createSymbolicLinkPointingTo(Path.of("real"))
        writer.mkdir("alias/x/y", parents = true)
        assertTrue(work.resolve("real/x/y").isDirectory())
    }

    // Names

    @Test
    fun `Turkish names and names with spaces round-trip`() = test {
        val writer = writer()
        writer.mkdir("Klasör adı")
        writer.write("Klasör adı/çalışma ğüş İı.txt", "içerik")
        assertEquals("içerik", work.resolve("Klasör adı/çalışma ğüş İı.txt").readText())
        writer.rename("Klasör adı/çalışma ğüş İı.txt", "Klasör adı/Şimdi öteki.txt")
        assertEquals("içerik", work.resolve("Klasör adı/Şimdi öteki.txt").readText())
        writer.delete("Klasör adı", recursive = true)
        assertFalse(work.resolve("Klasör adı").exists())
    }

    // Path traversal

    @Test
    fun `paths that escape the root are rejected before any request`() = test {
        val writer = writer()
        val bad = listOf("../outside/secret.txt", "a/../../outside/secret.txt", "/outside/secret.txt",
            "/workother/x", "./a", "a\u0000b", "/")
        val before = server.requestCount()
        for (path in bad) {
            assertFailure(SftpFailure.PathRejected) { writer.write(path, "x", SftpWriteOptions(overwrite = true)) }
            assertFailure(SftpFailure.PathRejected) { writer.delete(path, recursive = true) }
            assertFailure(SftpFailure.PathRejected) { writer.mkdir(path, parents = true) }
            assertFailure(SftpFailure.PathRejected) { writer.rename(path, "ok") }
            assertFailure(SftpFailure.PathRejected) { writer.rename("ok", path, overwrite = true) }
        }
        assertEquals(before, server.requestCount())
        assertEquals("outside", server.home.resolve("outside/secret.txt").readText())
    }

    @Test
    fun `encoded dots are a plain name inside the root`() = test {
        val writer = writer()
        writer.mkdir("%2e%2e")
        writer.write("%2e%2e/x.txt", "x")
        assertEquals("x", work.resolve("%2e%2e/x.txt").readText())
    }

    @Test
    fun `absolute paths inside the root work`() = test {
        val writer = writer()
        writer.write("/work/abs.txt", "x")
        assertEquals("x", work.resolve("abs.txt").readText())
    }

    @Test
    fun `a root reached through a symlink is its real folder`() = test {
        server = TestSftpServer().closeLater()
        work = server.home.resolve("work").createDirectory()
        server.home.resolve("alias").createSymbolicLinkPointingTo(Path.of("work"))
        val writer = openSftpWriter(session(), "/alias").closeLater()
        writer.write("a.txt", "x")
        writer.mkdir("d/e", parents = true)
        assertEquals("x", work.resolve("a.txt").readText())
        assertTrue(work.resolve("d/e").isDirectory())
    }

    @Test
    fun `a missing root fails to open`() = test {
        server = TestSftpServer().closeLater()
        assertFailure(SftpFailure.NotFound) { openSftpWriter(session(), "/missing") }
        assertFailure(SftpFailure.PathRejected) { openSftpWriter(session(), "relative") }
    }

    // Lifecycle

    @Test
    fun `a caller cancelled at hand-over gets no writer, and its channel is closed`() = test {
        server = TestSftpServer().closeLater()
        work = server.home.resolve("work").createDirectory()
        val session = session()
        lateinit var caller: Job
        withContext(Dispatchers.Default) {
            val opening = async(start = CoroutineStart.LAZY) {
                openSftpWriter(session, "/work", beforeHandOver = { caller.cancel() }).closeLater()
            }
            caller = opening
            opening.start()
            assertFailsWith<CancellationException> { opening.await() }
        }
        eventually("channel closed") { server.openSubsystems() == 0 }
        assertTrue(session.connected.value)
        openSftpWriter(session, "/work").closeLater().write("a.txt", "x")
    }

    @Test
    fun `closing the writer keeps the session, closing the session ends the writer`() = test {
        server = TestSftpServer().closeLater()
        work = server.home.resolve("work").createDirectory()
        val session = session()
        val first = openSftpWriter(session, "/work")
        first.close()
        first.close()
        assertTrue(session.connected.value)
        assertFailsWith<SshException.Disconnected> { first.write("a.txt", "x") }

        val second = openSftpWriter(session, "/work")
        second.write("a.txt", "x")
        session.close()
        assertFailsWith<SshException.Disconnected> { second.write("b.txt", "x") }
        assertFailsWith<SshException.Disconnected> { openSftpWriter(session, "/work") }
    }

    private class PatternStream(private val size: Long, private val digest: MessageDigest) : InputStream() {
        private var position = 0L
        var closed = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= size) return -1
            val n = minOf(len.toLong(), size - position, 7919).toInt()
            for (i in 0 until n) b[off + i] = ((position + i) * 31 % 251).toByte()
            digest.update(b, off, n)
            position += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val ACCEPT = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }
    }
}
