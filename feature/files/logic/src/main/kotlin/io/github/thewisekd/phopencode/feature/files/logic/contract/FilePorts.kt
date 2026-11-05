package io.github.thewisekd.phopencode.feature.files.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import java.io.InputStream
import java.io.OutputStream

/*
 * The ports: what the files presenters need from the server, as small interfaces the logic tasks
 * test against fakes. The file service of P8.1 (`core/files`) implements them, directly or through
 * an adapter in this feature; until it is wired, `FilesModule` binds them all to [NoFileService].
 *
 * Shared rules for every port:
 * - `directory` is the project's folder, the `directory` of every `FilesRoute`: an absolute path,
 *   and the root nothing may leave (P8.1 opens its SFTP client with that root).
 * - A `path` is absolute and inside `directory`, as [FileEntry.path] gives it, or relative to
 *   `directory`. A path that leaves `directory` fails with [FilesFailure.PathRejected] before any
 *   request is sent.
 * - Every call is on the active connection of `ConnectionManager`. Failures are [FilesException],
 *   mapped with [FilesFailure.of]; cancellation stays a `CancellationException` and stops the work.
 * - Implementations never log a path or file contents.
 */

/** The direct children of a folder. Filled by P8.1; used by the tree logic (P8.4). */
interface FileListing {
    /**
     * In no particular order, without `.` and `..`; sorting and hiding are the tree's. A symlink is
     * reported as a symlink. [FileEntry.ignored] is set for entries `.gitignore` covers, which is
     * the tree's `.gitignore` marker.
     */
    suspend fun list(directory: String, folder: String): List<FileEntry>
}

/**
 * Reading and saving one file. Filled by P8.1; used by the viewer logic (P8.6) and the editor
 * logic (P8.8).
 */
interface FileContent {
    /** Follows symlinks. [FileEntry.sizeBytes] and [FileEntry.modifiedAt] are known here. */
    suspend fun stat(directory: String, path: String): FileEntry

    /**
     * The whole file, byte for byte; decoding is the caller's.
     *
     * @param maxBytes Fails with [FilesFailure.TooLarge] above this, without reading the rest.
     */
    suspend fun read(directory: String, path: String, maxBytes: Long): ByteArray

    /**
     * Replaces the file atomically (a temporary file next to it, then a rename), or creates it. On
     * any failure the file is unchanged. Keeps the mode of the file it replaces.
     *
     * Conflicts are the caller's: the editor compares [stat]'s `modifiedAt` with the one it loaded
     * before it writes.
     *
     * @return The file after the write, with its new `modifiedAt`.
     */
    suspend fun write(directory: String, path: String, content: ByteArray): FileEntry
}

/** Delete, rename and new folder. Filled by P8.1; used by the file operations logic (P8.10). */
interface FileChanges {
    /**
     * Deletes a file, a symlink (never its target) or a folder.
     *
     * @param recursive Whether a folder's content goes too. Without it, a folder with content fails
     *   with [FilesFailure.NotEmpty].
     */
    suspend fun delete(directory: String, path: String, recursive: Boolean)

    /** Renames or moves; an existing [to] fails with [FilesFailure.AlreadyExists], never replaced. */
    suspend fun move(directory: String, from: String, to: String)

    /** Creates one folder; its parent must exist. An existing one fails with [FilesFailure.AlreadyExists]. */
    suspend fun createFolder(directory: String, path: String)
}

/** Upload and download. Filled by P8.1; used by the transfer logic (P8.12). */
interface FileTransfers {
    /** As [FileContent.stat]: the size of a download before it starts. */
    suspend fun stat(directory: String, path: String): FileEntry

    /**
     * Streams [source] to a temporary file next to [path], then renames it over [path]. On any
     * failure or cancellation [path] is unchanged and no temporary file remains. [source] stays
     * open: it belongs to the caller.
     *
     * @param overwrite Whether an existing file is replaced. Without it, [FilesFailure.AlreadyExists],
     *   which the transfer logic turns into its overwrite prompt.
     * @param onProgress Gets the total bytes sent so far.
     */
    suspend fun upload(
        directory: String,
        path: String,
        source: InputStream,
        overwrite: Boolean,
        onProgress: (Long) -> Unit,
    )

    /**
     * Streams the file into [sink], byte for byte. A failure can leave [sink] partly written: the
     * transfer logic deletes the local file then. [sink] stays open: it belongs to the caller.
     *
     * @param onProgress Gets the total bytes written so far.
     * @return The total bytes written.
     */
    suspend fun download(directory: String, path: String, sink: OutputStream, onProgress: (Long) -> Unit): Long
}

/**
 * Name and content search under the project's folder. Filled by P8.1 (names through `FsApi.find`,
 * content through `rg` or `grep` over SSH, every argument through `ShellQuote`); used by the search
 * logic (P8.14).
 *
 * To tell whether more results exist, the search logic asks for one more than it shows.
 */
interface FileSearch {
    /** Files and folders whose name matches [query]; at most [limit]. */
    suspend fun findNames(directory: String, query: String, limit: Int): List<FileEntry>

    /**
     * Lines that contain [query] as plain text, not a pattern; at most [limit]. [query] is passed as
     * one quoted argument after `--`, so it is never read as an option.
     *
     * @param tool Fails with [FilesFailure.ToolMissing] when it is not installed; the search logic
     *   then tries [ContentSearchTool.Grep].
     */
    suspend fun searchContent(directory: String, query: String, tool: ContentSearchTool, limit: Int): List<ContentHit>
}

/** A line that matched a content search. */
data class ContentHit(
    val path: String,
    /** 1-based, as `FilesRoute.Viewer.line` takes it. */
    val line: Int,
    /** The matched line, cut to a length a row can show. */
    val text: String,
) {
    override fun toString(): String = "ContentHit(path=***, line=$line, text=***)"
}

enum class ContentSearchTool {
    /** `rg`, which honours `.gitignore`. */
    Ripgrep,

    /** `grep -rnF`, when `rg` is missing. */
    Grep,
}

/**
 * Files with changes, for the tree's git badges. Used by the tree logic (P8.4); [ConnectionGitChanges]
 * fills it from `VcsApi.status`.
 */
fun interface GitChanges {
    /**
     * Every changed file of the repository [directory] is in, with paths as `VcsApi.status` gives
     * them. Empty when there is no repository.
     */
    suspend fun changes(directory: String): List<VcsFile>
}

/** [GitChanges] from the API client of the active connection, `ConnectionState.Connected`. */
class ConnectionGitChanges(private val connection: ConnectionManager) : GitChanges {
    override suspend fun changes(directory: String): List<VcsFile> {
        val connected = connection.state.value as? ConnectionState.Connected
            ?: throw FilesException(FilesFailure.NotConnected)
        return try {
            connected.client.vcs.status(Location(directory))
        } catch (error: ApiException) {
            throw FilesException(FilesFailure.of(error.error), error)
        }
    }
}

/**
 * Every port, failing every call with [FilesFailure.Unsupported] before it touches anything. What
 * `FilesModule` binds the ports to until the file service of P8.1 is wired in.
 */
object NoFileService : FileListing, FileContent, FileChanges, FileTransfers, FileSearch {
    private fun unsupported(): Nothing = throw FilesException(FilesFailure.Unsupported)

    override suspend fun list(directory: String, folder: String): List<FileEntry> = unsupported()

    override suspend fun stat(directory: String, path: String): FileEntry = unsupported()

    override suspend fun read(directory: String, path: String, maxBytes: Long): ByteArray = unsupported()

    override suspend fun write(directory: String, path: String, content: ByteArray): FileEntry = unsupported()

    override suspend fun delete(directory: String, path: String, recursive: Boolean): Unit = unsupported()

    override suspend fun move(directory: String, from: String, to: String): Unit = unsupported()

    override suspend fun createFolder(directory: String, path: String): Unit = unsupported()

    override suspend fun upload(
        directory: String,
        path: String,
        source: InputStream,
        overwrite: Boolean,
        onProgress: (Long) -> Unit,
    ): Unit = unsupported()

    override suspend fun download(directory: String, path: String, sink: OutputStream, onProgress: (Long) -> Unit): Long =
        unsupported()

    override suspend fun findNames(directory: String, query: String, limit: Int): List<FileEntry> = unsupported()

    override suspend fun searchContent(
        directory: String,
        query: String,
        tool: ContentSearchTool,
        limit: Int,
    ): List<ContentHit> = unsupported()
}
