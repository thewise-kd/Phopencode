package io.github.thewisekd.phopencode.feature.files.logic.editor

import io.github.thewisekd.phopencode.feature.files.logic.contract.LineEnding
import kotlin.test.Test
import kotlin.test.assertEquals

class LineEndingsTest {
    private fun restore(original: String, edited: String, maxDiffCells: Long = LineEndings.MAX_DIFF_CELLS): String =
        LineEndings.restore(edited, LineEndings.split(original), maxDiffCells).encode()

    @Test
    fun `split keeps every kind of ending`() {
        val split = LineEndings.split("a\r\nb\nc\rd")

        assertEquals(listOf("a", "b", "c", "d"), split.lines)
        assertEquals(listOf(LineEnding.CrLf, LineEnding.Lf, LineEnding.Cr), split.endings)
        assertEquals("a\nb\nc\nd", split.text)
    }

    @Test
    fun `untouched text comes back byte for byte`() {
        val original = "a\r\nb\nc\r\rd\n"

        assertEquals(original, restore(original, LineEndings.split(original).text))
    }

    @Test
    fun `separated edits, an insertion and a deletion keep every untouched line's ending`() {
        val original = "l1\r\nl2\nl3\rl4\r\nl5\nl6\r\nl7\nl8\rl9\n"
        val edited = "l1\nL2\nl3\nl4\nnew\nl5\nl7\nL8\nl9\n"

        // L2 and L8 replace l2 and l8, so they keep those endings; "new" takes the file's first.
        assertEquals("l1\r\nL2\nl3\rl4\r\nnew\r\nl5\nl7\nL8\rl9\n", restore(original, edited))
    }

    @Test
    fun `moved lines keep their endings where content matches`() {
        val original = "a\r\nb\nc\rd\r\n"
        val edited = "a\nc\nb\nd\n"

        // The diff keeps "a", one of "b"/"c", and "d"; the other is new and takes CRLF.
        val restored = restore(original, edited)
        assertEquals(true, restored == "a\r\nc\rb\r\nd\r\n" || restored == "a\r\nc\r\nb\nd\r\n", restored)
    }

    @Test
    fun `above the diff limit the middle maps line by line`() {
        val original = "l1\r\nl2\nl3\rl4\r\nl5\nl6\r\n"
        val edited = "l1\nL2\nl3\nl4\nl5\nL6\n"

        // A changed line at both ends of the middle keeps the diff from running.
        assertEquals("l1\r\nL2\nl3\rl4\r\nl5\nL6\r\n", restore(original, edited, maxDiffCells = 1))
    }

    @Test
    fun `a new line at the end takes the file's first ending`() {
        assertEquals("a\rb\rc\r", restore("a\rb\r", "a\nb\nc\n"))
    }
}
