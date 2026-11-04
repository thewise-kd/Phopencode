package io.github.thewisekd.phopencode.core.ssh.sftp.read

import com.hierynomus.sshj.sftp.RemoteResourceSelector
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.connect.RedactedFailure
import io.github.thewisekd.phopencode.core.ssh.sftp.RealPaths
import io.github.thewisekd.phopencode.core.ssh.sftp.SftpRoot
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException

/**
 * The read side of `SftpClient` over one sshj SFTP channel: [list], [stat], [lstat], [readLink],
 * [read] and [download], with the signatures and the meaning the `SftpClient` KDoc gives them.
 *
 * Every path goes through [SftpRoot] first, the path policy the write side shares, so a rejected
 * path never reaches the server. Then, on the server, [RealPaths] walks it like the kernel does,
 * with the same bounds as the write side: every segment, `..` and symlink hop must stay inside the
 * real root, else the read fails with [SftpFailure.PathRejected], fixed text and no cause, so no
 * outside path is revealed. A symlink inside the root that points inside it works as usual. This
 * costs one `lstat` per path segment, and the walk and the request are separate, so something that
 * swaps a folder for a symlink in between can still win that race.
 *
 * [lstat] and [readLink] do not follow the last segment: the link entry itself is reported, and
 * [readLink] gives its target as stored, wherever it points. A link in an earlier segment is
 * followed and must stay inside, as for any other read. For the root itself they look at the root
 * as configured, so a symlinked root reports as the link. [list] reports symlinks as symlinks without
 * following them. Entries carry absolute, normalised paths under the root as configured (not the
 * real one), which this reader accepts back.
 *
 * Failures are [SshException.Sftp], or [SshException.Disconnected] once [connected] is false. Their
 * causes are redacted: sshj's messages can quote a path or the server's text. A failure of the
 * caller's own sink or callback is not one of these: it passes through unchanged. Cancellation is
 * checked between network reads and between directory entries; a single request that the server
 * never answers ends with sshj's request timeout or with the connection.
 *
 * @param connected Whether the connection under [sftp] is still up.
 * @param onClose Releases the channel; runs once, on the first [close].
 */
internal class SftpReader(
    private val sftp: SFTPClient,
    root: String,
    private val connected: () -> Boolean = { true },
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    private val paths = SftpRoot.of(root)
    private val closed = AtomicBoolean()

    /** Bound to the real root, found on the server at the first read that needs it. */
    @Volatile
    private var realPaths: RealPaths? = null

    val root: String get() = paths.path

    /**
     * The direct children of a folder, without `.` and `..`. A symlink is reported as a symlink.
     *
     * The server sends a large folder in batches. Cancellation is checked before each entry of a
     * batch, so a cancelled listing asks for no further batch and closes the folder handle.
     */
    suspend fun list(path: String): List<FileEntry> {
        val folder = paths.resolve(path)
        return remote {
            sftpCall { sftp.sftpEngine.openDir(locate(folder, followLast = true)) }.closing { directory ->
                val untilCancelled = RemoteResourceSelector {
                    if (isActive) RemoteResourceSelector.Result.ACCEPT else RemoteResourceSelector.Result.BREAK
                }
                val entries = sftpCall { directory.scan(untilCancelled) }
                ensureActive()
                entries.filter { it.name != "." && it.name != ".." }.map { entry(child(folder, it.name), it.attributes) }
            }
        }
    }

    /** Follows symlinks. */
    suspend fun stat(path: String): FileEntry {
        val remotePath = paths.resolve(path)
        return remote { entry(remotePath, sftpCall { sftp.sftpEngine.stat(locate(remotePath, followLast = true)) }) }
    }

    /** Does not follow symlinks. */
    suspend fun lstat(path: String): FileEntry {
        val remotePath = paths.resolve(path)
        return remote { entry(remotePath, sftpCall { sftp.sftpEngine.lstat(locate(remotePath, followLast = false)) }) }
    }

    /** A symlink's target as stored, which may be relative. */
    suspend fun readLink(path: String): String {
        val remotePath = paths.resolve(path)
        return remote { sftpCall { sftp.sftpEngine.readLink(locate(remotePath, followLast = false)) } }
    }

    /**
     * Reads a whole file into memory, byte for byte. Fails with [SftpFailure.TooLarge] above
     * [maxBytes], without reading the rest: a size the server reports above the limit fails before
     * any read, and otherwise no read goes past the limit by more than one byte.
     */
    suspend fun read(path: String, maxBytes: Long): ByteArray {
        require(maxBytes >= 0) { "The limit must not be negative" }
        val remotePath = paths.resolve(path)
        return remote {
            open(locate(remotePath, followLast = true)).closing { file ->
                val attributes = sftpCall { file.fetchAttributes() }
                if (attributes.has(FileAttributes.Flag.SIZE) && attributes.size > maxBytes) throw tooLarge()
                // One byte past the limit tells a file of exactly maxBytes from a larger one.
                val limit = minOf(maxBytes, MAX_ARRAY_BYTES)
                val content = ByteArrayOutputStream(minOf(limit, INITIAL_CAPACITY).toInt())
                val total = copy(stream(file, length = limit + 1), content, stopAfter = limit + 1)
                if (total > maxBytes || total > MAX_ARRAY_BYTES) throw tooLarge()
                content.toByteArray()
            }
        }
    }

    /**
     * Streams a file into [sink], byte for byte. A failure can leave [sink] partly written. [sink]
     * stays open.
     *
     * A failure of [sink] or of [onProgress] belongs to the caller and passes through unchanged,
     * after the file handle is closed; only the server's side is translated.
     *
     * @param onProgress Gets the total bytes written so far, after each write.
     * @return The total bytes written.
     */
    suspend fun download(path: String, sink: OutputStream, onProgress: (Long) -> Unit = {}): Long {
        val remotePath = paths.resolve(path)
        return remote {
            open(locate(remotePath, followLast = true)).closing { file ->
                copy(stream(file, length = 0), sink, onProgress = onProgress)
            }
        }
    }

    /** Safe to call more than once. The session stays open. */
    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }

    override fun toString(): String = "SftpReader(closed=${closed.get()})"

    /**
     * The real location of [logical], a path [paths] accepted, after a walk that stays inside the
     * real root; see [RealPaths.locate].
     *
     * Without [followLast], the root itself is the root as configured, not followed: a symlinked
     * root then reports as the link, as any other last segment does.
     *
     * Cancellation is checked before each request of the walk.
     */
    private fun CoroutineScope.locate(logical: String, followLast: Boolean): String = sftpCall {
        if (!followLast && logical == paths.path) return@sftpCall logical
        val checkpoint = { ensureActive() }
        val bounded = realPaths ?: bind(checkpoint)
        bounded.locate(logical, followLast, checkpoint)
    }

    /**
     * Finds the real root. It is kept once it exists, so that a root that is missing now is looked
     * up again later.
     */
    private fun bind(checkpoint: () -> Unit): RealPaths {
        val free = RealPaths(sftp.sftpEngine, null)
        val real = free.locate(paths.path, followLast = true, checkpoint)
        val bounded = RealPaths(sftp.sftpEngine, RealPaths.Bounds(SftpRoot.of(real), paths))
        if (free.lstatOrNull(real) != null) realPaths = bounded
        return bounded
    }

    private fun open(remotePath: String): RemoteFile =
        sftpCall { sftp.sftpEngine.open(remotePath, EnumSet.of(OpenMode.READ)) }

    /** @param length 0 for the whole file; otherwise no read-ahead goes past it. */
    private fun stream(file: RemoteFile, length: Long): InputStream =
        file.ReadAheadRemoteFileInputStream(MAX_UNCONFIRMED_READS, 0, length)

    /** Only [source] is the server's side; [sink] and [onProgress] are the caller's. */
    private fun CoroutineScope.copy(
        source: InputStream,
        sink: OutputStream,
        stopAfter: Long = Long.MAX_VALUE,
        onProgress: (Long) -> Unit = {},
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (total < stopAfter) {
            ensureActive()
            val count = sftpCall { source.read(buffer, 0, minOf(buffer.size.toLong(), stopAfter - total).toInt()) }
            if (count < 0) break
            if (count == 0) continue
            sink.write(buffer, 0, count)
            total += count
            onProgress(total)
        }
        return total
    }

    /** Runs [block] on the IO dispatcher, once the connection and this reader are known to be open. */
    private suspend fun <T> remote(block: CoroutineScope.() -> T): T {
        if (!connected()) throw SshException.Disconnected()
        check(!closed.get()) { "The SFTP reader is closed" }
        return withContext(Dispatchers.IO) { block() }
    }

    /**
     * Wraps one call into sshj, and only that: its [IOException]s become this module's failures.
     * Nothing of the caller's runs inside.
     */
    private inline fun <T> sftpCall(block: () -> T): T = try {
        block()
    } catch (failure: SshException) {
        throw failure
    } catch (failure: IOException) {
        throw translate(failure)
    }

    /**
     * Runs [block] and closes this handle, also when [block] fails or is cancelled. A failure to
     * close after a failure is dropped, so that the first failure, possibly the caller's own,
     * passes on unchanged; its text, which could quote a path, is not attached.
     */
    private inline fun <R : Closeable, T> R.closing(block: (R) -> T): T {
        val result = try {
            block(this)
        } catch (failure: Throwable) {
            runCatching { close() }
            throw failure
        }
        sftpCall { close() }
        return result
    }

    private fun translate(failure: IOException): SshException {
        val cause = RedactedFailure.of(failure)
        val status = (failure as? SFTPException)?.statusCode
        val known = when (status) {
            StatusCode.NO_SUCH_FILE, StatusCode.NO_SUCH_PATH -> SftpFailure.NotFound
            StatusCode.PERMISSION_DENIED, StatusCode.WRITE_PROTECT -> SftpFailure.PermissionDenied
            StatusCode.FILE_ALREADY_EXISTS -> SftpFailure.AlreadyExists
            StatusCode.DIR_NOT_EMPTY -> SftpFailure.NotEmpty
            else -> null
        }
        return when {
            known != null -> SshException.Sftp(known, cause)
            status == StatusCode.NO_CONNECTION || status == StatusCode.CONNECITON_LOST -> SshException.Disconnected(cause)
            transportFailed(failure) || !connected() -> SshException.Disconnected(cause)
            else -> SshException.Sftp(SftpFailure.Failed, cause)
        }
    }

    private companion object {
        /** sshj's default for its own transfers. */
        const val MAX_UNCONFIRMED_READS = 16
        const val BUFFER_BYTES = 32 * 1024
        const val INITIAL_CAPACITY = 64L * 1024

        /** The largest array the JVM reliably allocates. */
        const val MAX_ARRAY_BYTES = Int.MAX_VALUE - 8L

        fun tooLarge() = SshException.Sftp(SftpFailure.TooLarge)

        fun child(folder: String, name: String): String = if (folder == "/") "/$name" else "$folder/$name"

        fun entry(path: String, attributes: FileAttributes): FileEntry = FileEntry(
            path = path,
            type = typeOf(attributes),
            sizeBytes = attributes.size.takeIf { attributes.has(FileAttributes.Flag.SIZE) },
            modifiedAt = Instant.fromEpochSeconds(attributes.mtime).takeIf { attributes.has(FileAttributes.Flag.ACMODTIME) },
        )

        fun typeOf(attributes: FileAttributes): FileEntryType {
            if (!attributes.has(FileAttributes.Flag.MODE)) return FileEntryType.Unknown("unknown")
            return when (attributes.type) {
                FileMode.Type.REGULAR -> FileEntryType.File
                FileMode.Type.DIRECTORY -> FileEntryType.Directory
                FileMode.Type.SYMLINK -> FileEntryType.Symlink
                FileMode.Type.BLOCK_SPECIAL -> FileEntryType.Unknown("block")
                FileMode.Type.CHAR_SPECIAL -> FileEntryType.Unknown("char")
                FileMode.Type.FIFO_SPECIAL -> FileEntryType.Unknown("fifo")
                FileMode.Type.SOCKET_SPECIAL -> FileEntryType.Unknown("socket")
                FileMode.Type.UNKNOWN, null -> FileEntryType.Unknown("unknown")
            }
        }
    }
}

private const val MAX_CAUSE_DEPTH = 8

/**
 * Whether sshj blames the connection itself. A request pending when the connection ends fails with
 * the transport's failure as its cause, often before the session has noticed the loss.
 */
internal fun transportFailed(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSE_DEPTH)
        .any { it is TransportException }
