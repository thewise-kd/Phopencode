package io.github.thewisekd.phopencode.feature.connect.logic.folder

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugDetailTest {
    private fun frames(count: Int): Array<StackTraceElement> =
        Array(count) { StackTraceElement("test.Frames", "frame$it", "Frames.kt", it + 1) }

    private fun failure(message: String?, frames: Int, cause: Throwable? = null): Throwable =
        IllegalStateException(message, cause).apply { stackTrace = frames(frames) }

    @Test
    fun `nothing to tell gives null`() {
        assertNull(DebugDetail.of(failure = null, lastEnd = null))
    }

    @Test
    fun `every link of the chain is named with its class and message`() {
        val inner = IOException("inner reason").apply { stackTrace = frames(2) }
        val text = DebugDetail.of(failure("outer reason", 2, inner), lastEnd = null)!!

        assertTrue(text.startsWith("Failure:\n"), text)
        assertTrue("java.lang.IllegalStateException: outer reason\n" in text, text)
        assertTrue("Caused by: java.io.IOException: inner reason\n" in text, text)
        assertTrue("    at test.Frames.frame0(Frames.kt:1)" in text, text)
        assertFalse("Connection last ended" in text, text)
    }

    @Test
    fun `a link without a message is named by its class alone`() {
        val text = DebugDetail.of(failure(null, 1), lastEnd = null)!!

        assertTrue("java.lang.IllegalStateException\n" in text, text)
    }

    @Test
    fun `each link shows at most fifteen frames and counts the rest`() {
        val inner = IOException("inner").apply { stackTrace = frames(20) }
        val text = DebugDetail.of(failure("outer", 40, inner), lastEnd = null)!!

        assertEquals(30, text.lines().count { it.startsWith("    at ") }, text)
        assertTrue("    ... 25 more" in text, text)
        assertTrue("    ... 5 more" in text, text)
        assertFalse("frame15(" in text.substringBefore("Caused by"), text)
    }

    @Test
    fun `the connection's last end follows the failure under its own heading`() {
        val end = IllegalStateException("disconnect() called").apply { stackTrace = frames(1) }
        val text = DebugDetail.of(failure("listing failed", 1), lastEnd = end)!!

        val failureAt = text.indexOf("listing failed")
        val headingAt = text.indexOf("Connection last ended:\n")
        assertTrue(failureAt in 0 until headingAt, text)
        assertTrue(text.indexOf("disconnect() called") > headingAt, text)
    }

    @Test
    fun `the last end alone is enough`() {
        val text = DebugDetail.of(failure = null, lastEnd = failure("close() called", 1))!!

        assertTrue(text.startsWith("Connection last ended:\n"), text)
    }

    @Test
    fun `a cause cycle is followed once`() {
        val first = IOException("first").apply { stackTrace = frames(1) }
        val second = IOException("second", first).apply { stackTrace = frames(1) }
        first.initCause(second)

        val text = DebugDetail.of(second, lastEnd = null)!!

        assertEquals(1, Regex("java.io.IOException: first").findAll(text).count(), text)
        assertEquals(1, Regex("java.io.IOException: second").findAll(text).count(), text)
    }

    @Test
    fun `a long chain is cut after its last shown link`() {
        var chain: Throwable = IOException("link 0").apply { stackTrace = frames(1) }
        repeat(30) { chain = IOException("link ${it + 1}", chain).apply { stackTrace = frames(1) } }

        val text = DebugDetail.of(chain, lastEnd = null)!!

        assertEquals(DebugDetail.MAX_LINKS, text.lines().count { it.contains("java.io.IOException: link") }, text)
        assertTrue("more causes" in text, text)
    }

    @Test
    fun `the text is capped`() {
        val text = DebugDetail.of(failure("x".repeat(20_000), 15), lastEnd = failure("y".repeat(20_000), 15))!!

        assertTrue(text.length <= DebugDetail.MAX_LENGTH, "length ${text.length}")
        assertTrue(text.endsWith(DebugDetail.TRUNCATED), text.takeLast(100))
    }

    @Test
    fun `a short text is not capped`() {
        val text = DebugDetail.of(failure("short", 1), lastEnd = null)!!

        assertFalse(text.endsWith(DebugDetail.TRUNCATED), text)
    }

    /** The formatted text of a failure with [message] and no frames. */
    private fun shown(message: String): String = DebugDetail.of(failure(message, 0), lastEnd = null)!!

    /** A made-up credential, assembled at runtime so that no literal looks like one. */
    private val value = listOf("Zq", "8w", "Lm", "4x").joinToString("")

    /** A run of 49 base64 characters, with digits and both cases, and no slash. */
    private val base64Run = listOf("Ab3", "+Xy9").joinToString("").repeat(7)

    private fun assertMasked(text: String, vararg hidden: String) {
        hidden.forEach { assertFalse(it in text, "\"$it\" survived in: $text") }
        assertTrue(DebugDetail.MASK in text, text)
    }

    @Test
    fun `the detail is redacted with the shared patterns, in every link and the last end`() {
        val pair = "pass" + "word=" + value
        val inner = IOException("login with $pair").apply { stackTrace = frames(1) }
        val end = IOException("ended: Bearer $value").apply { stackTrace = frames(1) }

        val text = DebugDetail.of(failure("GET https://tester:$value@server.example/", 1, inner), lastEnd = end)!!

        assertMasked(text, value)
        assertTrue("server.example" in text, text)
    }

    @Test
    fun `a credential is masked before the cap so no part of it survives at the edge`() {
        // "Failure:\n" and "java.lang.IllegalStateException: " come first: 42 characters. Unmasked,
        // the cut would fall ten characters into the run.
        val padding = "p".repeat(DebugDetail.MAX_LENGTH - DebugDetail.TRUNCATED.length - 10 - 42 - 1) + " "
        val text = shown(padding + base64Run + " " + "z".repeat(100))

        assertFalse(base64Run.take(10) in text, text.takeLast(200))
    }
}
