package io.github.thewisekd.phopencode.feature.git.logic.status

import io.github.thewisekd.phopencode.core.git.command.GitCommand
import io.github.thewisekd.phopencode.core.git.command.GitCommands
import io.github.thewisekd.phopencode.core.git.command.GitNames
import io.github.thewisekd.phopencode.core.git.command.GitRunner
import io.github.thewisekd.phopencode.core.git.parse.GitParseException
import io.github.thewisekd.phopencode.core.git.parse.GitPath
import io.github.thewisekd.phopencode.core.git.parse.StatusParser
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
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
 * The status screen's logic: reads `git status` of the repository holding [directory], groups the
 * files, and stages and unstages rows.
 *
 * Every read first resolves the work tree root (`rev-parse --show-toplevel`), then runs `status`
 * there; stage and unstage run from that root too, because status paths are root-relative.
 *
 * Git work runs one piece at a time behind a lock, so two commands never race for the index lock.
 * A stage or unstage marks its rows [StatusState.pending], runs, then reads the status again. A
 * [StatusIntent.Refresh] replaces a read that is still running. Nothing is logged.
 *
 * @param directory The folder from the route, where git runs.
 * @param git Runs `git` on the active connection.
 */
class DefaultStatusPresenter(
    private val directory: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
) : StatusPresenter {
    private val mutableState = MutableStateFlow(StatusState(directory = directory))

    override val state: StateFlow<StatusState> = mutableState.asStateFlow()

    private val eventChannel = Channel<StatusEvent>(Channel.BUFFERED)

    override val events: Flow<StatusEvent> = eventChannel.receiveAsFlow()

    private val gitLock = Mutex()

    /** The running read; touched only from [onIntent] and the constructor, on the caller's thread. */
    private var loadJob: Job? = null

    /**
     * Guards [snapshot] together with [mutableState]: the rows shown and the snapshot they come from
     * change in one step, and an intent reads both in one step, so it never checks a row against
     * another read's paths or root.
     */
    private val viewLock = Any()

    /** The read the rows shown come from; null until one succeeded and after one failed. */
    private var snapshot: StatusSnapshot? = null

    init {
        load()
    }

    override fun onIntent(intent: StatusIntent) {
        when (intent) {
            StatusIntent.Refresh, StatusIntent.Retry -> load()
            is StatusIntent.Stage -> stage(intent.change)
            is StatusIntent.Unstage -> unstage(intent.change)
            StatusIntent.StageAll -> plan { read, pending -> all(read, read.unstaged + read.untracked, pending, GitCommands::add) }
            StatusIntent.UnstageAll -> plan { read, pending -> all(read, read.staged, pending, GitCommands::unstage) }
            is StatusIntent.OpenDiff -> openDiff(intent.change, intent.staged)
            StatusIntent.OpenCommit -> send(StatusEvent.OpenCommit)
            StatusIntent.OpenLog -> send(StatusEvent.OpenLog)
            StatusIntent.OpenBranches -> send(StatusEvent.OpenBranches)
            StatusIntent.OpenRemote -> send(StatusEvent.OpenRemote)
            StatusIntent.DismissError -> edit { it.copy(actionError = null) }
            StatusIntent.Close -> send(StatusEvent.Close)
        }
    }

    /** Changes the state under [viewLock]. */
    private inline fun edit(change: (StatusState) -> StatusState) {
        synchronized(viewLock) { mutableState.value = change(mutableState.value) }
    }

    // Reading

    /**
     * Reads the status, replacing a read still running. With rows shown it is a refresh that keeps
     * them; after an error it starts over as a fresh load.
     */
    private fun load() {
        loadJob?.cancel()
        edit { current ->
            when {
                current.error != null -> current.copy(loading = true, refreshing = false, error = null)
                current.loading -> current
                else -> current.copy(refreshing = true)
            }
        }
        loadJob = scope.launch {
            yield()
            gitLock.withLock {
                when (val read = readStatus()) {
                    is Outcome.Done -> show(read.value)
                    is Outcome.Failed -> synchronized(viewLock) {
                        snapshot = null
                        mutableState.value = mutableState.value.copy(
                            loading = false, refreshing = false, branch = null, staged = emptyList(),
                            unstaged = emptyList(), untracked = emptyList(), conflicted = emptyList(),
                            error = read.failure,
                        )
                    }
                }
            }
        }
    }

    /** Shows a successful read; a refresh another intent started stays marked until it ends. */
    private fun show(read: StatusSnapshot, endsRefresh: Boolean = true) = synchronized(viewLock) {
        snapshot = read
        val current = mutableState.value
        mutableState.value = current.copy(
            loading = false, refreshing = current.refreshing && !endsRefresh, branch = read.branch,
            staged = read.staged, unstaged = read.unstaged, untracked = read.untracked,
            conflicted = read.conflicted, error = null,
        )
    }

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

    // Stage and unstage

    /** What an intent decided against one snapshot. */
    private sealed interface Plan {
        /** A row whose paths cannot be passed back to git. */
        data object Reject : Plan

        /**
         * @param rows The row paths to mark pending.
         * @param rejected Some rows were left out because their paths cannot be passed back.
         */
        class Run(
            val rows: Set<String>,
            val paths: List<String>,
            val rejected: Boolean,
            val build: (String, List<String>) -> GitCommand,
        ) : Plan
    }

    private fun stage(change: GitFileChange) = plan { read, pending ->
        val inGroup = change in read.unstaged || change in read.untracked || change in read.conflicted
        if (inGroup && change.path !in pending) single(read, change, GitCommands::add) else null
    }

    private fun unstage(change: GitFileChange) = plan { read, pending ->
        if (change in read.staged && change.path !in pending) single(read, change, GitCommands::unstage) else null
    }

    private fun single(read: StatusSnapshot, change: GitFileChange, build: (String, List<String>) -> GitCommand): Plan {
        val paths = pathsOf(change)
        return if (read.isRepresentable(paths)) Plan.Run(setOf(change.path), paths, rejected = false, build) else Plan.Reject
    }

    /** Every row of [rows] not pending whose paths can be passed back; the others are reported. */
    private fun all(
        read: StatusSnapshot,
        rows: List<GitFileChange>,
        pending: Set<String>,
        build: (String, List<String>) -> GitCommand,
    ): Plan? {
        val waiting = rows.filter { it.path !in pending }
        val (fine, refused) = waiting.partition { read.isRepresentable(pathsOf(it)) }
        if (fine.isEmpty()) return if (refused.isNotEmpty()) Plan.Reject else null
        val paths = fine.flatMap(::pathsOf).distinct()
        return Plan.Run(fine.mapTo(HashSet()) { it.path }, paths, rejected = refused.isNotEmpty(), build)
    }

    /**
     * Decides an action from the snapshot whose rows are shown and the pending rows, read and marked
     * in one step under [viewLock], then runs it with that same snapshot's root. Nothing happens
     * while the status loads or shows an error; null from [decide] ignores the intent.
     */
    private fun plan(decide: (StatusSnapshot, Set<String>) -> Plan?) {
        val (read, run) = synchronized(viewLock) {
            val current = mutableState.value
            val read = snapshot
            if (read == null || current.loading || current.error != null) return
            when (val decided = decide(read, current.pending)) {
                null -> return
                Plan.Reject -> {
                    mutableState.value = current.copy(actionError = GitFailure.Rejected)
                    return
                }
                is Plan.Run -> {
                    mutableState.value = current.copy(pending = current.pending + decided.rows, actionError = null)
                    read to decided
                }
            }
        }
        act(read, run)
    }

    /**
     * Runs the plan's command for its paths in pieces of a bounded command line, then reads the
     * status again once any command ran, failed or not. A failure is [StatusState.actionError]; the first piece that
     * fails stops the rest. Rows left out ([Plan.Run.rejected]) report [GitFailure.Rejected] when
     * nothing else failed.
     */
    private fun act(read: StatusSnapshot, run: Plan.Run) {
        scope.launch {
            yield()
            gitLock.withLock {
                var attempted = false
                val ran = gitCall { runner ->
                    for (piece in pieces(run.paths)) {
                        attempted = true
                        val result = runner.run(run.build(read.root, piece))
                        if (!result.succeeded) throw GitException(failureOf(result))
                    }
                }
                // A command that failed may still have staged part of its paths: once anything
                // ran, the rows come from a fresh read, and the failure stays in actionError.
                val reread = if (attempted) readStatus() else null
                if (reread is Outcome.Done) show(reread.value, endsRefresh = false)
                val failure = (ran as? Outcome.Failed)?.failure
                    ?: (reread as? Outcome.Failed)?.failure
                    ?: GitFailure.Rejected.takeIf { run.rejected }
                edit { it.copy(pending = it.pending - run.rows, actionError = failure ?: it.actionError) }
            }
        }
    }

    /** Every path and, for a rename, the old path too: both sides of it move together. */
    private fun pathsOf(change: GitFileChange): List<String> =
        listOfNotNull(change.path, change.originalPath?.takeIf { change.kind == GitChangeKind.Renamed })

    // Navigation

    private fun openDiff(change: GitFileChange, staged: Boolean) {
        val open = synchronized(viewLock) {
            // No read stands behind the row (still loading, or the last read failed): its path
            // cannot be checked, so the tap is ignored.
            val read = snapshot ?: return
            val fine = read.isRepresentable(listOf(change.path))
            if (!fine) mutableState.value = mutableState.value.copy(actionError = GitFailure.Rejected)
            fine
        }
        if (open) send(StatusEvent.OpenDiff(change.path, staged))
    }

    private fun send(event: StatusEvent) {
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
                    // A command builder refused its input: a relative folder, a path it cannot carry.
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
        /** Path bytes per command line; a longer list runs as several commands. */
        const val MAX_PATH_BYTES_PER_COMMAND: Int = 32 * 1024

        private const val NEWLINE = '\n'.code.toByte()

        /** [paths] in order, in runs of at most [MAX_PATH_BYTES_PER_COMMAND] bytes; a longer path runs alone. */
        fun pieces(paths: List<String>): List<List<String>> {
            val pieces = mutableListOf<List<String>>()
            var piece = mutableListOf<String>()
            var size = 0
            for (path in paths) {
                // Quoting and the separating space add a few bytes per path.
                val bytes = path.toByteArray(Charsets.UTF_8).size + 3
                if (piece.isNotEmpty() && size + bytes > MAX_PATH_BYTES_PER_COMMAND) {
                    pieces += piece
                    piece = mutableListOf()
                    size = 0
                }
                piece += path
                size += bytes
            }
            if (piece.isNotEmpty()) pieces += piece
            return pieces
        }
    }
}
