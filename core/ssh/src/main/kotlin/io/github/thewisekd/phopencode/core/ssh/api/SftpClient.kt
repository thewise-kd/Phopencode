package io.github.thewisekd.phopencode.core.ssh.api

import io.github.thewisekd.phopencode.core.model.FileEntry
import java.io.InputStream
import java.io.OutputStream

/**
 * File access over one SFTP channel, confined to [root].
 *
 * Every path is either relative to [root] or absolute and inside it. A path with a `.` or `..`
 * segment, a NUL byte, or a place outside [root] fails with [SftpFailure.PathRejected] before any
 * request reaches the server. Reads ([list], [stat], [read], [download]) and changes alike then
 * fail with [SftpFailure.PathRejected] when the path's real location, after every symlink hop, is
 * outside [root]: a symlink that leads out of [root] is neither read nor changed through. A root of
 * `/` gives the whole server and the same segment rules. Reporting a symlink itself ([lstat],
 * [readLink], a [list] entry, [delete]) does not follow it, so a link entry inside [root] is still
 * reported, with its stored target, even when that target is outside [root].
 *
 * Failures are [SshException.Sftp], or [SshException.Disconnected] when the connection ends.
 * Cancelling stops the transfer. Stream arguments stay open: they belong to the caller.
 */
interface SftpClient : AutoCloseable {
    val root: String

    /**
     * The direct children of a folder, without `.` and `..`, in no particular order. A symlink is
     * reported as a symlink.
     */
    suspend fun list(path: String): List<FileEntry>

    /** Follows symlinks, as long as every hop stays inside [root]. */
    suspend fun stat(path: String): FileEntry

    /** Does not follow a symlink in the last segment; earlier segments must stay inside [root]. */
    suspend fun lstat(path: String): FileEntry

    /** A symlink's target as stored, which may be relative and may point outside [root]. */
    suspend fun readLink(path: String): String

    /**
     * Reads a whole file into memory, byte for byte.
     *
     * @param maxBytes Fails with [SftpFailure.TooLarge] above this, without reading the rest.
     */
    suspend fun read(path: String, maxBytes: Long): ByteArray

    /**
     * Streams a file into [sink], byte for byte. A failure can leave [sink] partly written.
     *
     * @param onProgress Gets the total bytes written so far.
     * @return The total bytes written.
     */
    suspend fun download(path: String, sink: OutputStream, onProgress: (Long) -> Unit = {}): Long

    /**
     * Streams [source] to a temporary file next to [path], then renames it over [path]. Readers see
     * the old file or the new one, never a mix. On any failure or cancellation [path] is unchanged
     * and the temporary file is gone. This is both the upload and the save operation.
     *
     * A symlink at [path] stays a symlink: the file it points at is what gets replaced.
     *
     * @param onProgress Gets the total bytes sent so far.
     */
    suspend fun writeAtomic(
        path: String,
        source: InputStream,
        options: SftpWriteOptions = SftpWriteOptions(),
        onProgress: (Long) -> Unit = {},
    )

    /**
     * Deletes a file, a symlink (never its target) or a folder.
     *
     * @param recursive Whether a folder's content goes too. It does not follow symlinks. Without
     *   it, a folder with content fails with [SftpFailure.NotEmpty].
     */
    suspend fun delete(path: String, recursive: Boolean = false)

    /** @param overwrite Whether an existing [to] is replaced. Without it, [SftpFailure.AlreadyExists]. */
    suspend fun rename(from: String, to: String, overwrite: Boolean = false)

    /**
     * @param parents Whether missing parents are created too, and an existing folder is accepted.
     *   Without it, an existing folder fails with [SftpFailure.AlreadyExists].
     */
    suspend fun mkdir(path: String, parents: Boolean = false)

    /** Safe to call more than once. The session stays open. */
    override fun close()
}

/**
 * @property overwrite Whether an existing file is replaced. Without it, [SftpFailure.AlreadyExists].
 * @property permissions Mode bits of the file, such as [OWNER_ONLY]. Null keeps the mode of the file
 *   being replaced, and uses the server's default for a new file. A temporary file is created
 *   owner-only, so that a secret is never readable while it is being written.
 */
data class SftpWriteOptions(
    val overwrite: Boolean = false,
    val permissions: Int? = null,
) {
    init {
        require(permissions == null || permissions in 0..MAX_PERMISSIONS) { "Permissions are out of range" }
    }

    companion object {
        /** Mode 0600. */
        const val OWNER_ONLY: Int = 0b110_000_000

        private const val MAX_PERMISSIONS = 0b111_111_111_111
    }
}
