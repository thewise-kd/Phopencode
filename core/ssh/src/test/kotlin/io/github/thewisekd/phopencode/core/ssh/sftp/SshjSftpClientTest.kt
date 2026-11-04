package io.github.thewisekd.phopencode.core.ssh.sftp

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SftpWriteOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.ssh.connect.password
import io.github.thewisekd.phopencode.core.ssh.sftp.write.TestSftpServer
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/** The composite [SftpClient] the default connector opens: reads and writes over one channel. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SshjSftpClientTest {
    private val closeables = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(50.seconds) { body() } }

    private val server = TestSftpServer().closeLater()

    /** The local folder behind `/work`. */
    private val work: Path = server.home.resolve("work").createDirectory()

    private suspend fun session(): SshSession = SshjConnector().connect(server.target, password(), ACCEPT).closeLater()

    private suspend fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for: $what")
            delay(20)
        }
    }

    private suspend fun assertFailure(expected: SftpFailure, block: suspend () -> Unit) {
        assertEquals(expected, assertFailsWith<SshException.Sftp> { block() }.failure)
    }

    private fun bytes(text: String) = ByteArrayInputStream(text.encodeToByteArray())

    @Test
    fun `reads and writes share one SFTP channel`() = test {
        work.resolve("a.txt").writeText("old")
        val client = session().openSftp("/work").closeLater()
        assertEquals("/work", client.root)
        assertEquals(listOf("a.txt"), client.list("").map { it.name })
        client.writeAtomic("b.txt", bytes("new"))
        assertEquals("new", client.read("b.txt", 1024).decodeToString())
        client.mkdir("folder")
        assertEquals(1, server.openSubsystems())
    }

    @Test
    fun `closing twice closes the channel once and leaves the session usable`() = test {
        work.resolve("a.txt").writeText("text")
        val session = session()
        val client = session.openSftp("/work")
        client.close()
        client.close()
        eventually("the channel closed") { server.openSubsystems() == 0 }
        assertTrue(session.connected.value)
        val next = session.openSftp("/work").closeLater()
        assertEquals("text", next.read("a.txt", 1024).decodeToString())
    }

    @Test
    fun `a closed client refuses reads and writes alike`() = test {
        val client = session().openSftp("/work")
        client.close()
        assertFailsWith<IllegalStateException> { client.list("") }
        assertFailsWith<IllegalStateException> { client.writeAtomic("a.txt", bytes("x")) }
    }

    @Test
    fun `closing the session closes the channel, and both halves report Disconnected`() = test {
        val session = session()
        val client = session.openSftp("/work").closeLater()
        session.close()
        eventually("the channel closed") { server.openSubsystems() == 0 }
        assertFailsWith<SshException.Disconnected> { client.list("") }
        assertFailsWith<SshException.Disconnected> { client.writeAtomic("a.txt", bytes("x")) }
        assertFalse(work.resolve("a.txt").exists())
    }

    @Test
    fun `home is the login folder inside the root, and the root otherwise`() = test {
        val session = session()
        // MINA starts at the server's `/` unless told otherwise.
        assertEquals("/", session.openSftp("/").closeLater().home())
        assertEquals("/work", session.openSftp("/work").closeLater().home())

        work.resolve("user").createDirectory()
        server.sftpHome = "/work/user"
        assertEquals("/work/user", session.openSftp("/work").closeLater().home())
        assertEquals("/work/user", session.openSftp("/").closeLater().home())
        assertEquals("/work/user", session.openSftp("/work/user").closeLater().home())
    }

    @Test
    fun `home through a symlinked root is given under the root as configured`() = test {
        work.resolve("user").createDirectory()
        server.home.resolve("linked").createSymbolicLinkPointingTo(work)
        server.sftpHome = "/work/user"
        val session = session()
        assertEquals("/linked/user", session.openSftp("/linked").closeLater().home())
        server.sftpHome = "/linked/user"
        assertEquals("/linked/user", session.openSftp("/linked").closeLater().home())
        assertEquals("/work/user", session.openSftp("/work").closeLater().home())
    }

    @Test
    fun `a home that leaves the root through a symlink gives the root`() = test {
        val outside = server.home.resolve("outside").createDirectory()
        work.resolve("out").createSymbolicLinkPointingTo(outside)
        server.sftpHome = "/work/out"
        assertEquals("/work", session().openSftp("/work").closeLater().home())
    }

    @Test
    fun `home of a closed client fails, and of a closed session reports Disconnected`() = test {
        val session = session()
        val closed = session.openSftp("/work")
        closed.close()
        assertFailsWith<IllegalStateException> { closed.home() }
        val client = session.openSftp("/work").closeLater()
        session.close()
        eventually("the channel closed") { server.openSubsystems() == 0 }
        assertFailsWith<SshException.Disconnected> { client.home() }
    }

    @Test
    fun `a missing root fails with NotFound and leaves no channel`() = test {
        val session = session()
        assertFailure(SftpFailure.NotFound) { session.openSftp("/missing") }
        eventually("the channel closed") { server.openSubsystems() == 0 }
    }

    @Test
    fun `a root that is a file fails with Sftp and leaves no channel`() = test {
        work.resolve("a.txt").writeText("text")
        val session = session()
        assertFailsWith<SshException.Sftp> { session.openSftp("/work/a.txt") }
        eventually("the channel closed") { server.openSubsystems() == 0 }
    }

    @Test
    fun `a malformed root fails before any channel opens`() = test {
        val session = session()
        assertFailure(SftpFailure.PathRejected) { session.openSftp("work") }
        assertFailure(SftpFailure.PathRejected) { session.openSftp("/work/../etc") }
        assertEquals(0, server.requestCount())
        assertEquals(0, server.openSubsystems())
    }

    @Test
    fun `reads and writes share one path policy, checked before any request`() = test {
        val client = session().openSftp("/work").closeLater()
        val before = server.requestCount()
        for (path in listOf("a\uD800b", "../x", "a\u0000b", "/outside/x")) {
            assertFailure(SftpFailure.PathRejected) { client.stat(path) }
            assertFailure(SftpFailure.PathRejected) { client.read(path, 1024) }
            assertFailure(SftpFailure.PathRejected) { client.writeAtomic(path, bytes("x")) }
            assertFailure(SftpFailure.PathRejected) { client.delete(path) }
            assertFailure(SftpFailure.PathRejected) { client.mkdir(path) }
        }
        assertEquals(before, server.requestCount())
    }

    @Test
    fun `reads and changes through a symlink out of the root are both rejected`() = test {
        val outside = server.home.resolve("outside").createDirectory()
        outside.resolve("secret.txt").writeText("outside")
        // Relative: the server's file system is rooted at its home, the link lives on the real one.
        work.resolve("out").createSymbolicLinkPointingTo(Path.of("../outside"))
        work.resolve("in.txt").writeText("inside")
        work.resolve("in").createSymbolicLinkPointingTo(Path.of("in.txt"))
        val client = session().openSftp("/work").closeLater()
        assertFailure(SftpFailure.PathRejected) { client.read("out/secret.txt", 1024) }
        assertFailure(SftpFailure.PathRejected) { client.stat("out/secret.txt") }
        assertFailure(SftpFailure.PathRejected) { client.stat("out") }
        assertFailure(SftpFailure.PathRejected) { client.list("out") }
        assertEquals("inside", client.read("in", 1024).decodeToString())
        assertFailure(SftpFailure.PathRejected) {
            client.writeAtomic("out/secret.txt", bytes("changed"), SftpWriteOptions(overwrite = true))
        }
        assertFailure(SftpFailure.PathRejected) { client.delete("out/secret.txt") }
        assertEquals("outside", outside.resolve("secret.txt").readText())
    }

    @Test
    fun `a failing source fails the write with Sftp, its message redacted, and the target untouched`() = test {
        work.resolve("a.txt").writeText("old")
        val client = session().openSftp("/work").closeLater()
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("caller detail /home/someone")
        }
        val failure = assertFailsWith<SshException.Sftp> {
            client.writeAtomic("a.txt", failing, SftpWriteOptions(overwrite = true))
        }
        assertEquals(SftpFailure.Failed, failure.failure)
        val texts = generateSequence<Throwable>(failure) { it.cause }.map { it.toString() + it.message }.toList()
        assertTrue(texts.none { "someone" in it }, "The source's message leaked: $texts")
        assertEquals("old", work.resolve("a.txt").readText())
        assertEquals(listOf("a.txt"), client.list("").map { it.name })
    }

    @Test
    fun `a failure of the caller's progress callback passes through unchanged`() = test {
        val client = session().openSftp("/work").closeLater()
        val mine = IllegalStateException("mine")
        val thrown = assertFailsWith<IllegalStateException> {
            client.writeAtomic("a.txt", bytes("x")) { throw mine }
        }
        assertEquals(mine, thrown)
        assertContentEquals(emptyList(), client.list("").map { it.name })
    }

    @Test
    fun `an IOException from the progress callback of a write passes through unchanged`() = test {
        work.resolve("a.txt").writeText("old")
        val client = session().openSftp("/work").closeLater()
        val mine = IOException("mine")
        val thrown = assertFailsWith<IOException> {
            client.writeAtomic("a.txt", bytes("new"), SftpWriteOptions(overwrite = true)) { throw mine }
        }
        assertPassedThrough(mine, thrown)
        assertEquals("old", work.resolve("a.txt").readText())
        assertEquals(listOf("a.txt"), client.list("").map { it.name })
    }

    @Test
    fun `an IOException from the progress callback of a download passes through unchanged`() = test {
        work.resolve("a.txt").writeText("text")
        val client = session().openSftp("/work").closeLater()
        val mine = IOException("mine")
        val thrown = assertFailsWith<IOException> {
            client.download("a.txt", java.io.ByteArrayOutputStream()) { throw mine }
        }
        assertPassedThrough(mine, thrown)
    }

    /**
     * [thrown] is [original], or the copy that kotlinx.coroutines' stack trace recovery (on in
     * tests, which run with assertions) makes across a dispatcher switch: same type, original as
     * its cause.
     */
    private fun assertPassedThrough(original: Throwable, thrown: Throwable) {
        if (thrown === original) return
        assertEquals(original.javaClass, thrown.javaClass)
        assertSame(original, thrown.cause)
    }

    private companion object {
        val ACCEPT = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }
    }
}
