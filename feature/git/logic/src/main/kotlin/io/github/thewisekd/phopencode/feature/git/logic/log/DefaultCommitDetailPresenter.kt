package io.github.thewisekd.phopencode.feature.git.logic.log

import io.github.thewisekd.phopencode.core.git.parse.LogParser
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetail
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * One commit (P9.8): its header from `log -1`, its files from `show --numstat -z` and
 * `show --name-status -z`, both against the first parent; a root commit lists every file as added.
 *
 * [commit] must be a full lowercase hash (40 or 64 hex digits, as the history sends); anything else
 * is [GitFailure.Rejected] before a command runs. Git reads it as `<hash>^{commit}`, so the hash of
 * a tree or blob is [GitFailure.NotFound], as is an unknown one; an annotated tag peels to its
 * commit, whose hash then differs from [commit], which is [GitFailure.NotFound] too.
 *
 * Known limit: for a merge, `show --name-status` prints only the combined diff, so the files come
 * from `--numstat` alone (which git computes against the first parent): renames are
 * [io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind.Renamed], every other
 * file Modified, even one the merge added or deleted.
 *
 * @param directory The folder from the route, where git runs.
 * @param commit The hash from the route; checked before it reaches a command.
 * @param git Runs `git` on the active connection.
 */
class DefaultCommitDetailPresenter(
    private val directory: String,
    private val commit: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
) : CommitDetailPresenter {
    private val mutableState = MutableStateFlow(CommitDetailState(commit = commit))

    override val state: StateFlow<CommitDetailState> = mutableState.asStateFlow()

    private val eventChannel = Channel<CommitDetailEvent>(Channel.BUFFERED)

    override val events: Flow<CommitDetailEvent> = eventChannel.receiveAsFlow()

    private val lock = Any()
    private var job: Job? = null

    init {
        start()
    }

    override fun onIntent(intent: CommitDetailIntent) {
        when (intent) {
            is CommitDetailIntent.OpenFile -> {
                val files = mutableState.value.detail?.files.orEmpty()
                if (files.any { it.path == intent.path }) eventChannel.trySend(CommitDetailEvent.OpenFileDiff(intent.path))
            }
            CommitDetailIntent.Retry -> synchronized(lock) { if (mutableState.value.error != null) start() }
            CommitDetailIntent.Close -> eventChannel.trySend(CommitDetailEvent.Close)
        }
    }

    /** The caller holds [lock] or runs in the constructor. */
    private fun start() {
        job?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            yield()
            val next = try {
                CommitDetailState(commit = commit, loading = false, detail = load())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                CommitDetailState(commit = commit, loading = false, error = failureOf(failure))
            }
            mutableState.value = next
        }
    }

    private suspend fun load(): CommitDetail {
        if (!isFullHash(commit)) throw GitException(GitFailure.Rejected)
        val header = checkedInput { LogCommands.commit(directory, commit) }
        val numStat = checkedInput { LogCommands.numStat(directory, commit) }
        val nameStatus = checkedInput { LogCommands.nameStatus(directory, commit) }
        return git.session { session ->
            val commits = LogParser.parse(session.read(header))
            val parsed = commits.singleOrNull() ?: throw GitException(GitFailure.Unknown)
            // An annotated tag peels to the commit it names: the object itself is not a commit.
            if (parsed.hash != commit) throw GitException(GitFailure.NotFound)
            val counts = CommitFiles.parseNumStat(session.read(numStat))
            val files = if (parsed.isMerge) {
                CommitFiles.fromNumStat(counts)
            } else {
                CommitFiles.join(counts, CommitFiles.parseNameStatus(session.read(nameStatus)))
            }
            CommitDetail(
                summary = parsed.toSummary(),
                body = parsed.body,
                committerName = parsed.committerName,
                committerEmail = parsed.committerEmail,
                committedAt = Instant.fromEpochSeconds(parsed.commitTime),
                files = files,
            )
        }
    }
}
