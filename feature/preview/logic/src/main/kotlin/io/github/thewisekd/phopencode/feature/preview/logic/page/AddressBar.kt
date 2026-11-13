package io.github.thewisekd.phopencode.feature.preview.logic.page

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin

/**
 * What the address bar may load (P13.11): a path on the preview origin only, normalised.
 *
 * - Surrounding whitespace is dropped; an empty text is `/`. A control character anywhere refuses
 *   the text.
 * - A text with a scheme is refused unless it is an `http` address on the origin, which becomes its
 *   path. Without an open bridge no full address is accepted.
 * - Backslashes count as slashes, as browsers read them, and runs of slashes collapse to one, so a
 *   path can never turn into a scheme-relative address (`//host`) that leaves the origin. A missing
 *   leading slash is added.
 * - Dot segments (`.`, `..`, also percent-encoded) are resolved and never climb above `/`.
 * - Characters an address cannot hold are percent-encoded as UTF-8; a valid `%XX` is kept, so
 *   nothing is encoded twice.
 * - The bridge's secret parameter is dropped from the query; an empty query or fragment is dropped.
 * - A result longer than [MAX_LENGTH] is refused.
 */
internal object AddressBar {
    const val MAX_LENGTH = 2048

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")
    private val SLASHES = Regex("/{2,}")
    private const val PATH_EXTRA = "-._~!$&'()*+,;=:@/"
    private const val QUERY_EXTRA = "$PATH_EXTRA?"

    /** The normalised path, query and fragment, or null when [text] is refused. */
    fun normalize(text: String, origin: PreviewOrigin?): String? {
        val trimmed = text.trim()
        if (trimmed.any { it.isISOControl() }) return null
        var rest = trimmed.replace('\\', '/')
        if (SCHEME.containsMatchIn(rest)) {
            if (origin == null) return null
            val uri = NavigationPolicy.parseHttp(rest)?.takeIf { NavigationPolicy.isOn(origin, it) } ?: return null
            rest = NavigationPolicy.pathOf(uri)
        }
        if (!rest.startsWith('/')) rest = "/$rest"

        val hash = rest.indexOf('#')
        val fragment = if (hash < 0) null else rest.substring(hash + 1)
        val beforeHash = if (hash < 0) rest else rest.substring(0, hash)
        val mark = beforeHash.indexOf('?')
        val query = if (mark < 0) null else beforeHash.substring(mark + 1)
        val path = if (mark < 0) beforeHash else beforeHash.substring(0, mark)

        val normalPath = encode(withoutDotSegments(path.replace(SLASHES, "/")), PATH_EXTRA)
        val normalQuery = NavigationPolicy.withoutSecret(query?.let { encode(it, QUERY_EXTRA) })
        val normalFragment = fragment?.let { encode(it, QUERY_EXTRA) }
        val result = buildString {
            append(normalPath)
            if (!normalQuery.isNullOrEmpty()) append('?').append(normalQuery)
            if (!normalFragment.isNullOrEmpty()) append('#').append(normalFragment)
        }
        return result.takeIf { it.length <= MAX_LENGTH }
    }

    /** RFC 3986 `remove_dot_segments` on an absolute [path], with `%2e` read as a dot. */
    private fun withoutDotSegments(path: String): String {
        val segments = path.split('/').drop(1)
        val out = ArrayList<String>()
        segments.forEachIndexed { index, segment ->
            val last = index == segments.lastIndex
            when (segment.replace("%2e", ".", ignoreCase = true)) {
                "." -> if (last) out.add("")
                ".." -> {
                    if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                    if (last) out.add("")
                }
                else -> out.add(segment)
            }
        }
        return "/" + out.joinToString("/")
    }

    /** Percent-encodes every character of [part] other than ASCII letters, digits, [extra] and a valid `%XX`. */
    private fun encode(part: String, extra: String): String = buildString {
        var index = 0
        while (index < part.length) {
            val char = part[index]
            when {
                char == '%' && isHex(part, index + 1) && isHex(part, index + 2) -> {
                    append(part, index, index + 3)
                    index += 3
                    continue
                }
                char.code < 0x80 && (char.isLetterOrDigit() || char in extra) -> append(char)
                else -> {
                    val end = if (char.isHighSurrogate() && index + 1 < part.length && part[index + 1].isLowSurrogate()) index + 2 else index + 1
                    part.substring(index, end).toByteArray(Charsets.UTF_8).forEach { byte ->
                        append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
                    }
                    index = end
                    continue
                }
            }
            index++
        }
    }

    private fun isHex(text: String, index: Int): Boolean = index < text.length && text[index].lowercaseChar() in "0123456789abcdef"

    private const val HEX = "0123456789ABCDEF"
}
