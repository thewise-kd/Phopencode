package io.github.thewisekd.phopencode.core.files

/**
 * Reads the output of `rg --null` and `grep --null`: records of `path NUL line ':' text '\n'`.
 *
 * The NUL is the only byte a path cannot hold, so a name with colons or line breaks parses
 * correctly. Each record is read in order; a record that does not have this shape stops the
 * parse rather than guess where the next one starts. A path that is not normal or not below the
 * searched folder is dropped.
 */
internal object SearchOutput {
    /**
     * @property cut Whether the output held more than was returned: the limit was reached, the last
     *   record was cut off, or a record was malformed.
     * @property shortened Whether a returned line was shortened to the line limit.
     */
    class Parsed(val matches: List<ContentMatch>, val cut: Boolean, val shortened: Boolean = false)

    private const val NUL: Byte = 0
    private const val NEWLINE: Byte = '\n'.code.toByte()
    private const val COLON: Byte = ':'.code.toByte()

    fun parse(bytes: ByteArray, directory: String, maxResults: Int, maxLineChars: Int): Parsed {
        val matches = ArrayList<ContentMatch>()
        var position = 0
        var shortened = false
        while (position < bytes.size) {
            if (matches.size == maxResults) return Parsed(matches, cut = true, shortened)
            val nul = indexOf(bytes, NUL, position)
            if (nul < 0) return Parsed(matches, cut = true, shortened)
            var digitsEnd = nul + 1
            while (digitsEnd < bytes.size && bytes[digitsEnd] in '0'.code..'9'.code) digitsEnd++
            if (digitsEnd >= bytes.size) return Parsed(matches, cut = true, shortened)
            if (digitsEnd == nul + 1 || digitsEnd - nul - 1 > MAX_DIGITS || bytes[digitsEnd] != COLON) {
                return Parsed(matches, cut = true, shortened)
            }
            val end = indexOf(bytes, NEWLINE, digitsEnd + 1)
            if (end < 0) return Parsed(matches, cut = true, shortened)
            val path = bytes.decodeToString(position, nul)
            val line = bytes.decodeToString(nul + 1, digitsEnd).toLong()
            if (line > Int.MAX_VALUE) return Parsed(matches, cut = true, shortened)
            var textEnd = end
            if (textEnd > digitsEnd + 1 && bytes[textEnd - 1] == '\r'.code.toByte()) textEnd--
            val text = bytes.decodeToString(digitsEnd + 1, textEnd)
            if (line >= 1 && RemotePath.isNormal(path) && RemotePath.isWithin(path, directory) && path != directory) {
                if (text.length > maxLineChars) shortened = true
                matches += ContentMatch(path, line.toInt(), text.shorten(maxLineChars))
            }
            position = end + 1
        }
        return Parsed(matches, cut = false, shortened)
    }

    private fun String.shorten(max: Int): String {
        if (length <= max) return this
        val end = if (this[max - 1].isHighSurrogate()) max - 1 else max
        return substring(0, end)
    }

    private fun indexOf(bytes: ByteArray, byte: Byte, from: Int): Int {
        for (index in from until bytes.size) if (bytes[index] == byte) return index
        return -1
    }

    /**
     * Bounds the digits so they always fit a `Long`; the value is then checked against
     * `Int.MAX_VALUE`, so every line number an `Int` holds parses, and a larger one stops the parse.
     */
    private const val MAX_DIGITS = 18
}
