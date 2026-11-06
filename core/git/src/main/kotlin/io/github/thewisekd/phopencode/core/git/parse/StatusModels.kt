package io.github.thewisekd.phopencode.core.git.parse

/**
 * Parsed `git status --porcelain=v2 -z --branch` output; see [StatusParser].
 *
 * [branch] is null when the output had no `# branch.*` headers, that is when `--branch` was not
 * given. [entries] keep git's order. A clean repository has no entries.
 */
public data class GitStatus(
    val branch: StatusBranch?,
    val entries: List<StatusEntry>,
    /** The `# stash <n>` header (git 2.35+, `--show-stash`); null when absent. */
    val stashCount: Int? = null,
) {
    /** Whether no entry is listed. Ignored files count, so leave out `--ignored` to ask "is it clean". */
    val isClean: Boolean get() = entries.isEmpty()
}

/**
 * The `# branch.*` headers.
 *
 * [toString] hides [head] and [upstream].
 */
public data class StatusBranch(
    /** The full hash of HEAD; null for a repository with no commits yet (`(initial)`). */
    val oid: String?,
    /** The current branch name; null when HEAD is detached (`(detached)`). */
    val head: String?,
    /** The upstream such as `origin/main`; null when none is configured. */
    val upstream: String?,
    /**
     * Commits on HEAD not on the upstream. Null when there is no upstream, when the configured
     * upstream no longer exists (gone: git prints no `# branch.ab` line), or when the counts are
     * unknown ([aheadBehindUnknown]).
     */
    val ahead: Int?,
    /** Commits on the upstream not on HEAD; null exactly when [ahead] is null. */
    val behind: Int?,
    /**
     * Whether git reported `# branch.ab +? -?`: the branches differ but git did not count, because
     * the command had `--no-ahead-behind` (or a `status.aheadBehind=false` that a git version honours
     * for porcelain output) and no `--ahead-behind`. Distinct from in sync (0, 0).
     */
    val aheadBehindUnknown: Boolean = false,
) {
    /** Whether the repository has no commits yet. */
    val isInitial: Boolean get() = oid == null

    /** Whether HEAD is detached. */
    val isDetached: Boolean get() = head == null

    /** Whether an upstream is configured but git could not compare with it (it is gone). */
    val isUpstreamGone: Boolean get() = upstream != null && ahead == null && !aheadBehindUnknown

    override fun toString(): String =
        "StatusBranch(oid=$oid, head=${mask(head)}, upstream=${mask(upstream)}, ahead=$ahead, behind=$behind, " +
            "aheadBehindUnknown=$aheadBehindUnknown)"
}

/** One letter of the `XY` field; [symbol] is the letter git prints. */
public enum class StatusCode(public val symbol: Char) {
    UNMODIFIED('.'),
    MODIFIED('M'),
    TYPE_CHANGED('T'),
    ADDED('A'),
    DELETED('D'),
    RENAMED('R'),
    COPIED('C'),
    UPDATED_UNMERGED('U'),
    ;

    internal companion object {
        fun of(symbol: Char): StatusCode? = entries.firstOrNull { it.symbol == symbol }
    }
}

/** The `<sub>` field when the entry is a submodule (`S<c><m><u>`). */
public data class SubmoduleStatus(
    val commitChanged: Boolean,
    val hasTrackedChanges: Boolean,
    val hasUntrackedChanges: Boolean,
)

/** The kind of conflict of an unmerged entry, from its `XY` field. */
public enum class ConflictKind(public val code: String) {
    BOTH_DELETED("DD"),
    ADDED_BY_US("AU"),
    DELETED_BY_THEM("UD"),
    ADDED_BY_THEM("UA"),
    DELETED_BY_US("DU"),
    BOTH_ADDED("AA"),
    BOTH_MODIFIED("UU"),
    ;

    internal companion object {
        fun of(code: String): ConflictKind? = entries.firstOrNull { it.code == code }
    }
}

/** Whether a `2` entry is a rename or a copy. */
public enum class RenameKind { RENAME, COPY }

/**
 * One entry of the status. Modes are the octal file modes as numbers (`100644` is `0x81A4`), and
 * hashes are full object names; a missing side has mode 0 and an all-zero hash, as git prints them.
 */
public sealed class StatusEntry {
    /** The path in the working tree (for renames and copies, the new path). */
    public abstract val path: GitPath
}

/** An ordinary changed entry (`1`). [index] is the staged change, [worktree] the unstaged one. */
public data class ChangedEntry(
    val index: StatusCode,
    val worktree: StatusCode,
    val submodule: SubmoduleStatus?,
    val headMode: Int,
    val indexMode: Int,
    val worktreeMode: Int,
    val headHash: String,
    val indexHash: String,
    override val path: GitPath,
) : StatusEntry()

/** A renamed or copied entry (`2`); [score] is the similarity percentage. */
public data class RenamedEntry(
    val index: StatusCode,
    val worktree: StatusCode,
    val submodule: SubmoduleStatus?,
    val headMode: Int,
    val indexMode: Int,
    val worktreeMode: Int,
    val headHash: String,
    val indexHash: String,
    val kind: RenameKind,
    val score: Int,
    override val path: GitPath,
    /** The path the entry was renamed or copied from. */
    val originalPath: GitPath,
) : StatusEntry()

/** An unmerged (conflicted) entry (`u`). Stage 1 is the base, 2 is ours, 3 is theirs. */
public data class UnmergedEntry(
    val conflict: ConflictKind,
    val submodule: SubmoduleStatus?,
    val stage1Mode: Int,
    val stage2Mode: Int,
    val stage3Mode: Int,
    val worktreeMode: Int,
    val stage1Hash: String,
    val stage2Hash: String,
    val stage3Hash: String,
    override val path: GitPath,
) : StatusEntry()

/** An untracked file (`?`). */
public data class UntrackedEntry(override val path: GitPath) : StatusEntry()

/** An ignored file (`!`), listed only with `--ignored`. */
public data class IgnoredEntry(override val path: GitPath) : StatusEntry()

internal fun mask(value: String?): String = if (value == null) "null" else "<${value.length} chars>"
