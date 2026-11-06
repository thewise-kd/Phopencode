package io.github.thewisekd.phopencode.core.git.command

import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.io.IOException
import kotlin.time.Duration

/**
 * Runs [GitCommand]s through a [RemoteExec], normally the active connection's. A non-zero exit is a
 * [GitResult], not an exception: git reports "not a repository", a refused push or a failing hook
 * that way, and the caller decides what each means.
 *
 * Cancelling the calling coroutine ends the remote process (see [RemoteExec]) and rethrows the
 * cancellation unchanged. Nothing here logs.
 */
class GitRunner(private val exec: RemoteExec) {
    /**
     * @param maxOutputBytes Limit for stdout and for stderr, each.
     * @throws GitException.TimedOut when the command ran past its [GitCommand.timeout].
     * @throws GitException.OutputTooLarge when either stream passed [maxOutputBytes].
     * @throws GitException.ConnectionLost when the connection ended or could not be used.
     */
    suspend fun run(command: GitCommand, maxOutputBytes: Long = RemoteExec.DEFAULT_MAX_OUTPUT_BYTES): GitResult {
        val request = command.toExecRequest()
        try {
            val result = exec.run(request, maxOutputBytes)
            return GitResult(result.stdout, result.stderr, result.exitCode, result.exitSignal)
        } catch (e: SshException.Timeout) {
            throw GitException.TimedOut(e)
        } catch (e: SshException.OutputLimitExceeded) {
            throw GitException.OutputTooLarge(e.limitBytes, e)
        } catch (e: SshException) {
            throw GitException.ConnectionLost(e)
        } finally {
            request.stdin?.fill(0)
        }
    }

    /**
     * Throws away unstaged changes to tracked files, back to the index; a deleted file comes back.
     * Destructive: the caller confirms first.
     *
     * Two steps. A `status` of [paths] (literal, with untracked and ignored entries) must report
     * every path as a tracked, non-submodule file whose work tree copy is modified, deleted or
     * changed type to a file or symlink, and nothing untracked or ignored at or under any of them.
     * Otherwise the result is [WorktreeDiscard.Refused] and nothing changes. Git reports a nested
     * repository standing where a tracked file was as a plain deletion and lists it nowhere, so for
     * each deleted path `git -C <path> version` must fail, proving nothing that can be entered is
     * there. Every ancestor folder of a path must not be reported by `status` (an untracked or
     * ignored file or link where the folder was) and, where it exists, `git -C <ancestor> rev-parse
     * --show-prefix` must print exactly its own path, which a symlink or a nested repository does
     * not. Only then `checkout-index -f -u` writes the files. So a folder that replaced a tracked file, with
     * anything in it, is never removed, and an unchanged or unknown path is refused.
     *
     * Known limit: a path replaced by a folder between the two steps would still be overwritten.
     *
     * @param directory The work tree root ([GitCommands.revParseToplevel]): `status` prints root
     *   paths, so from a subfolder no path matches and the call is refused.
     * @param paths As `status` prints them.
     * @throws IllegalArgumentException for an empty list or an invalid path.
     * @throws GitException as [run].
     */
    suspend fun discardWorktree(
        directory: String,
        paths: List<String>,
        timeout: Duration? = ExecRequest.DEFAULT_TIMEOUT,
    ): WorktreeDiscard {
        val check = run(GitCommands.discardCheck(directory, paths, timeout))
        if (!check.succeeded) return WorktreeDiscard.Failed(check)
        val deleted = DiscardCheck.deletedPaths(paths, check.stdout) ?: return WorktreeDiscard.Refused
        // Git reports a nested repository that stands where a tracked file was as a plain deletion
        // and lists it nowhere, so each "deleted" path is probed: entering it must fail.
        for (path in deleted) {
            if (run(GitCommands.directoryProbe(directory, path, timeout)).succeeded) return WorktreeDiscard.Refused
        }
        // Every ancestor folder that exists must be a real folder of this work tree: entered, it
        // reports exactly its own path as prefix. A symlink (to anywhere) or a nested repository
        // reports something else; a missing folder fails and is created by checkout-index.
        for (ancestor in paths.flatMap(DiscardCheck::ancestors).distinct()) {
            val prefix = run(GitCommands.directoryPrefix(directory, ancestor, timeout))
            if (prefix.succeeded && String(prefix.stdout, Charsets.UTF_8) != "$ancestor/\n") return WorktreeDiscard.Refused
        }
        val discard = run(GitCommands.discardWorktree(directory, paths.distinct(), timeout))
        return if (discard.succeeded) WorktreeDiscard.Discarded else WorktreeDiscard.Failed(discard)
    }

    /**
     * The diff of a file git does not track against nothing, so every line shows as added
     * ([GitCommands.diffUntracked]). Read-only. Three steps:
     * 1. `status -- path` (literal, untracked files listed) must report exactly `? path`. So the path
     *    is an untracked file or symlink: not tracked, ignored, missing or a folder, and not beyond a
     *    symlink, where git lists nothing; a named pipe is never listed either.
     * 2. [GitCommands.untrackedFolderCheck] must print [directory] and the path's folder: every
     *    folder on the way is a real folder of this work tree, so no symlink leads out of it and no
     *    nested repository stands there, and [directory] is the root.
     * 3. `diff --no-index -- /dev/null path`, whose exit 1 with output is the normal result.
     *
     * Known limit: a folder replaced by a symlink between the checks and the diff would be followed.
     *
     * @param directory The work tree root as `rev-parse --show-toplevel` prints it; any other spelling
     *   of it is refused.
     * @param path As `status` prints it, relative to [directory] ([GitCommands.isWorkTreePath]).
     * @param maxOutputBytes Limit for the diff's output; the checks print little.
     * @throws IllegalArgumentException for a path that is absolute, holds `..` and so on.
     * @throws GitException as [run]; [GitException.OutputTooLarge] when the diff passed
     *   [maxOutputBytes].
     */
    suspend fun diffUntracked(
        directory: String,
        path: String,
        output: DiffOutput = DiffOutput.Patch,
        contextLines: Int = 3,
        maxOutputBytes: Long = RemoteExec.DEFAULT_MAX_OUTPUT_BYTES,
        timeout: Duration? = ExecRequest.DEFAULT_TIMEOUT,
    ): UntrackedDiff {
        val diffCommand = GitCommands.diffUntracked(directory, path, output, contextLines, timeout)
        val folderCheck = GitCommands.untrackedFolderCheck(directory, path, timeout)
        val status = run(GitCommands.status(directory, includeUntracked = true, paths = listOf(path), timeout = timeout), CHECK_OUTPUT_BYTES)
        if (!status.succeeded) return UntrackedDiff.Failed(status)
        val untracked = "? $path".toByteArray(Charsets.UTF_8)
        if (records(status.stdout).none { it.contentEquals(untracked) }) return UntrackedDiff.Refused
        val folder = path.substringBeforeLast('/', "")
        val expected = (directory + "\n" + (if (folder.isEmpty()) "" else "$folder/") + "\n").toByteArray(Charsets.UTF_8)
        val place = run(folderCheck, CHECK_OUTPUT_BYTES)
        if (!place.succeeded || !place.stdout.contentEquals(expected)) return UntrackedDiff.Refused
        val diff = run(diffCommand, maxOutputBytes)
        val differs = diff.exitCode == EXIT_DIFFERS && diff.stdout.isNotEmpty()
        return if (diff.succeeded || differs) UntrackedDiff.Diff(diff.stdout) else UntrackedDiff.Failed(diff)
    }

    private fun records(stdout: ByteArray): List<ByteArray> {
        val records = mutableListOf<ByteArray>()
        var start = 0
        for (index in stdout.indices) {
            if (stdout[index] == NUL) {
                records += stdout.copyOfRange(start, index)
                start = index + 1
            }
        }
        if (start < stdout.size) records += stdout.copyOfRange(start, stdout.size)
        return records
    }

    private companion object {
        const val NUL: Byte = 0

        /** `diff --no-index` exits 1 when the files differ. */
        const val EXIT_DIFFERS = 1

        /** The checks print one status record or two short lines. */
        const val CHECK_OUTPUT_BYTES: Long = 64L * 1024
    }
}

/** What [GitRunner.diffUntracked] read. */
sealed interface UntrackedDiff {
    /**
     * The output of `diff --no-index`, read up to the caller's limit. It holds file content, so it
     * does not print.
     */
    class Diff(val output: ByteArray) : UntrackedDiff {
        override fun toString(): String = "Diff(${output.size} bytes)"
    }

    /**
     * `status` does not list the path as an untracked file (it is tracked, ignored, missing, a
     * folder or beyond a symlink), or a folder on its way is not a real folder of this work tree.
     * Nothing was read.
     */
    data object Refused : UntrackedDiff

    /** A step exited non-zero (or the diff printed nothing), for example outside a repository. */
    class Failed(val result: GitResult) : UntrackedDiff {
        override fun toString(): String = "Failed($result)"
    }
}

/** What [GitRunner.discardWorktree] did. */
sealed interface WorktreeDiscard {
    /** Every path is back to its staged content. */
    data object Discarded : WorktreeDiscard

    /**
     * At least one path is not a tracked file with an unstaged change, or something untracked or
     * ignored lies at or under it, such as a folder that replaced the file. Nothing changed.
     */
    data object Refused : WorktreeDiscard

    /** A step exited non-zero, for example outside a repository. */
    class Failed(val result: GitResult) : WorktreeDiscard {
        override fun toString(): String = "Failed($result)"
    }
}

/** Reads the `status --porcelain=v2 -z` of [GitCommands.discardCheck]. */
internal object DiscardCheck {
    private val FILE_MODES = setOf("100644", "100755", "120000")

    /** True when [deletedPaths] allows the discard. */
    fun allows(paths: List<String>, statusOutput: ByteArray): Boolean = deletedPaths(paths, statusOutput) != null

    /**
     * Null when some path is not discardable; otherwise the paths whose work tree copy is reported
     * deleted, which the caller still has to probe for a folder.
     */
    fun deletedPaths(paths: List<String>, statusOutput: ByteArray): Set<String>? {
        val records = String(statusOutput, Charsets.UTF_8).split('\u0000')
        val discardable = mutableSetOf<String>()
        val deleted = mutableSetOf<String>()
        val blockers = mutableListOf<String>()
        val reported = mutableSetOf<String>()
        var index = 0
        while (index < records.size) {
            val record = records[index]
            reported += recordPath(record)
            when {
                record.isEmpty() || record.startsWith("# ") -> Unit
                record.startsWith("1 ") -> {
                    // 1 XY sub mH mI mW hH hI path
                    val fields = record.split(' ', limit = 9)
                    if (fields.size == 9 && isDiscardable(fields[1], fields[2], fields[5])) {
                        discardable += fields[8]
                        if (fields[1][1] == 'D') deleted += fields[8]
                    }
                }
                record.startsWith("? ") || record.startsWith("! ") -> blockers += record.substring(2)
                // Renames (2), unmerged entries (u) and anything unknown: never discardable.
                record.startsWith("2 ") -> index++
                else -> Unit
            }
            index++
        }
        val allowed = paths.all { path ->
            path in discardable &&
                blockers.none { it == path || it.startsWith("$path/") } &&
                // Anything reported at an ancestor (an untracked or ignored file or link where the
                // folder was) would be deleted to make room for the folder.
                ancestors(path).none { it in reported || "$it/" in reported }
        }
        return if (allowed) paths.filter { it in deleted }.toSet() else null
    }

    /** `a/b/c.txt` gives `a` and `a/b`. */
    fun ancestors(path: String): List<String> {
        val parts = path.split('/').dropLast(1)
        return parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
    }

    /** The path a status record names, or the record itself for anything unknown. */
    private fun recordPath(record: String): String = when {
        record.startsWith("1 ") -> record.split(' ', limit = 9).getOrElse(8) { record }
        record.startsWith("2 ") -> record.split(' ', limit = 10).getOrElse(9) { record }
        record.startsWith("u ") -> record.split(' ', limit = 11).getOrElse(10) { record }
        record.startsWith("? ") || record.startsWith("! ") -> record.substring(2)
        else -> record
    }

    private fun isDiscardable(xy: String, submodule: String, worktreeMode: String): Boolean {
        if (xy.length != 2 || submodule != "N...") return false
        return when (xy[1]) {
            'M', 'D' -> true
            'T' -> worktreeMode in FILE_MODES
            else -> false
        }
    }
}

/**
 * What a finished git command left. Output stays bytes: paths and file content need not be UTF-8.
 * It can hold anything from the repository, so it does not print.
 *
 * @property exitCode Null when the process died from a signal or the server reported nothing.
 * @property exitSignal The signal's name without `SIG`.
 */
class GitResult(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitCode: Int?,
    val exitSignal: String?,
) {
    val succeeded: Boolean get() = exitCode == 0

    /**
     * stderr as text, invalid UTF-8 replaced. Git writes it in English (`LC_ALL=C`), so callers can
     * match messages such as `not a git repository`. It can quote paths: show it, never log it.
     */
    fun stderrText(): String = String(stderr, Charsets.UTF_8)

    override fun toString(): String =
        "GitResult(exitCode=$exitCode, exitSignal=$exitSignal, stdout=${stdout.size} bytes, stderr=${stderr.size} bytes)"
}

/**
 * A git command that did not finish with an exit status. Messages are fixed text; none quotes a
 * path, a message or output.
 */
sealed class GitException(message: String, cause: Throwable?) : IOException(message, cause) {
    /** The command ran past its timeout; the remote process was ended. */
    class TimedOut(cause: Throwable?) : GitException("Git command timed out", cause)

    /** stdout or stderr passed the limit; the remote process was ended. */
    class OutputTooLarge(val limitBytes: Long, cause: Throwable?) : GitException("Git output exceeded the limit", cause)

    /** The SSH connection ended or could not be used. */
    class ConnectionLost(cause: Throwable?) : GitException("Connection lost while running git", cause)
}
