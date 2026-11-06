package io.github.thewisekd.phopencode.core.ssh.tunnel

import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout

/**
 * Bounds checks on the tunnel streams. They must not rely on `Objects.checkFromIndexSize`, which
 * Android has only from API 30 while minSdk is 26.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TunnelStreamBoundsTest {
    private val closeables = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    private val server = TunnelSshServer().closeLater()
    private val echo = EchoServer().closeLater()
    private val session: SshSession = runBlocking { connectWithTunnel(server) }.closeLater()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    private fun connected(): Socket =
        session.socketFactory().createSocket().closeLater().apply {
            connect(InetSocketAddress(LOOPBACK, echo.port), 5_000)
            // A read that got past the check would wait without limit and trip the class timeout.
            soTimeout = 0
        }

    private fun Socket.readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var done = 0
        while (done < count) {
            val read = getInputStream().read(out, done, count - done)
            if (read < 0) error("Stream ended after $done bytes")
            done += read
        }
        return out
    }

    @Test
    fun `read refuses bad ranges before waiting for data`() {
        val input = connected().getInputStream()
        val buffer = ByteArray(8)
        for ((off, len) in BAD_RANGES) {
            assertFailsWith<IndexOutOfBoundsException>("off=$off len=$len") { input.read(buffer, off, len) }
        }
    }

    @Test
    fun `read refuses bad ranges without consuming buffered bytes`() {
        val socket = connected()
        socket.getOutputStream().apply { write(byteArrayOf(1, 2, 3)); flush() }
        assertTrue(eventually { socket.getInputStream().available() == 3 })
        val buffer = ByteArray(8)
        for ((off, len) in BAD_RANGES) {
            assertFailsWith<IndexOutOfBoundsException>("off=$off len=$len") {
                socket.getInputStream().read(buffer, off, len)
            }
        }
        assertEquals(3, socket.getInputStream().available())
        assertContentEquals(byteArrayOf(1, 2, 3), socket.readExactly(3))
    }

    @Test
    fun `write refuses bad ranges and sends nothing`() {
        val socket = connected()
        val output = socket.getOutputStream()
        val buffer = ByteArray(8) { 0x55 }
        for ((off, len) in BAD_RANGES) {
            assertFailsWith<IndexOutOfBoundsException>("off=$off len=$len") { output.write(buffer, off, len) }
        }
        output.apply { write(byteArrayOf(7, 8)); flush() }
        assertContentEquals(byteArrayOf(7, 8), socket.readExactly(2))
        Thread.sleep(200)
        assertEquals(0, socket.getInputStream().available())
    }

    @Test
    fun `zero-length read returns 0 and zero-length write sends nothing`() {
        val socket = connected()
        val buffer = ByteArray(4)
        assertEquals(0, socket.getInputStream().read(buffer, 0, 0))
        assertEquals(0, socket.getInputStream().read(buffer, 4, 0))
        assertEquals(0, socket.getInputStream().read(ByteArray(0), 0, 0))

        socket.getOutputStream().apply {
            write(buffer, 0, 0)
            write(buffer, 4, 0)
            write(ByteArray(0))
            write(byteArrayOf(9))
            flush()
        }
        assertEquals(9, socket.getInputStream().read())
        Thread.sleep(200)
        assertEquals(0, socket.getInputStream().available())
    }

    @Test
    fun `in-range reads and writes at the edges of the array still work`() {
        val socket = connected()
        val source = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        socket.getOutputStream().apply {
            write(source, 2, 3)
            write(source, 5, 3)
            write(source, 0, 8)
            flush()
        }
        val target = ByteArray(14)
        var done = 0
        while (done < target.size) {
            val read = socket.getInputStream().read(target, done, target.size - done)
            assertTrue(read > 0)
            done += read
        }
        assertContentEquals(byteArrayOf(2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7), target)
    }

    @Test
    fun `the tunnel package uses no JDK API newer than Android API 26`() {
        val sources = File(SOURCE_DIR)
        assertTrue(sources.isDirectory, "Missing ${sources.absolutePath}")
        val files = sources.listFiles { file -> file.extension == "kt" }.orEmpty()
        assertTrue(files.isNotEmpty(), "No sources in ${sources.absolutePath}")
        val found = files.flatMap { file ->
            file.readLines().withIndex().filterNot { (_, line) -> line.isComment() }.flatMap { (index, line) ->
                NEWER_APIS.filter { it.containsMatchIn(line) }.map { "${file.name}:${index + 1}: ${line.trim()}" }
            }
        }
        assertEquals(emptyList<String>(), found)
    }

    /** KDoc and line comments may name the APIs; only code must not call them. */
    private fun String.isComment(): Boolean = trimStart().let { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }

    private companion object {
        /** Offset and length pairs that do not fit an 8-byte array, including ones whose sum overflows. */
        val BAD_RANGES = listOf(
            -1 to 1,
            -1 to 0,
            0 to -1,
            4 to -1,
            0 to 9,
            8 to 1,
            9 to 0,
            1 to 8,
            Int.MAX_VALUE to 1,
            1 to Int.MAX_VALUE,
            Int.MAX_VALUE to Int.MAX_VALUE,
            Int.MIN_VALUE to 0,
            0 to Int.MIN_VALUE,
        )

        // Gradle runs tests in the module directory.
        const val SOURCE_DIR = "src/main/kotlin/io/github/thewisekd/phopencode/core/ssh/tunnel"

        /** JDK methods that Android lacks at API 26 and that lint cannot see in a JVM module. */
        val NEWER_APIS = listOf(
            Regex("""\bcheckFromIndexSize\b"""),
            Regex("""\bcheckFromToIndex\b"""),
            Regex("""\bObjects\s*\.\s*checkIndex\b"""),
            Regex("""\breadAllBytes\b"""),
            Regex("""\breadNBytes\b"""),
            Regex("""\btransferTo\b"""),
            Regex("""\bnullInputStream\b"""),
            Regex("""\bnullOutputStream\b"""),
            Regex("""\b(List|Set|Map)\s*\.\s*(of|copyOf|ofEntries)\b"""),
            Regex("""\bjava\.util\.concurrent\.Flow\b"""),
            Regex("""\bonSpinWait\b"""),
        )
    }
}
