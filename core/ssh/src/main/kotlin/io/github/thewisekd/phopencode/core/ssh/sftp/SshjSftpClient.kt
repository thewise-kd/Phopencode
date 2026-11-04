package io.github.thewisekd.phopencode.core.ssh.sftp

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SftpWriteOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.connect.RedactedFailure
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import io.github.thewisekd.phopencode.core.ssh.sftp.read.SftpReader
import io.github.thewisekd.phopencode.core.ssh.sftp.read.transportFailed
import io.github.thewisekd.phopencode.core.ssh.sftp.write.SftpWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.SFTPClient

/**
 * The [SftpClient] of an [SshjSession]: the read side ([SftpReader]) and the write side
 * ([SftpWriter]) over one SFTP channel, which the session tracks and this client closes once.
 *
 * Paths: both sides check the text with the same [SftpRoot] rules before any request, so a path is
 * rejected or accepted alike by a read and a change. Both then walk the path on the server like
 * the kernel does, with [RealPaths], and stay inside the real root at every step and symlink hop
 * (see [SftpReader] and [SftpWriter]): a symlink inside the root that points outside can be listed,
 * `lstat`ed and `readLink`ed as a link, but neither read nor changed through.
 *
 * Failures are [SshException.Sftp], or [SshException.Disconnected] once the connection is gone. A
 * failure of the caller's source stream while writing is reported as [SftpFailure.Failed] (or
 * Disconnected, when the connection ended meanwhile), with its message redacted; a failure of the
 * caller's sink or progress callback passes through unchanged. After [close], every call fails
 * with [IllegalStateException].
 */
internal class SshjSftpClient private constructor(
    private val reader: SftpReader,
    private val writer: SftpWriter,
    private val connected: () -> Boolean,
    private val release: () -> Unit,
) : SftpClient {
    private val closed = AtomicBoolean()

    override val root: String get() = writer.root

    override suspend fun home(): String = open().home()

    override suspend fun list(path: String): List<FileEntry> = open().list(path)

    override suspend fun stat(path: String): FileEntry = open().stat(path)

    override suspend fun lstat(path: String): FileEntry = open().lstat(path)

    override suspend fun readLink(path: String): String = open().readLink(path)

    override suspend fun read(path: String, maxBytes: Long): ByteArray = open().read(path, maxBytes)

    override suspend fun download(path: String, sink: OutputStream, onProgress: (Long) -> Unit): Long =
        open().download(path, sink, onProgress)

    override suspend fun writeAtomic(
        path: String,
        source: InputStream,
        options: SftpWriteOptions,
        onProgress: (Long) -> Unit,
    ) {
        open()
        try {
            writer.writeAtomic(path, SourceReads(source), options, onProgress)
        } catch (failure: SourceFailure) {
            // Only the source's reads are marked; the progress callback's failures pass as they
            // are. The source's text is the caller's and may quote a path.
            val cause = RedactedFailure.of(failure.original())
            throw if (connected()) SshException.Sftp(SftpFailure.Failed, cause) else SshException.Disconnected(cause)
        }
    }

    /** Marks a failure of the caller's source stream, so it is told apart from everything else. */
    private class SourceFailure(cause: IOException) : IOException(cause) {
        /** Stack trace recovery may wrap a copy around this one; the first other cause is the source's. */
        fun original(): Throwable =
            generateSequence<Throwable>(this) { it.cause.takeIf { cause -> cause !== it } }
                .take(MAX_CAUSE_DEPTH)
                .firstOrNull { it !is SourceFailure } ?: this
    }

    /** The caller's source, with every read failure marked as [SourceFailure]. It never closes the source. */
    private class SourceReads(private val source: InputStream) : InputStream() {
        override fun read(): Int = marked { source.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = marked { source.read(b, off, len) }

        override fun available(): Int = marked { source.available() }

        override fun close() = Unit

        private inline fun <T> marked(block: () -> T): T = try {
            block()
        } catch (failure: IOException) {
            throw SourceFailure(failure)
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) = changes().delete(path, recursive)

    override suspend fun rename(from: String, to: String, overwrite: Boolean) = changes().rename(from, to, overwrite)

    override suspend fun mkdir(path: String, parents: Boolean) = changes().mkdir(path, parents)

    /** Closes the channel once. The session stays open. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reader.close()
        writer.close()
        release()
    }

    override fun toString(): String = "SshjSftpClient(closed=${closed.get()})"

    private fun open(): SftpReader {
        check(!closed.get()) { "The SFTP client is closed" }
        return reader
    }

    private fun changes(): SftpWriter {
        open()
        return writer
    }

    companion object {
        private const val MAX_CAUSE_DEPTH = 8

        /**
         * Opens one SFTP channel on [session] and the client over it, confined to [root].
         *
         * A malformed [root] fails with [SftpFailure.PathRejected] before the channel opens; a
         * missing one, or one that is not a folder, fails with [SshException.Sftp] and leaves no
         * channel. The channel is handed to [SshjSession.track], so it closes with the session. A
         * failure or cancellation while opening leaves no channel behind.
         *
         * @throws SshException.Disconnected when the session is closed or lost.
         */
        suspend fun open(session: SshjSession, root: String): SftpClient {
            SftpRoot.of(root)
            session.requireConnected()
            val connected = { session.connected.value && session.client.isConnected }
            // Opening is bounded by sshj's timeouts and runs to the end. withContext drops its
            // result when the caller is cancelled meanwhile, so the client is kept where the catch
            // below sees it, and closed there when the caller is gone.
            var opened: SshjSftpClient? = null
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    val sftp: SFTPClient = try {
                        session.client.newSFTPClient()
                    } catch (failure: Exception) {
                        val cause = RedactedFailure.of(failure)
                        // A lost session says Disconnected; a server without SFTP says Failed.
                        throw if (connected() && !transportFailed(failure)) {
                            SshException.Sftp(SftpFailure.Failed, cause)
                        } else {
                            SshException.Disconnected(cause)
                        }
                    }
                    if (!session.track(sftp)) throw SshException.Disconnected()
                    val release = {
                        session.untrack(sftp)
                        runCatching { sftp.close() }
                        Unit
                    }
                    opened = try {
                        // The writer checks on the server that the root is a folder.
                        val writer = SftpWriter.open(sftp, root)
                        SshjSftpClient(SftpReader(sftp, root, connected), writer, connected, release)
                    } catch (failure: Throwable) {
                        release()
                        throw failure
                    }
                }
                currentCoroutineContext().ensureActive()
                return checkNotNull(opened)
            } catch (failure: Throwable) {
                opened?.close()
                throw failure
            }
        }
    }
}
