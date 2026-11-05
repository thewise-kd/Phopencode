package io.github.thewisekd.phopencode.feature.files.logic.tree

/**
 * Path arithmetic of the tree, on server paths (always `/`-separated). Purely textual: nothing here
 * touches the server, and a symlink is not resolved.
 *
 * @param directory The project's folder; the root nothing may leave.
 */
internal class TreePaths(directory: String) {
    /** [directory] without `.`, `..`, repeated or trailing slashes. */
    val root: String = normalize(directory) ?: directory

    /**
     * [path], absolute or relative to [root], as a normalized absolute path inside [root]; null when
     * it is blank, malformed, or leaves [root].
     */
    fun resolve(path: String): String? {
        if (path.isBlank() || '\u0000' in path) return null
        val absolute = if (path.startsWith("/")) path else "$root/$path"
        val normalized = normalize(absolute) ?: return null
        return normalized.takeIf(::isInside)
    }

    /** [path] relative to [root]: empty for [root] itself, null outside it. */
    fun relative(path: String): String? {
        val resolved = resolve(path) ?: return null
        if (resolved == root) return ""
        return resolved.removePrefix(if (root == "/") "/" else "$root/")
    }

    /** The parent of [path] inside [root]; null at [root] or outside it. */
    fun parent(path: String): String? {
        val resolved = resolve(path) ?: return null
        if (resolved == root) return null
        val parent = resolved.substringBeforeLast('/').ifEmpty { "/" }
        return parent.takeIf(::isInside)
    }

    private fun isInside(path: String): Boolean =
        path == root || path.startsWith(if (root == "/") "/" else "$root/")

    companion object {
        /**
         * An absolute path with `.` and `..` worked out and slashes collapsed; null when it is not
         * absolute or `..` climbs above `/`.
         */
        fun normalize(path: String): String? {
            if (!path.startsWith("/")) return null
            val segments = ArrayDeque<String>()
            for (segment in path.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isEmpty()) return null else segments.removeLast()
                    else -> segments.addLast(segment)
                }
            }
            return segments.joinToString(separator = "/", prefix = "/")
        }
    }
}
