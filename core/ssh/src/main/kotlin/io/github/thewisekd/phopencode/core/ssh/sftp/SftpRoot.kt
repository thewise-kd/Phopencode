package io.github.thewisekd.phopencode.core.ssh.sftp

import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException

/**
 * The lexical half of the `SftpClient` path rules, shared by the read and the write side, which
 * needs no server: a path is relative to [path] or absolute and at or under it, compared segment by
 * segment. Empty segments (`a//b`, a trailing `/`) are dropped. A `.` or `..` segment, a NUL, a lone
 * UTF-16 surrogate (which would not survive the UTF-8 encoding on the wire, so a request would name
 * another file) or an absolute path outside [path] fails with [SftpFailure.PathRejected]. Nothing
 * is decoded: names are opaque bytes to SFTP, so `%2e%2e` or `a\..\b` are plain names on a POSIX
 * server.
 *
 * Symlinks are not looked at. Where a path really is after symlinks needs the server; both sides
 * check that with [RealPaths].
 *
 * @property path Absolute, without empty segments or a trailing `/` (except the root `/`).
 */
internal class SftpRoot private constructor(val path: String) {
    /** The absolute, normalised form of [path]; the root itself for an empty path. */
    fun resolve(path: String): String {
        val segments = segmentsOf(path)
        if (!path.startsWith('/')) return join(this.path, segments)
        val absolute = join("/", segments)
        if (!contains(absolute)) throw rejected()
        return absolute
    }

    /** Whether [absolute], a normalised absolute path, is the root or below it. */
    fun contains(absolute: String): Boolean =
        path == "/" || absolute == path || absolute.startsWith("$path/")

    /** The segments of [absolute], a path [contains] accepts, below the root. */
    fun segments(absolute: String): List<String> {
        require(contains(absolute))
        return absolute.removePrefix(path).split('/').filter { it.isNotEmpty() }
    }

    override fun toString(): String = "SftpRoot"

    companion object {
        /** @throws SshException.Sftp with [SftpFailure.PathRejected] for a malformed root. */
        fun of(root: String): SftpRoot {
            if (!root.startsWith('/')) throw rejected()
            return SftpRoot(join("/", segmentsOf(root)))
        }

        fun parentOf(absolute: String): String =
            absolute.substringBeforeLast('/').ifEmpty { "/" }

        fun nameOf(absolute: String): String = absolute.substringAfterLast('/')

        fun child(parent: String, name: String): String = if (parent == "/") "/$name" else "$parent/$name"

        /** Fixed text and no cause, so that the path never reaches a log. */
        fun rejected(): SshException.Sftp = SshException.Sftp(SftpFailure.PathRejected)

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

        private fun join(base: String, segments: List<String>): String =
            segments.fold(base) { parent, name -> child(parent, name) }
    }
}
