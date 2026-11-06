package io.github.thewisekd.phopencode.feature.git.logic.commit

import io.github.thewisekd.phopencode.core.git.command.GitCommand
import io.github.thewisekd.phopencode.core.git.command.GitCommands
import io.github.thewisekd.phopencode.core.git.command.GitNames
import io.github.thewisekd.phopencode.core.git.command.GitResult
import io.github.thewisekd.phopencode.core.git.command.GitRunner
import io.github.thewisekd.phopencode.core.git.command.WorktreeDiscard
import io.github.thewisekd.phopencode.core.git.parse.GitParseException
import io.github.thewisekd.phopencode.core.git.parse.GitPath
import io.github.thewisekd.phopencode.core.git.parse.StatusParser
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitError
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitMessageProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.status.DefaultStatusPresenter
import io.github.thewisekd.phopencode.feature.git.logic.status.StatusSnapshot
import io.github.thewisekd.phopencode.feature.git.logic.status.failureOf
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
 * The commit screen's logic: reads the changed files of the repository holding [directory], stages
 * and unstages them, discards a tracked file's unstaged changes after a confirmation, and commits.
 *
 * Every read resolves the work tree root (`rev-parse --show-toplevel`) and runs `status` there, as
 * the status screen does ([StatusSnapshot]); every action runs from that root too, because status
 * paths are root-relative. [CommitState.unstaged] holds the conflicted rows, then the unstaged
 * ones, then the untracked ones.
 *
 * Git work runs one piece at a time behind a lock, so two commands never race for the index lock,
 * and only one action runs at a time ([CommitState.busy]); an action asked for meanwhile is
 * ignored. An action never runs from rows captured when it was asked for: inside the lock, right
 * before running, it decides again from the newest published read, and runs nothing when that read
 * is stale or a newer one is still on its way ([CommitState.loading]). Every read has a generation
 * number; only the newest publishes its rows and ends [CommitState.loading]. After any action that ran a command, failed or not, the files are read again: a failing
 * hook may have changed them, and a failed `add` may have staged part of its paths.
 *
 * Commit rules:
 * - A blank message is refused locally ([CommitMessageProblem.Blank]) and nothing staged is
 *   [CommitError.NothingStaged]; neither runs a command.
 * - The message goes through stdin ([GitCommands.commit]).
 * - Git exits 1 when a hook refuses the commit, with the hook's output on stderr (git sends a
 *   hook's stdout there too): that is [CommitError.HookRejected]. Exit 1 with only stdout is git's
 *   own "nothing to commit" status listing: [CommitError.NothingStaged]. Unmerged files are
 *   [GitFailure.Conflict]; anything else is [CommitError.Failed].
 * - On success the message is cleared (unless it was edited meanwhile) and
 *   [CommitEvent.Committed] carries the new `HEAD`.
 *
 * Discarding only runs [GitRunner.discardWorktree], the checked restore of `core/git`, and only for
 * a row [CommitState.canDiscard] allows; it refuses anything untracked or unmerged on its own too.
 *
 * Nothing is logged: no path, message or hook output.
 *
 * @param directory The folder from the route, where git runs.
 * @param git Runs `git` on the active connection.
 */
class DefaultCommitPresenter(
    private val directory: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
) : CommitPresenter {
    private val mutableState = MutableStateFlow(CommitState(directory = directory))

    override val state: StateFlow<CommitState> = mutableState.asStateFlow()

    private val eventChannel = Channel<CommitEvent>(Channel.BUFFERED)

    override val events: Flow<CommitEvent> = eventChannel.receiveAsFlow()

    private val gitLock = Mutex()

    /** The running read; touched only from [onIntent] and the constructor, on the caller's thread. */
    private var loadJob: Job? = null

    /**
     * Guards [snapshot], [generation] and [mutableState] together: the rows shown and the read they
     * come from change in one step.
     */
    private val viewLock = Any()

    /**
     * The newest published read; null until one succeeded and after one failed. Git work reads it
     * only inside [gitLock], right before it runs (see [runAction]).
     */
    private var snapshot: StatusSnapshot? = null

    /**
     * The number of the newest read asked for. A read publishes its result, and ends
     * [CommitState.loading], only while its number is still this one; an older result is dropped.
     */
    private var generation = 0L

    init {
        load()
    }

    override fun onIntent(intent: CommitIntent) {
        when (intent) {
            is CommitIntent.EditMessage -> edit { it.copy(message = intent.text, messageProblem = null) }
            is CommitIntent.Stage -> stage(intent.change)
            is CommitIntent.Unstage -> unstage(intent.change)
            CommitIntent.StageAll -> paths(CommitOp.Stage, GitCommands::add) { read -> all(read, stageable(read)) }
            CommitIntent.UnstageAll -> paths(CommitOp.Unstage, GitCommands::unstage) { read -> all(read, read.staged) }
            is CommitIntent.RequestDiscard -> requestDiscard(intent.change)
            CommitIntent.ConfirmDiscard -> confirmDiscard()
            CommitIntent.CancelDiscard -> edit { it.copy(confirmDiscard = null) }
            CommitIntent.Commit -> commit()
            is CommitIntent.OpenDiff -> openDiff(intent.change, intent.staged)
            CommitIntent.Refresh -> load()
            CommitIntent.DismissError -> edit { it.copy(error = null) }
            CommitIntent.Close -> send(CommitEvent.Close)
        }
    }

    /** Changes the state under [viewLock]. */
    private inline fun edit(change: (CommitState) -> CommitState) {
        synchronized(viewLock) { mutableState.value = change(mutableState.value) }
    }

    // Reading

    /**
     * Reads the files as a new [generation], replacing a read still running. With rows shown the
     * rows stay until the new ones arrive; after a failed read it starts over as a fresh load. The
     * message is kept.
     *
     * [CommitState.loading] stays set until the newest read lands. A cancelled read that still
     * finishes publishes nothing, and a read that finds a newer one asked for skips itself.
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
                publish(number, readStatus(), afterAction = false)
            }
        }
    }

    private fun isNewest(number: Long): Boolean = synchronized(viewLock) { number == generation }

    /**
     * Shows [read] if [number] is still the newest generation, and ends [CommitState.loading]; drops
     * it otherwise. A pending discard confirmation whose row is gone is dropped too.
     *
     * A failed read leaves no rows to act on. After a load the rows go and the failure shows (the
     * contract has no operation for a read, so it is [CommitOp.Commit], the screen's own purpose).
     * After an action the rows stay on screen but stale: no action runs from them until a read
     * succeeds ([CommitIntent.Refresh]); the action reports the failure itself.
     */
    private fun publish(number: Long, read: Outcome<StatusSnapshot>, afterAction: Boolean) = synchronized(viewLock) {
        val newest = number == generation
        val current = mutableState.value
        when (read) {
            is Outcome.Done -> if (newest) {
                snapshot = read.value
                val unstaged = unstagedOf(read.value)
                mutableState.value = current.copy(
                    loading = false,
                    staged = read.value.staged,
                    unstaged = unstaged,
                    confirmDiscard = current.confirmDiscard?.takeIf { it in unstaged },
                )
            }
            is Outcome.Failed -> when {
                // The action changed files the old read lists: it is stale even if a newer read
                // is on its way.
                afterAction -> {
                    snapshot = null
                    mutableState.value = current.copy(loading = current.loading && !newest, confirmDiscard = null)
                }
                newest -> {
                    snapshot = null
                    mutableState.value = current.copy(
                        loading = false, staged = emptyList(), unstaged = emptyList(), confirmDiscard = null,
                        error = CommitError.Failed(CommitOp.Commit, read.failure),
                    )
                }
                else -> Unit
            }
        }
        Unit
    }

    private fun unstagedOf(read: StatusSnapshot): List<GitFileChange> = read.conflicted + read.unstaged + read.untracked

    private suspend fun readStatus(): Outcome<StatusSnapshot> = gitCall { runner ->
        val root = resolveRoot(runner)
        val result = runner.run(GitCommands.status(root))
        if (!result.succeeded) throw GitException(failureOf(result))
        StatusSnapshot.of(root, StatusParser.parse(result.stdout)) ?: throw GitException(GitFailure.Unknown)
    }

    /** The work tree root of [directory], as `rev-parse --show-toplevel` prints it. */
    private suspend fun resolveRoot(runner: GitRunner): String {
        val result = runner.run(GitCommands.revParseToplevel(directory))
        if (!result.succeeded) throw GitException(failureOf(result))
        val out = result.stdout
        if (out.isEmpty() || out.last() != NEWLINE) throw GitException(GitFailure.Unknown)
        val path = GitPath(out.copyOfRange(0, out.size - 1))
        // A root that is not UTF-8 cannot be written into a command line as the same bytes.
        if (!path.isValidUtf8) throw GitException(GitFailure.Rejected)
        val root = path.text
        if (!root.startsWith('/') || !GitNames.isValidPath(root)) throw GitException(GitFailure.Unknown)
        return root
    }

    // Starting actions

    /**
     * Marks [op] as running if a read stands behind the rows and no other action runs; [accept]
     * filters intents that cannot apply (a row not shown). Only a gate for the screen: the action
     * decides what to run later, inside [gitLock], from the newest read ([runAction]).
     */
    private inline fun begin(op: CommitOp, accept: (CommitState, StatusSnapshot) -> Boolean = { _, _ -> true }): Boolean =
        synchronized(viewLock) {
            val current = mutableState.value
            val read = snapshot ?: return false
            if (current.busy != null || !accept(current, read)) return false
            mutableState.value = current.copy(busy = op, error = null)
            true
        }

    // Stage and unstage

    /** Paths an action runs on, decided from one read. */
    private sealed interface Plan {
        /** Nothing applies any more; nothing runs. */
        data object Skip : Plan

        /** A row whose paths cannot be passed back to git. */
        data object Reject : Plan

        /** @param rejected Some rows were left out because their paths cannot be passed back. */
        class Run(val paths: List<String>, val rejected: Boolean) : Plan
    }

    private fun stage(change: GitFileChange) {
        // The same row, kind included, must still be shown: a file that became conflicted since
        // is a different row.
        val decide = { read: StatusSnapshot -> if (change in unstagedOf(read)) single(read, change) else Plan.Skip }
        paths(CommitOp.Stage, GitCommands::add, decide)
    }

    private fun unstage(change: GitFileChange) {
        val decide = { read: StatusSnapshot -> if (change in read.staged) single(read, change) else Plan.Skip }
        paths(CommitOp.Unstage, GitCommands::unstage, decide)
    }

    /**
     * What [CommitIntent.StageAll] takes: every unstaged and untracked row, but not a conflicted
     * one. Staging a conflicted file marks it resolved with its conflict markers in it, so that is
     * left to a deliberate [CommitIntent.Stage] of the row, as on the status screen.
     */
    private fun stageable(read: StatusSnapshot): List<GitFileChange> = read.unstaged + read.untracked

    private fun single(read: StatusSnapshot, change: GitFileChange): Plan {
        val paths = pathsOf(change)
        return if (read.isRepresentable(paths)) Plan.Run(paths, rejected = false) else Plan.Reject
    }

    /** Every row of [rows] whose paths can be passed back; the others are reported. */
    private fun all(read: StatusSnapshot, rows: List<GitFileChange>): Plan {
        val (fine, refused) = rows.partition { read.isRepresentable(pathsOf(it)) }
        if (fine.isEmpty()) return if (refused.isNotEmpty()) Plan.Reject else Plan.Skip
        return Plan.Run(fine.flatMap(::pathsOf).distinct(), rejected = refused.isNotEmpty())
    }

    /**
     * Stages or unstages: [decide] picks the paths from the newest read inside [gitLock], and
     * [build] runs them in pieces of a bounded command line from that read's root. The first piece
     * that fails stops the rest.
     */
    private fun paths(op: CommitOp, build: (String, List<String>) -> GitCommand, decide: (StatusSnapshot) -> Plan) {
        if (!begin(op) { _, read -> decide(read) != Plan.Skip }) return
        runAction(op) { read, _ ->
            when (val plan = decide(read)) {
                Plan.Skip -> ActionResult(reread = false, error = null)
                Plan.Reject -> ActionResult(reread = false, error = CommitError.Failed(op, GitFailure.Rejected))
                is Plan.Run -> {
                    var attempted = false
                    val ran = gitCall { runner ->
                        for (piece in DefaultStatusPresenter.pieces(plan.paths)) {
                            attempted = true
                            val result = runner.run(build(read.root, piece))
                            if (!result.succeeded) throw GitException(failureOf(result))
                        }
                    }
                    val failure = (ran as? Outcome.Failed)?.failure ?: GitFailure.Rejected.takeIf { plan.rejected }
                    ActionResult(reread = attempted, error = failure?.let { CommitError.Failed(op, it) })
                }
            }
        }
    }

    /** Every path and, for a rename, the old path too: both sides of it move together. */
    private fun pathsOf(change: GitFileChange): List<String> =
        listOfNotNull(change.path, change.originalPath?.takeIf { change.kind == GitChangeKind.Renamed })

    // Discard

    /** Whether [change] is a row of [read] that may be discarded. */
    private fun discardable(read: StatusSnapshot, change: GitFileChange): Boolean =
        CommitState.canDiscard(change) && read.isRepresentable(listOf(change.path))

    /** Asks for the confirmation of a row the screen shows as unstaged; nothing runs yet. */
    private fun requestDiscard(change: GitFileChange) = synchronized(viewLock) {
        val current = mutableState.value
        val read = snapshot ?: return
        if (current.busy != null || change !in unstagedOf(read)) return
        mutableState.value = if (discardable(read, change)) {
            current.copy(confirmDiscard = change)
        } else {
            current.copy(confirmDiscard = null, error = CommitError.Failed(CommitOp.Discard, GitFailure.Rejected))
        }
    }

    /**
     * Discards the row waiting for the confirmation. While an action or a read runs, the
     * confirmation waits rather than being dropped. Inside [gitLock] the row is checked again
     * against the newest read: gone, or changed kind (conflicted, untracked), nothing runs.
     */
    private fun confirmDiscard() {
        val change = synchronized(viewLock) { mutableState.value.confirmDiscard ?: return }
        val started = begin(CommitOp.Discard) { current, _ -> !current.loading && current.confirmDiscard == change }
        if (!started) return
        edit { it.copy(confirmDiscard = null) }
        runAction(CommitOp.Discard) { read, _ ->
            when {
                change !in unstagedOf(read) -> ActionResult(reread = false, error = null)
                !discardable(read, change) ->
                    ActionResult(reread = false, error = CommitError.Failed(CommitOp.Discard, GitFailure.Rejected))
                else -> {
                    val ran = gitCall { runner -> runner.discardWorktree(read.root, listOf(change.path)) }
                    val failure = when (ran) {
                        is Outcome.Failed -> ran.failure
                        is Outcome.Done -> when (val discard = ran.value) {
                            WorktreeDiscard.Discarded -> null
                            WorktreeDiscard.Refused -> GitFailure.Rejected
                            is WorktreeDiscard.Failed -> failureOf(discard.result)
                        }
                    }
                    // A refused or failed discard may still have run its check: read again.
                    val attempted = !(ran is Outcome.Failed && ran.failure == GitFailure.Rejected)
                    ActionResult(reread = attempted, error = failure?.let { CommitError.Failed(CommitOp.Discard, it) })
                }
            }
        }
    }

    // Commit

    /**
     * Commits. A blank message or nothing staged is answered at once for the screen; both are
     * checked again inside [gitLock] against the newest message and read, which decide.
     */
    private fun commit() {
        val started = begin(CommitOp.Commit) { current, read ->
            when {
                current.message.isBlank() -> {
                    mutableState.value = current.copy(messageProblem = CommitMessageProblem.Blank)
                    false
                }
                read.staged.isEmpty() -> {
                    mutableState.value = current.copy(error = CommitError.NothingStaged)
                    false
                }
                else -> true
            }
        }
        if (!started) return
        edit { it.copy(messageProblem = null) }
        runAction(CommitOp.Commit) { read, current ->
            val message = current.message
            when {
                message.isBlank() -> {
                    edit { it.copy(messageProblem = CommitMessageProblem.Blank) }
                    ActionResult(reread = false, error = null)
                }
                read.staged.isEmpty() -> ActionResult(reread = false, error = CommitError.NothingStaged)
                else -> runCommit(read, message)
            }
        }
    }

    private suspend fun runCommit(read: StatusSnapshot, message: String): ActionResult {
        var attempted = false
        val ran = gitCall { runner ->
            // Built first: a message git cannot take is refused before anything runs.
            val command = GitCommands.commit(read.root, message)
            attempted = true
            runner.run(command)
        }
        return when (ran) {
            is Outcome.Failed -> ActionResult(reread = attempted, error = CommitError.Failed(CommitOp.Commit, ran.failure))
            is Outcome.Done -> if (ran.value.succeeded) {
                edit { if (it.message == message) it.copy(message = "") else it }
                when (val head = gitCall { runner -> readHead(runner, read.root) }) {
                    is Outcome.Done -> ActionResult(reread = true, error = null, event = CommitEvent.Committed(head.value))
                    is Outcome.Failed -> ActionResult(reread = true, error = CommitError.Failed(CommitOp.Commit, head.failure))
                }
            } else {
                ActionResult(reread = true, error = commitError(ran.value))
            }
        }
    }

    /** The full hash of `HEAD`, right after the commit made it. */
    private suspend fun readHead(runner: GitRunner, root: String): String {
        val result = runner.run(GitCommands.log(root, "%H", maxCount = 1))
        if (!result.succeeded) throw GitException(failureOf(result))
        val hash = String(result.stdout, Charsets.US_ASCII).trimEnd('\u0000', '\n')
        if (!HASH.matches(hash)) throw GitException(GitFailure.Unknown)
        return hash
    }

    /** What a `commit` that exited non-zero means; see the class rules. */
    private fun commitError(result: GitResult): CommitError {
        val stderr = result.stderrText()
        if (result.exitCode == HOOK_REFUSED) {
            return if (stderr.isEmpty() && result.stdout.isNotEmpty()) {
                CommitError.NothingStaged
            } else {
                CommitError.HookRejected(cut(stderr.trimEnd()))
            }
        }
        if (stderr.lines().any { it.startsWith(UNMERGED_ERROR) }) {
            return CommitError.Failed(CommitOp.Commit, GitFailure.Conflict)
        }
        return CommitError.Failed(CommitOp.Commit, failureOf(result))
    }

    /** [text] cut to [MAX_HOOK_OUTPUT_CHARS], never through a surrogate pair, with `…` when cut. */
    private fun cut(text: String): String {
        if (text.length <= MAX_HOOK_OUTPUT_CHARS) return text
        var end = MAX_HOOK_OUTPUT_CHARS
        if (text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end) + "…"
    }

    // Running actions

    /**
     * What an action left: whether the files must be read again, the error to show (null keeps the
     * screen clean), and an event to send.
     */
    private class ActionResult(val reread: Boolean, val error: CommitError?, val event: CommitEvent? = null)

    /**
     * Runs [action] inside [gitLock] with the newest published read and the state of that moment,
     * so that what it runs is decided right before running. With no read behind the rows (stale or
     * failed) or a read still on its way ([CommitState.loading]), nothing runs. After a command ran
     * the files are read again as a new generation; then [op] ends.
     */
    private fun runAction(op: CommitOp, action: suspend (StatusSnapshot, CommitState) -> ActionResult) {
        scope.launch {
            yield()
            gitLock.withLock {
                try {
                    val (read, current) = synchronized(viewLock) { snapshot to mutableState.value }
                    val result = if (read == null || current.loading) ActionResult(reread = false, error = null) else action(read, current)
                    val reread = if (result.reread) {
                        val number = synchronized(viewLock) { ++generation }
                        readStatus().also { publish(number, it, afterAction = true) }
                    } else {
                        null
                    }
                    val error = result.error ?: (reread as? Outcome.Failed)?.failure?.let { CommitError.Failed(op, it) }
                    edit { it.copy(busy = null, error = error ?: it.error) }
                    result.event?.let(::send)
                } catch (cancelled: CancellationException) {
                    edit { if (it.busy == op) it.copy(busy = null) else it }
                    throw cancelled
                }
            }
        }
    }

    // Navigation

    private fun openDiff(change: GitFileChange, staged: Boolean) {
        val open = synchronized(viewLock) {
            // No read stands behind the row: its path cannot be checked, so the tap is ignored.
            val read = snapshot ?: return
            val fine = read.isRepresentable(listOf(change.path))
            if (!fine) {
                mutableState.value = mutableState.value.copy(error = CommitError.Failed(CommitOp.Commit, GitFailure.Rejected))
            }
            fine
        }
        if (open) send(CommitEvent.OpenDiff(change.path, staged))
    }

    private fun send(event: CommitEvent) {
        eventChannel.trySend(event)
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
                    // A command builder refused its input: a path or message it cannot carry.
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

    internal companion object {
        /** Characters of hook output kept for the screen. */
        const val MAX_HOOK_OUTPUT_CHARS: Int = 4_000

        /** Path bytes per command line; a longer list runs as several commands. */
        const val MAX_PATH_BYTES_PER_COMMAND: Int = DefaultStatusPresenter.MAX_PATH_BYTES_PER_COMMAND

        /** What `git commit` exits with when a hook refuses, and for its "nothing to commit". */
        private const val HOOK_REFUSED = 1

        private const val UNMERGED_ERROR = "error: Committing is not possible because you have unmerged files."

        private const val NEWLINE = '\n'.code.toByte()

        private val HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
    }
}
