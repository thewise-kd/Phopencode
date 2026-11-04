package io.github.thewisekd.phopencode.feature.connect.logic.folder

/**
 * Server paths in the one form the folder picker shows and stores: absolute, `/`-separated, with no
 * empty, `.` or `..` segment and no trailing `/` except for the root `/` itself. `RecentFoldersStore`
 * keeps paths exactly as given, so two spellings of one folder must not reach it.
 *
 * `..` is resolved by the text alone, as `Up` does: the parent of a symlinked folder is the folder
 * the link is in, not the parent of its target.
 */
internal object FolderPaths {
    const val ROOT: String = "/"

    /**
     * The canonical form of [path]; a relative [path] resolves against [base], itself canonical.
     * `..` never goes above the root. Null for a path with a NUL byte, which no server path has.
     */
    fun canonical(path: String, base: String = ROOT): String? {
        if ('\u0000' in path) return null
        val segments = ArrayDeque<String>()
        if (!path.startsWith('/')) base.split('/').filterTo(segments) { it.isNotEmpty() }
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/", prefix = "/")
    }

    /** The parent of [path], canonical; the root is its own parent. */
    fun parent(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { ROOT }

    /** [name] inside [parent], both canonical. */
    fun child(parent: String, name: String): String = if (parent == ROOT) "/$name" else "$parent/$name"
}
