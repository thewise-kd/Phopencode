package io.github.thewisekd.phopencode.feature.git.logic.contract

/** How a file changed, as `git status` and `git diff --name-status` tell it. */
enum class GitChangeKind {
    Added,
    Modified,
    Deleted,
    Renamed,
    Copied,

    /** A file became a symlink, or the other way round. */
    TypeChanged,

    /** Not tracked by git and not ignored. */
    Untracked,

    /** In a merge conflict, not resolved yet. */
    Unmerged,
}

/**
 * One changed file, a row of the status and commit screens.
 *
 * @property path Relative to the repository, as git prints it. May hold any character but NUL,
 *   newlines included.
 * @property originalPath The path before a rename or copy.
 */
data class GitFileChange(
    val path: String,
    val kind: GitChangeKind,
    val originalPath: String? = null,
) {
    override fun toString(): String =
        "GitFileChange(path=***, kind=$kind, originalPath=${originalPath?.let { "***" }})"
}

/**
 * Where `HEAD` is, from the branch headers of `git status --porcelain=v2 --branch`.
 *
 * @property head The checked-out branch; null when `HEAD` is detached.
 * @property commit The commit `HEAD` is on; null on a branch without commits yet.
 * @property upstream The tracked branch, such as `origin/main`; null when none is set.
 * @property ahead Commits on [head] that [upstream] lacks; 0 without an upstream.
 * @property behind Commits on [upstream] that [head] lacks; 0 without an upstream.
 */
data class GitBranchStatus(
    val head: String?,
    val commit: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
) {
    val isDetached: Boolean
        get() = head == null

    val hasUpstream: Boolean
        get() = upstream != null

    override fun toString(): String =
        "GitBranchStatus(head=${head?.let { "***" }}, commit=$commit, upstream=${upstream?.let { "***" }}, " +
            "ahead=$ahead, behind=$behind)"
}
