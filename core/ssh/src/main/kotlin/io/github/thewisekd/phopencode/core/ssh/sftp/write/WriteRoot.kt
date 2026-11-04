package io.github.thewisekd.phopencode.core.ssh.sftp.write

import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException

/**
 * The lexical half of the `SftpClient` path rules, which needs no server: a path is relative to
 * [path] or absolute and inside it, has no `.`, `..` or NUL, and empty segments (`a//b`, a trailing
 * `/`) are dropped. Anything else fails with [SftpFailure.PathRejected]. Names are opaque bytes to
 * SFTP, so `%2e%2e` or `..\x` are plain names, not escapes.
 *
 * The other half, where a path really is after symlinks, needs the server; see [SftpWriter].
 *
 * @property path Absolute, without empty segments or a trailing `/` (except the root `/`).
 */
internal class WriteRoot private constructor(val path: String) {
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

    override fun toString(): String = "WriteRoot"

    companion object {
        /** @throws SshException.Sftp with [SftpFailure.PathRejected] for a malformed root. */
        fun of(root: String): WriteRoot {
            if (!root.startsWith('/')) throw rejected()
            return WriteRoot(join("/", segmentsOf(root)))
        }

        fun parentOf(absolute: String): String =
            absolute.substringBeforeLast('/').ifEmpty { "/" }

        fun nameOf(absolute: String): String = absolute.substringAfterLast('/')

        fun child(parent: String, name: String): String = if (parent == "/") "/$name" else "$parent/$name"

        fun rejected(): SshException.Sftp = SshException.Sftp(SftpFailure.PathRejected)

        private fun segmentsOf(path: String): List<String> {
            if ('\u0000' in path) throw rejected()
            val segments = path.split('/').filter { it.isNotEmpty() }
            if (segments.any { it == "." || it == ".." }) throw rejected()
            return segments
        }

        private fun join(base: String, segments: List<String>): String =
            segments.fold(base) { parent, name -> child(parent, name) }
    }
}
