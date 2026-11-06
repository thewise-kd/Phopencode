package io.github.thewisekd.phopencode.core.git.parse

// Byte-level helpers shared by the parsers. Ranges are [from, to); nothing here reads past `to`.

internal fun fail(reason: String, offset: Int): Nothing = throw GitParseException(reason, offset)

internal fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

internal const val NUL: Byte = 0
internal const val LF: Byte = '\n'.code.toByte()
internal const val SPACE: Byte = ' '.code.toByte()
internal const val TAB: Byte = '\t'.code.toByte()
internal const val QUOTE: Byte = '"'.code.toByte()
internal const val BACKSLASH: Byte = '\\'.code.toByte()

internal fun ByteArray.indexOf(byte: Byte, from: Int, to: Int = size): Int {
    var i = from
    while (i < to) {
        if (this[i] == byte) return i
        i++
    }
    return -1
}

internal fun ByteArray.startsWith(prefix: ByteArray, from: Int, to: Int): Boolean {
    if (to - from < prefix.size) return false
    for (i in prefix.indices) if (this[from + i] != prefix[i]) return false
    return true
}

internal fun ByteArray.rangeEquals(from: Int, to: Int, other: ByteArray): Boolean =
    to - from == other.size && startsWith(other, from, to)

/** A non-negative decimal Int without sign or leading `+`. */
internal fun ByteArray.parseCount(from: Int, to: Int, what: String): Int {
    if (from >= to || to - from > 10) fail("invalid $what", from)
    var value = 0L
    for (i in from until to) {
        val c = this[i] - '0'.code.toByte()
        if (c !in 0..9) fail("invalid $what", i)
        value = value * 10 + c
    }
    if (value > Int.MAX_VALUE) fail("$what out of range", from)
    return value.toInt()
}

/** A decimal Long with an optional leading `-`. */
internal fun ByteArray.parseLong(from: Int, to: Int, what: String): Long {
    var i = from
    val negative = i < to && this[i] == '-'.code.toByte()
    if (negative) i++
    if (i >= to || to - i > 18) fail("invalid $what", from)
    var value = 0L
    while (i < to) {
        val c = this[i] - '0'.code.toByte()
        if (c !in 0..9) fail("invalid $what", i)
        value = value * 10 + c
        i++
    }
    return if (negative) -value else value
}

/** An octal file mode such as `100644`, as a number. */
internal fun ByteArray.parseMode(from: Int, to: Int): Int {
    if (from >= to || to - from > 7) fail("invalid file mode", from)
    var value = 0
    for (i in from until to) {
        val c = this[i] - '0'.code.toByte()
        if (c !in 0..7) fail("invalid file mode", i)
        value = value * 8 + c
    }
    return value
}

internal fun ByteArray.isLowerHex(from: Int, to: Int): Boolean {
    if (from >= to) return false
    for (i in from until to) {
        val c = this[i].toInt()
        if (c !in '0'.code..'9'.code && c !in 'a'.code..'f'.code) return false
    }
    return true
}

/** A full object name: 40 (SHA-1) or 64 (SHA-256) lowercase hex digits. */
internal fun ByteArray.parseFullHash(from: Int, to: Int): String {
    val length = to - from
    if ((length != 40 && length != 64) || !isLowerHex(from, to)) fail("invalid object name", from)
    return String(this, from, length, Charsets.US_ASCII)
}

/** Splits `[from, to)` into space-separated fields, each non-empty. */
internal class FieldReader(private val data: ByteArray, private var pos: Int, private val end: Int) {
    /** The next field, which must be followed by a space. */
    fun next(what: String): IntRange {
        val space = data.indexOf(SPACE, pos, end)
        if (space < 0) fail("missing $what", pos)
        if (space == pos) fail("empty $what", pos)
        val range = pos until space
        pos = space + 1
        return range
    }

    /** Everything that is left, which must not be empty. */
    fun rest(what: String): IntRange {
        if (pos >= end) fail("missing $what", pos)
        return pos until end
    }

    fun mode(): Int = next("file mode").let { data.parseMode(it.first, it.last + 1) }

    fun hash(): String = next("object name").let { data.parseFullHash(it.first, it.last + 1) }
}
