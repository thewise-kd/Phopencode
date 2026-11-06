package io.github.thewisekd.phopencode.feature.git.logic.branch

import io.github.thewisekd.phopencode.core.git.command.GitResult
import io.github.thewisekd.phopencode.core.git.parse.BranchKind
import io.github.thewisekd.phopencode.core.git.parse.BranchParser
import io.github.thewisekd.phopencode.core.git.parse.GitStatus
import io.github.thewisekd.phopencode.core.git.parse.UpstreamState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranch
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.core.git.parse.GitBranch as RefBranch

/**
 * One read of the branches, as the screen shows them, with the full ref names the logic acts on.
 *
 * Local names are unique among local branches and remote names among remote ones, so a shown
 * branch is found again by its side ([GitBranch.isRemote]) and its name. A local branch may share
 * its name with a remote one (`origin/x` under `refs/heads/`); the two lists keep them apart.
 *
 * @property upstreams For each local branch name, the full ref of its upstream, or null.
 */
internal class BranchListing(
    val local: List<GitBranch>,
    val remote: List<GitBranch>,
    val dirty: Boolean,
    private val upstreams: Map<String, String?>,
) {
    /** The shown branch [branch] stands for, read fresh from this listing; null when it is gone. */
    fun find(branch: GitBranch): GitBranch? = find(branch.name, branch.isRemote)

    fun find(name: String, remote: Boolean): GitBranch? = (if (remote) this.remote else local).firstOrNull { it.name == name }

    /** The full ref of the upstream of the local branch [name]. */
    fun upstreamRef(name: String): String? = upstreams[name]

    override fun toString(): String = "BranchListing(local=${local.size}, remote=${remote.size}, dirty=$dirty)"

    companion object {
        const val HEADS = "refs/heads/"
        const val REMOTES = "refs/remotes/"

        /**
         * Builds the listing from `branch --list -a` in [BranchParser.FORMAT] and a `status` of tracked
         * files. Symbolic refs (`origin/HEAD`) are left out. A current branch with no commits yet has
         * no ref; it comes from the status and is listed as current with no commit.
         */
        fun of(branches: List<RefBranch>, status: GitStatus): BranchListing {
            val local = mutableListOf<GitBranch>()
            val remote = mutableListOf<GitBranch>()
            val upstreams = HashMap<String, String?>()
            for (ref in branches) {
                if (ref.isSymbolic) continue
                when (ref.kind) {
                    BranchKind.LOCAL -> {
                        val tracking = ref.upstreamState as? UpstreamState.Tracking
                        // A gone upstream shows as none: there is nothing to compare with.
                        local += GitBranch(
                            name = ref.name,
                            isCurrent = ref.isCurrent,
                            upstream = ref.upstreamName.takeIf { tracking != null },
                            ahead = tracking?.ahead ?: 0,
                            behind = tracking?.behind ?: 0,
                            commit = ref.hash,
                        )
                        upstreams[ref.name] = ref.upstream.takeIf { tracking != null }
                    }
                    BranchKind.REMOTE -> remote += GitBranch(name = ref.name, isRemote = true, commit = ref.hash)
                }
            }
            val head = status.branch?.head
            if (head != null && local.none { it.isCurrent } && local.none { it.name == head }) {
                local.add(0, GitBranch(name = head, isCurrent = true))
            }
            return BranchListing(local, remote, dirty = status.entries.isNotEmpty(), upstreams = upstreams)
        }

        /**
         * Parses the branch list, without the `(HEAD detached at …)` or `(no branch, …)` entry
         * `branch --list` adds when no branch is checked out. A ref name never starts with `(`.
         */
        fun parseBranches(stdout: ByteArray): List<RefBranch> {
            val kept = java.io.ByteArrayOutputStream(stdout.size)
            var start = 0
            while (start < stdout.size) {
                var end = start
                while (end < stdout.size && stdout[end] != NEWLINE) end++
                val lineEnd = if (end < stdout.size) end + 1 else end
                if (stdout[start] != OPEN_PAREN) kept.write(stdout, start, lineEnd - start)
                start = lineEnd
            }
            return BranchParser.parse(kept.toByteArray())
        }

        private const val NEWLINE = '\n'.code.toByte()
        private const val OPEN_PAREN = '('.code.toByte()
    }
}

/** What a failed branch command means; see [failureOf]. */
internal sealed interface BranchCommandFailure {
    data class Failed(val failure: GitFailure) : BranchCommandFailure

    /** `branch -d` refused a branch that is not merged. */
    data object NotMerged : BranchCommandFailure

    /** `branch` refused a name a branch already has. */
    data object Exists : BranchCommandFailure
}

/**
 * What a non-zero exit of a branch command means. Git writes its messages in English (`LC_ALL=C`).
 * Only the start of a line is matched, or a fragment holding a space, which no branch name can
 * hold, so a quoted name cannot pass for a message; paths git lists start with a tab.
 */
internal fun failureOf(result: GitResult): BranchCommandFailure {
    val code = result.exitCode ?: return BranchCommandFailure.Failed(GitFailure.GitError(null))
    if (code == COMMAND_NOT_FOUND) return BranchCommandFailure.Failed(GitFailure.GitMissing)
    val lines = result.stderrText().lines()
    fun any(test: (String) -> Boolean) = lines.any(test)
    val failure = when {
        any { it.startsWith("fatal: not a git repository") || it.startsWith("fatal: this operation must be run in a work tree") } ->
            GitFailure.NotRepository
        any { it.startsWith("fatal: cannot change to '") } -> GitFailure.NotFound
        any { it.startsWith("error: ") && (it.endsWith("' is not fully merged") || it.endsWith("' is not fully merged.")) } ->
            return BranchCommandFailure.NotMerged
        any { it.startsWith("fatal: a branch named '") && it.endsWith("' already exists") } -> return BranchCommandFailure.Exists
        any { it.startsWith("error: ") && it.contains(" would be overwritten by ") } -> GitFailure.WouldOverwrite
        any { it.startsWith("error: you need to resolve your current index first") } -> GitFailure.Conflict
        any {
            it.startsWith("fatal: invalid reference: ") ||
                it.startsWith("fatal: not a valid object name: ") ||
                (it.startsWith("error: branch '") && it.endsWith("' not found")) ||
                (it.startsWith("error: branch '") && it.endsWith("' not found."))
        } -> GitFailure.NotFound
        else -> GitFailure.GitError(code)
    }
    return BranchCommandFailure.Failed(failure)
}

/** What a POSIX shell exits with when it cannot find the program (`env` or `git`). */
private const val COMMAND_NOT_FOUND = 127
