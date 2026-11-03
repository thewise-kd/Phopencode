package io.github.thewisekd.phopencode.core.testing.ssh

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SftpWriteOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private fun failure(reason: SftpFailure): Nothing = throw SshException.Sftp(reason)

/**
 * An SFTP channel over a [FakeRemoteFiles], with the confinement rules of [SftpClient].
 *
 * The rules cover changes, as the interface says: reading through a symlink that leaves [root] works
 * here, and writing through one does not. After [close] a call fails with [SftpFailure.Failed]; the
 * interface leaves that open, and a real client's channel is gone too.
 *
 * @param files The tree. Share it with the test, or with other clients of one session.
 */
class FakeSftpClient(
    override val root: String,
    private val files: FakeRemoteFiles = FakeRemoteFiles(),
) : SftpClient {
    private val rootSegments = FakeRemoteFiles.segments(root)
    private val failures = ArrayDeque<SshException>()
    private val requestCount = AtomicInteger()

    @Volatile
    private var closed = false

    @Volatile
    private var disconnected = false

    /** The requests that reached the server. A path the rules refuse sends none. */
    val requests: Int
        get() = requestCount.get()

    /** The next call that passes the path rules fails with [failure] instead. */
    fun failNext(failure: SshException) {
        synchronized(failures) { failures.addLast(failure) }
    }

    /** Ends the connection. Later calls, and transfers under way, fail with [SshException.Disconnected]. */
    fun disconnect() {
        disconnected = true
    }

    override fun close() {
        closed = true
    }

    /** The path as absolute segments, or [SftpFailure.PathRejected] if the rules refuse it. */
    private fun confined(path: String): List<String> {
        if ('\u0000' in path) failure(SftpFailure.PathRejected)
        val segments = FakeRemoteFiles.segments(path)
        if (segments.any { it == "." || it == ".." }) failure(SftpFailure.PathRejected)
        if (!path.startsWith('/')) return rootSegments + segments
        if (!segments.isInsideRoot()) failure(SftpFailure.PathRejected)
        return segments
    }

    private fun List<String>.isInsideRoot() = take(rootSegments.size) == rootSegments

    private fun request() {
        checkConnected()
        if (closed) failure(SftpFailure.Failed)
        requestCount.incrementAndGet()
        synchronized(failures) { failures.removeFirstOrNull() }?.let { throw it }
    }

    private fun checkConnected() {
        if (disconnected) throw SshException.Disconnected()
    }

    /** The root as the server sees it: the root itself may be a symlink. */
    private fun realRoot() = files.realPath(rootSegments, followLast = true)

    /** Where a change to [segments] would land. */
    private fun changing(segments: List<String>, followLast: Boolean = false): List<String> {
        val real = files.realPath(segments, followLast || segments == rootSegments)
        val root = realRoot()
        if (real.take(root.size) != root) failure(SftpFailure.PathRejected)
        files.checkAllowed(real)
        return real
    }

    /** Like [changing], for a change that would take the root away, however the path spells it. */
    private fun changingBelowRoot(segments: List<String>): List<String> {
        val real = changing(segments)
        if (real == realRoot()) failure(SftpFailure.Failed)
        return real
    }

    private fun reading(segments: List<String>, followLast: Boolean): List<String> =
        files.realPath(segments, followLast).also(files::checkAllowed)

    private fun entry(segments: List<String>, node: FakeRemoteFiles.Node): FileEntry {
        val path = segments.joinToString("/", prefix = "/")
        return when (node) {
            is FakeRemoteFiles.File -> FileEntry(path, FileEntryType.File, node.bytes.size.toLong())
            is FakeRemoteFiles.Directory -> FileEntry(path, FileEntryType.Directory)
            is FakeRemoteFiles.Link -> FileEntry(path, FileEntryType.Symlink, node.target.length.toLong())
        }
    }

    private fun fileAt(real: List<String>): FakeRemoteFiles.File = when (val node = files.atomically { files.node(real) }) {
        null -> failure(SftpFailure.NotFound)
        is FakeRemoteFiles.File -> node
        else -> failure(SftpFailure.Failed)
    }

    override suspend fun list(path: String): List<FileEntry> {
        val segments = confined(path)
        request()
        val real = reading(segments, followLast = true)
        return files.atomically {
            when (val node = files.node(real)) {
                null -> failure(SftpFailure.NotFound)
                is FakeRemoteFiles.Directory -> node.children.map { (name, child) -> entry(segments + name, child) }
                else -> failure(SftpFailure.Failed)
            }
        }
    }

    private fun report(path: String, followLast: Boolean): FileEntry {
        val segments = confined(path)
        request()
        val real = reading(segments, followLast)
        return entry(segments, files.atomically { files.node(real) } ?: failure(SftpFailure.NotFound))
    }

    override suspend fun stat(path: String): FileEntry = report(path, followLast = true)

    override suspend fun lstat(path: String): FileEntry = report(path, followLast = false)

    override suspend fun readLink(path: String): String {
        val segments = confined(path)
        request()
        val real = reading(segments, followLast = false)
        return when (val node = files.atomically { files.node(real) }) {
            null -> failure(SftpFailure.NotFound)
            is FakeRemoteFiles.Link -> node.target
            else -> failure(SftpFailure.Failed)
        }
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray {
        val segments = confined(path)
        request()
        val bytes = fileAt(reading(segments, followLast = true)).bytes
        if (bytes.size > maxBytes) failure(SftpFailure.TooLarge)
        return bytes.copyOf()
    }

    override suspend fun download(path: String, sink: OutputStream, onProgress: (Long) -> Unit): Long {
        val segments = confined(path)
        request()
        val bytes = fileAt(reading(segments, followLast = true)).bytes
        var written = 0
        while (written < bytes.size) {
            currentCoroutineContext().ensureActive()
            checkConnected()
            val size = minOf(CHUNK_BYTES, bytes.size - written)
            try {
                sink.write(bytes, written, size)
            } catch (_: IOException) {
                failure(SftpFailure.Failed)
            }
            written += size
            onProgress(written.toLong())
        }
        currentCoroutineContext().ensureActive()
        checkConnected()
        return written.toLong()
    }

    override suspend fun writeAtomic(
        path: String,
        source: InputStream,
        options: SftpWriteOptions,
        onProgress: (Long) -> Unit,
    ) {
        val segments = confined(path)
        request()
        val real = changing(segments, followLast = true)
        val existing = files.atomically { existingFile(real, options.overwrite) }
        val temporary = files.addTemporary(real, SftpWriteOptions.OWNER_ONLY)
        var finished = false
        try {
            stream(source, temporary, onProgress)
            files.atomically {
                files.checkInPlace(temporary)
                val replaced = existingFile(real, options.overwrite)
                val mode = options.permissions ?: replaced?.mode ?: existing?.mode ?: FakeRemoteFiles.DEFAULT_FILE_MODE
                temporary.file.mode = mode
                files.move(temporary.path, real)
            }
            finished = true
        } finally {
            if (!finished) files.discard(temporary)
        }
    }

    /** The file a write would replace, after the checks that can refuse it. */
    private fun existingFile(real: List<String>, overwrite: Boolean): FakeRemoteFiles.File? {
        if (files.parentOf(real) == null) failure(SftpFailure.NotFound)
        return when (val node = files.node(real)) {
            null -> null
            is FakeRemoteFiles.File -> if (overwrite) node else failure(SftpFailure.AlreadyExists)
            else -> failure(if (overwrite) SftpFailure.Failed else SftpFailure.AlreadyExists)
        }
    }

    private suspend fun stream(
        source: InputStream,
        temporary: FakeRemoteFiles.Temporary,
        onProgress: (Long) -> Unit,
    ) {
        val buffer = ByteArray(CHUNK_BYTES)
        var sent = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            checkConnected()
            val count = try {
                source.read(buffer)
            } catch (_: IOException) {
                checkConnected()
                failure(SftpFailure.Failed)
            }
            if (count < 0) break
            files.append(temporary, buffer.copyOf(count))
            sent += count
            if (count > 0) onProgress(sent)
        }
        currentCoroutineContext().ensureActive()
        checkConnected()
    }

    override suspend fun delete(path: String, recursive: Boolean) {
        val segments = confined(path)
        request()
        val real = changingBelowRoot(segments)
        files.atomically {
            when (val node = files.node(real)) {
                null -> failure(SftpFailure.NotFound)
                is FakeRemoteFiles.Directory -> if (node.children.isNotEmpty() && !recursive) failure(SftpFailure.NotEmpty)
                else -> Unit
            }
            files.remove(real)
        }
    }

    override suspend fun rename(from: String, to: String, overwrite: Boolean) {
        val source = confined(from)
        val target = confined(to)
        request()
        val realSource = changingBelowRoot(source)
        val realTarget = changingBelowRoot(target)
        files.atomically {
            files.node(realSource) ?: failure(SftpFailure.NotFound)
            if (files.parentOf(realTarget) == null) failure(SftpFailure.NotFound)
            val replaced = files.node(realTarget)
            if (replaced != null && !overwrite) failure(SftpFailure.AlreadyExists)
            if (realTarget.size > realSource.size && realTarget.take(realSource.size) == realSource) {
                failure(SftpFailure.Failed)
            }
            // As rename(2): a folder replaces only a folder, and anything else only a non-folder.
            val moved = files.node(realSource)
            if (replaced != null && (replaced is FakeRemoteFiles.Directory) != (moved is FakeRemoteFiles.Directory)) {
                failure(SftpFailure.Failed)
            }
            if (replaced is FakeRemoteFiles.Directory && replaced.children.isNotEmpty()) failure(SftpFailure.NotEmpty)
            if (realSource != realTarget) files.move(realSource, realTarget)
        }
    }

    override suspend fun mkdir(path: String, parents: Boolean) {
        val segments = confined(path)
        request()
        if (parents) {
            (rootSegments.size..segments.size).forEach { depth -> ensureFolder(segments.take(depth)) }
            return
        }
        val real = changing(segments)
        files.atomically {
            if (files.parentOf(real) == null) failure(SftpFailure.NotFound)
            if (files.node(real) != null) failure(SftpFailure.AlreadyExists)
            files.addDirectory(real)
        }
    }

    private fun ensureFolder(segments: List<String>) {
        val real = changing(segments, followLast = true)
        files.atomically {
            when (files.node(real)) {
                null -> {
                    if (files.parentOf(real) == null) failure(SftpFailure.NotFound)
                    files.addDirectory(real)
                }
                is FakeRemoteFiles.Directory -> Unit
                else -> failure(SftpFailure.AlreadyExists)
            }
        }
    }

    private companion object {
        const val CHUNK_BYTES = 32 * 1024
    }
}
