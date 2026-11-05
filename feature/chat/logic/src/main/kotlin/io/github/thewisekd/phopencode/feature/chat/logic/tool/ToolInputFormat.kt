package io.github.thewisekd.phopencode.feature.chat.logic.tool

import io.github.thewisekd.phopencode.core.model.DataValue

/**
 * Formats a tool's input for the card: parsed input as JSON indented by two spaces; raw text, as
 * the reducer keeps it while the input streams, as it is.
 */
internal object ToolInputFormat {
    private const val INDENT = "  "

    fun format(input: DataValue): String =
        when (input) {
            is DataValue.Text -> input.value
            else -> buildString { append(input, 0) }
        }

    private fun StringBuilder.append(value: DataValue, depth: Int) {
        when (value) {
            DataValue.Null -> append("null")
            is DataValue.Bool -> append(value.value)
            is DataValue.Num -> append(value.value.toPlainString())
            is DataValue.Text -> appendQuoted(value.value)
            is DataValue.Items -> appendBlock('[', ']', value.values, depth) { item -> append(item, depth + 1) }
            is DataValue.Fields -> appendBlock('{', '}', value.values.entries.toList(), depth) { (key, item) ->
                appendQuoted(key)
                append(": ")
                append(item, depth + 1)
            }
        }
    }

    private inline fun <T> StringBuilder.appendBlock(
        open: Char,
        close: Char,
        entries: List<T>,
        depth: Int,
        appendEntry: StringBuilder.(T) -> Unit,
    ) {
        append(open)
        if (entries.isEmpty()) {
            append(close)
            return
        }
        entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append('\n')
            repeat(depth + 1) { append(INDENT) }
            appendEntry(entry)
        }
        append('\n')
        repeat(depth) { append(INDENT) }
        append(close)
    }

    private fun StringBuilder.appendQuoted(text: String) {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (char < ' ') append("\\u").append(char.code.toString(16).padStart(4, '0')) else append(char)
            }
        }
        append('"')
    }
}
