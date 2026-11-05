package io.github.thewisekd.phopencode.core.files

import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.model.FileEntry
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Instant

/**
 * The files of one server, confined to [root]: listing, reading, writing, deleting, moving and
 * searching.
 *
 * **Paths.** Every path is relative to [root], or absolute and at or under it. A `.` or `..`
 * segment, a NUL, half a surrogate pair or a place outside [root] fails with
 * [FileError.PathRejected] before anything reaches the server; so does a path whose real location,
 * after symlinks, is outside [root] (the SFTP layer checks that). Paths in results are absolute and
 * normalised. Names are taken as they are: spaces, quotes, newlines, a leading `-`, `$(...)` and any
 * Unicode are ordinary names.
 *
 * **Transport.** Listing, reading, writing, deleting and moving go over SFTP, which has no shell.
 * Name search goes through the opencode API. Content search runs `rg`, or `grep` where `rg` is
 * missing, over SSH; every argument of that command passes through `ShellQuote`, and a `--` ends
 * the options before the folder. Nothing runs with `sudo`, and no call stops or signals a process
 * other than the search it started itself.
 *
 * **Failures.** Every call fails with [FileException]; its [FileError] says why. Cancellation
 * propagates as usual and stops the transfer or the search.
 *
 * **Logging.** No error, result or option prints a path, a name, a query or file content.
 *
 * The service does not own its connection: closing the SFTP client or the session is the caller's.
 */
interface FileService {
    /** The folder every path is confined to, absolute and normalised. */
    val root: String

    /** The login user's home folder, or [root] when the home is outside it. */
    suspend fun home(): String

    /** The direct children of a folder, without `.` and `..`, in no particular order. A symlink is reported as one. */
    suspend fun list(path: String): List<FileEntry>

    /** Follows symlinks inside [root]. */
    suspend fun stat(path: String): FileEntry

    /** Does not follow a symlink in the last segment. */
    suspend fun lstat(path: String): FileEntry

    /**
     * Reads a whole file. The size is checked first: a file above [maxBytes] fails with
     * [FileError.TooLarge] and is not loaded. A folder fails with [FileError.NotAFile].
     */
    suspend fun read(path: String, maxBytes: Long = DEFAULT_MAX_READ_BYTES): FileContent

    /** [upload] of a byte array: the save operation of an editor. */
    suspend fun write(path: String, content: ByteArray, options: FileWriteOptions = FileWriteOptions())

    /**
     * Streams [source] to a temporary file in the same folder, then renames it over [path]. Readers
     * see the old file or the new one, never a mix; on any failure or cancellation [path] is
     * unchanged and no temporary file remains. The replaced file's mode is kept unless
     * [FileWriteOptions.permissions] says otherwise. A symlink at [path] stays a symlink.
     *
     * @param source Stays open: it belongs to the caller.
     * @param onProgress Gets the total bytes sent so far.
     */
    suspend fun upload(
        path: String,
        source: InputStream,
        options: FileWriteOptions = FileWriteOptions(),
        onProgress: (Long) -> Unit = {},
    )

    /**
     * Streams a file into [sink]. A failure can leave [sink] partly written: a caller that saves to
     * a local file writes a temporary one and renames it.
     *
     * @return The total bytes written.
     */
    suspend fun download(path: String, sink: OutputStream, onProgress: (Long) -> Unit = {}): Long

    /** @param parents Whether missing parents are created too, and an existing folder is accepted. */
    suspend fun createDirectory(path: String, parents: Boolean = false)

    /**
     * Deletes a file, a symlink (never what it points at) or a folder. [root] itself fails with
     * [FileError.RootProtected].
     *
     * @param recursive Whether a folder's content goes too, without following symlinks. Without it,
     *   a folder with content fails with [FileError.NotEmpty].
     */
    suspend fun delete(path: String, recursive: Boolean = false)

    /**
     * Moves or renames [from] to [to]. A folder cannot move below itself
     * ([FileError.MoveIntoItself]) and [root] cannot move ([FileError.RootProtected]).
     *
     * @param overwrite Whether an existing [to] is replaced. Without it, [FileError.AlreadyExists].
     */
    suspend fun move(from: String, to: String, overwrite: Boolean = false)

    /**
     * Finds files and folders by name below [directory], through the opencode API. A blank query
     * finds nothing and sends nothing. Before the API is asked, [directory] must be a folder whose
     * real location, after symlinks, is inside [root] (checked over SFTP, as for [searchContent]);
     * returned entries that would leave it are dropped. Known limit: check and search are separate
     * steps, so a symlink swapped in between them on the server is not caught.
     *
     * @param limit At most this many entries, 1 to [MAX_NAME_RESULTS].
     */
    suspend fun findByName(
        directory: String,
        query: String,
        type: FileSearchType? = null,
        limit: Int = DEFAULT_NAME_RESULTS,
    ): NameSearchResult

    /**
     * Finds lines below [directory] that hold [query]'s text, with `rg` if the server has it and
     * `grep -rn` otherwise. `rg` honours `.gitignore` and skips hidden files; `grep` skips `.git`
     * only. Both skip binary files and do not follow symlinks below [directory]. The server needs
     * `head` too, which caps the output; without it, or without either tool, the search fails with
     * [FileError.SearchUnavailable].
     *
     * [directory] is checked over SFTP first, as for [findByName]. Known limit: check and search are
     * separate steps, so a symlink swapped in between them on the server is not caught.
     */
    suspend fun searchContent(directory: String, query: ContentQuery): ContentSearchResult

    companion object {
        const val DEFAULT_MAX_READ_BYTES: Long = 2L * 1024 * 1024
        const val DEFAULT_NAME_RESULTS: Int = 100
        const val MAX_NAME_RESULTS: Int = 1000
    }
}

/**
 * A file read whole. A plain class: it prints neither the path nor the content.
 *
 * @property entry The file as `stat` saw it just before the read; [FileEntry.modifiedAt] is what an
 *   editor passes back as [FileWriteOptions.expectedModifiedAt].
 */
class FileContent(val entry: FileEntry, val bytes: ByteArray) {
    override fun toString(): String = "FileContent(${bytes.size} bytes)"
}

/**
 * @property overwrite Whether an existing file is replaced. Without it, [FileError.AlreadyExists].
 * @property expectedModifiedAt When set, the file must still exist with this modification time,
 *   or the write fails with [FileError.Conflict] and changes nothing. A server that reports no time
 *   cannot be checked, and the write goes ahead. The check comes just before the write, so a change
 *   in between can still slip through.
 * @property permissions Mode bits for the file. Null keeps the replaced file's mode, and gives a
 *   new file the server's default.
 */
data class FileWriteOptions(
    val overwrite: Boolean = false,
    val expectedModifiedAt: Instant? = null,
    val permissions: Int? = null,
)

/**
 * @property entries Absolute paths, at most the limit asked for. They carry no size or time: the API
 *   does not give them.
 * @property truncated Whether there were more.
 */
class NameSearchResult(val entries: List<FileEntry>, val truncated: Boolean) {
    override fun toString(): String = "NameSearchResult(${entries.size} entries, truncated=$truncated)"
}
