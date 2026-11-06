package io.github.thewisekd.phopencode.feature.git.logic.log

import io.github.thewisekd.phopencode.core.git.parse.GitParseException
import io.github.thewisekd.phopencode.core.git.parse.GitPath
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind

/**
 * Reads the file lists of `show --numstat -z` and `show --name-status -z` without a header
 * ([LogCommands.numStat], [LogCommands.nameStatus]). `core/git`'s diff parser reads patches only.
 *
 * Formats, every path raw (`-z` turns C-quoting off) and ended by NUL:
 * - numstat: `<added>\t<deleted>\t<path>\0`, or for a rename or copy `<added>\t<deleted>\t\0<old>\0<new>\0`;
 *   both counts are `-` for a binary file.
 * - name-status: `<letter>\0<path>\0`, or `R<score>\0<old>\0<new>\0` and `C<score>\0<old>\0<new>\0`.
 *
 * Every read stays inside the input; anything else fails with [GitParseException], whose message
 * holds no input bytes.
 */
internal object CommitFiles {
    class NumStat(val path: String, val oldPath: String?, val additions: Int?, val deletions: Int?)

    class NameStatus(val kind: GitChangeKind, val path: String, val oldPath: String?)

    private const val NUL: Byte = 0
    private const val TAB: Byte = '\t'.code.toByte()
    private const val DASH: Byte = '-'.code.toByte()
    private const val MAX_COUNT_DIGITS = 10
    private const val MAX_SCORE_DIGITS = 3
    private const val MAX_SCORE = 100

    fun parseNumStat(stdout: ByteArray): List<NumStat> {
        val files = ArrayList<NumStat>()
        val reader = Reader(stdout)
        while (!reader.atEnd) {
            val additions = reader.count(TAB, "added count")
            val deletions = reader.count(TAB, "deleted count")
            if ((additions == null) != (deletions == null)) fail("binary marker on one count only", reader.position)
            files += if (reader.peek() == NUL) {
                reader.skip()
                val old = reader.path("old path")
                NumStat(reader.path("new path"), old, additions, deletions)
            } else {
                NumStat(reader.path("path"), null, additions, deletions)
            }
        }
        return files
    }

    fun parseNameStatus(stdout: ByteArray): List<NameStatus> {
        val files = ArrayList<NameStatus>()
        val reader = Reader(stdout)
        while (!reader.atEnd) {
            val start = reader.position
            val status = reader.field("status")
            if (status.isEmpty()) fail("empty status", start)
            val letter = status[0].toInt().toChar()
            val score = status.copyOfRange(1, status.size)
            val kind = when (letter) {
                'A' -> GitChangeKind.Added
                'M' -> GitChangeKind.Modified
                'D' -> GitChangeKind.Deleted
                'T' -> GitChangeKind.TypeChanged
                'U' -> GitChangeKind.Unmerged
                'R' -> GitChangeKind.Renamed
                'C' -> GitChangeKind.Copied
                else -> fail("unknown status", start)
            }
            val paired = kind == GitChangeKind.Renamed || kind == GitChangeKind.Copied
            if (paired) checkScore(score, start) else if (score.isNotEmpty()) fail("unexpected score", start)
            files += if (paired) {
                val old = reader.path("old path")
                NameStatus(kind, reader.path("new path"), old)
            } else {
                NameStatus(kind, reader.path("path"), null)
            }
        }
        return files
    }

    /** The files of a commit with one parent or none: kinds from [nameStatus], counts from [numStat]. */
    fun join(numStat: List<NumStat>, nameStatus: List<NameStatus>): List<CommitFileChange> {
        if (numStat.size != nameStatus.size) fail("file lists differ in length", 0)
        return numStat.indices.map { index ->
            val counts = numStat[index]
            val status = nameStatus[index]
            if (counts.path != status.path || counts.oldPath != status.oldPath) fail("file lists differ", index)
            CommitFileChange(status.path, status.kind, status.oldPath, counts.additions, counts.deletions)
        }
    }

    /**
     * The files of a merge from [numStat] alone. For a merge `show --name-status` prints only the
     * combined diff, so the kind is [GitChangeKind.Renamed] for a rename and
     * [GitChangeKind.Modified] for everything else, additions and deletions included.
     */
    fun fromNumStat(numStat: List<NumStat>): List<CommitFileChange> = numStat.map { counts ->
        val kind = if (counts.oldPath != null) GitChangeKind.Renamed else GitChangeKind.Modified
        CommitFileChange(counts.path, kind, counts.oldPath, counts.additions, counts.deletions)
    }

    private fun checkScore(score: ByteArray, offset: Int) {
        if (score.isEmpty() || score.size > MAX_SCORE_DIGITS) fail("invalid similarity score", offset)
        var value = 0
        for (byte in score) {
            val digit = byte - '0'.code.toByte()
            if (digit !in 0..9) fail("invalid similarity score", offset)
            value = value * 10 + digit
        }
        if (value > MAX_SCORE) fail("invalid similarity score", offset)
    }

    private fun fail(reason: String, offset: Int): Nothing = throw GitParseException(reason, offset)

    private class Reader(private val data: ByteArray) {
        var position = 0
            private set

        val atEnd: Boolean get() = position >= data.size

        fun peek(): Byte {
            if (atEnd) fail("truncated record", position)
            return data[position]
        }

        fun skip() {
            peek()
            position++
        }

        /** The bytes up to the next [end], which is consumed. */
        fun until(end: Byte, what: String): ByteArray {
            var index = position
            while (index < data.size && data[index] != end) index++
            if (index >= data.size) fail("unterminated $what", position)
            val bytes = data.copyOfRange(position, index)
            position = index + 1
            return bytes
        }

        fun field(what: String): ByteArray = until(NUL, what)

        fun path(what: String): String {
            val start = position
            val bytes = field(what)
            if (bytes.isEmpty()) fail("empty $what", start)
            return GitPath(bytes).text
        }

        /** A count ended by [end]: decimal digits, or `-` for a binary file (null). */
        fun count(end: Byte, what: String): Int? {
            val start = position
            var index = position
            while (index < data.size && data[index] != end && data[index] != NUL) index++
            if (index >= data.size || data[index] != end) fail("unterminated $what", start)
            val length = index - position
            val value = when {
                length == 1 && data[position] == DASH -> null
                length == 0 || length > MAX_COUNT_DIGITS -> fail("invalid $what", start)
                else -> {
                    var number = 0L
                    for (i in position until index) {
                        val digit = data[i] - '0'.code.toByte()
                        if (digit !in 0..9) fail("invalid $what", i)
                        number = number * 10 + digit
                    }
                    if (number > Int.MAX_VALUE) fail("$what out of range", start)
                    number.toInt()
                }
            }
            position = index + 1
            return value
        }
    }
}
