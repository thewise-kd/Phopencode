package io.github.thewisekd.phopencode.feature.git.logic.diff

import io.github.thewisekd.phopencode.core.git.command.DiffOutput
import io.github.thewisekd.phopencode.core.git.command.GitCommand
import io.github.thewisekd.phopencode.core.git.command.GitCommands
import io.github.thewisekd.phopencode.core.git.command.GitNames
import io.github.thewisekd.phopencode.core.git.command.GitResult
import io.github.thewisekd.phopencode.core.git.command.GitRunner
import io.github.thewisekd.phopencode.core.git.command.ShowOutput
import io.github.thewisekd.phopencode.core.git.parse.DiffParser
import io.github.thewisekd.phopencode.core.git.parse.GitParseException
import io.github.thewisekd.phopencode.core.git.parse.StatusParser
import io.github.thewisekd.phopencode.core.git.parse.StatusEntry
import io.github.thewisekd.phopencode.core.git.parse.UnmergedEntry
import io.github.thewisekd.phopencode.core.git.parse.UntrackedEntry
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffContent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileDiff
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import io.github.thewisekd.phopencode.core.git.command.GitException as CommandException

/**
 * Reads the diff of one [target] in [directory]. Every failure is a [GitException] (cancellation
 * stays a `CancellationException`); nothing is logged.
 *
 * The steps, each one git command:
 * 1. `rev-parse --show-toplevel`: the work tree root, since `diff` and `show` take and print paths
 *    relative to it while [directory] may be a subfolder.
 * 2. For [DiffTarget.InCommit] only, `show -s --format=%P <commit>^{commit}`: the parents, which
 *    also proves the hash names a commit (a blob or tree is [GitFailure.NotFound]).
 * 3. `--name-status -z` of the whole diff, to learn whether the path is the new name of a rename
 *    and, if so, its old name. Without it, a diff limited to the new path shows the file as added.
 *    When this list is too long or too slow, the load goes on without the pairing.
 * 4. The patch of the path (and of its old name after a rename), read up to [maxBytes]: past it,
 *    git's process is ended and the content is [DiffContent.TooLarge], whose line counts come from
 *    `--numstat -z` of the same paths (0 when that fails too).
 * 5. For [DiffTarget.Working] with an empty patch only, `status` of the path: an untracked file has
 *    no diff against the index and is shown as [GitChangeKind.Untracked] without lines.
 *
 * Decisions:
 * - A root commit has no parent; `show` diffs it against the empty tree, so every file is added.
 * - A merge commit is refused with [GitFailure.Unknown] before its diff runs: `show` prints a
 *   combined diff (`--cc`) for merges, which the parser does not read, and no command of `core/git`
 *   asks for the first parent's diff instead.
 * - An unmerged path is [GitFailure.Conflict], decided before the patch is read (`U` in the name
 *   list, or `status` when the list was skipped and the patch is too large), so a conflict never
 *   shows as too large; git also prints a combined diff for it in the work tree and
 *   `* Unmerged path` in the index, which are refused the same way.
 * - A path that matches nothing (unchanged, gone, or a name that is not valid UTF-8, which a route's
 *   text path cannot name exactly) is [DiffContent.Unchanged].
 */
internal class DiffLoader(
    private val directory: String,
    private val target: DiffTarget,
    private val maxBytes: Long,
) {
    private val path: String = target.path

    suspend fun load(exec: RemoteExec): GitFileDiff {
        checkInput()
        val runner = GitRunner(exec)
        val root = toplevel(runner)
        return when (target) {
            is DiffTarget.Working -> worktree(runner, root, staged = false)
            is DiffTarget.Staged -> worktree(runner, root, staged = true)
            is DiffTarget.InCommit -> commit(runner, root, target.commit)
        }
    }

    /** Refuses what no command would accept before anything runs. */
    private fun checkInput() {
        if (!directory.startsWith('/') || !GitNames.isValidPath(directory) || !GitNames.isValidPath(path)) reject()
        if (target is DiffTarget.InCommit && !GitNames.isValidRevision(revision(target.commit))) reject()
    }

    private suspend fun toplevel(runner: GitRunner): String {
        val result = run(runner, build { GitCommands.revParseToplevel(directory) }, SMALL_OUTPUT_BYTES)
        if (!result.succeeded) throw GitException(DiffMapping.failureOf(result))
        val bytes = result.stdout.let { if (it.isNotEmpty() && it.last() == LF) it.copyOf(it.size - 1) else it }
        val root = strictUtf8(bytes) ?: reject()
        if (!root.startsWith('/') || !GitNames.isValidPath(root)) throw GitException(GitFailure.Unknown)
        return root
    }

    private suspend fun worktree(runner: GitRunner, root: String, staged: Boolean): GitFileDiff {
        val summary = summary(runner, build { GitCommands.diff(root, staged, output = DiffOutput.NameStatus) })
        // Checked before the patch: a conflicted file's combined diff can pass the byte limit,
        // which would hide the conflict behind TooLarge.
        if (summary?.kind == GitChangeKind.Unmerged) throw GitException(GitFailure.Conflict)
        val paths = pathsOf(summary)
        val patch = runOrTooLarge(runner, build { GitCommands.diff(root, staged, paths = paths) })
        if (patch == null) {
            // Without the name list (too long or too slow), status tells whether the path is in conflict.
            if (summary == null && statusOf(runner, root) is UnmergedEntry) throw GitException(GitFailure.Conflict)
            return tooLarge(summary) { runner.tryRun(build { GitCommands.diff(root, staged, paths = paths, output = DiffOutput.NumStat) }) }
        }
        val diff = parse(patch)
        if (diff != null) return diff
        if (!staged && statusOf(runner, root) is UntrackedEntry) {
            return GitFileDiff(path, kind = GitChangeKind.Untracked, content = DiffContent.Unchanged, additions = 0, deletions = 0)
        }
        return unchanged()
    }

    private suspend fun commit(runner: GitRunner, root: String, commit: String): GitFileDiff {
        val revision = revision(commit)
        val parents = run(runner, build { GitCommands.show(root, revision, format = "%P", output = ShowOutput.None) }, SMALL_OUTPUT_BYTES)
        if (!parents.succeeded) throw GitException(DiffMapping.failureOf(parents))
        if (String(parents.stdout, Charsets.UTF_8).split(' ', '\n').count { it.isNotBlank() } > 1) {
            throw GitException(GitFailure.Unknown)
        }
        val summary = summary(runner, build { GitCommands.show(root, revision, output = ShowOutput.NameStatus) })
        val paths = pathsOf(summary)
        val patch = runOrTooLarge(runner, build { GitCommands.show(root, revision, paths = paths) })
            ?: return tooLarge(summary) { runner.tryRun(build { GitCommands.show(root, revision, output = ShowOutput.NumStat, paths = paths) }) }
        return parse(patch) ?: unchanged()
    }

    /**
     * The `--name-status` entry of the path. A failed command fails the load, but one that prints
     * too much or runs too long, as in a huge change set, only loses the rename pairing: null.
     */
    private suspend fun summary(runner: GitRunner, command: GitCommand): ChangeSummary? {
        val result = try {
            run(runner, command)
        } catch (e: GitException) {
            if (e.failure == GitFailure.OutputTooLarge || e.failure == GitFailure.TimedOut) return null
            throw e
        }
        if (!result.succeeded) throw GitException(DiffMapping.failureOf(result))
        return DiffMapping.nameStatus(path, result.stdout)
    }

    /** The path and, for a rename or copy, its old name, so that git pairs the two. */
    private fun pathsOf(summary: ChangeSummary?): List<String> {
        val oldPath = summary?.oldPath
        return if (oldPath != null && summary.oldPathExact && oldPath != path && GitNames.isValidPath(oldPath)) listOf(oldPath, path) else listOf(path)
    }

    /** The diff in a successful patch, or null when git printed nothing for the path. */
    private fun parse(result: GitResult): GitFileDiff? {
        if (!result.succeeded) throw GitException(DiffMapping.failureOf(result))
        val stdout = result.stdout
        if (CONFLICT_MARKERS.any { stdout.startsWith(it) }) throw GitException(GitFailure.Conflict)
        val files = try {
            DiffParser.parse(stdout)
        } catch (_: GitParseException) {
            throw GitException(GitFailure.Unknown)
        }
        return DiffMapping.fileDiff(path, files)
    }

    private suspend fun tooLarge(summary: ChangeSummary?, numStat: suspend () -> GitResult?): GitFileDiff {
        val counts = numStat()?.takeIf { it.succeeded }?.let { DiffMapping.numStat(path, it.stdout) }
        return GitFileDiff(
            path = path,
            oldPath = summary?.oldPath,
            kind = summary?.kind ?: GitChangeKind.Modified,
            content = DiffContent.TooLarge(maxBytes),
            additions = counts?.first ?: 0,
            deletions = counts?.second ?: 0,
        )
    }

    /** The status entry of the path, or null when it has none; the output is small, one path. */
    private suspend fun statusOf(runner: GitRunner, root: String): StatusEntry? {
        val result = run(runner, build { GitCommands.status(root, includeUntracked = true, paths = listOf(path)) }, SMALL_OUTPUT_BYTES)
        if (!result.succeeded) throw GitException(DiffMapping.failureOf(result))
        val status = try {
            StatusParser.parse(result.stdout)
        } catch (_: GitParseException) {
            throw GitException(GitFailure.Unknown)
        }
        return status.entries.firstOrNull { it.path.isValidUtf8 && it.path.text == path }
    }

    private fun unchanged() = GitFileDiff(path, content = DiffContent.Unchanged, additions = 0, deletions = 0)

    /** Runs the patch; null when it printed more than [maxBytes], which ended it. */
    private suspend fun runOrTooLarge(runner: GitRunner, command: GitCommand): GitResult? = try {
        run(runner, command, maxBytes)
    } catch (e: GitException) {
        if (e.failure == GitFailure.OutputTooLarge) null else throw e
    }

    /** For the counts of a too large diff: any failure but cancellation is "unknown". */
    private suspend fun GitRunner.tryRun(command: GitCommand): GitResult? = try {
        run(this, command)
    } catch (_: GitException) {
        null
    }

    private suspend fun run(runner: GitRunner, command: GitCommand, maxOutputBytes: Long = RemoteExec.DEFAULT_MAX_OUTPUT_BYTES): GitResult =
        try {
            runner.run(command, maxOutputBytes)
        } catch (e: CommandException.OutputTooLarge) {
            throw GitException(GitFailure.OutputTooLarge)
        } catch (e: CommandException.TimedOut) {
            throw GitException(GitFailure.TimedOut)
        } catch (e: CommandException.ConnectionLost) {
            val cause = e.cause
            throw GitException(if (cause is SshException) GitFailure.of(cause) else GitFailure.NotConnected)
        }

    /** Builds a command; input a builder refuses is [GitFailure.Rejected]. */
    private inline fun build(block: () -> GitCommand): GitCommand = try {
        block()
    } catch (_: IllegalArgumentException) {
        reject()
    }

    private fun reject(): Nothing = throw GitException(GitFailure.Rejected)

    private fun revision(commit: String): String = "$commit^{commit}"

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    override fun toString(): String = "DiffLoader($target)"

    private companion object {
        const val LF: Byte = 0x0A

        /** For commands that print a line or two: the root, the parents. */
        const val SMALL_OUTPUT_BYTES: Long = 64L * 1024

        val CONFLICT_MARKERS = listOf("diff --cc ", "diff --combined ", "* Unmerged path ").map { it.toByteArray(Charsets.US_ASCII) }
    }
}
