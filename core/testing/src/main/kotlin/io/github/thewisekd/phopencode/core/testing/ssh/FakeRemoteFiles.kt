package io.github.thewisekd.phopencode.core.testing.ssh

import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.util.TreeMap

/**
 * A server's files in memory, shared by every [FakeSftpClient] of a session. The functions here are
 * the server's side: they arrange and inspect the tree with absolute paths, and no root applies.
 */
class FakeRemoteFiles {
    internal sealed class Node

    internal class File(@Volatile var bytes: ByteArray, @Volatile var mode: Int) : Node()

    internal class Directory : Node() {
        val children = TreeMap<String, Node>()
    }

    internal class Link(val target: String) : Node()

    /** A file being written. Its [file] is what counts: a rename above it makes [path] stale. */
    internal class Temporary(val path: List<String>, val file: File)

    private val top = Directory()
    private val denied = HashSet<List<String>>()
    private var temporaries = 0

    /** Adds a file, and any missing folders above it. */
    @Synchronized
    fun addFile(path: String, content: ByteArray, mode: Int = DEFAULT_FILE_MODE) {
        val segments = segments(path)
        directoryAt(segments.dropLast(1)).children[segments.last()] = File(content.copyOf(), mode)
    }

    /** Adds a folder, and any missing folders above it. */
    @Synchronized
    fun addDirectory(path: String) {
        directoryAt(segments(path))
    }

    /** Adds a symlink to [target], stored as given. */
    @Synchronized
    fun addSymlink(path: String, target: String) {
        val segments = segments(path)
        directoryAt(segments.dropLast(1)).children[segments.last()] = Link(target)
    }

    /** Clients get [SftpFailure.PermissionDenied] on [path] and everything below it. */
    @Synchronized
    fun deny(path: String) {
        denied += segments(path)
    }

    /** The bytes of a file, or null if no file is there. A symlink at [path] is not followed. */
    @Synchronized
    fun content(path: String): ByteArray? = (node(segments(path)) as? File)?.bytes?.copyOf()

    /** Whether anything is at [path], without following a symlink there. */
    @Synchronized
    fun exists(path: String): Boolean = node(segments(path)) != null

    /** The permission bits of a file. */
    @Synchronized
    fun mode(path: String): Int = (node(segments(path)) as File).mode

    /** The names in a folder, temporary files included. */
    @Synchronized
    fun names(path: String): Set<String> = (node(segments(path)) as Directory).children.keys.toSet()

    private fun directoryAt(segments: List<String>): Directory {
        var current = top
        segments.forEach { name ->
            current = current.children.getOrPut(name) { Directory() } as Directory
        }
        return current
    }

    internal fun node(segments: List<String>): Node? {
        var current: Node = top
        segments.forEach { name ->
            current = (current as? Directory)?.children?.get(name) ?: return null
        }
        return current
    }

    /**
     * Where [segments] really is, going through symlinks. The last segment's link is followed only
     * if asked. What does not exist stays as named, so this also says where a new file would land.
     */
    @Synchronized
    internal fun realPath(segments: List<String>, followLast: Boolean): List<String> {
        val pending = ArrayDeque(segments)
        val real = mutableListOf<String>()
        var hops = 0
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            when (name) {
                "." -> continue
                ".." -> {
                    real.removeLastOrNull()
                    continue
                }
            }
            val link = node(real + name) as? Link
            if (link == null || (pending.isEmpty() && !followLast)) {
                real += name
                continue
            }
            if (++hops > MAX_LINK_HOPS) throw SshException.Sftp(SftpFailure.Failed)
            if (link.target.startsWith('/')) real.clear()
            link.target.split('/').filter { it.isNotEmpty() }.asReversed().forEach(pending::addFirst)
        }
        return real
    }

    @Synchronized
    internal fun checkAllowed(real: List<String>) {
        if ((0..real.size).any { real.take(it) in denied }) throw SshException.Sftp(SftpFailure.PermissionDenied)
    }

    /** The folder [real] is in, or null. */
    @Synchronized
    internal fun parentOf(real: List<String>): Directory? = node(real.dropLast(1)) as? Directory

    /** Adds an empty file with [mode] next to [real], under a free name, and gives its full path. */
    @Synchronized
    internal fun addTemporary(real: List<String>, mode: Int): Temporary {
        val folder = parentOf(real) ?: throw SshException.Sftp(SftpFailure.NotFound)
        val parent = real.dropLast(1)
        val path = generateSequence { parent + ".${real.last()}.fake-${++temporaries}" }.first { node(it) == null }
        val file = File(ByteArray(0), mode)
        folder.children[path.last()] = file
        return Temporary(path, file)
    }

    /** Fails if the file is no longer where the write left it. */
    @Synchronized
    internal fun checkInPlace(temporary: Temporary) {
        if (node(temporary.path) !== temporary.file) throw SshException.Sftp(SftpFailure.Failed)
    }

    @Synchronized
    internal fun append(temporary: Temporary, chunk: ByteArray) {
        checkInPlace(temporary)
        temporary.file.bytes += chunk
    }

    /** Removes the file wherever a rename took it, if it still exists. */
    @Synchronized
    internal fun discard(temporary: Temporary) {
        top.removeNode(temporary.file)
    }

    private fun Directory.removeNode(target: Node): Boolean {
        val name = children.entries.firstOrNull { it.value === target }?.key
        if (name != null) {
            children.remove(name)
            return true
        }
        return children.values.filterIsInstance<Directory>().any { it.removeNode(target) }
    }

    /** Puts a whole node in place of what is at [to], which readers see in one step. */
    @Synchronized
    internal fun move(from: List<String>, to: List<String>) {
        val parent = parentOf(from) ?: return
        val moved = parent.children.remove(from.last()) ?: return
        parentOf(to)?.children?.set(to.last(), moved)
    }

    @Synchronized
    internal fun remove(path: List<String>) {
        parentOf(path)?.children?.remove(path.last())
    }

    @Synchronized
    internal fun addDirectory(real: List<String>) {
        parentOf(real)?.children?.set(real.last(), Directory())
    }

    /** Runs a check and a change that must not interleave with another client's. */
    internal fun <T> atomically(block: () -> T): T = synchronized(this, block)

    companion object {
        /** Mode 0644, what a server's umask usually leaves. */
        const val DEFAULT_FILE_MODE: Int = 0b110_100_100

        private const val MAX_LINK_HOPS = 40

        internal fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
    }
}
