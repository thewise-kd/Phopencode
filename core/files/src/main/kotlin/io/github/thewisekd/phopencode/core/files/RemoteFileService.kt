package io.github.thewisekd.phopencode.core.files

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SftpWriteOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * [FileService] over one SFTP channel ([sftp], whose root is the service's), the session's
 * [exec] for content search, and the opencode API's [fs] for name search. See [FileService] for
 * the rules.
 */
class RemoteFileService(
    private val sftp: SftpClient,
    private val exec: RemoteExec,
    private val fs: FsApi,
) : FileService {
    /** @throws FileException with [FileError.PathRejected] for a malformed SFTP root. */
    override val root: String = RemotePath.resolve(sftp.root, "")

    private fun resolve(path: String): String = RemotePath.resolve(root, path)

    override suspend fun home(): String = guarded { sftp.home() }

    override suspend fun list(path: String): List<FileEntry> {
        val folder = resolve(path)
        return guarded {
            try {
                sftp.list(folder)
            } catch (failure: SshException.Sftp) {
                if (failure.failure == SftpFailure.Failed && isNotFolder(folder)) throw FileException(FileError.NotADirectory)
                throw failure
            }
        }
    }

    /**
     * Whether [path] exists and is not a folder. An SSH failure of the check counts as no, so the
     * original failure is reported; cancellation and anything unexpected propagate.
     */
    private suspend fun isNotFolder(path: String): Boolean = try {
        sftp.stat(path).type != FileEntryType.Directory
    } catch (_: SshException) {
        false
    }

    override suspend fun stat(path: String): FileEntry {
        val target = resolve(path)
        return guarded { sftp.stat(target) }
    }

    override suspend fun lstat(path: String): FileEntry {
        val target = resolve(path)
        return guarded { sftp.lstat(target) }
    }

    override suspend fun read(path: String, maxBytes: Long): FileContent {
        require(maxBytes >= 0) { "The limit must not be negative" }
        val target = resolve(path)
        return guarded {
            val entry = sftp.stat(target)
            if (entry.type != FileEntryType.File) throw FileException(FileError.NotAFile)
            val size = entry.sizeBytes
            if (size != null && size > maxBytes) throw FileException(FileError.TooLarge(size, maxBytes))
            val bytes = try {
                sftp.read(target, maxBytes)
            } catch (failure: SshException.Sftp) {
                if (failure.failure == SftpFailure.TooLarge) throw FileException(FileError.TooLarge(null, maxBytes))
                throw failure
            }
            FileContent(entry, bytes)
        }
    }

    override suspend fun write(path: String, content: ByteArray, options: FileWriteOptions) =
        upload(path, ByteArrayInputStream(content), options)

    override suspend fun upload(
        path: String,
        source: InputStream,
        options: FileWriteOptions,
        onProgress: (Long) -> Unit,
    ) {
        val target = resolve(path)
        val sftpOptions = try {
            SftpWriteOptions(overwrite = options.overwrite, permissions = options.permissions)
        } catch (_: IllegalArgumentException) {
            throw FileException(FileError.Failed)
        }
        guarded {
            options.expectedModifiedAt?.let { expected ->
                val current = try {
                    sftp.stat(target)
                } catch (failure: SshException.Sftp) {
                    if (failure.failure == SftpFailure.NotFound) throw FileException(FileError.Conflict)
                    throw failure
                }
                val actual = current.modifiedAt
                if (actual != null && actual != expected) throw FileException(FileError.Conflict)
            }
            sftp.writeAtomic(target, source, sftpOptions, onProgress)
        }
    }

    override suspend fun download(path: String, sink: OutputStream, onProgress: (Long) -> Unit): Long {
        val target = resolve(path)
        return guarded { sftp.download(target, sink, onProgress) }
    }

    override suspend fun createDirectory(path: String, parents: Boolean) {
        val target = resolve(path)
        guarded { sftp.mkdir(target, parents) }
    }

    override suspend fun delete(path: String, recursive: Boolean) {
        val target = resolve(path)
        if (target == root) throw FileException(FileError.RootProtected)
        guarded { sftp.delete(target, recursive) }
    }

    override suspend fun move(from: String, to: String, overwrite: Boolean) {
        val source = resolve(from)
        val target = resolve(to)
        if (source == root || target == root) throw FileException(FileError.RootProtected)
        if (target != source && RemotePath.isWithin(target, source)) throw FileException(FileError.MoveIntoItself)
        guarded { sftp.rename(source, target, overwrite) }
    }

    override suspend fun findByName(
        directory: String,
        query: String,
        type: FileSearchType?,
        limit: Int,
    ): NameSearchResult {
        require(limit in 1..FileService.MAX_NAME_RESULTS) { "The limit is out of range" }
        val folder = resolve(directory)
        if (query.isBlank()) return NameSearchResult(emptyList(), truncated = false)
        val found = guarded {
            requireFolderInsideRoot(folder)
            fs.find(Location(folder), query, type, limit + 1)
        }
        val entries = found.mapNotNull { it.under(folder) }
        return NameSearchResult(entries.take(limit), truncated = found.size > limit)
    }

    /**
     * Before a search reaches the API or a shell: `stat` over SFTP, which follows symlinks only while
     * every hop stays inside the root and fails with `PathRejected` otherwise, must find a folder.
     *
     * Known limit: this is a check, then a separate use (time of check to time of use). A symlink
     * swapped in on the server between this `stat` and the API call or the search command is not
     * caught.
     */
    private suspend fun requireFolderInsideRoot(folder: String) {
        if (sftp.stat(folder).type != FileEntryType.Directory) throw FileException(FileError.NotADirectory)
    }

    /** The entry with its path made absolute, or null for a path that would leave [folder]. */
    private fun FileEntry.under(folder: String): FileEntry? {
        val relative = path.trimStart('/')
        if (relative.isEmpty()) return null
        val absolute = try {
            RemotePath.resolve(folder, relative)
        } catch (_: FileException) {
            return null
        }
        return if (absolute != folder && RemotePath.isWithin(absolute, folder)) copy(path = absolute) else null
    }

    override suspend fun searchContent(directory: String, query: ContentQuery): ContentSearchResult {
        val folder = resolve(directory)
        return guarded {
            requireFolderInsideRoot(folder)
            val command = try {
                SearchCommand.build(folder, query)
            } catch (_: IllegalArgumentException) {
                throw FileException(FileError.PathRejected)
            }
            val result = exec.run(
                ExecRequest(command, timeout = query.timeout),
                maxOutputBytes = query.maxOutputBytes + OUTPUT_SLACK_BYTES,
            )
            when (val outcome = SearchCommand.outcome(result.stderr)) {
                SearchCommand.Outcome.Unavailable -> throw FileException(FileError.SearchUnavailable)
                SearchCommand.Outcome.Unknown -> throw FileException(FileError.SearchFailed)
                is SearchCommand.Outcome.Ran -> {
                    val parsed = SearchOutput.parse(result.stdout, folder, query.maxResults, query.maxLineChars)
                    val cutAtBytes = result.stdout.size >= query.maxOutputBytes
                    // SIGPIPE is the expected end only when head really filled up and closed the pipe.
                    val stoppedByHead = outcome.status == SIGPIPE_STATUS && cutAtBytes
                    val clean = (outcome.status == 0 || outcome.status == 1 || stoppedByHead) && outcome.headStatus == 0
                    if (!clean && parsed.matches.isEmpty()) throw FileException(FileError.SearchFailed)
                    val fileLimitReached = parsed.matches.groupingBy { it.path }.eachCount().values
                        .any { it >= query.maxMatchesPerFile }
                    ContentSearchResult(
                        matches = parsed.matches,
                        tool = outcome.tool,
                        truncated = parsed.cut || parsed.shortened || cutAtBytes || fileLimitReached,
                        incomplete = !clean,
                    )
                }
            }
        }
    }

    override fun toString(): String = "RemoteFileService"

    private companion object {
        /** Room on top of the output limit for what `head` lets through and the marker. */
        const val OUTPUT_SLACK_BYTES = 64L * 1024

        /** A POSIX shell reports death by signal N as 128 + N; SIGPIPE is 13 on Linux and the BSDs. */
        const val SIGPIPE_STATUS = 128 + 13
    }
}

/** Runs [block], turning SSH and API failures into [FileException]. */
private inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (failure: SshException) {
    throw FileException(failure.toFileError(), failure)
} catch (failure: ApiException) {
    throw FileException(FileError.Api(failure.error))
}

private fun SshException.toFileError(): FileError = when (this) {
    is SshException.Sftp -> when (failure) {
        SftpFailure.NotFound -> FileError.NotFound
        SftpFailure.PermissionDenied -> FileError.PermissionDenied
        SftpFailure.AlreadyExists -> FileError.AlreadyExists
        SftpFailure.NotEmpty -> FileError.NotEmpty
        SftpFailure.PathRejected -> FileError.PathRejected
        SftpFailure.TooLarge -> FileError.TooLarge(null, 0)
        SftpFailure.Failed -> FileError.Failed
    }
    is SshException.Disconnected -> FileError.Disconnected
    is SshException.Timeout -> FileError.Timeout
    is SshException.OutputLimitExceeded -> FileError.SearchFailed
    else -> FileError.Failed
}
