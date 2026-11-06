package io.github.thewisekd.phopencode.feature.git.logic.contract

import kotlin.time.Instant

/** The number of hash characters [CommitSummary.shortHash] keeps. */
const val SHORT_HASH_LENGTH: Int = 7

/**
 * One commit of the history.
 *
 * @property hash The full hash.
 * @property parents Full hashes; more than one for a merge, none for a root commit.
 * @property subject The first line of the message.
 * @property refs Branch and tag names pointing at this commit, as `%D` prints them.
 */
data class CommitSummary(
    val hash: String,
    val parents: List<String> = emptyList(),
    val subject: String,
    val authorName: String,
    val authorEmail: String,
    val authoredAt: Instant,
    val refs: List<String> = emptyList(),
) {
    val shortHash: String
        get() = hash.take(SHORT_HASH_LENGTH)

    val isMerge: Boolean
        get() = parents.size > 1

    override fun toString(): String =
        "CommitSummary(hash=$hash, parents=${parents.size}, subject=***, author=***, authoredAt=$authoredAt, refs=${refs.size})"
}

/**
 * The history, `GitRoute.Log` (P9.8 logic, P9.9 screen): commits newest first, a page at a time,
 * of the current branch or of the branch [branch] filters to.
 *
 * Where it comes from: `git log -z` through [GitConnection], `DefaultLogPresenter.DEFAULT_PAGE_SIZE`
 * commits a page; [branches] from the branch list.
 *
 * @property directory The folder from the route; never changes.
 * @property branch The filter: a local or remote branch name; null is `HEAD`.
 * @property branches The names the filter offers.
 * @property loading The first page of [branch] is loading; [commits] is empty meanwhile.
 * @property loadingMore The next page is loading, below [commits].
 * @property endReached The last page is in [commits].
 * @property error The first page could not be read.
 * @property moreError The next page could not be read; [commits] stays.
 */
data class LogState(
    val directory: String,
    val branch: String? = null,
    val branches: List<String> = emptyList(),
    val commits: List<CommitSummary> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: GitFailure? = null,
    val moreError: GitFailure? = null,
) {
    /** Whether [LogIntent.LoadMore] would load a page now. */
    val canLoadMore: Boolean
        get() = !loading && !loadingMore && !endReached && error == null

    /** Loaded, without commits and without an error: a branch without commits. */
    val isEmpty: Boolean
        get() = !loading && error == null && commits.isEmpty()

    override fun toString(): String =
        "LogState(directory=***, branch=${branch?.let { "***" }}, branches=${branches.size}, commits=${commits.size}, " +
            "loading=$loading, loadingMore=$loadingMore, endReached=$endReached, error=$error, moreError=$moreError)"
}

sealed interface LogIntent {
    /** The list's end came into view; nothing unless [LogState.canLoadMore]. Retries [LogState.moreError]. */
    data object LoadMore : LogIntent

    /** Starts over at the first page of [branch]; null is `HEAD`. */
    data class FilterBranch(val branch: String?) : LogIntent {
        override fun toString(): String = "FilterBranch(${branch?.let { "***" }})"
    }

    /** Starts over at the first page. */
    data object Refresh : LogIntent

    /** Reads again after [LogState.error]. */
    data object Retry : LogIntent

    /** A row tapped: sends [LogEvent.OpenCommit]. */
    data class OpenCommit(val hash: String) : LogIntent

    data object Close : LogIntent
}

sealed interface LogEvent {
    data class OpenCommit(val hash: String) : LogEvent

    data object Close : LogEvent
}

interface LogPresenter : Presenter<LogState, LogIntent, LogEvent>

/**
 * A file a commit changed.
 *
 * @property oldPath The path before a rename or copy.
 * @property additions Null for a binary file, as `--numstat` prints `-`.
 * @property deletions As [additions].
 */
data class CommitFileChange(
    val path: String,
    val kind: GitChangeKind,
    val oldPath: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
) {
    val isBinary: Boolean
        get() = additions == null && deletions == null

    override fun toString(): String =
        "CommitFileChange(path=***, kind=$kind, oldPath=${oldPath?.let { "***" }}, additions=$additions, deletions=$deletions)"
}

/**
 * Everything about one commit.
 *
 * @property body The message after the subject, without the blank line between them.
 * @property files Against the first parent.
 */
data class CommitDetail(
    val summary: CommitSummary,
    val body: String,
    val committerName: String,
    val committerEmail: String,
    val committedAt: Instant,
    val files: List<CommitFileChange>,
) {
    override fun toString(): String =
        "CommitDetail(summary=$summary, body=***, committer=***, committedAt=$committedAt, files=${files.size})"
}

/**
 * One commit, `GitRoute.CommitDetail` (P9.8 logic, P9.9 screen): its message, author, date and the
 * files it changed; a file opens its diff in the commit.
 *
 * @property commit The hash from the route; never changes.
 * @property detail Null until loaded.
 * @property error The commit could not be read; [GitFailure.NotFound] for an unknown hash.
 */
data class CommitDetailState(
    val commit: String,
    val loading: Boolean = true,
    val detail: CommitDetail? = null,
    val error: GitFailure? = null,
)

sealed interface CommitDetailIntent {
    /** A file tapped: sends [CommitDetailEvent.OpenFileDiff]. */
    data class OpenFile(val path: String) : CommitDetailIntent {
        override fun toString(): String = "OpenFile(***)"
    }

    data object Retry : CommitDetailIntent

    data object Close : CommitDetailIntent
}

sealed interface CommitDetailEvent {
    data class OpenFileDiff(val path: String) : CommitDetailEvent {
        override fun toString(): String = "OpenFileDiff(***)"
    }

    data object Close : CommitDetailEvent
}

interface CommitDetailPresenter : Presenter<CommitDetailState, CommitDetailIntent, CommitDetailEvent>
