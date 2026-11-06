package io.github.thewisekd.phopencode.feature.git.logic.contract

/**
 * The state of the working tree, `GitRoute.Status` (P9.4 logic, P9.5 screen): the branch, and the
 * changed files grouped as git groups them. The hub of the feature: it opens a file's diff, the
 * commit screen, the history, the branches and the remote operations.
 *
 * Where it comes from: `git status --porcelain=v2 -z --branch` through [GitConnection]. A file
 * changed both in the index and in the working tree is a row of [staged] and of [unstaged].
 *
 * @property directory The folder from the route; never changes.
 * @property refreshing A reload with the rows still shown.
 * @property branch Null until loaded.
 * @property conflicted Unmerged files ([GitChangeKind.Unmerged]); staging one marks it resolved.
 * @property pending Paths whose stage or unstage runs; their rows wait.
 * @property error The status could not be read; nothing else is shown.
 * @property actionError The last stage or unstage failed; the rows stay as they were.
 */
data class StatusState(
    val directory: String,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val branch: GitBranchStatus? = null,
    val staged: List<GitFileChange> = emptyList(),
    val unstaged: List<GitFileChange> = emptyList(),
    val untracked: List<GitFileChange> = emptyList(),
    val conflicted: List<GitFileChange> = emptyList(),
    val pending: Set<String> = emptySet(),
    val error: GitFailure? = null,
    val actionError: GitFailure? = null,
) {
    /** Rows in all groups. */
    val changeCount: Int
        get() = staged.size + unstaged.size + untracked.size + conflicted.size

    /** Loaded, without an error and without changes: nothing to commit. */
    val isClean: Boolean
        get() = !loading && error == null && changeCount == 0

    override fun toString(): String =
        "StatusState(directory=***, loading=$loading, refreshing=$refreshing, branch=$branch, " +
            "staged=${staged.size}, unstaged=${unstaged.size}, untracked=${untracked.size}, " +
            "conflicted=${conflicted.size}, pending=${pending.size}, error=$error, actionError=$actionError)"
}

sealed interface StatusIntent {
    /**
     * Reads the status again, keeping the rows until the new ones arrive. The graph sends it
     * whenever the screen is shown again: after a diff, a commit, a branch switch or a pull.
     */
    data object Refresh : StatusIntent

    /** Reads again after [StatusState.error]. */
    data object Retry : StatusIntent

    /** Stages a row of [StatusState.unstaged], [StatusState.untracked] or [StatusState.conflicted]. */
    data class Stage(val change: GitFileChange) : StatusIntent

    /** Takes a row of [StatusState.staged] out of the index; the working tree keeps the change. */
    data class Unstage(val change: GitFileChange) : StatusIntent

    /** Stages every unstaged and untracked row; conflicted rows stay. */
    data object StageAll : StatusIntent

    data object UnstageAll : StatusIntent

    /**
     * A row tapped: sends [StatusEvent.OpenDiff].
     *
     * @param staged Whether the row is in [StatusState.staged]: the index against `HEAD` instead of
     *   the working tree against the index.
     */
    data class OpenDiff(val change: GitFileChange, val staged: Boolean) : StatusIntent

    data object OpenCommit : StatusIntent

    data object OpenLog : StatusIntent

    data object OpenBranches : StatusIntent

    data object OpenRemote : StatusIntent

    /** Clears [StatusState.actionError]. */
    data object DismissError : StatusIntent

    data object Close : StatusIntent
}

sealed interface StatusEvent {
    data class OpenDiff(val path: String, val staged: Boolean) : StatusEvent {
        override fun toString(): String = "OpenDiff(path=***, staged=$staged)"
    }

    data object OpenCommit : StatusEvent

    data object OpenLog : StatusEvent

    data object OpenBranches : StatusEvent

    data object OpenRemote : StatusEvent

    data object Close : StatusEvent
}

interface StatusPresenter : Presenter<StatusState, StatusIntent, StatusEvent>
