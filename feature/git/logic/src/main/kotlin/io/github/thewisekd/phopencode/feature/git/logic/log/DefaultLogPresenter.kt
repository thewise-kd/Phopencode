package io.github.thewisekd.phopencode.feature.git.logic.log

import io.github.thewisekd.phopencode.core.git.command.GitNames
import io.github.thewisekd.phopencode.core.git.parse.BranchParser
import io.github.thewisekd.phopencode.core.git.parse.GitBranch
import io.github.thewisekd.phopencode.core.git.parse.GitCommit
import io.github.thewisekd.phopencode.core.git.parse.LogParser
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitSummary
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
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
 * The history, a page at a time (P9.8).
 *
 * Each page asks `log` for one commit more than [pageSize]: when it comes, more exist, and it is
 * dropped. The first page's newest commit is the tip; later pages read from that hash with `--skip`,
 * so commits added to the branch meanwhile shift nothing and no page repeats or misses a commit. A
 * commit already shown is never added twice.
 *
 * The branch filter offers every local and remote branch but symbolic ones (`origin/HEAD`), by
 * short name (`main`, `origin/main`); where two branches share a short name, such as a local
 * branch called `origin/main` next to the remote one, both are offered by full ref name
 * (`refs/heads/origin/main`, `refs/remotes/origin/main`). A listed name is passed to git as its
 * full ref name, so a tag, file or other branch of the same name cannot shadow it. The filter is
 * kept as that full ref name: when a later branch list labels it differently, [LogState.branch]
 * follows; when the ref is gone, the filter is cleared and the history of `HEAD` shown, never
 * another ref's. A name not in the list is passed to git by name. Branch names also fill [CommitSummary.refs] (tags are not read). A failed branch list
 * leaves the history usable and the filter empty.
 *
 * A filter change, [LogIntent.Refresh] and [LogIntent.Retry] drop whatever page is loading: its
 * result is never shown. Nothing is logged.
 *
 * @param directory The folder from the route, where git runs.
 * @param git Runs `git` on the active connection.
 * @param pageSize Commits per page, at least 1.
 */
class DefaultLogPresenter(
    private val directory: String,
    private val git: GitConnection,
    private val scope: CoroutineScope,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : LogPresenter {
    init {
        require(pageSize > 0) { "Page size must be positive" }
    }

    private val mutableState = MutableStateFlow(LogState(directory = directory))

    override val state: StateFlow<LogState> = mutableState.asStateFlow()

    private val eventChannel = Channel<LogEvent>(Channel.BUFFERED)

    override val events: Flow<LogEvent> = eventChannel.receiveAsFlow()

    private val lock = Any()

    /** Bumped by every new first page; a page of an older generation is dropped. */
    private var generation = 0
    private var pageJob: Job? = null
    private var branchJob: Job? = null

    /** Bumped by every branch read; a list of an older read is dropped. */
    private var branchGeneration = 0

    /** The commit later pages read from; null until a first page came. */
    private var tip: String? = null
    private var seen: MutableSet<String> = HashSet()

    /** Offered name to full ref name. */
    private var refNames: Map<String, String> = emptyMap()

    /** The filter; null is `HEAD`. */
    private var selection: Selection? = null

    /** Hash to the branch names pointing at it. */
    private var refsByHash: Map<String, List<String>> = emptyMap()

    init {
        startFirstPage(null)
        startBranches()
    }

    override fun onIntent(intent: LogIntent) {
        when (intent) {
            LogIntent.LoadMore -> loadMore()
            is LogIntent.FilterBranch -> synchronized(lock) {
                val target = intent.branch?.let { label -> Selection(label, refNames[label]) }
                if (!sameTarget(target, selection) || mutableState.value.error != null) startFirstPage(target)
            }
            LogIntent.Refresh -> synchronized(lock) {
                startFirstPage(selection)
                startBranches()
            }
            LogIntent.Retry -> synchronized(lock) {
                if (mutableState.value.error != null) {
                    startFirstPage(selection)
                    if (refNames.isEmpty()) startBranches()
                }
            }
            is LogIntent.OpenCommit -> eventChannel.trySend(LogEvent.OpenCommit(intent.hash))
            LogIntent.Close -> eventChannel.trySend(LogEvent.Close)
        }
    }

    /** Starts over at the first page of [target]; the caller holds [lock] or runs in the constructor. */
    private fun startFirstPage(target: Selection?) {
        val token = ++generation
        selection = target
        pageJob?.cancel()
        tip = null
        seen = HashSet()
        mutableState.update {
            it.copy(
                branch = target?.label, commits = emptyList(), loading = true, loadingMore = false,
                endReached = false, error = null, moreError = null,
            )
        }
        val revision = revisionOf(target)
        pageJob = scope.launch {
            yield()
            val outcome = try {
                Result.success(readPage(revision ?: throw GitException(GitFailure.Rejected), skip = 0, head = target == null))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            synchronized(lock) {
                if (token != generation) return@launch
                outcome.fold(
                    onSuccess = { page ->
                        tip = page.commits.firstOrNull()?.hash
                        val commits = fresh(page.commits)
                        mutableState.update { it.copy(commits = commits, loading = false, endReached = !page.more) }
                    },
                    onFailure = { failure ->
                        mutableState.update { it.copy(loading = false, error = failureOf(failure as Exception)) }
                    },
                )
            }
        }
    }

    private fun loadMore() = synchronized(lock) {
        val current = mutableState.value
        val from = tip
        if (!current.canLoadMore || from == null) return@synchronized
        val token = generation
        val skip = current.commits.size
        mutableState.update { it.copy(loadingMore = true, moreError = null) }
        pageJob = scope.launch {
            yield()
            val outcome = try {
                Result.success(readPage(Revision(from), skip = skip, head = false))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            synchronized(lock) {
                if (token != generation) return@launch
                outcome.fold(
                    onSuccess = { page ->
                        val added = fresh(page.commits)
                        mutableState.update { it.copy(commits = it.commits + added, loadingMore = false, endReached = !page.more) }
                    },
                    onFailure = { failure ->
                        mutableState.update { it.copy(loadingMore = false, moreError = failureOf(failure as Exception)) }
                    },
                )
            }
        }
    }

    /**
     * Reads the branch list for the filter and the commits' refs; a failure keeps what was there.
     * Only the newest read may publish: a list read by an older one, even one that finished after
     * being cancelled, is dropped. The caller holds [lock] or runs in the constructor.
     */
    private fun startBranches() {
        val token = ++branchGeneration
        branchJob?.cancel()
        branchJob = scope.launch {
            yield()
            val branches = try {
                git.session { session -> parseBranches(session.read(checkedInput { LogCommands.branches(directory) })) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return@launch
            }
            val listed = branches.filter { !it.isSymbolic }.distinctBy { it.refName }
            val labels = branchLabels(listed)
            val names = LinkedHashMap<String, String>()
            val byHash = HashMap<String, MutableList<String>>()
            for (branch in listed) {
                val label = labels.getValue(branch.refName)
                names[label] = branch.refName
                byHash.getOrPut(branch.hash) { ArrayList() } += label
            }
            synchronized(lock) {
                if (token != branchGeneration) return@launch
                refNames = names
                refsByHash = byHash
                // The filter follows its ref: relabelled when the label changed, cleared when gone.
                val selected = selection
                val selectedRef = selected?.ref
                val relabelled = selectedRef?.let(labels::get)
                if (selectedRef != null && relabelled != null && relabelled != selected.label) {
                    selection = Selection(relabelled, selectedRef)
                }
                mutableState.update { state ->
                    state.copy(
                        branch = selection?.label,
                        branches = names.keys.toList(),
                        commits = state.commits.map { it.copy(refs = refsOf(it.hash)) },
                    )
                }
                if (selectedRef != null && relabelled == null) startFirstPage(null)
            }
        }
    }

    /** A revision to read [target] from; a null result means the name is refused. */
    private fun revisionOf(target: Selection?): Revision? = when {
        target == null -> Revision(null)
        target.ref != null -> Revision(target.ref)
        !GitNames.isValidBranchName(target.label) -> null
        else -> Revision(target.label)
    }

    /**
     * A filter: [label] as [LogState.branch] shows it, and the full ref name it was listed with;
     * [ref] is null for a name not in the branch list, which git resolves by name.
     */
    private data class Selection(val label: String, val ref: String?)

    private fun sameTarget(a: Selection?, b: Selection?): Boolean = when {
        a == null || b == null -> a == b
        a.ref != null || b.ref != null -> a.ref == b.ref
        else -> a.label == b.label
    }

    private class Revision(val value: String?)

    private class Page(val commits: List<CommitSummary>, val more: Boolean)

    private suspend fun readPage(revision: Revision, skip: Int, head: Boolean): Page {
        val count = if (pageSize == Int.MAX_VALUE) pageSize else pageSize + 1
        val command = checkedInput { LogCommands.page(directory, revision.value, skip, count) }
        val parsed = git.session { session ->
            val result = session.run(command)
            when {
                result.succeeded -> LogParser.parse(result.stdout)
                head && skip == 0 && isUnbornHead(result) -> emptyList()
                else -> throw GitException(failureOf(result))
            }
        }
        return Page(parsed.take(pageSize).map { it.toSummary() }, more = parsed.size > pageSize)
    }

    /**
     * The commits of [page] not shown yet, marked as shown, with the refs of the branch list read
     * last. The caller holds [lock].
     */
    private fun fresh(page: List<CommitSummary>): List<CommitSummary> =
        page.filter { seen.add(it.hash) }.map { it.copy(refs = refsOf(it.hash)) }

    private fun refsOf(hash: String): List<String> = refsByHash[hash].orEmpty()

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
    }
}

/**
 * The name each branch is offered by, keyed by full ref name: its short name, or its full ref name
 * where that short name is not unique. Full ref names are unique, so the result is too.
 */
internal fun branchLabels(branches: List<GitBranch>): Map<String, String> {
    val labels = branches.associate { it.refName to it.name }.toMutableMap()
    while (true) {
        val taken = labels.values.groupingBy { it }.eachCount()
        val clashing = labels.filter { (refName, label) -> label != refName && taken.getValue(label) > 1 }.keys
        if (clashing.isEmpty()) return labels
        clashing.forEach { labels[it] = it }
    }
}

/** Branch list lines, without the `(HEAD detached at …)` entry `branch --list` adds when detached. */
internal fun parseBranches(stdout: ByteArray): List<GitBranch> {
    val kept = java.io.ByteArrayOutputStream(stdout.size)
    var start = 0
    while (start < stdout.size) {
        var end = start
        while (end < stdout.size && stdout[end] != '\n'.code.toByte()) end++
        val lineEnd = if (end < stdout.size) end + 1 else end
        if (stdout[start] != '('.code.toByte()) kept.write(stdout, start, lineEnd - start)
        start = lineEnd
    }
    return BranchParser.parse(kept.toByteArray())
}

internal fun GitCommit.toSummary(refs: List<String> = emptyList()): CommitSummary = CommitSummary(
    hash = hash,
    parents = parents,
    subject = subject,
    authorName = authorName,
    authorEmail = authorEmail,
    authoredAt = Instant.fromEpochSeconds(authorTime),
    refs = refs,
)
