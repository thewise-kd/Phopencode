package io.github.thewisekd.phopencode.core.git.parse

/** Whether a branch is under `refs/heads/` or `refs/remotes/`. */
public enum class BranchKind { LOCAL, REMOTE }

/** How a branch relates to its configured upstream. */
public sealed class UpstreamState {
    /** The upstream exists; [ahead] and [behind] are commit counts, both 0 when in sync. */
    public data class Tracking(val ahead: Int, val behind: Int) : UpstreamState()

    /** The upstream is configured but no longer exists. */
    public data object Gone : UpstreamState()
}

/**
 * One branch from [BranchParser]. [toString] hides the names.
 */
public data class GitBranch(
    /** The full ref name, such as `refs/heads/main` or `refs/remotes/origin/main`. */
    val refName: String,
    val kind: BranchKind,
    /** Whether this is the branch checked out in this worktree. */
    val isCurrent: Boolean,
    /** The full hash the branch points to. */
    val hash: String,
    /** The full ref name of the upstream; null when none is configured. */
    val upstream: String?,
    /** Null exactly when [upstream] is null. */
    val upstreamState: UpstreamState?,
    /** For a symbolic ref such as `refs/remotes/origin/HEAD`, the full ref it points to. */
    val symrefTarget: String?,
) {
    /** The name without `refs/heads/` or `refs/remotes/`: `main`, `origin/main`. */
    val name: String get() = shortRef(refName)

    /** The short upstream name, such as `origin/main`; null without upstream. */
    val upstreamName: String? get() = upstream?.let(::shortRef)

    /** Whether this is a symbolic ref, such as `origin/HEAD`, that a branch list usually hides. */
    val isSymbolic: Boolean get() = symrefTarget != null

    override fun toString(): String =
        "GitBranch(refName=${mask(refName)}, kind=$kind, isCurrent=$isCurrent, hash=$hash, " +
            "upstream=${mask(upstream)}, upstreamState=$upstreamState, symrefTarget=${mask(symrefTarget)})"
}

internal fun shortRef(ref: String): String = when {
    ref.startsWith("refs/heads/") -> ref.removePrefix("refs/heads/")
    ref.startsWith("refs/remotes/") -> ref.removePrefix("refs/remotes/")
    else -> ref
}
