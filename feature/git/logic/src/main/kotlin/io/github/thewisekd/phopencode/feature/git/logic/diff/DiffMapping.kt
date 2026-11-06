package io.github.thewisekd.phopencode.feature.git.logic.diff

import io.github.thewisekd.phopencode.core.git.command.GitResult
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import io.github.thewisekd.phopencode.core.git.parse.DiffChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffContent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffHunk
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLine
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLineKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileDiff
import io.github.thewisekd.phopencode.core.git.parse.DiffFile as ParsedFile
import io.github.thewisekd.phopencode.core.git.parse.DiffHunk as ParsedHunk
import io.github.thewisekd.phopencode.core.git.parse.DiffLineKind as ParsedLineKind

/** The marker git prints after a line without a final newline; git never translates it. */
internal const val NO_NEWLINE_MARKER = "\\ No newline at end of file"

/**
 * One file as `--name-status -z` or `--numstat -z` lists it, paths decoded as UTF-8 for matching
 * against a route's path.
 */
internal data class ChangeSummary(
    val path: String,
    val oldPath: String? = null,
    /** False when the old name's bytes are not valid UTF-8: [oldPath] is then for display only. */
    val oldPathExact: Boolean = true,
    val kind: GitChangeKind = GitChangeKind.Modified,
    val additions: Int = 0,
    val deletions: Int = 0,
) {
    override fun toString(): String =
        "ChangeSummary(path=***, oldPath=${oldPath?.let { "***" }}, kind=$kind, additions=$additions, deletions=$deletions)"
}

internal object DiffMapping {
    /**
     * The diff of [path] among the parsed [files]: the file whose new path (or, deleted, old path)
     * is [path]. A type change (a file that became a symlink or back) comes from git as a deletion
     * and an addition of the same path; the two become one [GitChangeKind.TypeChanged] diff with
     * the hunks of both. Null when no file matches.
     */
    fun fileDiff(path: String, files: List<ParsedFile>): GitFileDiff? {
        val matching = files.filter { it.path.isValidUtf8 && it.path.text == path }
        val deleted = matching.firstOrNull { it.change == DiffChange.DELETED }
        val added = matching.firstOrNull { it.change == DiffChange.ADDED }
        if (deleted != null && added != null) {
            val content = when {
                deleted.isBinary || added.isBinary -> DiffContent.Binary
                else -> content(deleted.hunks + added.hunks)
            }
            return GitFileDiff(
                path = path,
                kind = GitChangeKind.TypeChanged,
                content = content,
                additions = deleted.additions + added.additions,
                deletions = deleted.deletions + added.deletions,
            )
        }
        val file = matching.firstOrNull() ?: return null
        val kind = kindOf(file.change)
        return GitFileDiff(
            path = file.path.text,
            oldPath = if (kind == GitChangeKind.Renamed || kind == GitChangeKind.Copied) file.oldPath?.text else null,
            kind = kind,
            content = if (file.isBinary) DiffContent.Binary else content(file.hunks),
            additions = file.additions,
            deletions = file.deletions,
        )
    }

    private fun content(hunks: List<ParsedHunk>): DiffContent =
        if (hunks.isEmpty()) DiffContent.Unchanged else DiffContent.Text(hunks.map(::hunk))

    private fun hunk(hunk: ParsedHunk): DiffHunk = DiffHunk(
        oldStart = hunk.oldStart,
        oldCount = hunk.oldCount,
        newStart = hunk.newStart,
        newCount = hunk.newCount,
        section = hunk.section.ifEmpty { null },
        lines = buildList {
            for (line in hunk.lines) {
                val kind = when (line.kind) {
                    ParsedLineKind.CONTEXT -> DiffLineKind.Context
                    ParsedLineKind.ADDED -> DiffLineKind.Added
                    ParsedLineKind.REMOVED -> DiffLineKind.Deleted
                }
                add(DiffLine(kind, line.text, line.oldLine, line.newLine))
                if (line.noNewlineAtEnd) add(DiffLine(DiffLineKind.NoNewline, NO_NEWLINE_MARKER, null, null))
            }
        },
    )

    private fun kindOf(change: DiffChange): GitChangeKind = when (change) {
        DiffChange.ADDED -> GitChangeKind.Added
        DiffChange.DELETED -> GitChangeKind.Deleted
        DiffChange.MODIFIED -> GitChangeKind.Modified
        DiffChange.RENAMED -> GitChangeKind.Renamed
        DiffChange.COPIED -> GitChangeKind.Copied
    }

    /**
     * The entry of `--name-status -z` output whose new path is [path], or null. Records are
     * `X\0path\0`, or `R<score>\0old\0new\0` and `C<score>\0old\0new\0`.
     */
    fun nameStatus(path: String, stdout: ByteArray): ChangeSummary? {
        val tokens = tokens(stdout)
        var index = 0
        while (index < tokens.size) {
            val status = tokens[index].text
            if (status.isEmpty()) return null
            val twoPaths = status[0] == 'R' || status[0] == 'C'
            val oldPath = if (twoPaths) tokens.getOrNull(index + 1) ?: return null else null
            val newPath = tokens.getOrNull(index + if (twoPaths) 2 else 1) ?: return null
            if (newPath.matches(path)) {
                return ChangeSummary(
                    path = path,
                    oldPath = oldPath?.text,
                    oldPathExact = oldPath?.exact ?: true,
                    kind = kindOf(status[0]),
                )
            }
            index += if (twoPaths) 3 else 2
        }
        return null
    }

    /**
     * The added and deleted line counts of [path] in `--numstat -z` output, or null. Records are
     * `added\tdeleted\tpath\0`, or `added\tdeleted\t\0old\0new\0` for a rename; `-` (a binary
     * file) counts as 0.
     */
    fun numStat(path: String, stdout: ByteArray): Pair<Int, Int>? {
        val tokens = tokens(stdout)
        var index = 0
        while (index < tokens.size) {
            val record = tokens[index]
            val fields = record.text.split('\t', limit = 3)
            if (fields.size != 3) return null
            val matches = if (fields[2].isEmpty()) {
                index += 2
                (tokens.getOrNull(index) ?: return null).matches(path)
            } else {
                // The counts are ASCII, so the record is exact exactly when its path is.
                record.exact && fields[2] == path
            }
            if (matches) return count(fields[0]) to count(fields[1])
            index++
        }
        return null
    }

    private fun count(field: String): Int = field.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun kindOf(letter: Char): GitChangeKind = when (letter) {
        'A' -> GitChangeKind.Added
        'D' -> GitChangeKind.Deleted
        'R' -> GitChangeKind.Renamed
        'C' -> GitChangeKind.Copied
        'T' -> GitChangeKind.TypeChanged
        'U' -> GitChangeKind.Unmerged
        else -> GitChangeKind.Modified
    }

    /**
     * One NUL-terminated field. [text] is decoded leniently, for display; [exact] tells whether the
     * bytes were valid UTF-8, the only case in which [text] names the same file as the bytes.
     */
    private class Token(val text: String, val exact: Boolean) {
        /** Only an exact field matches: a lossy one could equal a path that holds U+FFFD itself. */
        fun matches(path: String): Boolean = exact && text == path
    }

    /** NUL-terminated fields, each decoded on its own; the empty tail is dropped. */
    private fun tokens(stdout: ByteArray): List<Token> {
        if (stdout.isEmpty()) return emptyList()
        val end = if (stdout.last() == NUL) stdout.size - 1 else stdout.size
        val tokens = ArrayList<Token>()
        var start = 0
        while (start <= end) {
            var stop = start
            while (stop < end && stdout[stop] != NUL) stop++
            val bytes = stdout.copyOfRange(start, stop)
            tokens += Token(String(bytes, Charsets.UTF_8), strictUtf8(bytes) != null)
            start = stop + 1
        }
        return tokens
    }

    private const val NUL: Byte = 0

    /** [bytes] as UTF-8, or null when they are not valid UTF-8. */
    fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * What a command that exited non-zero means. Git writes stderr in English (`LC_ALL=C`); the
     * text is matched here and never kept or logged.
     */
    fun failureOf(result: GitResult): GitFailure {
        if (result.exitCode == EXIT_COMMAND_NOT_FOUND) return GitFailure.GitMissing
        val stderr = result.stderrText()
        return when {
            "not a git repository" in stderr -> GitFailure.NotRepository
            NOT_FOUND_MESSAGES.any { it in stderr } -> GitFailure.NotFound
            else -> GitFailure.GitError(result.exitCode)
        }
    }

    private const val EXIT_COMMAND_NOT_FOUND = 127

    private val NOT_FOUND_MESSAGES = listOf(
        "cannot change to",
        "unknown revision",
        "bad revision",
        "bad object",
        "invalid object name",
        "ambiguous argument",
        "not a commit",
        "does not exist",
    )
}
