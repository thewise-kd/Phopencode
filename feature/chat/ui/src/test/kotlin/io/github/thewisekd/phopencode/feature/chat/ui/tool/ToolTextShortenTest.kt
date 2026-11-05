package io.github.thewisekd.phopencode.feature.chat.ui.tool

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How the card shortens a long input or output before drawing it. */
class ToolTextShortenTest {
    @Test
    fun `a short text is kept whole`() {
        val shortened = shortenToolText("a\nb", maxLines = 3, maxChars = 100)

        assertEquals(ShortenedText("a\nb", hiddenLines = 0, hiddenChars = 0), shortened)
        assertFalse(shortened.truncated)
    }

    @Test
    fun `an empty text stays empty`() {
        assertEquals(ShortenedText("", 0, 0), shortenToolText("", maxLines = 3, maxChars = 100))
    }

    @Test
    fun `a trailing newline does not count as a line`() {
        assertEquals(ShortenedText("a\nb", 0, 0), shortenToolText("a\nb\n", maxLines = 2, maxChars = 100))
    }

    @Test
    fun `Windows line ends count as one`() {
        assertEquals(ShortenedText("a\nb", 1, 2), shortenToolText("a\r\nb\r\nc", maxLines = 2, maxChars = 100))
    }

    @Test
    fun `lines over the limit are cut and counted`() {
        val shortened = shortenToolText(numberedLines(10), maxLines = 4, maxChars = 1_000)

        assertEquals(numberedLines(4), shortened.text)
        assertEquals(6, shortened.hiddenLines)
        assertEquals(numberedLines(10).length - numberedLines(4).length, shortened.hiddenChars)
        assertTrue(shortened.truncated)
    }

    @Test
    fun `exactly the line limit is not cut`() {
        assertEquals(ShortenedText(numberedLines(4), 0, 0), shortenToolText(numberedLines(4), maxLines = 4, maxChars = 1_000))
    }

    @Test
    fun `characters over the limit are cut and the lines after them counted`() {
        // "line 1\nline 2\nline 3": cut inside "line 2", so line 2 shows in part and line 3 is hidden.
        val shortened = shortenToolText(numberedLines(3), maxLines = 10, maxChars = 10)

        assertEquals("line 1\nlin", shortened.text)
        assertEquals(1, shortened.hiddenLines)
        assertEquals(numberedLines(3).length - 10, shortened.hiddenChars)
    }

    @Test
    fun `a character cut right after a line end counts the next line as hidden`() {
        // "ab\ncd\nef" cut after "ab\n": no character of "cd" shows, so both "cd" and "ef" are hidden.
        assertEquals(ShortenedText("ab", hiddenLines = 2, hiddenChars = 5), shortenToolText("ab\ncd\nef", maxLines = 10, maxChars = 3))
    }

    @Test
    fun `one long line is cut by characters with no hidden lines`() {
        val shortened = shortenToolText("x".repeat(50), maxLines = 10, maxChars = 20)

        assertEquals("x".repeat(20), shortened.text)
        assertEquals(0, shortened.hiddenLines)
        assertEquals(30, shortened.hiddenChars)
        assertTrue(shortened.truncated)
    }

    @Test
    fun `a cut never splits a character made of two code units`() {
        val emoji = "😀"
        val shortened = shortenToolText("ab$emoji$emoji", maxLines = 10, maxChars = 3)

        assertEquals("ab", shortened.text)
        assertEquals(4, shortened.hiddenChars)
    }

    @Test
    fun `the card's limits are a screenful, not the whole output`() {
        val shortened = shortenToolText(numberedLines(TOOL_MAX_LINES + 25))

        assertEquals(numberedLines(TOOL_MAX_LINES), shortened.text)
        assertEquals(25, shortened.hiddenLines)
        assertEquals(TOOL_MAX_CHARS, shortenToolText("y".repeat(TOOL_MAX_CHARS * 3)).text.length)
    }
}
