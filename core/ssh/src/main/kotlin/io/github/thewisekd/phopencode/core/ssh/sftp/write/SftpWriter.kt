package io.github.thewisekd.phopencode.core.ssh.sftp.write

import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SftpWriteOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.sftp.RealPaths
import io.github.thewisekd.phopencode.core.ssh.sftp.SftpRoot
import io.github.thewisekd.phopencode.core.ssh.sftp.SftpRoot.Companion.child
import io.github.thewisekd.phopencode.core.ssh.sftp.SftpRoot.Companion.nameOf
import io.github.thewisekd.phopencode.core.ssh.sftp.SftpRoot.Companion.parentOf
import io.github.thewisekd.phopencode.core.ssh.sftp.SftpRoot.Companion.rejected
import java.io.InputStream
import java.security.SecureRandom
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.PacketType
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceFilter
import net.schmizz.sshj.sftp.Request
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException

/**
 * The write half of [SftpClient] over one sshj SFTP channel: [writeAtomic], [delete], [rename] and
 * [mkdir], with the signatures and the rules of that interface. `SshjSftpClient` composes it with
 * the read half into one [SftpClient] over the same channel.
 *
 * Paths: [SftpRoot] checks the text before any request. Then, on the server, [RealPaths] finds
 * where the folder that holds the changed name really is, and every symlink on the way must land
 * inside the real root, so a symlinked folder cannot lead a change outside. This costs one `lstat`
 * per path segment. [writeAtomic] also follows a symlink at the target itself, under the same rule.
 * The checks and the change are separate requests: something else that swaps a folder for a
 * symlink in between can still win that race.
 *
 * [writeAtomic] writes `.phopencode-<128 random bits>.tmp` in the target's real folder, created
 * exclusively and owner-only, syncs it with `fsync@openssh.com` when the server offers it, gives it
 * its final mode, and renames it over the target:
 * - With `posix-rename@openssh.com`, one atomic rename replaces the target.
 * - Without it, plain SFTP rename never replaces, so the old target is first renamed aside to a
 *   `.bak` name, then the new file is renamed into place and the old one removed. Between those two
 *   renames the target is missing: readers see the old file, no file, or the new one, never a mix.
 *   If the second rename fails, the old file is renamed back. If removing the old file fails at
 *   the end, it stays under its `.bak` name; the write still counts as done. [rename] with
 *   overwrite uses the same steps.
 *
 * The final mode is [SftpWriteOptions.permissions], else the replaced file's mode, else the mode the
 * server gives a new file, learned from an empty probe file created and removed beside it. Owner and
 * group are not carried over: the new file belongs to the login user.
 *
 * A [SftpWriteOptions.overwrite] write never replaces a folder: that gives
 * [SftpFailure.AlreadyExists], as without overwrite. A failure of the caller's source stream is
 * thrown as it is, after the cleanup. A cancellation that arrives once the final rename is done is
 * too late to undo it: the write then returns normally, and the caller stays cancelled.
 *
 * Nothing here logs; paths and content never leave through messages, which are fixed text.
 */
internal class SftpWriter private constructor(
    private val engine: SFTPEngine,
    private val paths: SftpRoot,
    private val realRoot: SftpRoot,
    private val onClose: () -> Unit,
    private val hooks: WriteHooks,
) : AutoCloseable {
    private val real = RealPaths(engine, RealPaths.Bounds(realRoot, paths))
    private val closed = AtomicBoolean()

    val root: String get() = paths.path

    suspend fun writeAtomic(
        path: String,
        source: InputStream,
        options: SftpWriteOptions = SftpWriteOptions(),
        onProgress: (Long) -> Unit = {},
    ) {
        val logical = changeable(path)
        val target = io { resolveWriteTarget(logical) }
        val existing = target.attributes
        if (existing != null && (existing.type == FileMode.Type.DIRECTORY || !options.overwrite)) {
            throw SshException.Sftp(SftpFailure.AlreadyExists)
        }
        val folder = parentOf(target.path)
        val temp = child(folder, randomName(TEMP_SUFFIX))
        // Set inside the I/O blocks, not from their results: withContext drops its result when the
        // caller is cancelled meanwhile, and the cleanup must still see the open handle, or know
        // that the rename happened.
        var file: OpenFile? = null
        var committed = false
        try {
            val open = io {
                openExclusive(temp, OWNER_ONLY).also {
                    file = it
                    hooks.afterTempOpened()
                }
            }
            val out = open.remote.RemoteFileOutputStream(0L, MAX_UNCONFIRMED_WRITES)
            val buffer = ByteArray(CHUNK_BYTES)
            var total = 0L
            while (true) {
                val read = runInterruptible(Dispatchers.IO) { source.read(buffer) }
                if (read < 0) break
                currentCoroutineContext().ensureActive()
                if (read == 0) continue
                io { out.write(buffer, 0, read) }
                total += read
                onProgress(total)
            }
            io {
                out.flush()
                val mode = options.permissions ?: existing?.permissions() ?: defaultMode(folder)
                open.remote.setAttributes(FileAttributes.Builder().withPermissions(mode).build())
                if (engine.supportsServerExtension(FSYNC, OPENSSH)) {
                    engine.newExtendedRequest("$FSYNC@$OPENSSH").putString(open.handle).let(::await)
                        .ensureStatusPacketIsOK()
                }
                open.remote.close()
                file = null
            }
            // Once the rename is on its way, its outcome is the outcome: no cancellation between.
            io(NonCancellable) {
                replace(temp, target.path, options.overwrite)
                committed = true
                hooks.afterCommit()
            }
        } catch (e: CancellationException) {
            // A cancellation that arrives after the rename is too late: the file is replaced, so
            // the write reports done. The caller's coroutine stays cancelled.
            if (!committed) throw e
        } finally {
            if (!committed) {
                withContext(NonCancellable + Dispatchers.IO) {
                    file?.let { runCatching { it.remote.close() } }
                    runCatching { engine.remove(temp) }
                }
            }
        }
    }

    suspend fun delete(path: String, recursive: Boolean = false) {
        val logical = changeable(path)
        io {
            val real = realChild(logical)
            val attributes = engine.lstat(real)
            when {
                attributes.type != FileMode.Type.DIRECTORY -> engine.remove(real)
                recursive -> deleteTree(real)
                else -> removeEmptyFolder(real)
            }
        }
    }

    suspend fun rename(from: String, to: String, overwrite: Boolean = false) {
        val source = changeable(from)
        val destination = changeable(to)
        io {
            val realSource = realChild(source)
            val realDestination = realChild(destination)
            engine.lstat(realSource)
            if (realSource == realDestination) return@io
            if (!overwrite && lstatOrNull(realDestination) != null) throw SshException.Sftp(SftpFailure.AlreadyExists)
            replace(realSource, realDestination, overwrite)
        }
    }

    suspend fun mkdir(path: String, parents: Boolean = false) {
        val logical = changeable(path)
        io {
            if (!parents) {
                val real = realChild(logical)
                try {
                    engine.makeDir(real)
                } catch (e: SFTPException) {
                    if (lstatOrNull(real) != null) throw SshException.Sftp(SftpFailure.AlreadyExists)
                    throw e
                }
                return@io
            }
            var current = realRoot.path
            for (name in paths.segments(logical)) {
                val next = child(current, name)
                val attributes = lstatOrNull(next)
                current = when (attributes?.type) {
                    null -> {
                        try {
                            engine.makeDir(next)
                        } catch (e: SFTPException) {
                            // Someone else may have made it meanwhile.
                            if (lstatOrNull(next)?.type != FileMode.Type.DIRECTORY) throw e
                        }
                        next
                    }
                    FileMode.Type.DIRECTORY -> next
                    FileMode.Type.SYMLINK -> {
                        val target = real.locateLink(next, followLast = true)
                        if (engine.stat(target).type != FileMode.Type.DIRECTORY) {
                            throw SshException.Sftp(SftpFailure.AlreadyExists)
                        }
                        target
                    }
                    else -> throw SshException.Sftp(SftpFailure.AlreadyExists)
                }
            }
        }
    }

    /** Closes the channel through the owner's callback, once. */
    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }

    override fun toString(): String = "SftpWriter(closed=${closed.get()})"

    // Paths

    /** The logical path of something a change may touch: inside the root, never the root itself. */
    private fun changeable(path: String): String {
        val logical = paths.resolve(path)
        if (logical == paths.path) throw rejected()
        return logical
    }

    /** [logical]'s name in the real location of its folder, which must be inside the real root. */
    private fun realChild(logical: String): String {
        val name = nameOf(logical)
        if (name.isEmpty()) throw rejected()
        return real.locate(logical, followLast = false)
    }

    private class Target(val path: String, val attributes: FileAttributes?)

    /** Follows symlinks at [logical] to the file a write replaces, or the name it creates. */
    private fun resolveWriteTarget(logical: String): Target {
        var current = realChild(logical)
        var hops = 0
        while (true) {
            val attributes = lstatOrNull(current) ?: return Target(current, null)
            if (attributes.type != FileMode.Type.SYMLINK) return Target(current, attributes)
            // Up to MAX_SYMLINKS links are followed; the one after that is a loop.
            if (++hops > RealPaths.MAX_SYMLINKS) throw SshException.Sftp(SftpFailure.Failed)
            current = real.locateLink(current, followLast = false)
        }
    }

    // Requests

    private class OpenFile(val remote: RemoteFile, val handle: ByteArray)

    /** sshj's own open hides the handle, which `fsync@openssh.com` needs. */
    private fun openExclusive(path: String, attributes: FileAttributes): OpenFile {
        val request = engine.newRequest(PacketType.OPEN)
            .putString(path, engine.subsystem.remoteCharset)
            .putUInt32(OpenMode.toMask(EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).toLong())
            .putFileAttributes(attributes)
        val handle = await(request).ensurePacketTypeIs(PacketType.HANDLE).readBytes()
        return OpenFile(RemoteFile(engine, path, handle), handle)
    }

    private fun await(request: Request): Response =
        engine.request(request).retrieve(engine.timeoutMs.toLong(), TimeUnit.MILLISECONDS)

    private fun lstatOrNull(path: String): FileAttributes? = real.lstatOrNull(path)

    /** The mode the server gives a new file in [folder], from an empty probe file. */
    private fun defaultMode(folder: String): Int {
        val probe = child(folder, randomName(PROBE_SUFFIX))
        try {
            val open = openExclusive(probe, FileAttributes.EMPTY)
            val attributes = try {
                open.remote.fetchAttributes()
            } finally {
                runCatching { open.remote.close() }
            }
            return attributes.permissions() ?: DEFAULT_FILE_MODE
        } finally {
            runCatching { engine.remove(probe) }
        }
    }

    /**
     * Renames [from] to [to]. Without [overwrite] an existing [to] fails. With it, `posix-rename`
     * replaces at once, or else the old [to] goes aside first (see the class comment).
     */
    private fun replace(from: String, to: String, overwrite: Boolean) {
        if (overwrite && engine.supportsServerExtension(POSIX_RENAME, OPENSSH)) {
            val charset = engine.subsystem.remoteCharset
            engine.newExtendedRequest("$POSIX_RENAME@$OPENSSH").putString(from, charset).putString(to, charset)
                .let(::await).ensureStatusPacketIsOK()
            return
        }
        try {
            engine.rename(from, to, emptySet())
            return
        } catch (e: SFTPException) {
            val existing = lstatOrNull(to) ?: throw e
            if (!overwrite) throw SshException.Sftp(SftpFailure.AlreadyExists, null)
            moveAside(from, to, existing, e)
        }
    }

    /** The fallback without `posix-rename`, with POSIX `rename(2)` rules for folders. */
    private fun moveAside(from: String, to: String, existing: FileAttributes, renameFailure: SFTPException) {
        val movingFolder = engine.lstat(from).type == FileMode.Type.DIRECTORY
        val replacingFolder = existing.type == FileMode.Type.DIRECTORY
        if (movingFolder != replacingFolder) throw renameFailure
        if (replacingFolder && childNames(to).isNotEmpty()) throw SshException.Sftp(SftpFailure.NotEmpty)
        val backup = child(parentOf(to), randomName(BACKUP_SUFFIX))
        engine.rename(to, backup, emptySet())
        try {
            engine.rename(from, to, emptySet())
        } catch (e: Exception) {
            runCatching { engine.rename(backup, to, emptySet()) }
            throw e
        }
        runCatching { if (replacingFolder) engine.removeDir(backup) else engine.remove(backup) }
    }

    private fun removeEmptyFolder(path: String) {
        try {
            engine.removeDir(path)
        } catch (e: SFTPException) {
            // SFTP v3 has no status for this; the server says only FAILURE.
            if (runCatching { childNames(path).isNotEmpty() }.getOrDefault(false)) {
                throw SshException.Sftp(SftpFailure.NotEmpty)
            }
            throw e
        }
    }

    /**
     * Removes a folder's content, then the folder. Every entry is checked with `lstat`, not with the
     * listing's attributes, which some servers report through symlinks; a symlink is removed as a
     * link.
     */
    private suspend fun deleteTree(folder: String) {
        for (name in childNames(folder)) {
            currentCoroutineContext().ensureActive()
            val entry = child(folder, name)
            val attributes = lstatOrNull(entry) ?: continue
            if (attributes.type == FileMode.Type.DIRECTORY) deleteTree(entry) else engine.remove(entry)
        }
        engine.removeDir(folder)
    }

    private fun childNames(folder: String): List<String> {
        val names = engine.openDir(folder).use { dir -> dir.scan(null as RemoteResourceFilter?).map { it.name } }
            .filter { it != "." && it != ".." }
        if (names.any { it.isEmpty() || '/' in it || '\u0000' in it }) throw SshException.Sftp(SftpFailure.Failed)
        return names
    }

    // Errors

    private suspend fun <T> io(
        context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> T,
    ): T {
        if (closed.get()) throw SshException.Disconnected()
        return withContext(Dispatchers.IO + context) {
            try {
                block()
            } catch (e: Exception) {
                throw failureOf(engine, e) { closed.get() }
            }
        }
    }

    companion object {
        /**
         * The writer over [sftp], confined to [root]. [onClose] runs once, at the first [close]; it
         * is where the owner of [sftp] closes it.
         *
         * @throws SshException.Sftp [SftpFailure.PathRejected] for a malformed root, before any
         *   request; [SftpFailure.NotFound] for a missing one.
         */
        suspend fun open(
            sftp: SFTPClient,
            root: String,
            onClose: () -> Unit = {},
            hooks: WriteHooks = WriteHooks(),
        ): SftpWriter {
            val paths = SftpRoot.of(root)
            val engine = sftp.sftpEngine
            val realRoot = withContext(Dispatchers.IO) {
                try {
                    val real = SftpRoot.of(RealPaths(engine, null).locate(paths.path, followLast = true))
                    if (engine.stat(real.path).type != FileMode.Type.DIRECTORY) {
                        throw SshException.Sftp(SftpFailure.Failed)
                    }
                    real
                } catch (e: Exception) {
                    throw failureOf(engine, e) { false }
                }
            }
            return SftpWriter(engine, paths, realRoot, onClose, hooks)
        }

        private const val CHUNK_BYTES = 32 * 1024
        private const val MAX_UNCONFIRMED_WRITES = 16
        private const val DEFAULT_FILE_MODE = 0b110_100_100
        private const val OPENSSH = "openssh.com"
        private const val FSYNC = "fsync"
        private const val POSIX_RENAME = "posix-rename"
        private const val TEMP_SUFFIX = ".tmp"
        private const val PROBE_SUFFIX = ".probe"
        private const val BACKUP_SUFFIX = ".bak"
        private val OWNER_ONLY = FileAttributes.Builder().withPermissions(SftpWriteOptions.OWNER_ONLY).build()
        private val random = SecureRandom()

        /** Unguessable, so that no one can prepare the name; short, so that no name gets too long. */
        internal fun randomName(suffix: String): String {
            val bytes = ByteArray(16).also(random::nextBytes)
            return ".phopencode-${hexOf(bytes)}$suffix"
        }

        /** Lowercase hex, two digits per byte; written by hand because Android has no hex API before API 34. */
        internal fun hexOf(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            bytes.forEachIndexed { i, byte ->
                val value = byte.toInt() and 0xff
                out[2 * i] = HEX_DIGITS[value ushr 4]
                out[2 * i + 1] = HEX_DIGITS[value and 0x0f]
            }
            return String(out)
        }

        private const val HEX_DIGITS = "0123456789abcdef"

        private fun FileAttributes.permissions(): Int? =
            if (has(FileAttributes.Flag.MODE)) mode.permissionsMask else null
    }
}

/** For tests: runs on the I/O thread at the points where a cancellation is hardest to handle. */
internal class WriteHooks(
    val afterTempOpened: () -> Unit = {},
    val afterCommit: () -> Unit = {},
)
