package io.github.thewisekd.phopencode.feature.git.logic.branch

import io.github.thewisekd.phopencode.core.git.command.BranchScope
import io.github.thewisekd.phopencode.core.git.command.GitCommand
import io.github.thewisekd.phopencode.core.git.command.GitCommands
import io.github.thewisekd.phopencode.core.git.command.GitNames
import io.github.thewisekd.phopencode.core.git.command.GitResult
import io.github.thewisekd.phopencode.core.git.command.GitRunner
import io.github.thewisekd.phopencode.core.git.parse.BranchParser
import io.github.thewisekd.phopencode.core.git.parse.GitParseException
import io.github.thewisekd.phopencode.core.git.parse.StatusParser
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchActionError
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchDialog
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchNameProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranch
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import io.github.thewisekd.phopencode.core.git.command.GitException as GitCommandException

/**
 * The branches screen's logic: lists local and remote branches, and creates, switches and deletes
 * local ones, keeping the rules of [BranchesState].
 *
 * - A read runs `branch --list -a` and a `status` of tracked files (for [BranchesState.dirty];
 *   untracked files are not listed, so the output stays small and they do not count as dirty).
 * - Switching runs `switch -q --no-overwrite-ignore`, never with an option that discards changes:
 *   with a dirty tree it asks first, and a change in the way makes git refuse
 *   ([GitFailure.WouldOverwrite]). A remote branch `<remote>/<name>` switches to the local `<name>`:
 *   when that is free, `branch <name> refs/remotes/<remote>/<name>` creates it first, tracking the
 *   remote branch (git's default for a remote-tracking start point), so a tag or another remote's
 *   branch of the same name cannot stand in. A local `<name>` with another upstream makes the tap
 *   [GitFailure.Rejected]. The local name is what follows the first `/`, so a remote whose name
 *   holds `/` gives a longer local name. If the switch then fails, the new branch stays.
 * - Deleting asks first ([BranchDialog.ConfirmDelete]) and runs `branch -d`; when git says the
 *   branch is not merged it asks again ([BranchDialog.ConfirmDeleteUnmerged]) and only then runs
 *   `branch -D`. The current branch is refused ([GitFailure.Rejected]); remote branches are never
 *   deleted.
 * - Creating checks the name with [GitNames.isValidBranchName] and against the local branches as it
 *   is typed; a start point that names a listed branch is passed as its full ref (a local branch
 *   first), so a tag of the same name cannot stand in for it. Switching to the new branch afterwards
 *   asks first on a dirty tree when it starts from another point than `HEAD`.
 *
 * One operation runs at a time ([BranchesState.busy]); git commands run one at a time behind a
 * lock. A refresh replaces a read still running, and a read older than the last one started is
 * dropped. Every intent acts on the branch as the last read shows it, found by side and name; a
 * branch not listed is ignored. Nothing is logged.
 *
 * @param directory The folder from the route, where git runs.
 * @param git Runs `git` on the active connection.
 */
class DefaultBranchesPresenter(
    private val directory: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
) : BranchesPresenter {
    private val mutableState = MutableStateFlow(BranchesState(directory = directory))

    override val state: StateFlow<BranchesState> = mutableState.asStateFlow()

    private val eventChannel = Channel<BranchEvent>(Channel.BUFFERED)

    override val events: Flow<BranchEvent> = eventChannel.receiveAsFlow()

    private val gitLock = Mutex()

    /** Guards [listing], [generation], [createStart] together with [mutableState]. */
    private val viewLock = Any()

    /** The read the lists shown come from; null until one succeeded and after one failed. */
    private var listing: BranchListing? = null

    /** Counts reads started; only the last one started may show its result. */
    private var generation = 0L

    /** The start point of the open create dialog as passed to git; null is `HEAD`. */
    private var createStart: String? = null

    /** The running read; touched only from [onIntent] and the constructor, on the caller's thread. */
    private var loadJob: Job? = null

    init {
        load()
    }

    override fun onIntent(intent: BranchIntent) {
        when (intent) {
            is BranchIntent.Search -> edit { it.copy(query = intent.query) }
            BranchIntent.Refresh, BranchIntent.Retry -> load()
            is BranchIntent.Switch -> requestSwitch(intent.branch)
            is BranchIntent.StartCreate -> startCreate(intent.startPoint)
            is BranchIntent.EditName -> editCreate { read, dialog -> dialog.copy(name = intent.name, problem = problemOf(read, intent.name)) }
            is BranchIntent.SetSwitchAfter -> editCreate { _, dialog -> dialog.copy(switchAfter = intent.switchAfter) }
            BranchIntent.SubmitCreate -> submitCreate()
            is BranchIntent.Delete -> requestDelete(intent.branch)
            BranchIntent.Confirm -> confirm()
            BranchIntent.DismissDialog -> edit { it.copy(dialog = null) }
            BranchIntent.DismissError -> edit { it.copy(actionError = null) }
            BranchIntent.Close -> eventChannel.trySend(BranchEvent.Close)
        }
    }

    private inline fun edit(change: (BranchesState) -> BranchesState) {
        synchronized(viewLock) { mutableState.value = change(mutableState.value) }
    }

    /** The listing an action may start from: lists shown, no error, nothing running. Call under [viewLock]. */
    private fun readyListing(): BranchListing? {
        val current = mutableState.value
        return listing.takeIf { !current.loading && current.error == null && current.busy == null }
    }

    // Reading

    private fun load() {
        loadJob?.cancel()
        edit { if (it.error != null) it.copy(loading = true, error = null) else it }
        loadJob = scope.launch {
            yield()
            gitLock.withLock { reload() }
        }
    }

    /** Reads the branches and shows them unless a later read started meanwhile. Hold [gitLock]. */
    private suspend fun reload() {
        val started = synchronized(viewLock) { ++generation }
        val read = gitCall { runner -> read(runner) }
        synchronized(viewLock) {
            if (started != generation) return
            val current = mutableState.value
            mutableState.value = when (read) {
                is Outcome.Done -> {
                    listing = read.value
                    current.copy(loading = false, local = read.value.local, remote = read.value.remote, dirty = read.value.dirty, error = null)
                }
                is Outcome.Failed -> {
                    listing = null
                    current.copy(loading = false, local = emptyList(), remote = emptyList(), dirty = false, error = read.failure)
                }
            }
        }
    }

    private suspend fun read(runner: GitRunner): BranchListing {
        val branches = checked(runner.run(GitCommands.branchList(directory, BRANCH_FORMAT, BranchScope.All)))
        val status = checked(runner.run(GitCommands.status(directory, includeUntracked = false)))
        return BranchListing.of(BranchListing.parseBranches(branches.stdout), StatusParser.parse(status.stdout))
    }

    private fun checked(result: GitResult): GitResult {
        if (!result.succeeded) throw GitException(failureOf(result).asGitFailure(result.exitCode))
        return result
    }

    // Switching

    /** Where a switch to a branch goes. */
    private sealed interface SwitchPlan {
        /** Already there. */
        data object Stay : SwitchPlan

        data object Reject : SwitchPlan

        /**
         * `switch` to the local branch [name]; with [createFrom], first `branch name createFrom` from
         * that full remote ref, which sets the new branch to track it.
         */
        data class To(val name: String, val createFrom: String? = null) : SwitchPlan
    }

    private fun planSwitch(read: BranchListing, branch: GitBranch): SwitchPlan {
        if (!branch.isRemote) return if (branch.isCurrent) SwitchPlan.Stay else SwitchPlan.To(branch.name)
        val name = localNameOf(branch.name) ?: return SwitchPlan.Reject
        val remoteRef = BranchListing.REMOTES + branch.name
        // Created from the full ref, never from git's guess on the bare name, which a tag of that
        // name or the same branch on another remote would take over.
        val existing = read.find(name, remote = false) ?: return SwitchPlan.To(name, createFrom = remoteRef)
        return when {
            read.upstreamRef(name) != remoteRef -> SwitchPlan.Reject
            existing.isCurrent -> SwitchPlan.Stay
            else -> SwitchPlan.To(name)
        }
    }

    /** `origin/feature/x` is `feature/x`; null when nothing follows the remote's name. */
    private fun localNameOf(remoteName: String): String? = remoteName.substringAfter('/', "").ifEmpty { null }

    private fun requestSwitch(branch: GitBranch) {
        val target = synchronized(viewLock) {
            val read = readyListing() ?: return
            val fresh = read.find(branch) ?: return
            val current = mutableState.value
            when (val plan = planSwitch(read, fresh)) {
                SwitchPlan.Stay -> return
                SwitchPlan.Reject -> {
                    mutableState.value = current.copy(actionError = BranchActionError(BranchOp.Switch, GitFailure.Rejected))
                    return
                }
                is SwitchPlan.To -> if (read.dirty) {
                    mutableState.value = current.copy(dialog = BranchDialog.ConfirmSwitchDirty(fresh), actionError = null)
                    return
                } else {
                    mutableState.value = current.copy(busy = BranchOp.Switch, dialog = null, actionError = null)
                    plan
                }
            }
        }
        launchSwitch(target)
    }

    private fun launchSwitch(plan: SwitchPlan.To) {
        scope.launch {
            yield()
            gitLock.withLock { switchTo(plan.name, plan.createFrom) }
        }
    }

    /**
     * Creates the tracking branch when [createFrom] is set, switches, reads again and ends the
     * operation. A branch created before a switch that failed stays. Hold [gitLock].
     */
    private suspend fun switchTo(name: String, createFrom: String? = null) {
        val createFailure = if (createFrom == null) null else command { GitCommands.createBranch(directory, name, createFrom) }
        val failure = createFailure ?: command { GitCommands.switchBranch(directory, name) }
        reload()
        finish(BranchOp.Switch, failure)
        if (failure == null) eventChannel.trySend(BranchEvent.Switched)
    }

    // Creating

    private fun startCreate(startPoint: String?) {
        edit { current ->
            val read = readyListing() ?: return
            val resolved = when (startPoint) {
                null -> null
                else -> resolveStart(read, startPoint)
                    ?: return@edit current.copy(actionError = BranchActionError(BranchOp.Create, GitFailure.Rejected))
            }
            createStart = resolved
            current.copy(dialog = BranchDialog.Create(startPoint = startPoint), actionError = null)
        }
    }

    /** A listed local, then remote, branch as its full ref; any other revision git may resolve as given. */
    private fun resolveStart(read: BranchListing, startPoint: String): String? = when {
        read.find(startPoint, remote = false)?.commit != null -> BranchListing.HEADS + startPoint
        read.find(startPoint, remote = true) != null -> BranchListing.REMOTES + startPoint
        GitNames.isValidRevision(startPoint) -> startPoint
        else -> null
    }

    private inline fun editCreate(change: (BranchListing?, BranchDialog.Create) -> BranchDialog.Create) {
        edit { current ->
            val dialog = current.dialog as? BranchDialog.Create ?: return
            current.copy(dialog = change(listing, dialog))
        }
    }

    private fun problemOf(read: BranchListing?, name: String): BranchNameProblem? = when {
        name.isBlank() -> BranchNameProblem.Blank
        !GitNames.isValidBranchName(name) -> BranchNameProblem.Invalid
        read?.find(name, remote = false) != null -> BranchNameProblem.Taken
        else -> null
    }

    private fun submitCreate() {
        val (dialog, start) = synchronized(viewLock) {
            val current = mutableState.value
            val dialog = current.dialog as? BranchDialog.Create ?: return
            val read = readyListing() ?: return
            val problem = problemOf(read, dialog.name)
            if (problem != null) {
                mutableState.value = current.copy(dialog = dialog.copy(problem = problem))
                return
            }
            mutableState.value = current.copy(dialog = dialog.copy(problem = null), busy = BranchOp.Create, actionError = null)
            dialog to createStart
        }
        scope.launch {
            yield()
            gitLock.withLock { create(dialog.name, start, dialog.switchAfter) }
        }
    }

    /** Creates the branch, then switches to it when asked. Hold [gitLock]. */
    private suspend fun create(name: String, start: String?, switchAfter: Boolean) {
        val failure = command { GitCommands.createBranch(directory, name, start) }
        reload()
        when (failure) {
            null -> Unit
            BranchCommandFailure.Exists -> {
                edit { current ->
                    val dialog = current.dialog as? BranchDialog.Create
                    val marked = if (dialog != null && dialog.name == name) dialog.copy(problem = BranchNameProblem.Taken) else dialog
                    current.copy(dialog = marked ?: current.dialog, busy = null)
                }
                return
            }
            else -> {
                finish(BranchOp.Create, failure)
                return
            }
        }
        val switchNow = synchronized(viewLock) {
            val current = mutableState.value
            val created = listing?.find(name, remote = false)
            val dialog = if (current.dialog is BranchDialog.Create) null else current.dialog
            when {
                !switchAfter || created == null -> {
                    mutableState.value = current.copy(dialog = dialog, busy = null)
                    false
                }
                start != null && listing?.dirty == true -> {
                    mutableState.value = current.copy(dialog = BranchDialog.ConfirmSwitchDirty(created), busy = null)
                    false
                }
                else -> {
                    mutableState.value = current.copy(dialog = dialog, busy = BranchOp.Switch)
                    true
                }
            }
        }
        if (switchNow) switchTo(name)
    }

    // Deleting

    private fun requestDelete(branch: GitBranch) {
        edit { current ->
            val read = readyListing() ?: return
            val fresh = read.find(branch) ?: return
            when {
                fresh.isRemote -> return
                fresh.isCurrent -> current.copy(actionError = BranchActionError(BranchOp.Delete, GitFailure.Rejected))
                else -> current.copy(dialog = BranchDialog.ConfirmDelete(fresh), actionError = null)
            }
        }
    }

    private fun confirm() {
        val start: () -> Unit = synchronized(viewLock) {
            val current = mutableState.value
            val dialog = current.dialog ?: return
            val read = readyListing() ?: return
            val asked = when (dialog) {
                is BranchDialog.ConfirmSwitchDirty -> dialog.branch
                is BranchDialog.ConfirmDelete -> dialog.branch
                is BranchDialog.ConfirmDeleteUnmerged -> dialog.branch
                is BranchDialog.Create -> return
            }
            val op = if (dialog is BranchDialog.ConfirmSwitchDirty) BranchOp.Switch else BranchOp.Delete
            val fresh = read.find(asked)
            if (fresh == null) {
                mutableState.value = current.copy(dialog = null, actionError = BranchActionError(op, GitFailure.NotFound))
                return
            }
            if (dialog is BranchDialog.ConfirmSwitchDirty) {
                when (val plan = planSwitch(read, fresh)) {
                    SwitchPlan.Stay -> {
                        mutableState.value = current.copy(dialog = null)
                        return
                    }
                    SwitchPlan.Reject -> {
                        mutableState.value = current.copy(dialog = null, actionError = BranchActionError(op, GitFailure.Rejected))
                        return
                    }
                    is SwitchPlan.To -> {
                        mutableState.value = current.copy(dialog = null, busy = op, actionError = null)
                        return@synchronized { launchSwitch(plan) }
                    }
                }
            }
            if (fresh.isRemote || fresh.isCurrent) {
                mutableState.value = current.copy(dialog = null, actionError = BranchActionError(op, GitFailure.Rejected))
                return
            }
            mutableState.value = current.copy(dialog = null, busy = op, actionError = null)
            val unmerged = dialog is BranchDialog.ConfirmDeleteUnmerged
            return@synchronized { launchDelete(fresh, unmerged) }
        }
        start()
    }

    /**
     * Deletes with `-d`, or with `-D` after the user confirmed an unmerged branch. A `-d` git refuses
     * as not merged asks for that confirmation.
     */
    private fun launchDelete(branch: GitBranch, allowUnmerged: Boolean) {
        scope.launch {
            yield()
            gitLock.withLock {
                val failure = command { GitCommands.deleteBranch(directory, branch.name, allowUnmerged) }
                reload()
                if (failure == BranchCommandFailure.NotMerged && !allowUnmerged) {
                    edit { current ->
                        val fresh = listing?.find(branch) ?: branch
                        current.copy(dialog = BranchDialog.ConfirmDeleteUnmerged(fresh), busy = null)
                    }
                } else {
                    finish(BranchOp.Delete, failure)
                }
            }
        }
    }

    // Running git

    private fun finish(op: BranchOp, failure: BranchCommandFailure?) {
        edit { current -> current.copy(busy = null, actionError = failure?.let { BranchActionError(op, it.asGitFailure(null)) }) }
    }

    /** Runs the command [build] makes; null when it succeeded. */
    private suspend fun command(build: () -> GitCommand): BranchCommandFailure? =
        when (val ran = gitCall { runner -> runner.run(build()) }) {
            is Outcome.Failed -> BranchCommandFailure.Failed(ran.failure)
            is Outcome.Done -> if (ran.value.succeeded) null else failureOf(ran.value)
        }

    private fun BranchCommandFailure.asGitFailure(exitCode: Int?): GitFailure = when (this) {
        is BranchCommandFailure.Failed -> failure
        BranchCommandFailure.NotMerged, BranchCommandFailure.Exists -> GitFailure.GitError(exitCode)
    }

    /**
     * Runs [block] on the connection and turns every failure into a [GitFailure]; cancellation stays
     * a [CancellationException]. No cause is kept: an error deep in a chain may quote a path.
     */
    private suspend fun <T> gitCall(block: suspend (GitRunner) -> T): Outcome<T> = try {
        Outcome.Done(
            git.withExec { exec ->
                try {
                    block(GitRunner(exec))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: GitCommandException) {
                    throw GitException(failureOf(failure))
                } catch (_: GitParseException) {
                    throw GitException(GitFailure.Unknown)
                } catch (_: IllegalArgumentException) {
                    // A command builder refused its input: a relative folder, a name it cannot carry.
                    throw GitException(GitFailure.Rejected)
                }
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: GitException) {
        Outcome.Failed(failure.failure)
    } catch (_: Exception) {
        Outcome.Failed(GitFailure.Unknown)
    }

    private fun failureOf(failure: GitCommandException): GitFailure = when (failure) {
        is GitCommandException.TimedOut -> GitFailure.TimedOut
        is GitCommandException.OutputTooLarge -> GitFailure.OutputTooLarge
        is GitCommandException.ConnectionLost -> (failure.cause as? SshException)?.let(GitFailure::of) ?: GitFailure.NotConnected
    }

    private sealed interface Outcome<out T> {
        class Done<T>(val value: T) : Outcome<T>

        class Failed(val failure: GitFailure) : Outcome<Nothing>
    }

    private companion object {
        /** [BranchParser.FORMAT] without its option name: [GitCommands] adds `--format=` itself. */
        val BRANCH_FORMAT: String = BranchParser.FORMAT.removePrefix("--format=")
    }
}
