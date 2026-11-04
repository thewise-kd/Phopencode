package io.github.thewisekd.phopencode.core.ssh.sftp.read

import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException

/**
 * Resolves the paths of the read side against a root, as the `SftpClient` KDoc defines, without
 * any request to the server.
 *
 * A path is relative to [root], or absolute and at or under it. Empty segments and a trailing slash
 * are dropped. A `.` or `..` segment, a NUL, a lone UTF-16 surrogate (which would not survive the
 * UTF-8 encoding on the wire) or an absolute path outside [root] fails with
 * [SftpFailure.PathRejected]. Nothing is decoded: `%2e%2e` or `a\..\b` are plain names on a POSIX
 * server. Every resolved path is absolute and normalised.
 *
 * Symlinks are not looked at: whether a link leads out of the root matters only for changes, which
 * this read side does not make.
 *
 * @param root Absolute; the same rules apply to it. `/` gives the whole server.
 */
internal class SftpReadPaths(root: String) {
    private val rootSegments: List<String> = segments(root).takeIf { root.startsWith('/') } ?: throw rejected()

    val root: String = join(rootSegments)

    fun resolve(path: String): String {
        val segments = segments(path)
        if (!path.startsWith('/')) return join(rootSegments + segments)
        if (segments.size < rootSegments.size || segments.subList(0, rootSegments.size) != rootSegments) {
            throw rejected()
        }
        return join(segments)
    }

    private companion object {
        fun segments(path: String): List<String> {
            if (malformed(path)) throw rejected()
            val segments = path.split('/').filter { it.isNotEmpty() }
            if (segments.any { it == "." || it == ".." }) throw rejected()
            return segments
        }

        fun malformed(path: String): Boolean {
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

        fun join(segments: List<String>): String = segments.joinToString(separator = "/", prefix = "/")

        /** Fixed text and no cause, so that the path never reaches a log. */
        fun rejected() = SshException.Sftp(SftpFailure.PathRejected)
    }
}
