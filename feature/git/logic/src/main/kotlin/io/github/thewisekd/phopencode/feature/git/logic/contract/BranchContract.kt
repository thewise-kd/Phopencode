package io.github.thewisekd.phopencode.feature.git.logic.contract

/**
 * One branch of the list.
 *
 * @property name A local name such as `main`, or a remote one such as `origin/main`.
 * @property upstream For a local branch, the branch it tracks.
 * @property ahead Against [upstream]; 0 without one.
 * @property behind Against [upstream]; 0 without one.
 * @property commit The hash of the tip.
 * @property subject The subject of the tip's commit.
 */
data class GitBranch(
    val name: String,
    val isRemote: Boolean = false,
    val isCurrent: Boolean = false,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val commit: String? = null,
    val subject: String? = null,
) {
    override fun toString(): String =
        "GitBranch(name=***, isRemote=$isRemote, isCurrent=$isCurrent, upstream=${upstream?.let { "***" }}, " +
            "ahead=$ahead, behind=$behind, commit=$commit, subject=${subject?.let { "***" }})"
}

/**
 * The branches, `GitRoute.Branches` (P9.12 logic, P9.13 screen): local and remote apart, a search,
 * and create, switch and delete.
 *
 * Rules the logic keeps:
 * - Switching with uncommitted changes ([dirty]) asks first with [BranchDialog.ConfirmSwitchDirty];
 *   the switch itself never discards a change (no `--force`, no `--discard-changes`), so git
 *   refuses it with [GitFailure.WouldOverwrite] when a change is in the way.
 * - Switching to a remote branch creates the local branch that tracks it.
 * - Deleting uses `git branch -d`; a branch that is not merged asks with
 *   [BranchDialog.ConfirmDeleteUnmerged] before `-D`. The current branch is never deleted, and no
 *   remote branch is deleted here.
 *
 * @property directory The folder from the route; never changes.
 * @property query What the search filters both lists by; blank shows everything.
 * @property dirty The working tree has uncommitted changes.
 * @property busy What runs; the screen waits meanwhile.
 * @property error The branches could not be listed.
 * @property actionError The last create, switch or delete failed.
 */
data class BranchesState(
    val directory: String,
    val loading: Boolean = true,
    val local: List<GitBranch> = emptyList(),
    val remote: List<GitBranch> = emptyList(),
    val query: String = "",
    val dirty: Boolean = false,
    val dialog: BranchDialog? = null,
    val busy: BranchOp? = null,
    val error: GitFailure? = null,
    val actionError: BranchActionError? = null,
) {
    val current: GitBranch?
        get() = local.firstOrNull { it.isCurrent }

    /** [local] whose name holds [query], ignoring case and surrounding space. */
    val shownLocal: List<GitBranch>
        get() = local.filter(::matches)

    /** [remote] filtered as [shownLocal]. */
    val shownRemote: List<GitBranch>
        get() = remote.filter(::matches)

    private fun matches(branch: GitBranch): Boolean = branch.name.contains(query.trim(), ignoreCase = true)

    override fun toString(): String =
        "BranchesState(directory=***, loading=$loading, local=${local.size}, remote=${remote.size}, " +
            "query=${query.length} chars, dirty=$dirty, dialog=$dialog, busy=$busy, error=$error, actionError=$actionError)"
}

enum class BranchOp {
    Create,
    Switch,
    Delete,
}

data class BranchActionError(val op: BranchOp, val failure: GitFailure)

sealed interface BranchDialog {
    /**
     * A new branch.
     *
     * @property startPoint The branch it starts from; null is `HEAD`.
     * @property switchAfter Whether the new branch is checked out once created.
     * @property problem Why [name] cannot be used, checked as it is typed (`git check-ref-format`
     *   rules and the names already listed).
     */
    data class Create(
        val name: String = "",
        val startPoint: String? = null,
        val switchAfter: Boolean = true,
        val problem: BranchNameProblem? = null,
    ) : BranchDialog {
        val canSubmit: Boolean
            get() = name.isNotBlank() && problem == null

        override fun toString(): String =
            "Create(name=***, startPoint=${startPoint?.let { "***" }}, switchAfter=$switchAfter, problem=$problem)"
    }

    /** The working tree has changes; switching keeps them, or git refuses. */
    data class ConfirmSwitchDirty(val branch: GitBranch) : BranchDialog

    /** The logic may ask before any delete. */
    data class ConfirmDelete(val branch: GitBranch) : BranchDialog

    /** The branch has commits no other branch holds; deleting it loses them. */
    data class ConfirmDeleteUnmerged(val branch: GitBranch) : BranchDialog
}

enum class BranchNameProblem {
    Blank,

    /** Not a valid branch name, by `git check-ref-format` rules. */
    Invalid,

    /** A local branch already has the name. */
    Taken,
}

sealed interface BranchIntent {
    data class Search(val query: String) : BranchIntent {
        override fun toString(): String = "Search(***)"
    }

    /** Lists again; the graph sends it when the screen is shown again. */
    data object Refresh : BranchIntent

    /** Lists again after [BranchesState.error]. */
    data object Retry : BranchIntent

    /** A row tapped; asks first when [BranchesState.dirty]. Sends [BranchEvent.Switched] when done. */
    data class Switch(val branch: GitBranch) : BranchIntent

    /** Opens [BranchDialog.Create]. */
    data class StartCreate(val startPoint: String? = null) : BranchIntent {
        override fun toString(): String = "StartCreate(${startPoint?.let { "***" }})"
    }

    data class EditName(val name: String) : BranchIntent {
        override fun toString(): String = "EditName(***)"
    }

    data class SetSwitchAfter(val switchAfter: Boolean) : BranchIntent

    /** Creates the branch of [BranchDialog.Create] when [BranchDialog.Create.canSubmit]. */
    data object SubmitCreate : BranchIntent

    data class Delete(val branch: GitBranch) : BranchIntent

    /** Goes ahead with the switch or delete the open dialog asks about. */
    data object Confirm : BranchIntent

    data object DismissDialog : BranchIntent

    /** Clears [BranchesState.actionError]. */
    data object DismissError : BranchIntent

    data object Close : BranchIntent
}

sealed interface BranchEvent {
    /** Another branch is checked out: the graph goes back, and the screen below reloads. */
    data object Switched : BranchEvent

    data object Close : BranchEvent
}

interface BranchesPresenter : Presenter<BranchesState, BranchIntent, BranchEvent>
