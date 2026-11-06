package io.github.thewisekd.phopencode.feature.git.logic.status

import io.github.thewisekd.phopencode.core.git.command.GitResult
import io.github.thewisekd.phopencode.core.git.parse.ChangedEntry
import io.github.thewisekd.phopencode.core.git.parse.GitPath
import io.github.thewisekd.phopencode.core.git.parse.GitStatus
import io.github.thewisekd.phopencode.core.git.parse.IgnoredEntry
import io.github.thewisekd.phopencode.core.git.parse.RenamedEntry
import io.github.thewisekd.phopencode.core.git.parse.StatusBranch
import io.github.thewisekd.phopencode.core.git.parse.StatusCode
import io.github.thewisekd.phopencode.core.git.parse.UnmergedEntry
import io.github.thewisekd.phopencode.core.git.parse.UntrackedEntry
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange

/**
 * One read of the status, as the screen groups it.
 *
 * Rows carry paths as text ([GitPath.text]), while git names files by bytes. A path whose bytes are
 * not UTF-8 shows with U+FFFD and cannot be given back to git as the same bytes: a command line is
 * text. Such texts are in [unrepresentable], and so is any text two different byte paths share;
 * staging, unstaging or opening the diff of a row with one is refused ([GitFailure.Rejected]) rather
 * than run on some other file. Every other text encodes back to exactly the bytes git printed.
 *
 * @property root The work tree root the status ran in; the rows' paths are relative to it.
 */
internal class StatusSnapshot(
    val root: String,
    val branch: GitBranchStatus,
    val staged: List<GitFileChange>,
    val unstaged: List<GitFileChange>,
    val untracked: List<GitFileChange>,
    val conflicted: List<GitFileChange>,
    val unrepresentable: Set<String>,
) {
    /** Whether the row's path, and the original path it needs, can be passed back to git. */
    fun isRepresentable(paths: List<String>): Boolean = paths.none { it in unrepresentable }

    override fun toString(): String = "StatusSnapshot(***)"

    companion object {
        /** Null when the status had no branch headers, which `GitCommands.status` always asks for. */
        fun of(root: String, status: GitStatus): StatusSnapshot? {
            val branch = status.branch ?: return null
            val staged = mutableListOf<GitFileChange>()
            val unstaged = mutableListOf<GitFileChange>()
            val untracked = mutableListOf<GitFileChange>()
            val conflicted = mutableListOf<GitFileChange>()
            val texts = PathTexts()
            for (entry in status.entries) {
                when (entry) {
                    is ChangedEntry -> {
                        val path = texts.of(entry.path)
                        kindOf(entry.index)?.let { staged += GitFileChange(path, it) }
                        kindOf(entry.worktree)?.let { unstaged += GitFileChange(path, it) }
                    }
                    is RenamedEntry -> {
                        val path = texts.of(entry.path)
                        val original = texts.of(entry.originalPath)
                        kindOf(entry.index)?.let { staged += GitFileChange(path, it, original.takeIf { _ -> it.hasOrigin }) }
                        kindOf(entry.worktree)?.let { unstaged += GitFileChange(path, it, original.takeIf { _ -> it.hasOrigin }) }
                    }
                    is UnmergedEntry -> conflicted += GitFileChange(texts.of(entry.path), GitChangeKind.Unmerged)
                    is UntrackedEntry -> untracked += GitFileChange(texts.of(entry.path), GitChangeKind.Untracked)
                    is IgnoredEntry -> Unit
                }
            }
            return StatusSnapshot(root, branchOf(branch), staged, unstaged, untracked, conflicted, texts.unrepresentable)
        }

        /**
         * The contract has no field for counts git did not make or for an upstream that is gone. A
         * gone upstream shows as none, which is what the branch can act on (push it anew); uncounted
         * ahead/behind (never asked for: `GitCommands.status` passes `--ahead-behind`) keeps the
         * upstream with zero counts.
         */
        private fun branchOf(branch: StatusBranch) = GitBranchStatus(
            head = branch.head,
            commit = branch.oid,
            upstream = branch.upstream.takeUnless { branch.isUpstreamGone },
            ahead = branch.ahead ?: 0,
            behind = branch.behind ?: 0,
        )

        private val GitChangeKind.hasOrigin: Boolean
            get() = this == GitChangeKind.Renamed || this == GitChangeKind.Copied

        private fun kindOf(code: StatusCode): GitChangeKind? = when (code) {
            StatusCode.UNMODIFIED -> null
            StatusCode.MODIFIED -> GitChangeKind.Modified
            StatusCode.TYPE_CHANGED -> GitChangeKind.TypeChanged
            StatusCode.ADDED -> GitChangeKind.Added
            StatusCode.DELETED -> GitChangeKind.Deleted
            StatusCode.RENAMED -> GitChangeKind.Renamed
            StatusCode.COPIED -> GitChangeKind.Copied
            StatusCode.UPDATED_UNMERGED -> GitChangeKind.Unmerged
        }
    }
}

/** Collects the texts of paths, and those that do not name exactly one byte path. */
private class PathTexts {
    private val byText = HashMap<String, GitPath>()
    val unrepresentable = HashSet<String>()

    fun of(path: GitPath): String {
        val text = path.text
        if (!path.isValidUtf8) unrepresentable += text
        val earlier = byText.put(text, path)
        if (earlier != null && earlier != path) unrepresentable += text
        return text
    }
}

/**
 * The failure a finished command with a non-zero exit stands for. Git writes its messages in English
 * (`LC_ALL=C`); only the start of a `fatal:` line is matched, so a quoted path cannot pass for one.
 */
internal fun failureOf(result: GitResult): GitFailure {
    val code = result.exitCode ?: return GitFailure.GitError(null)
    if (code == COMMAND_NOT_FOUND) return GitFailure.GitMissing
    val lines = result.stderrText().lines()
    return when {
        lines.any { it.startsWith("fatal: not a git repository") || it.startsWith("fatal: this operation must be run in a work tree") } ->
            GitFailure.NotRepository
        lines.any { it.startsWith("fatal: cannot change to '") } -> GitFailure.NotFound
        lines.any { it.startsWith("fatal: pathspec '") && it.endsWith("' did not match any files") } -> GitFailure.NotFound
        else -> GitFailure.GitError(code)
    }
}

/** What a POSIX shell exits with when it cannot find the program (`env` or `git`). */
private const val COMMAND_NOT_FOUND = 127
