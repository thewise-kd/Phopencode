package io.github.thewisekd.phopencode.feature.files.logic.service

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.files.ContentQuery
import io.github.thewisekd.phopencode.core.files.FileError
import io.github.thewisekd.phopencode.core.files.FileException
import io.github.thewisekd.phopencode.core.files.FileService
import io.github.thewisekd.phopencode.core.files.FileWriteOptions
import io.github.thewisekd.phopencode.core.files.RemoteFileService
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.remote.connection.BoundConnection
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentHit
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentSearchTool
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileListing
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileSearch
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileTransfers
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/** Builds the file service of one call; [RemoteFileService] in the app, a fake in tests. */
fun interface FileServiceFactory {
    fun create(sftp: SftpClient, exec: RemoteExec, fs: FsApi): FileService
}

/**
 * The file ports on the file service (`core/files`) of the server [connection] stands on.
 *
 * Each call is bound to the connection that stands when it starts, through
 * [ConnectionManager.withConnection], and takes everything from that [BoundConnection] only:
 * - An SFTP client confined to the call's `directory`, opened on that connection's SSH session
 *   with [BoundConnection.openSftp], and closed when the call ends, by success, failure or
 *   cancellation.
 * - That session's command runner for content search, and that API client's `fs` for name search.
 * - When the connection ends or is replaced during the call (a new generation, or another server),
 *   the call fails with [FilesFailure.NotConnected]: a call never moves to a newer connection or to
 *   another server. No connection at all is [FilesFailure.NotConnected] too.
 *
 * Failures are [FilesException]; see [toFailure] for how a [FileError] maps. Cancellation is never
 * wrapped. Nothing is logged, and no failure carries a path.
 *
 * Nothing here stops or signals a server process; the content search ends only the search process
 * it started itself, as `core/files` does.
 */
class ConnectionFileService(
    private val connection: ConnectionManager,
    private val services: FileServiceFactory = FileServiceFactory(::RemoteFileService),
) : FileListing, FileContent, FileChanges, FileTransfers, FileSearch {

    override suspend fun list(directory: String, folder: String): List<FileEntry> =
        call(directory) { it.list(folder) }

    override suspend fun stat(directory: String, path: String): FileEntry =
        call(directory) { it.stat(path) }

    override suspend fun read(directory: String, path: String, maxBytes: Long): ByteArray {
        if (maxBytes < 0) throw FilesException(FilesFailure.Rejected)
        return call(directory) { it.read(path, maxBytes).bytes }
    }

    /**
     * Replaces or creates the file, keeping the replaced file's mode, then stats it, so the entry
     * returned carries the new `modifiedAt`.
     */
    override suspend fun write(directory: String, path: String, content: ByteArray): FileEntry =
        call(directory) { service ->
            service.write(path, content, FileWriteOptions(overwrite = true))
            service.stat(path)
        }

    override suspend fun delete(directory: String, path: String, recursive: Boolean) =
        call(directory) { it.delete(path, recursive) }

    override suspend fun move(directory: String, from: String, to: String) =
        call(directory) { it.move(from, to, overwrite = false) }

    override suspend fun createFolder(directory: String, path: String) =
        call(directory) { it.createDirectory(path, parents = false) }

    override suspend fun upload(
        directory: String,
        path: String,
        source: InputStream,
        overwrite: Boolean,
        onProgress: (Long) -> Unit,
    ) = call(directory) { it.upload(path, source, FileWriteOptions(overwrite = overwrite), onProgress) }

    override suspend fun download(directory: String, path: String, sink: OutputStream, onProgress: (Long) -> Unit): Long =
        call(directory) { it.download(path, sink, onProgress) }

    /**
     * Names under [directory], the folder searched being [directory] itself.
     *
     * Known limit: [limit] is clamped to 1..[FileService.MAX_NAME_RESULTS].
     */
    override suspend fun findNames(directory: String, query: String, limit: Int): List<FileEntry> {
        val clamped = limit.coerceIn(1, FileService.MAX_NAME_RESULTS)
        return call(directory) { it.findByName(directory, query, type = null, limit = clamped).entries }
    }

    /**
     * Lines under [directory] that hold [query] as plain text, case-sensitive.
     *
     * Known limits:
     * - [tool] is ignored: the file service picks `rg` when the server has it and `grep` otherwise,
     *   and fails with [FilesFailure.ToolMissing] only when it has neither (or no `head`).
     * - [limit] becomes [ContentQuery.maxResults], clamped to 1..[ContentQuery.MAX_RESULTS].
     *
     * A [query] that is empty or not one line fails with [FilesFailure.Rejected] before anything is
     * sent.
     */
    override suspend fun searchContent(
        directory: String,
        query: String,
        tool: ContentSearchTool,
        limit: Int,
    ): List<ContentHit> {
        if (!ContentQuery.isValidText(query)) throw FilesException(FilesFailure.Rejected)
        val contentQuery = ContentQuery(text = query, maxResults = limit.coerceIn(1, ContentQuery.MAX_RESULTS))
        return call(directory) { service ->
            service.searchContent(directory, contentQuery).matches.map { ContentHit(it.path, it.line, it.text) }
        }
    }

    /**
     * Runs [block] on a file service rooted at [directory], on the connection standing now. The SFTP
     * client, the command runner and the API's file search all come from the one [BoundConnection]
     * [ConnectionManager.withConnection] captured; nothing is looked up from the manager's state.
     */
    private suspend fun <T> call(directory: String, block: suspend (FileService) -> T): T = try {
        connection.withConnection { bound ->
            val sftp = bound.openSftp(directory)
            try {
                block(services.create(sftp, bound.exec, bound.fs))
            } finally {
                // Never suspends, so it also runs to its end on cancellation.
                closeQuietly(sftp)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw failure.toFilesException()
    }

    override fun toString(): String = "ConnectionFileService"

    private companion object {
        fun closeQuietly(sftp: SftpClient) {
            try {
                sftp.close()
            } catch (_: Exception) {
                // Closing is cleanup; the call's own outcome stands.
            }
        }
    }
}

/**
 * How a failure of a file call reaches the ports. No cause is kept: an SSH library error deep in
 * a chain may quote a path. Anything unknown, such as a local stream's failure, becomes
 * [FilesFailure.Unknown].
 */
internal fun Exception.toFilesException(): FilesException = when (this) {
    is FilesException -> this
    is FileException -> FilesException(error.toFailure())
    is NotConnectedException -> FilesException(FilesFailure.NotConnected)
    is SshException -> FilesException(FilesFailure.of(this))
    is ApiException -> FilesException(FilesFailure.of(error))
    else -> FilesException(FilesFailure.Unknown)
}

/** Every [FileError] as the [FilesFailure] the files screens word. */
internal fun FileError.toFailure(): FilesFailure = when (this) {
    FileError.NotFound -> FilesFailure.NotFound
    FileError.PermissionDenied -> FilesFailure.PermissionDenied
    FileError.AlreadyExists -> FilesFailure.AlreadyExists
    FileError.NotEmpty -> FilesFailure.NotEmpty
    FileError.PathRejected -> FilesFailure.PathRejected
    is FileError.TooLarge -> FilesFailure.TooLarge
    FileError.NotAFile,
    FileError.NotADirectory,
    FileError.RootProtected,
    FileError.MoveIntoItself,
    FileError.Conflict,
    -> FilesFailure.Rejected
    FileError.SearchUnavailable -> FilesFailure.ToolMissing
    FileError.SearchFailed, FileError.Failed -> FilesFailure.ServerFailed
    is FileError.Api -> FilesFailure.of(error)
    FileError.Disconnected -> FilesFailure.NotConnected
    FileError.Timeout -> FilesFailure.Unreachable
}
