package io.github.thewisekd.phopencode.feature.git.logic.contract

import io.github.thewisekd.phopencode.core.navigation.GitRoute

/**
 * Which diff of one file a diff screen shows. `GitRoute.FileDiff` and `GitRoute.CommitFileDiff`
 * both open the diff screen; [of] turns either into a target.
 */
sealed interface DiffTarget {
    /** Relative to the repository. */
    val path: String

    /** The working tree against the index: `git diff -- <path>`; an untracked file shows whole. */
    data class Working(override val path: String) : DiffTarget {
        override fun toString(): String = "Working(path=***)"
    }

    /** The index against `HEAD`: `git diff --cached -- <path>`. */
    data class Staged(override val path: String) : DiffTarget {
        override fun toString(): String = "Staged(path=***)"
    }

    /** What [commit] changed in the file, against its first parent. */
    data class InCommit(val commit: String, override val path: String) : DiffTarget {
        override fun toString(): String = "InCommit(commit=$commit, path=***)"
    }

    companion object {
        fun of(route: GitRoute.FileDiff): DiffTarget = if (route.staged) Staged(route.path) else Working(route.path)

        fun of(route: GitRoute.CommitFileDiff): DiffTarget = InCommit(route.commit, route.path)
    }
}

/**
 * The diff of one file, `GitRoute.FileDiff` and `GitRoute.CommitFileDiff` (P9.6 logic, P9.7
 * screen): line colours, horizontal scrolling, addition and deletion counts.
 *
 * Where it comes from: unified diff output through [GitConnection], read up to the presenter's
 * byte limit; past it the content is [DiffContent.TooLarge] and no line is kept.
 *
 * @property target From the route; never changes.
 * @property diff Null until loaded.
 * @property error The diff could not be read.
 */
data class DiffState(
    val target: DiffTarget,
    val loading: Boolean = true,
    val diff: GitFileDiff? = null,
    val error: GitFailure? = null,
)

/**
 * @property path Relative to the repository; after a rename, the new path.
 * @property oldPath The path before a rename or copy.
 * @property additions Added lines; for [DiffContent.TooLarge] as git counted them, 0 when unknown.
 * @property deletions Deleted lines, as [additions].
 */
data class GitFileDiff(
    val path: String,
    val oldPath: String? = null,
    val kind: GitChangeKind = GitChangeKind.Modified,
    val content: DiffContent,
    val additions: Int,
    val deletions: Int,
) {
    val isBinary: Boolean
        get() = content == DiffContent.Binary

    override fun toString(): String =
        "GitFileDiff(path=***, oldPath=${oldPath?.let { "***" }}, kind=$kind, content=$content, " +
            "additions=$additions, deletions=$deletions)"
}

sealed interface DiffContent {
    /** At least one hunk. */
    data class Text(val hunks: List<DiffHunk>) : DiffContent {
        override fun toString(): String = "Text(hunks=${hunks.size})"
    }

    /** git reports the file as binary; there are no lines to show. */
    data object Binary : DiffContent

    /** The diff is longer than [limitBytes]; none of it is shown. */
    data class TooLarge(val limitBytes: Long) : DiffContent

    /** No line changed, as for a change of the file mode only. */
    data object Unchanged : DiffContent
}

/**
 * One `@@ -oldStart,oldCount +newStart,newCount @@ section` block.
 *
 * @property section The text git prints after the second `@@`, such as the enclosing function;
 *   null when there is none.
 */
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val section: String? = null,
    val lines: List<DiffLine>,
) {
    override fun toString(): String =
        "DiffHunk(old=$oldStart,$oldCount, new=$newStart,$newCount, section=${section?.let { "***" }}, lines=${lines.size})"
}

/**
 * One line of a hunk. A context line has both numbers, an added line the new one only, a deleted
 * line the old one only, and the `\ No newline at end of file` marker neither.
 *
 * @property text Without the leading `+`, `-` or space and without the line break; for
 *   [DiffLineKind.NoNewline], the marker as git printed it.
 */
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldNumber: Int?,
    val newNumber: Int?,
) {
    init {
        val numbers = when (kind) {
            DiffLineKind.Context -> oldNumber != null && newNumber != null
            DiffLineKind.Added -> oldNumber == null && newNumber != null
            DiffLineKind.Deleted -> oldNumber != null && newNumber == null
            DiffLineKind.NoNewline -> oldNumber == null && newNumber == null
        }
        require(numbers) { "Line numbers do not match the line kind $kind" }
    }

    override fun toString(): String = "DiffLine(kind=$kind, text=***, old=$oldNumber, new=$newNumber)"
}

enum class DiffLineKind {
    Context,
    Added,
    Deleted,

    /** `\ No newline at end of file`, about the line before it. */
    NoNewline,
}

sealed interface DiffIntent {
    /** Reads the diff again, as when the screen is shown again. */
    data object Refresh : DiffIntent

    /** Reads again after [DiffState.error]. */
    data object Retry : DiffIntent

    data object Close : DiffIntent
}

sealed interface DiffEvent {
    data object Close : DiffEvent
}

interface DiffPresenter : Presenter<DiffState, DiffIntent, DiffEvent>
