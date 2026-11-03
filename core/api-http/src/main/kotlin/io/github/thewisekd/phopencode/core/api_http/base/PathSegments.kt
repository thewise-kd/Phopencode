package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException

/**
 * Keeps every path segment, usually an ID, one segment of the route it was meant for.
 *
 * OkHttp drops `.` and pops on `..`, including `%2e`, `%2E%2e` and mixed forms, and a server or
 * proxy may decode escapes once more. So a segment is refused when it is empty, or when decoding
 * its escapes, once or several times, ever gives `.` or `..`. Dots inside a longer value, such as
 * `v1.2`, `.hidden` or `a..b`, are ordinary text.
 */
internal object PathSegments {
    /** The error names no value: an ID may come from user input or a server, and errors get logged. */
    private val refused = ApiError.BadRequest("A path segment of the request is empty or a dot segment.")

    /** [segment] itself, or [ApiException] with [ApiError.BadRequest]. */
    fun checked(segment: String): String {
        if (segment.isEmpty() || isDotSegment(segment)) throw ApiException(refused)
        return segment
    }

    private fun isDotSegment(segment: String): Boolean {
        var current = segment
        repeat(MAX_DECODES) {
            if (current == "." || current == "..") return true
            val decoded = decodeEscapes(current)
            if (decoded == current) return false
            current = decoded
        }
        // Still changing after this many rounds: nothing honest is escaped that deep.
        return true
    }

    /**
     * Decodes `%XX` escapes of ASCII characters; every other character, a malformed escape and an
     * escape of a non-ASCII byte stay as they are. Only ASCII can make up `.`, `..` or a further
     * `%XX`, so that is all the dot check needs.
     */
    private fun decodeEscapes(text: String): String {
        if ('%' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val value = if (text[i] == '%' && i + 2 < text.length) {
                text.substring(i + 1, i + 3)
                    .takeIf { hex -> hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }
                    ?.toInt(HEX)
                    ?.takeIf { it < ASCII_LIMIT }
            } else {
                null
            }
            if (value != null) {
                out.append(value.toChar())
                i += 3
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private const val MAX_DECODES = 8
    private const val HEX = 16
    private const val ASCII_LIMIT = 0x80
}
