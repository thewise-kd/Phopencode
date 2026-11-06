package io.github.thewisekd.phopencode.feature.git.logic.remote

import io.github.thewisekd.phopencode.core.git.command.GitCommands
import io.github.thewisekd.phopencode.core.git.command.GitNames
import io.github.thewisekd.phopencode.core.git.command.GitResult
import io.github.thewisekd.phopencode.core.git.command.GitRunner
import io.github.thewisekd.phopencode.core.git.parse.GitParseException
import io.github.thewisekd.phopencode.core.git.parse.GitPath
import io.github.thewisekd.phopencode.core.git.parse.StatusParser
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitRemote
import io.github.thewisekd.phopencode.feature.git.logic.contract.PushPlan
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemotePresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteResult
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import io.github.thewisekd.phopencode.feature.git.logic.status.StatusSnapshot
import io.github.thewisekd.phopencode.feature.git.logic.status.failureOf
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * The remote screen's logic: reads the current branch with its upstream (ahead/behind, or gone) and
 * the configured remotes of the repository holding [directory], and fetches, pulls and pushes.
 *
 * Reading: every read resolves the work tree root (`rev-parse --show-toplevel`), runs `status` there
 * for the branch headers ([StatusSnapshot]) and `remote` for the remote names. Remote names come
 * only from that list; a name that is not a single safe word ([GitNames.isValidRemoteName], so not
 * one holding `/`) or whose bytes are not UTF-8 is left out, because it could not be passed back to
 * git as the same name. Remote URLs are never read. The selection stays while its remote is listed;
 * otherwise it falls back to the upstream's remote, then `origin`, then the first remote.
 *
 * Operations, all from the root:
 * - Fetch: [GitCommands.fetch] of the selected remote (`--multiple`, so git refuses a name that is
 *   not configured; no prune). Up to date when git printed nothing but its `Fetching <remote>` line.
 * - Pull: that fetch, then [GitCommands.mergeFastForward]. The target is `@{upstream}` when the
 *   upstream is on the selected remote, otherwise `refs/remotes/<remote>/<branch>` for the branch of
 *   the same name. It only fast-forwards; `git pull` itself is never run. Up to date when `HEAD`
 *   did not move.
 * - Push: [RemoteIntent.RequestPush] only builds the [PushPlan] for the confirmation. The push
 *   ([GitCommands.push]) sends `refs/heads/B:refs/heads/B` and has no way to force. It sets the
 *   upstream (`--set-upstream`) when the branch has none, and when its upstream is gone: the push
 *   then recreates the remote branch and points the branch at it again, which the plan's
 *   `setUpstream` shows before anything runs. The plan's `ahead` is the known count when the
 *   upstream is `<remote>/<branch>` of the selected remote, and 0 (unknown) otherwise. Up to date
 *   when git says `Everything up-to-date`.
 *
 * Failures come from git's stderr (`LC_ALL=C`), matched only on lines git writes itself, never on
 * `remote:` lines a server sends: a refused credential prompt is [GitFailure.CredentialRequired]
 * (the environment makes every prompt fail at once), a refused push [GitFailure.NonFastForward], an
 * impossible fast-forward [GitFailure.Diverged], local files in the way [GitFailure.WouldOverwrite],
 * an unfinished merge [GitFailure.Conflict], and an unknown remote or ref [GitFailure.NotFound].
 *
 * Concurrency: git work runs one piece at a time behind a lock, and one operation at a time
 * ([RemoteState.running]); another asked for meanwhile is ignored. Every read has a generation
 * number and only the newest publishes. An operation keeps the remote (and for a push the plan) the
 * user chose, but decides what to run inside the lock from the newest published read: a remote no
 * longer listed is [GitFailure.NotFound] and runs nothing, and a push whose plan changed asks for the
 * confirmation again instead of pushing.
 *
 * Cancelling: [RemoteIntent.Cancel] cancels the coroutine around the running git command, which ends
 * that command and no other process (`RemoteExec`), as the command's own timeout does; the result is
 * [RemoteResult.Cancelled]. After an operation that ran anything, failed or cancelled too, the state
 * is read again, so it shows what really happened (a push may have landed before the cancel did).
 * When that read fails, its failure shows as [RemoteState.error] and the result waits behind it.
 *
 * Nothing is logged: no remote name, URL, branch name or output.
 *
 * @param directory The folder from the route, where git runs.
 * @param git Runs `git` on the active connection.
 */
class DefaultRemotePresenter(
    private val directory: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
) : RemotePresenter {
    private val mutableState = MutableStateFlow(RemoteState(directory = directory))

    override val state: StateFlow<RemoteState> = mutableState.asStateFlow()

    private val eventChannel = Channel<RemoteEvent>(Channel.BUFFERED)

    override val events: Flow<RemoteEvent> = eventChannel.receiveAsFlow()

    private val gitLock = Mutex()

    /** The running read; touched only from [onIntent] and the constructor, on the caller's thread. */
    private var loadJob: Job? = null

    /** Guards [snapshot], [generation], [active] and [mutableState] together. */
    private val viewLock = Any()

    /** The newest published read; null until one succeeded and after one failed. */
    private var snapshot: Read? = null

    /** The number of the newest read asked for; only that read publishes. */
    private var generation = 0L

    /** The operation that runs, for [RemoteIntent.Cancel]. */
    private var active: Running? = null

    init {
        load()
    }

    override fun onIntent(intent: RemoteIntent) {
        when (intent) {
            is RemoteIntent.SelectRemote -> select(intent.name)
            RemoteIntent.Fetch -> fetch()
            RemoteIntent.Pull -> pull()
            RemoteIntent.RequestPush -> requestPush()
            RemoteIntent.ConfirmPush -> confirmPush()
            RemoteIntent.DismissPush -> edit { it.copy(confirmPush = null) }
            RemoteIntent.Cancel -> cancel()
            RemoteIntent.DismissResult -> edit { it.copy(result = null) }
            RemoteIntent.Refresh, RemoteIntent.Retry -> load()
            RemoteIntent.Close -> eventChannel.trySend(RemoteEvent.Close)
        }
    }

    private inline fun edit(change: (RemoteState) -> RemoteState) {
        synchronized(viewLock) { mutableState.value = change(mutableState.value) }
    }

    // Reading

    /**
     * One read: the work tree root, the branch headers and the remote names that can be passed back
     * to git.
     */
    private class Read(val root: String, val branch: GitBranchStatus, val remotes: List<String>) {
        /** The remote of the upstream, when it is one of [remotes]. */
        val upstreamRemote: String?
            get() {
                val upstream = branch.upstream ?: return null
                if ('/' !in upstream) return null
                return upstream.substringBefore('/').takeIf { it in remotes }
            }

        override fun toString(): String = "Read(***)"
    }

    /**
     * Reads as a new [generation], replacing a read still running. [RemoteState.loading] stays set
     * until the newest read lands; an older one publishes nothing.
     */
    private fun load() {
        loadJob?.cancel()
        val number = synchronized(viewLock) {
            val current = mutableState.value
            mutableState.value = if (snapshot == null) current.copy(loading = true, error = null) else current.copy(loading = true)
            ++generation
        }
        loadJob = scope.launch {
            yield()
            gitLock.withLock {
                if (!isNewest(number)) return@withLock
                publish(number, readRepository(), afterOp = false)
            }
        }
    }

    private fun isNewest(number: Long): Boolean = synchronized(viewLock) { number == generation }

    /**
     * Shows [read] if [number] is still the newest generation. A failure leaves nothing to act on;
     * after an operation it does so even when a newer read is on its way, because the operation
     * changed what the old read describes.
     */
    private fun publish(number: Long, read: Outcome<Read>, afterOp: Boolean) = synchronized(viewLock) {
        val newest = number == generation
        val current = mutableState.value
        when (read) {
            is Outcome.Done -> if (newest) {
                val value = read.value
                snapshot = value
                mutableState.value = current.copy(
                    loading = false,
                    error = null,
                    remotes = value.remotes.map { GitRemote(it) },
                    selected = current.selected?.takeIf { it in value.remotes } ?: defaultRemote(value),
                    branch = value.branch,
                )
            }
            is Outcome.Failed -> when {
                newest -> {
                    snapshot = null
                    mutableState.value = current.copy(loading = false, confirmPush = null, error = read.failure)
                }
                afterOp -> snapshot = null
                else -> Unit
            }
        }
        Unit
    }

    private fun defaultRemote(read: Read): String? =
        read.upstreamRemote ?: ORIGIN.takeIf { it in read.remotes } ?: read.remotes.firstOrNull()

    private suspend fun readRepository(): Outcome<Read> = gitCall { runner ->
        val root = resolveRoot(runner)
        val status = runner.run(GitCommands.status(root))
        if (!status.succeeded) throw GitException(failureOf(status))
        val branch = StatusSnapshot.of(root, StatusParser.parse(status.stdout))?.branch ?: throw GitException(GitFailure.Unknown)
        val list = runner.run(GitCommands.remoteList(root))
        if (!list.succeeded) throw GitException(failureOf(list))
        Read(root, branch, remoteNames(list.stdout))
    }

    /** The lines of `git remote` that are safe remote names and decode to exactly their bytes. */
    private fun remoteNames(stdout: ByteArray): List<String> {
        val names = mutableListOf<String>()
        var start = 0
        for (index in 0..stdout.size) {
            if (index < stdout.size && stdout[index] != NEWLINE) continue
            val path = GitPath(stdout.copyOfRange(start, index))
            start = index + 1
            if (!path.isValidUtf8) continue
            val name = path.text
            if (name.isNotEmpty() && GitNames.isValidRemoteName(name) && name !in names) names += name
        }
        return names
    }

    /** The work tree root of [directory], as `rev-parse --show-toplevel` prints it. */
    private suspend fun resolveRoot(runner: GitRunner): String {
        val result = runner.run(GitCommands.revParseToplevel(directory))
        if (!result.succeeded) throw GitException(failureOf(result))
        val out = result.stdout
        if (out.isEmpty() || out.last() != NEWLINE) throw GitException(GitFailure.Unknown)
        val path = GitPath(out.copyOfRange(0, out.size - 1))
        if (!path.isValidUtf8) throw GitException(GitFailure.Rejected)
        val root = path.text
        if (!root.startsWith('/') || !GitNames.isValidPath(root)) throw GitException(GitFailure.Unknown)
        return root
    }

    // Selecting

    private fun select(name: String) = synchronized(viewLock) {
        val current = mutableState.value
        if (current.running != null || current.remotes.none { it.name == name }) return
        if (current.selected != name) mutableState.value = current.copy(selected = name, confirmPush = null)
    }

    // Starting

    /** One operation that runs; [work] is the git call [RemoteIntent.Cancel] cancels. */
    private class Running(val op: RemoteOp) {
        var cancelled = false
        var work: Job? = null
    }

    /**
     * Marks [op] as running if a read stands behind the screen, a remote is selected and nothing else
     * runs, and returns the selected remote; null when the intent does not apply. Only a gate for the
     * screen: what runs is decided inside [gitLock] from the newest read.
     */
    private fun begin(op: RemoteOp): Pair<Running, String>? = synchronized(viewLock) {
        val current = mutableState.value
        val remote = current.selected ?: return null
        if (snapshot == null || current.running != null || current.error != null) return null
        val running = Running(op)
        active = running
        mutableState.value = current.copy(running = op, result = null, confirmPush = null)
        running to remote
    }

    private fun cancel() {
        val work = synchronized(viewLock) {
            val running = active ?: return
            running.cancelled = true
            running.work
        }
        work?.cancel()
    }

    /** What an operation left: its result (null for none) and whether anything ran. */
    private class OpEnd(val result: RemoteResult?, val ran: Boolean)

    /**
     * Runs [action] inside [gitLock] with the newest published read; with none, or a newer one still
     * on its way, nothing runs. After a command ran the state is read again as a new generation,
     * then [running] ends with its result.
     */
    private fun runOp(running: Running, action: suspend (Read) -> OpEnd) {
        scope.launch {
            yield()
            gitLock.withLock {
                try {
                    val (read, loading) = synchronized(viewLock) { snapshot to mutableState.value.loading }
                    val end = when {
                        synchronized(viewLock) { running.cancelled } -> OpEnd(RemoteResult.Cancelled(running.op), ran = false)
                        read == null || loading -> OpEnd(null, ran = false)
                        else -> action(read)
                    }
                    if (end.ran) {
                        val number = synchronized(viewLock) { ++generation }
                        publish(number, readRepository(), afterOp = true)
                    }
                    finish(running) { it.copy(result = end.result ?: it.result) }
                } catch (cancelled: CancellationException) {
                    finish(running) { it }
                    throw cancelled
                }
            }
        }
    }

    private inline fun finish(running: Running, change: (RemoteState) -> RemoteState) = synchronized(viewLock) {
        if (active === running) active = null
        val current = mutableState.value
        mutableState.value = change(if (current.running == running.op) current.copy(running = null) else current)
    }

    /** What a cancellable git call gave. */
    private sealed interface Step<out T> {
        class Done<T>(val outcome: Outcome<T>) : Step<T>

        data object Cancelled : Step<Nothing>
    }

    /**
     * Runs [block] as the cancellable work of [running]. A [RemoteIntent.Cancel], before or while it
     * runs, gives [Step.Cancelled] once the command has ended; cancelling the screen's scope
     * propagates.
     */
    private suspend fun <T> cancellable(running: Running, block: suspend (GitRunner) -> T): Step<T> = coroutineScope {
        val work = async(start = CoroutineStart.LAZY) { gitCall(block) }
        val go = synchronized(viewLock) {
            if (running.cancelled) {
                false
            } else {
                running.work = work
                true
            }
        }
        if (!go) {
            work.cancel()
            return@coroutineScope Step.Cancelled
        }
        try {
            Step.Done(work.await())
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            Step.Cancelled
        } finally {
            synchronized(viewLock) { if (running.work === work) running.work = null }
        }
    }

    /** The result of one command step of [op]: null to go on, or how the operation ends. */
    private fun stepEnd(op: RemoteOp, step: Step<GitResult>, mapFailure: (GitResult) -> GitFailure): RemoteResult? = when (step) {
        Step.Cancelled -> RemoteResult.Cancelled(op)
        is Step.Done -> when (val outcome = step.outcome) {
            is Outcome.Failed -> RemoteResult.Failed(op, outcome.failure)
            is Outcome.Done -> if (outcome.value.succeeded) null else RemoteResult.Failed(op, mapFailure(outcome.value))
        }
    }

    // Fetch

    private fun fetch() {
        val (running, remote) = begin(RemoteOp.Fetch) ?: return
        runOp(running) { read ->
            if (remote !in read.remotes) return@runOp OpEnd(RemoteResult.Failed(RemoteOp.Fetch, GitFailure.NotFound), ran = false)
            val step = cancellable(running) { runner -> runner.run(GitCommands.fetch(read.root, remote)) }
            val end = stepEnd(RemoteOp.Fetch, step, ::remoteFailure)
            val result = end ?: RemoteResult.Succeeded(RemoteOp.Fetch, upToDate = fetchedNothing((step as Step.Done).outcome))
            OpEnd(result, ran = true)
        }
    }

    /** `fetch --multiple` prints `Fetching <remote>` first; anything more is an update or a warning. */
    private fun fetchedNothing(outcome: Outcome<GitResult>): Boolean {
        val result = (outcome as? Outcome.Done)?.value ?: return false
        return result.stderrText().lines().all { it.isEmpty() || it.startsWith(FETCHING) }
    }

    // Pull

    private fun pull() {
        val (running, remote) = begin(RemoteOp.Pull) ?: return
        runOp(running) { read ->
            val head = read.branch.head
            when {
                head == null || !GitNames.isValidBranchName(head) ->
                    return@runOp OpEnd(RemoteResult.Failed(RemoteOp.Pull, GitFailure.Rejected), ran = false)
                remote !in read.remotes ->
                    return@runOp OpEnd(RemoteResult.Failed(RemoteOp.Pull, GitFailure.NotFound), ran = false)
            }
            val fetched = cancellable(running) { runner -> runner.run(GitCommands.fetch(read.root, remote)) }
            stepEnd(RemoteOp.Pull, fetched, ::remoteFailure)?.let { return@runOp OpEnd(it, ran = true) }
            val target = if (read.upstreamRemote == remote) UPSTREAM else "refs/remotes/$remote/$head"
            val merged = cancellable(running) { runner -> runner.run(GitCommands.mergeFastForward(read.root, target)) }
            stepEnd(RemoteOp.Pull, merged, ::remoteFailure)?.let { return@runOp OpEnd(it, ran = true) }
            // Read again here, inside the lock, to tell whether HEAD moved.
            val number = synchronized(viewLock) { ++generation }
            val after = readRepository()
            publish(number, after, afterOp = true)
            val moved = (after as? Outcome.Done)?.value?.branch?.commit != read.branch.commit
            OpEnd(RemoteResult.Succeeded(RemoteOp.Pull, upToDate = !moved), ran = false)
        }
    }

    // Push

    /** The plan for pushing the current branch of [read] to [remote], or why there is none. */
    private fun planOf(read: Read, remote: String): Outcome<PushPlan> {
        val branch = read.branch
        val head = branch.head
        if (head == null || !GitNames.isValidBranchName(head)) return Outcome.Failed(GitFailure.Rejected)
        if (remote !in read.remotes) return Outcome.Failed(GitFailure.NotFound)
        // No upstream, or a gone one: the push sets it, recreating the remote branch if needed.
        val setUpstream = branch.upstream == null || branch.upstreamGone
        val counted = branch.upstream == "$remote/$head" && !branch.upstreamGone && branch.aheadBehindKnown
        return Outcome.Done(PushPlan(remote = remote, branch = head, ahead = if (counted) branch.ahead else 0, setUpstream = setUpstream))
    }

    /** Opens the confirmation from the newest read; nothing runs. */
    private fun requestPush() = synchronized(viewLock) {
        val current = mutableState.value
        val read = snapshot ?: return
        val remote = current.selected ?: return
        if (current.running != null || current.error != null) return
        mutableState.value = when (val plan = planOf(read, remote)) {
            is Outcome.Done -> current.copy(confirmPush = plan.value, result = null)
            is Outcome.Failed -> current.copy(confirmPush = null, result = RemoteResult.Failed(RemoteOp.Push, plan.failure))
        }
    }

    /**
     * Pushes the confirmed plan. Inside [gitLock] the plan is made again from the newest read; when
     * it differs from the confirmed one in anything but the count, the confirmation opens again with
     * the new plan and nothing is pushed.
     */
    private fun confirmPush() {
        val confirmed = synchronized(viewLock) { mutableState.value.confirmPush } ?: return
        val (running, _) = begin(RemoteOp.Push) ?: return
        runOp(running) { read ->
            val plan = when (val planned = planOf(read, confirmed.remote)) {
                is Outcome.Failed -> return@runOp OpEnd(RemoteResult.Failed(RemoteOp.Push, planned.failure), ran = false)
                is Outcome.Done -> planned.value
            }
            if (plan.copy(ahead = confirmed.ahead) != confirmed) {
                edit { it.copy(confirmPush = plan) }
                return@runOp OpEnd(null, ran = false)
            }
            val step = cancellable(running) { runner ->
                runner.run(GitCommands.push(read.root, plan.remote, plan.branch, setUpstream = plan.setUpstream))
            }
            val end = stepEnd(RemoteOp.Push, step, ::remoteFailure)
            val result = end ?: RemoteResult.Succeeded(RemoteOp.Push, upToDate = pushedNothing((step as Step.Done).outcome))
            OpEnd(result, ran = true)
        }
    }

    private fun pushedNothing(outcome: Outcome<GitResult>): Boolean {
        val result = (outcome as? Outcome.Done)?.value ?: return false
        return result.stderrText().lines().any { it == EVERYTHING_UP_TO_DATE }
    }

    // Failures

    /**
     * What a remote command that exited non-zero means; see the class rules. Lines a server sends
     * start with `remote:` and are never matched.
     */
    private fun remoteFailure(result: GitResult): GitFailure {
        val lines = result.stderrText().lines().filterNot { it.startsWith(REMOTE_PREFIX) }
        fun any(test: (String) -> Boolean) = lines.any(test)
        return when {
            any { line -> CREDENTIAL_MARKS.any { it in line } } -> GitFailure.CredentialRequired
            any { it.startsWith(" ! [rejected]") || it.startsWith("hint: Updates were rejected") } -> GitFailure.NonFastForward
            any { it.startsWith("fatal: Not possible to fast-forward") } -> GitFailure.Diverged
            any { it.startsWith("error: ") && "would be overwritten by merge" in it } -> GitFailure.WouldOverwrite
            any { it.startsWith("error: Merging is not possible because you have unmerged files") || it.startsWith("fatal: You have not concluded your merge") } ->
                GitFailure.Conflict
            any { it.startsWith("fatal: no such remote") || it.startsWith("error: src refspec ") || it.startsWith("merge: ") && it.endsWith(" - not something we can merge") } ->
                GitFailure.NotFound
            any { it.startsWith("fatal: '") && it.endsWith("' does not appear to be a git repository") } -> GitFailure.NotFound
            else -> failureOf(result)
        }
    }

    // Running git

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
                    // A command builder refused its input: a name or path it cannot carry.
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
        const val ORIGIN = "origin"
        const val UPSTREAM = "@{upstream}"
        const val FETCHING = "Fetching "
        const val EVERYTHING_UP_TO_DATE = "Everything up-to-date"
        const val REMOTE_PREFIX = "remote:"
        const val NEWLINE = '\n'.code.toByte()

        /**
         * What git and OpenSSH print when a credential was needed and no prompt could ask for it:
         * askpass set to `false`, `GIT_TERMINAL_PROMPT=0`, `credential.interactive=never`, no
         * terminal for ssh.
         */
        val CREDENTIAL_MARKS = listOf(
            "could not read Username",
            "could not read Password",
            "terminal prompts disabled",
            "unable to get password",
            "Authentication failed for",
            "Permission denied (publickey",
            "Permission denied (password",
            "Permission denied (keyboard-interactive",
            "Permission denied, please try again",
        )
    }
}
