package io.github.thewisekd.phopencode.feature.files.logic.editor

import io.github.thewisekd.phopencode.feature.files.logic.contract.LineEnding

/**
 * A text split at its line breaks: [lines] has one more entry than [endings], and `endings[i]`
 * ends `lines[i]`. The last line has no ending; it is empty when the text ends with a line break.
 * Joining with `\n` gives the text the editor shows.
 */
internal class SplitText(val lines: List<String>, val endings: List<LineEnding>) {
    init {
        require(lines.size == endings.size + 1)
    }

    /** The text with `\n` line endings. */
    val text: String
        get() = lines.joinToString("\n")

    /** The first line ending, the file's own; [LineEnding.Lf] without one. */
    val lineEnding: LineEnding
        get() = endings.firstOrNull() ?: LineEnding.Lf

    /** The text with every line's own ending put back. */
    fun encode(): String = buildString {
        lines.forEachIndexed { index, line ->
            append(line)
            if (index < endings.size) append(endings[index].chars)
        }
    }

    override fun toString(): String = "SplitText(lines=${lines.size})"
}

internal object LineEndings {
    /** Splits [text] at `\r\n`, `\r` and `\n`, keeping which one ended each line. */
    fun split(text: String): SplitText {
        val lines = mutableListOf<String>()
        val endings = mutableListOf<LineEnding>()
        var start = 0
        var index = 0
        while (index < text.length) {
            val ending = when (text[index]) {
                '\n' -> LineEnding.Lf
                '\r' -> if (index + 1 < text.length && text[index + 1] == '\n') LineEnding.CrLf else LineEnding.Cr
                else -> null
            }
            if (ending == null) {
                index++
                continue
            }
            lines += text.substring(start, index)
            endings += ending
            index += ending.chars.length
            start = index
        }
        lines += text.substring(start)
        return SplitText(lines, endings)
    }

    /** [text] with every `\r\n` and `\r` turned into `\n`. */
    fun normalize(text: String): String =
        if (text.indexOf('\r') < 0) text else split(text).text

    /**
     * The most cells of the line diff, old lines times new lines in the changed middle: 1 Mi cells,
     * 4 MiB of table. Above it [restore] maps the middle line by line instead.
     */
    const val MAX_DIFF_CELLS: Long = 1L shl 20

    /**
     * The edited [text] (with `\n` line endings) given line endings that follow [base], the file as
     * loaded or last saved.
     *
     * The old and new lines are aligned by content: the common start and end first, then a longest
     * common subsequence over the lines between them.
     * - A line the edit left alone keeps its own ending, wherever it moved to.
     * - Between two untouched lines, the changed lines take the endings of the old lines they
     *   replaced, in order; lines beyond those, inserted ones, take the file's own ending,
     *   [SplitText.lineEnding].
     * - When the middle is larger than [maxDiffCells] (old lines times new lines), the diff would
     *   cost too much time and memory: the middle is then mapped line by line from its start,
     *   which keeps the endings of in-place edits but can move endings after an insertion or a
     *   deletion there.
     *
     * An untouched text comes back as [base], byte for byte; a file with one kind of line ending
     * keeps it on every line; whether the file ends with a line break is the text's.
     */
    fun restore(text: String, base: SplitText, maxDiffCells: Long = MAX_DIFF_CELLS): SplitText {
        val lines = text.split('\n')
        val old = base.lines
        val shared = minOf(lines.size, old.size)

        var prefix = 0
        while (prefix < shared && lines[prefix] == old[prefix]) prefix++
        var suffix = 0
        while (suffix < shared - prefix && lines[lines.size - 1 - suffix] == old[old.size - 1 - suffix]) suffix++

        // For each new line, the old line whose ending it takes; -1 for the file's own.
        val source = IntArray(lines.size) { -1 }
        for (index in 0 until prefix) source[index] = index
        val newEnd = lines.size - suffix
        val oldEnd = old.size - suffix
        for (index in 0 until suffix) source[newEnd + index] = oldEnd + index

        val oldCount = oldEnd - prefix
        val newCount = newEnd - prefix
        if (oldCount.toLong() * newCount <= maxDiffCells) {
            alignMiddle(old, lines, prefix, oldEnd, newEnd, source)
        } else {
            for (index in prefix until newEnd) if (index < oldEnd) source[index] = index
        }

        val fallback = base.lineEnding
        val endings = List(lines.size - 1) { index ->
            base.endings.getOrNull(source[index]) ?: fallback
        }
        return SplitText(lines, endings)
    }

    /**
     * Fills [source] for the new lines `[from, newEnd)` against the old lines `[from, oldEnd)`: a
     * longest common subsequence keeps matched lines on their own, and each run of changed lines
     * between two matches maps onto the old run it replaced, in order.
     */
    private fun alignMiddle(
        old: List<String>,
        lines: List<String>,
        from: Int,
        oldEnd: Int,
        newEnd: Int,
        source: IntArray,
    ) {
        val rows = oldEnd - from
        val columns = newEnd - from
        val width = columns + 1
        // table[i * width + j]: the longest common subsequence of old[from + i ..] and lines[from + j ..].
        val table = IntArray((rows + 1) * width)
        for (i in rows - 1 downTo 0) {
            for (j in columns - 1 downTo 0) {
                table[i * width + j] = if (old[from + i] == lines[from + j]) {
                    table[(i + 1) * width + j + 1] + 1
                } else {
                    maxOf(table[(i + 1) * width + j], table[i * width + j + 1])
                }
            }
        }

        var oldRun = 0
        var newRun = 0
        fun flush(oldAt: Int, newAt: Int) {
            for (k in 0 until (newAt - newRun)) {
                val replaced = oldRun + k
                if (replaced < oldAt) source[from + newRun + k] = from + replaced
            }
        }
        var i = 0
        var j = 0
        while (i < rows && j < columns) {
            when {
                old[from + i] == lines[from + j] -> {
                    flush(i, j)
                    source[from + j] = from + i
                    i++
                    j++
                    oldRun = i
                    newRun = j
                }
                table[(i + 1) * width + j] >= table[i * width + j + 1] -> i++
                else -> j++
            }
        }
        flush(rows, columns)
    }
}

private val LineEnding.chars: String
    get() = when (this) {
        LineEnding.Lf -> "\n"
        LineEnding.CrLf -> "\r\n"
        LineEnding.Cr -> "\r"
    }
