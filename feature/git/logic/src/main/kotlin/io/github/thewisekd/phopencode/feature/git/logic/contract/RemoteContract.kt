package io.github.thewisekd.phopencode.feature.git.logic.contract

/**
 * A remote of the repository.
 *
 * @property fetchUrl Can hold a user name or a token; never printed.
 * @property pushUrl As [fetchUrl]; null when it is the fetch URL.
 */
data class GitRemote(
    val name: String,
    val fetchUrl: String? = null,
    val pushUrl: String? = null,
) {
    override fun toString(): String =
        "GitRemote(name=***, fetchUrl=${fetchUrl?.let { "***" }}, pushUrl=${pushUrl?.let { "***" }})"
}

/**
 * Fetch, pull and push, `GitRoute.Remote` (P9.14 logic, P9.15 screen).
 *
 * Rules the logic keeps:
 * - Pull is `git pull --ff-only`: it never merges or rebases; diverged branches are
 *   [GitFailure.Diverged], local changes in the way [GitFailure.WouldOverwrite].
 * - Push never forces: no `--force`, `-f`, `--force-with-lease` or `+` refspec, and nothing here
 *   can ask for one. A refused push is [GitFailure.NonFastForward], which the screen explains
 *   (pull first). Push asks first with [confirmPush].
 * - A remote asking for credentials fails at once as [GitFailure.CredentialRequired]
 *   (`GIT_TERMINAL_PROMPT=0`, stdin closed, `GIT_SSH_COMMAND` with `BatchMode=yes`); nothing hangs.
 * - One operation runs at a time. [RemoteIntent.Cancel] cancels its coroutine, which ends the
 *   command it started and no other process; the result is [RemoteResult.Cancelled].
 *
 * @property directory The folder from the route; never changes.
 * @property selected The remote operations use; starts as the upstream's remote, or `origin`.
 * @property branch The current branch with its upstream and ahead/behind counts.
 * @property running The operation that runs.
 * @property confirmPush The push waiting for its confirmation.
 * @property result How the last operation ended; stays until dismissed or the next starts.
 * @property error The remotes could not be read.
 */
data class RemoteState(
    val directory: String,
    val loading: Boolean = true,
    val remotes: List<GitRemote> = emptyList(),
    val selected: String? = null,
    val branch: GitBranchStatus? = null,
    val running: RemoteOp? = null,
    val confirmPush: PushPlan? = null,
    val result: RemoteResult? = null,
    val error: GitFailure? = null,
) {
    /** Whether fetch, pull and push are enabled. */
    val canStart: Boolean
        get() = !loading && error == null && running == null && selected != null

    override fun toString(): String =
        "RemoteState(directory=***, loading=$loading, remotes=${remotes.size}, selected=${selected?.let { "***" }}, " +
            "branch=$branch, running=$running, confirmPush=$confirmPush, result=$result, error=$error)"
}

enum class RemoteOp {
    Fetch,

    /** `--ff-only`. */
    Pull,

    /** Never forced. */
    Push,
}

/**
 * What a push will do, shown in its confirmation.
 *
 * @property ahead The commits it sends, as far as known.
 * @property setUpstream The branch has no upstream yet; the push sets it (`-u`).
 */
data class PushPlan(
    val remote: String,
    val branch: String,
    val ahead: Int,
    val setUpstream: Boolean,
) {
    override fun toString(): String = "PushPlan(remote=***, branch=***, ahead=$ahead, setUpstream=$setUpstream)"
}

sealed interface RemoteResult {
    val op: RemoteOp

    /** @property upToDate Nothing was fetched, pulled or pushed. */
    data class Succeeded(override val op: RemoteOp, val upToDate: Boolean = false) : RemoteResult

    data class Failed(override val op: RemoteOp, val failure: GitFailure) : RemoteResult

    data class Cancelled(override val op: RemoteOp) : RemoteResult
}

sealed interface RemoteIntent {
    data class SelectRemote(val name: String) : RemoteIntent {
        override fun toString(): String = "SelectRemote(***)"
    }

    data object Fetch : RemoteIntent

    data object Pull : RemoteIntent

    /** Asks for the confirmation: sets [RemoteState.confirmPush]; nothing runs yet. */
    data object RequestPush : RemoteIntent

    /** Pushes [RemoteState.confirmPush]. */
    data object ConfirmPush : RemoteIntent

    data object DismissPush : RemoteIntent

    /** Cancels [RemoteState.running]. */
    data object Cancel : RemoteIntent

    /** Clears [RemoteState.result]. */
    data object DismissResult : RemoteIntent

    /** Reads the remotes and the branch again; the graph sends it when the screen is shown again. */
    data object Refresh : RemoteIntent

    /** Reads again after [RemoteState.error]. */
    data object Retry : RemoteIntent

    data object Close : RemoteIntent
}

sealed interface RemoteEvent {
    data object Close : RemoteEvent
}

interface RemotePresenter : Presenter<RemoteState, RemoteIntent, RemoteEvent>
