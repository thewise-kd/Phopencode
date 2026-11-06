package io.github.thewisekd.phopencode.feature.git.logic.contract

/**
 * Writing a commit, `GitRoute.Commit` (P9.10 logic, P9.11 screen): the message, the staged and
 * unstaged files with stage and unstage, discarding a file's changes after a confirmation, and the
 * commit itself.
 *
 * Rules the logic keeps:
 * - A blank message is never committed: [CommitIntent.Commit] sets [messageProblem] instead.
 * - The message goes to `git commit` through stdin (`-F -`), never on the command line.
 * - A failing hook (`pre-commit`, `commit-msg`) is [CommitError.HookRejected] with its output.
 * - Discarding restores a tracked file's working tree from the index (`git restore --worktree`),
 *   only after [CommitIntent.ConfirmDiscard], and only where [canDiscard] allows it. An untracked
 *   file is never deleted, and nothing resets the repository or the index.
 *
 * @property directory The folder from the route; never changes.
 * @property staged What the commit takes.
 * @property unstaged Working tree changes and untracked files ([GitChangeKind.Untracked]).
 * @property busy What runs; the screen waits meanwhile.
 * @property confirmDiscard The change waiting for the discard confirmation.
 * @property messageProblem Why the message was refused; cleared when it is edited.
 */
data class CommitState(
    val directory: String,
    val loading: Boolean = true,
    val message: String = "",
    val staged: List<GitFileChange> = emptyList(),
    val unstaged: List<GitFileChange> = emptyList(),
    val busy: CommitOp? = null,
    val confirmDiscard: GitFileChange? = null,
    val messageProblem: CommitMessageProblem? = null,
    val error: CommitError? = null,
) {
    /** Whether the commit button is enabled. */
    val canCommit: Boolean
        get() = !loading && busy == null && message.isNotBlank() && staged.isNotEmpty()

    override fun toString(): String =
        "CommitState(directory=***, loading=$loading, message=${message.length} chars, staged=${staged.size}, " +
            "unstaged=${unstaged.size}, busy=$busy, confirmDiscard=$confirmDiscard, " +
            "messageProblem=$messageProblem, error=$error)"

    companion object {
        /** Tracked changes only: an untracked file would be deleted, an unmerged one lose its conflict. */
        fun canDiscard(change: GitFileChange): Boolean =
            change.kind != GitChangeKind.Untracked && change.kind != GitChangeKind.Unmerged
    }
}

enum class CommitOp {
    Stage,
    Unstage,
    Discard,
    Commit,
}

enum class CommitMessageProblem {
    /** Empty, or white space only. */
    Blank,
}

sealed interface CommitError {
    data class Failed(val op: CommitOp, val failure: GitFailure) : CommitError

    /**
     * A hook refused the commit; nothing was committed.
     *
     * @property output What the hook printed, cut to a length the screen can show.
     */
    data class HookRejected(val output: String) : CommitError {
        override fun toString(): String = "HookRejected(output=***)"
    }

    /** [CommitIntent.Commit] with nothing staged. */
    data object NothingStaged : CommitError
}

sealed interface CommitIntent {
    data class EditMessage(val text: String) : CommitIntent {
        override fun toString(): String = "EditMessage(***)"
    }

    data class Stage(val change: GitFileChange) : CommitIntent

    data class Unstage(val change: GitFileChange) : CommitIntent

    /** Stages every row of [CommitState.unstaged]. */
    data object StageAll : CommitIntent

    data object UnstageAll : CommitIntent

    /** Asks for the confirmation: sets [CommitState.confirmDiscard]; nothing runs yet. */
    data class RequestDiscard(val change: GitFileChange) : CommitIntent

    /** Discards [CommitState.confirmDiscard]. */
    data object ConfirmDiscard : CommitIntent

    data object CancelDiscard : CommitIntent

    /** Commits [CommitState.staged] with [CommitState.message]; sends [CommitEvent.Committed]. */
    data object Commit : CommitIntent

    /** @param staged Whether the row is in [CommitState.staged]. */
    data class OpenDiff(val change: GitFileChange, val staged: Boolean) : CommitIntent

    /** Reads the files again, keeping the message. The graph sends it when the screen is shown again. */
    data object Refresh : CommitIntent

    /** Clears [CommitState.error]. */
    data object DismissError : CommitIntent

    data object Close : CommitIntent
}

sealed interface CommitEvent {
    /** @property hash The new commit. */
    data class Committed(val hash: String) : CommitEvent

    data class OpenDiff(val path: String, val staged: Boolean) : CommitEvent {
        override fun toString(): String = "OpenDiff(path=***, staged=$staged)"
    }

    data object Close : CommitEvent
}

interface CommitPresenter : Presenter<CommitState, CommitIntent, CommitEvent>
