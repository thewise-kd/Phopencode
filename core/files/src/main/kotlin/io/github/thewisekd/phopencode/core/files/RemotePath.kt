package io.github.thewisekd.phopencode.core.files

/**
 * The lexical path rules of [FileService], the same as the SFTP layer's: a path is relative to the
 * root, or absolute and at or under it, compared segment by segment. Empty segments are dropped. A
 * `.` or `..` segment, a NUL, half a surrogate pair or an absolute path outside the root fails with
 * [FileError.PathRejected]. Nothing is decoded: `%2e%2e` and `a\..\b` are plain names.
 *
 * Symlinks are not looked at here; the SFTP layer checks where a path really leads.
 */
internal object RemotePath {
    /** The absolute, normalised form of [path] under [root]: the root itself for an empty path. */
    fun resolve(root: String, path: String): String {
        if (!root.startsWith('/')) throw rejected()
        val base = join(segmentsOf(root))
        val segments = segmentsOf(path)
        if (!path.startsWith('/')) return join(segmentsOf(base) + segments)
        val absolute = join(segments)
        if (!isWithin(absolute, base)) throw rejected()
        return absolute
    }

    /** Whether [path] is [folder] or below it; both absolute and normalised. */
    fun isWithin(path: String, folder: String): Boolean =
        folder == "/" || path == folder || path.startsWith("$folder/")

    /** Whether [path], absolute, has the normal form [resolve] gives. */
    fun isNormal(path: String): Boolean =
        path.startsWith('/') && !malformed(path) && (path == "/" || path.substring(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." })

    fun parentOf(absolute: String): String = absolute.substringBeforeLast('/').ifEmpty { "/" }

    fun nameOf(absolute: String): String = absolute.substringAfterLast('/')

    fun child(parent: String, name: String): String = if (parent == "/") "/$name" else "$parent/$name"

    fun rejected(): FileException = FileException(FileError.PathRejected)

    private fun join(segments: List<String>): String = segments.joinToString("/", prefix = "/")

    private fun segmentsOf(path: String): List<String> {
        if (malformed(path)) throw rejected()
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) throw rejected()
        return segments
    }

    private fun malformed(path: String): Boolean {
        var index = 0
        while (index < path.length) {
            val char = path[index]
            when {
                char == '\u0000' -> return true
                char.isHighSurrogate() -> {
                    if (index + 1 >= path.length || !path[index + 1].isLowSurrogate()) return true
                    index++
                }
                char.isLowSurrogate() -> return true
            }
            index++
        }
        return false
    }
}
