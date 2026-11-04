package io.github.thewisekd.phopencode.core.ssh.sftp

import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.sftp.SftpRoot.Companion.child
import io.github.thewisekd.phopencode.core.ssh.sftp.SftpRoot.Companion.parentOf
import io.github.thewisekd.phopencode.core.ssh.sftp.SftpRoot.Companion.rejected
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException

/**
 * Where a path really is, found with `lstat` and `readlink` one segment at a time, the way the
 * kernel walks it. The read and the write side share it, so that both are confined alike. The
 * server's `realpath` is not used for this: OpenSSH resolves symlinks there, but other servers
 * (MINA among them) only tidy the text.
 *
 * A symlink target is never tidied as text: a `..` after a symlinked segment goes to the parent of
 * that link's real target, so `a/../x` with `a -> sub/deep` is `sub/x`, not `x`.
 *
 * With [bounds], the walk must stay inside the real root at every step: the start, every segment,
 * every `..` and every symlink hop. A walk that leaves it fails with [SftpFailure.PathRejected],
 * even one that would come back in. An absolute symlink target must start with the real root or
 * the logical root, which is then read as the real one. Without bounds (resolving the root itself)
 * anything goes.
 *
 * A segment with more after it must be a folder, after symlinks; anything else fails with
 * [SftpFailure.NotFound] before a following `..` applies, as the kernel's ENOTDIR does. So must the
 * target of a symlink whose stored target ends with `/`.
 *
 * A missing segment ends the walk: the rest is appended as text, and the request that follows
 * reports it missing; a `..` after a missing segment fails with [SftpFailure.NotFound], as in the
 * kernel.
 */
internal class RealPaths(private val engine: SFTPEngine, val bounds: Bounds?) {
    /** @property real The real root. @property logical The root as configured, maybe via a symlink. */
    class Bounds(val real: SftpRoot, val logical: SftpRoot)

    /**
     * The real location of [path], an absolute path without `.` or `..` that [SftpRoot] accepted.
     *
     * @param followLast Whether a symlink in the last segment is followed too. Without it, the
     *   result is the real folder plus the last name as given.
     * @param checkpoint Runs before each request to the server; it can stop the walk by throwing,
     *   for example when the caller is cancelled.
     */
    fun locate(path: String, followLast: Boolean, checkpoint: () -> Unit = {}): String {
        val (start, rest) = start(path.split('/').filter { it.isNotEmpty() })
        return walk(start, rest, followLast, Hops(checkpoint))
    }

    /** The real location of the target of the symlink at [link], a real path; see [locate]. */
    fun locateLink(link: String, followLast: Boolean, checkpoint: () -> Unit = {}): String =
        follow(link, followLast, Hops(checkpoint))

    fun lstatOrNull(path: String): FileAttributes? = try {
        engine.lstat(path)
    } catch (e: SFTPException) {
        if (e.statusCode == Response.StatusCode.NO_SUCH_FILE || e.statusCode == Response.StatusCode.NO_SUCH_PATH) {
            null
        } else {
            throw e
        }
    }

    private class Hops(val checkpoint: () -> Unit) {
        var count = 0
    }

    private fun lstatOrNull(path: String, hops: Hops): FileAttributes? {
        hops.checkpoint()
        return lstatOrNull(path)
    }

    /**
     * Where an absolute walk starts: the root it names, and the segments after it. Only plain
     * segments match the root; a `..` before the root's end does not.
     */
    private fun start(segments: List<String>): Pair<String, List<String>> {
        if (bounds == null) return "/" to segments
        for (root in listOf(bounds.real, bounds.logical)) {
            val prefix = root.path.split('/').filter { it.isNotEmpty() }
            if (segments.size >= prefix.size && segments.subList(0, prefix.size) == prefix) {
                return bounds.real.path to segments.drop(prefix.size)
            }
        }
        throw rejected()
    }

    private fun follow(link: String, followLast: Boolean, hops: Hops): String {
        if (++hops.count > MAX_SYMLINKS) throw SshException.Sftp(SftpFailure.Failed)
        hops.checkpoint()
        val target = engine.readLink(link)
        if (target.isEmpty() || '\u0000' in target) throw SshException.Sftp(SftpFailure.Failed)
        // A trailing `/` makes the last name a folder to enter, as the kernel reads it.
        val names = target.split('/').filter { it.isNotEmpty() }
        val segments = if (target.endsWith('/')) names + "." else names
        val (start, rest) = if (target.startsWith('/')) start(segments) else parentOf(link) to segments
        return walk(start, rest, followLast, hops)
    }

    /** [start] is real and inside the bounds; [segments] may hold `.` and `..`. */
    private fun walk(start: String, segments: List<String>, followLast: Boolean, hops: Hops): String {
        var current = inside(start)
        for ((index, name) in segments.withIndex()) {
            current = when (name) {
                "." -> current
                ".." -> parentOf(current)
                else -> {
                    val next = child(current, name)
                    val last = index == segments.lastIndex
                    val rest = segments.subList(index + 1, segments.size)
                    if (last && !followLast) {
                        next
                    } else {
                        val attributes = lstatOrNull(next, hops) ?: return missing(next, rest)
                        if (attributes.type == FileMode.Type.SYMLINK) {
                            val target = follow(next, followLast = true, hops)
                            if (!last) {
                                val resolved = lstatOrNull(target, hops) ?: return missing(target, rest)
                                requireFolder(resolved)
                            }
                            target
                        } else {
                            if (!last) requireFolder(attributes)
                            next
                        }
                    }
                }
            }
            inside(current)
        }
        return current
    }

    /** A segment with more after it must be a folder, as in the kernel (ENOTDIR). */
    private fun requireFolder(attributes: FileAttributes) {
        if (attributes.type != FileMode.Type.DIRECTORY) throw SshException.Sftp(SftpFailure.NotFound)
    }

    /** [next] does not exist; the [rest] after it can only be names under it. */
    private fun missing(next: String, rest: List<String>): String {
        if (rest.any { it == ".." }) throw SshException.Sftp(SftpFailure.NotFound)
        return rest.filter { it != "." }.fold(next, ::child)
    }

    private fun inside(path: String): String {
        if (bounds != null && !bounds.real.contains(path)) throw rejected()
        return path
    }

    companion object {
        /** As Linux allows. */
        const val MAX_SYMLINKS = 40
    }
}
