package io.github.thewisekd.phopencode.core.git.parse

/** What happened to a file in a diff. A mode-only change is [MODIFIED] with different modes. */
public enum class DiffChange { ADDED, DELETED, MODIFIED, RENAMED, COPIED }

/** Whether a hunk line is unchanged, added or removed. */
public enum class DiffLineKind { CONTEXT, ADDED, REMOVED }

/**
 * One line of a hunk.
 *
 * [content] is the line without its leading marker and without the newline; a carriage return of a
 * CRLF file stays in it. [oldLine] is set for context and removed lines, [newLine] for context and
 * added lines, both 1-based. [noNewlineAtEnd] is true when git followed the line with
 * `\ No newline at end of file`. [toString] hides the content.
 */
public class DiffLine(
    public val kind: DiffLineKind,
    public val oldLine: Int?,
    public val newLine: Int?,
    content: ByteArray,
    public val noNewlineAtEnd: Boolean,
) {
    private val content: ByteArray = content.copyOf()

    /** The content decoded as UTF-8 for display; invalid sequences become U+FFFD. */
    public val text: String by lazy { decodeUtf8(this.content, 0, this.content.size) }

    /** A copy of the raw content bytes. */
    public fun contentBytes(): ByteArray = content.copyOf()

    internal fun withNoNewlineAtEnd(): DiffLine = DiffLine(kind, oldLine, newLine, content, true)

    override fun equals(other: Any?): Boolean =
        other is DiffLine && kind == other.kind && oldLine == other.oldLine && newLine == other.newLine &&
            noNewlineAtEnd == other.noNewlineAtEnd && content.contentEquals(other.content)

    override fun hashCode(): Int {
        var h = kind.hashCode()
        h = 31 * h + (oldLine ?: 0)
        h = 31 * h + (newLine ?: 0)
        h = 31 * h + noNewlineAtEnd.hashCode()
        return 31 * h + content.contentHashCode()
    }

    override fun toString(): String =
        "DiffLine(kind=$kind, oldLine=$oldLine, newLine=$newLine, content=<${content.size} bytes>, noNewlineAtEnd=$noNewlineAtEnd)"
}

/**
 * One `@@ -oldStart,oldCount +newStart,newCount @@ section` hunk. [section] is the function context
 * git prints after the second `@@`, empty when none. [toString] hides it.
 */
public data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val section: String,
    val lines: List<DiffLine>,
) {
    override fun toString(): String =
        "DiffHunk(oldStart=$oldStart, oldCount=$oldCount, newStart=$newStart, newCount=$newCount, " +
            "section=${mask(section)}, lines=${lines.size})"
}

/**
 * One file of a unified diff.
 *
 * [oldPath] is null for an added file and [newPath] for a deleted one. Modes are octal file modes
 * as numbers, null for the missing side. [oldHash] and [newHash] are the possibly abbreviated object
 * names of the `index` line, null when git printed none (a pure rename or mode change). A binary file
 * has [isBinary] set and no hunks.
 */
public data class DiffFile(
    val oldPath: GitPath?,
    val newPath: GitPath?,
    val change: DiffChange,
    val oldMode: Int?,
    val newMode: Int?,
    /** The `similarity index` percentage of a rename or copy; null otherwise. */
    val similarity: Int?,
    val oldHash: String?,
    val newHash: String?,
    val isBinary: Boolean,
    val hunks: List<DiffHunk>,
) {
    /** The path to show: the new path, or the old one for a deleted file. */
    val path: GitPath get() = newPath ?: oldPath ?: error("a diff file always has a path")

    /** The number of added lines. */
    val additions: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.ADDED } }

    /** The number of removed lines. */
    val deletions: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.REMOVED } }

    /** Whether the file mode changed. */
    val isModeChange: Boolean get() = oldMode != null && newMode != null && oldMode != newMode
}
