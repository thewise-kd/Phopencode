package io.github.thewisekd.phopencode.core.ssh.exec

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessHeaderTest {
    private fun read(text: String) = ByteArrayInputStream(text.encodeToByteArray()).let { input ->
        val header = ProcessHeader.read(input)
        Triple(header.pid, header.leftover.decodeToString(), input.readBytes().decodeToString())
    }

    @Test
    fun `a complete header gives the pid and consumes only itself`() {
        assertEquals(Triple(4242L, "", "output"), read("phopencode-exec-pid=4242\noutput"))
    }

    @Test
    fun `output without the header is kept`() {
        assertEquals(Triple(null, "h", "ello"), read("hello"))
        assertEquals(Triple(null, "", ""), read(""))
    }

    @Test
    fun `a header cut short is kept as output`() {
        assertEquals(Triple(null, "phopencode-exec-pid=12", ""), read("phopencode-exec-pid=12"))
        assertEquals(Triple(null, "phopencode-exec", ""), read("phopencode-exec"))
    }

    @Test
    fun `a malformed number is kept as output`() {
        assertEquals(Triple(null, "phopencode-exec-pid=12x", "\nrest"), read("phopencode-exec-pid=12x\nrest"))
        assertEquals(Triple(null, "phopencode-exec-pid=\n", "rest"), read("phopencode-exec-pid=\nrest"))
        assertEquals(
            Triple(null, "phopencode-exec-pid=12345678901", "\n"),
            read("phopencode-exec-pid=12345678901\n"),
        )
    }

    @Test
    fun `pids 0 and 1 are consumed but never killed`() {
        assertEquals(Triple(null, "", "x"), read("phopencode-exec-pid=1\nx"))
        assertEquals(Triple(null, "", "x"), read("phopencode-exec-pid=0\nx"))
    }

    @Test
    fun `the wrapper quotes the command as one word`() {
        val wrapped = ProcessHeader.wrap("echo 'a'; rm -rf x")
        assertTrue(wrapped.endsWith(" phopencode-exec 'echo '\\''a'\\''; rm -rf x'"), wrapped)
    }

    @Test
    fun `the kill command targets the group, then the process`() {
        assertEquals(
            "kill -s KILL -- -321 2>/dev/null || kill -KILL -321 2>/dev/null || kill -s KILL 321 2>/dev/null",
            ProcessHeader.killCommand(321),
        )
        assertFailsWith<IllegalArgumentException> { ProcessHeader.killCommand(1) }
    }

    @Test
    fun `leftover bytes are exact`() {
        val bytes = byteArrayOf('p'.code.toByte(), -1, 0)
        val header = ProcessHeader.read(ByteArrayInputStream(bytes))
        assertNull(header.pid)
        assertContentEquals(bytes.copyOf(2), header.leftover)
    }
}
