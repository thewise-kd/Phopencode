package io.github.thewisekd.phopencode.core.git.parse

import java.io.ByteArrayOutputStream

/**
 * Parses unified diffs in git's format.
 *
 * Expected command (see [ARGUMENTS]):
 * `git diff --no-color --no-ext-diff --no-textconv --no-relative --find-renames --submodule=short --src-prefix=a/ --dst-prefix=b/`,
 * followed by e.g. `--cached`, `<from> <to>` and `-- <path>`. The explicit prefixes override
 * `diff.noprefix` and `diff.mnemonicPrefix`; `--submodule=short` overrides `diff.submodule=log|diff`,
 * so a submodule change is a `Subproject commit` hunk. `git diff-tree -p --no-commit-id` with the
 * same options works too. Paths may be quoted or not (`core.quotePath` either way); both are decoded
 * to bytes. Empty context lines written as empty lines (`diff.suppressBlankEmpty`) are understood.
 *
 * Not supported, failing with [GitParseException]: combined diffs of merges (`--cc`, the default of
 * `git show` on a merge; use `<parent> <merge>` instead), and any text before the first
 * `diff --git` line (`git show` headers, `--stat`). With `--binary` the binary patch is checked for
 * completeness, then skipped, and the file is reported as binary. Whitespace-ignoring options (`-w`, `-b`, `--ignore-blank-lines`)
 * are not supported either: they can print a content change without hunks, which this parser treats
 * as truncated output.
 *
 * Truncation is detected wherever git's output allows it: in a hunk, in a quoted path, in a binary
 * patch, in a missing final newline, an `old mode` without its `new mode`, and after the headers of a file whose content changed (different blob hashes,
 * similarity below 100%, or an added or deleted file without an `index` line) but which has neither
 * hunks nor a binary marker. Output cut exactly between two complete files cannot be detected.
 */
public object DiffParser {
    /** The arguments after `git` whose output [parse] expects, before the revisions and paths. */
    public val ARGUMENTS: List<String> = listOf(
        "diff",
        "--no-color",
        "--no-ext-diff",
        "--no-textconv",
        "--no-relative",
        "--find-renames",
        "--submodule=short",
        "--src-prefix=a/",
        "--dst-prefix=b/",
    )

    /** Parses the raw stdout; empty output is no files. Throws [GitParseException] when malformed. */
    public fun parse(stdout: ByteArray): List<DiffFile> {
        if (stdout.isEmpty()) return emptyList()
        if (stdout[stdout.size - 1] != LF) fail("diff output does not end with a newline", stdout.size)
        return Parse(stdout).run()
    }

    private val DIFF_GIT = ascii("diff --git ")
    private val DIFF_CC = ascii("diff --cc ")
    private val DIFF_COMBINED = ascii("diff --combined ")
    private val OLD_MODE = ascii("old mode ")
    private val NEW_MODE = ascii("new mode ")
    private val DELETED_FILE_MODE = ascii("deleted file mode ")
    private val NEW_FILE_MODE = ascii("new file mode ")
    private val COPY_FROM = ascii("copy from ")
    private val COPY_TO = ascii("copy to ")
    private val RENAME_FROM = ascii("rename from ")
    private val RENAME_TO = ascii("rename to ")
    private val SIMILARITY = ascii("similarity index ")
    private val DISSIMILARITY = ascii("dissimilarity index ")
    private val INDEX = ascii("index ")
    private val BINARY_FILES = ascii("Binary files ")
    private val GIT_BINARY_PATCH = ascii("GIT binary patch")
    private val LITERAL = ascii("literal ")
    private val DELTA = ascii("delta ")
    private val NO_NEWLINE = ascii("\\ No newline at end of file")
    private val MINUS = ascii("--- ")
    private val PLUS = ascii("+++ ")
    private val HUNK = ascii("@@ -")
    private val COMBINED_HUNK = ascii("@@@")
    private val DEV_NULL = ascii("/dev/null")
    private val A_PREFIX = ascii("a/")
    private val B_PREFIX = ascii("b/")
    private const val EMPTY_BLOB_SHA1 = "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"
    private const val EMPTY_BLOB_SHA256 = "473a0f4c3be8a93681a267e3b1e9a7dcda1185436fe141f7749120a303721813"

    /** A side of a `---`/`+++` line: absent until seen, then a path or `/dev/null` (null). */
    private class Side(val path: ByteArray?)

    private class Parse(private val d: ByteArray) {
        private val n = d.size

        /** Every line ends with LF, checked in [parse]. */
        private fun lineEnd(pos: Int): Int = d.indexOf(LF, pos)

        fun run(): List<DiffFile> {
            val files = ArrayList<DiffFile>()
            var pos = 0
            while (pos < n) {
                val end = lineEnd(pos)
                if (d.startsWith(DIFF_CC, pos, end) || d.startsWith(DIFF_COMBINED, pos, end)) {
                    fail("combined diffs are not supported", pos)
                }
                if (!d.startsWith(DIFF_GIT, pos, end)) fail("expected a diff --git line", pos)
                pos = file(pos, end, files)
            }
            return files
        }

        private fun file(headerPos: Int, headerEnd: Int, out: MutableList<DiffFile>): Int {
            val gitPaths = gitLine(headerPos + DIFF_GIT.size, headerEnd)
            var oldMode: Int? = null
            var newMode: Int? = null
            var newFileMode: Int? = null
            var deletedFileMode: Int? = null
            var indexMode: Int? = null
            var renameFrom: ByteArray? = null
            var renameTo: ByteArray? = null
            var copyFrom: ByteArray? = null
            var copyTo: ByteArray? = null
            var similarity: Int? = null
            var oldHash: String? = null
            var newHash: String? = null
            var sawHeader = false

            var p = headerEnd + 1
            while (p < n) {
                val e = lineEnd(p)
                when {
                    d.startsWith(OLD_MODE, p, e) -> oldMode = d.parseMode(p + OLD_MODE.size, e)
                    d.startsWith(NEW_MODE, p, e) -> newMode = d.parseMode(p + NEW_MODE.size, e)
                    d.startsWith(DELETED_FILE_MODE, p, e) -> deletedFileMode = d.parseMode(p + DELETED_FILE_MODE.size, e)
                    d.startsWith(NEW_FILE_MODE, p, e) -> newFileMode = d.parseMode(p + NEW_FILE_MODE.size, e)
                    d.startsWith(COPY_FROM, p, e) -> copyFrom = headerPath(p + COPY_FROM.size, e)
                    d.startsWith(COPY_TO, p, e) -> copyTo = headerPath(p + COPY_TO.size, e)
                    d.startsWith(RENAME_FROM, p, e) -> renameFrom = headerPath(p + RENAME_FROM.size, e)
                    d.startsWith(RENAME_TO, p, e) -> renameTo = headerPath(p + RENAME_TO.size, e)
                    d.startsWith(SIMILARITY, p, e) -> similarity = percent(p + SIMILARITY.size, e)
                    d.startsWith(DISSIMILARITY, p, e) -> percent(p + DISSIMILARITY.size, e)
                    d.startsWith(INDEX, p, e) -> {
                        val dots = indexOfDots(p + INDEX.size, e)
                        val space = d.indexOf(SPACE, dots + 2, e)
                        val hashEnd = if (space < 0) e else space
                        if (!d.isLowerHex(p + INDEX.size, dots) || !d.isLowerHex(dots + 2, hashEnd)) {
                            fail("malformed index line", p)
                        }
                        oldHash = String(d, p + INDEX.size, dots - p - INDEX.size, Charsets.US_ASCII)
                        newHash = String(d, dots + 2, hashEnd - dots - 2, Charsets.US_ASCII)
                        if (space >= 0) indexMode = d.parseMode(space + 1, e)
                    }
                    else -> break
                }
                sawHeader = true
                p = e + 1
            }

            var binary = false
            var minus: Side? = null
            var plus: Side? = null
            val hunks = ArrayList<DiffHunk>()
            if (p < n) {
                val e = lineEnd(p)
                when {
                    d.startsWith(BINARY_FILES, p, e) -> {
                        binary = true
                        p = e + 1
                    }
                    d.rangeEquals(p, e, GIT_BINARY_PATCH) -> {
                        binary = true
                        p = binaryPatch(e + 1)
                    }
                    d.startsWith(MINUS, p, e) -> {
                        minus = Side(sidePath(p + MINUS.size, e, A_PREFIX))
                        p = e + 1
                        if (p >= n) fail("missing +++ line", p)
                        val pe = lineEnd(p)
                        if (!d.startsWith(PLUS, p, pe)) fail("missing +++ line", p)
                        plus = Side(sidePath(p + PLUS.size, pe, B_PREFIX))
                        p = pe + 1
                        while (p < n && d[p] == '@'.code.toByte()) {
                            val he = lineEnd(p)
                            if (d.startsWith(COMBINED_HUNK, p, he)) fail("combined diffs are not supported", p)
                            p = hunk(p, he, hunks)
                        }
                        if (hunks.isEmpty()) fail("file without hunks", p)
                    }
                }
            }
            if (!sawHeader && !binary && minus == null) fail("diff --git without any change", headerPos)
            if ((oldMode == null) != (newMode == null)) fail("old mode and new mode must come together", headerPos)

            if ((renameFrom == null) != (renameTo == null)) fail("incomplete rename headers", headerPos)
            if ((copyFrom == null) != (copyTo == null)) fail("incomplete copy headers", headerPos)
            val added = newFileMode != null
            val deleted = deletedFileMode != null
            if (added && deleted) fail("file both added and deleted", headerPos)
            val change = when {
                added -> DiffChange.ADDED
                deleted -> DiffChange.DELETED
                copyFrom != null -> DiffChange.COPIED
                renameFrom != null -> DiffChange.RENAMED
                else -> DiffChange.MODIFIED
            }
            val oldPath = if (added) {
                null
            } else {
                renameFrom ?: copyFrom ?: minus?.let { it.path ?: fail("old side is /dev/null", headerPos) }
                    ?: gitPaths?.first ?: fail("cannot tell the old path", headerPos)
            }
            val newPath = if (deleted) {
                null
            } else {
                renameTo ?: copyTo ?: plus?.let { it.path ?: fail("new side is /dev/null", headerPos) }
                    ?: gitPaths?.second ?: fail("cannot tell the new path", headerPos)
            }
            if (!binary && hunks.isEmpty()) checkNotTruncated(headerPos, added || deleted, oldHash, newHash, similarity)
            out += DiffFile(
                oldPath = oldPath?.let(::GitPath),
                newPath = newPath?.let(::GitPath),
                change = change,
                oldMode = if (added) null else deletedFileMode ?: oldMode ?: indexMode,
                newMode = if (deleted) null else newFileMode ?: newMode ?: indexMode,
                similarity = similarity,
                oldHash = oldHash,
                newHash = newHash,
                isBinary = binary,
                hunks = hunks,
            )
            return p
        }

        /**
         * A file without hunks or binary marker is complete only when its content did not change:
         * a mode change, a pure rename or copy, or an added or deleted empty file. Anything else
         * means the output stopped after the headers.
         */
        private fun checkNotTruncated(pos: Int, addedOrDeleted: Boolean, oldHash: String?, newHash: String?, similarity: Int?) {
            if (similarity != null && similarity < 100) fail("renamed file content missing, output truncated", pos)
            if (oldHash == null || newHash == null) {
                if (addedOrDeleted) fail("added or deleted file without index line, output truncated", pos)
                return
            }
            if (oldHash == newHash) return
            if (isEmptyOrMissing(oldHash) && isEmptyOrMissing(newHash)) return
            fail("file content missing, output truncated", pos)
        }

        /** Whether an abbreviated blob name is the null object or the empty blob (SHA-1 or SHA-256). */
        private fun isEmptyOrMissing(hash: String): Boolean =
            hash.all { it == '0' } || EMPTY_BLOB_SHA1.startsWith(hash) || EMPTY_BLOB_SHA256.startsWith(hash)

        /**
         * Skips the body of a `GIT binary patch` starting at [pos] and returns the position after it.
         * Git always writes two blocks (forward, then reverse), each a `literal <n>` or `delta <n>`
         * line, base85 lines that start with a length letter, and an empty line. Anything less is
         * truncated.
         */
        private fun binaryPatch(pos: Int): Int {
            var p = pos
            repeat(2) {
                if (p >= n) fail("truncated binary patch", p)
                val e = lineEnd(p)
                val numberFrom = when {
                    d.startsWith(LITERAL, p, e) -> p + LITERAL.size
                    d.startsWith(DELTA, p, e) -> p + DELTA.size
                    else -> fail("malformed binary patch", p)
                }
                d.parseCount(numberFrom, e, "binary patch size")
                p = e + 1
                while (true) {
                    if (p >= n) fail("truncated binary patch", p)
                    val le = lineEnd(p)
                    if (le == p) break
                    val c = d[p].toInt().toChar()
                    if (c !in 'A'..'Z' && c !in 'a'..'z') fail("malformed binary patch", p)
                    p = le + 1
                }
                p++
            }
            return p
        }

        private fun indexOfDots(from: Int, to: Int): Int {
            var i = from
            while (i + 1 < to) {
                if (d[i] == '.'.code.toByte() && d[i + 1] == '.'.code.toByte()) return i
                i++
            }
            fail("malformed index line", from)
        }

        private fun percent(from: Int, to: Int): Int {
            if (to - from < 2 || d[to - 1] != '%'.code.toByte()) fail("malformed similarity", from)
            val value = d.parseCount(from, to - 1, "similarity")
            if (value > 100) fail("similarity out of range", from)
            return value
        }

        /** A path of a `rename from`-style header: quoted or raw, without prefix. */
        private fun headerPath(from: Int, to: Int): ByteArray {
            if (from >= to) fail("empty path", from)
            if (d[from] != QUOTE) return d.copyOfRange(from, to)
            val (bytes, next) = unquote(from, to)
            if (next != to) fail("text after a quoted path", next)
            return bytes
        }

        /**
         * A path of a `---`/`+++` line, or null for `/dev/null`. Git appends a tab when the name
         * holds a space; an unquoted name never ends in a tab because a tab forces quoting.
         */
        private fun sidePath(from: Int, to: Int, prefix: ByteArray): ByteArray? {
            if (from >= to) fail("empty path", from)
            val raw = if (d[from] == QUOTE) {
                val (bytes, next) = unquote(from, to)
                if (next != to && !(next + 1 == to && d[next] == TAB)) fail("text after a quoted path", next)
                bytes
            } else {
                val end = if (d[to - 1] == TAB) to - 1 else to
                if (d.rangeEquals(from, end, DEV_NULL)) return null
                d.copyOfRange(from, end)
            }
            if (!raw.startsWith(prefix, 0, raw.size) || raw.size == prefix.size) fail("unexpected path prefix", from)
            return raw.copyOfRange(prefix.size, raw.size)
        }

        /**
         * The two paths of `diff --git a/X b/Y`, without prefixes, or null when they cannot be told
         * apart (two different unquoted names with spaces; then rename headers name them).
         */
        private fun gitLine(from: Int, to: Int): Pair<ByteArray, ByteArray>? {
            if (from >= to) fail("empty diff --git line", from)
            val a: ByteArray
            val b: ByteArray
            if (d[from] == QUOTE) {
                val (first, next) = unquote(from, to)
                if (next + 1 >= to || d[next] != SPACE) fail("malformed diff --git line", next)
                a = first
                b = headerPath(next + 1, to)
            } else {
                val quoted = indexOfSpaceQuote(from, to)
                if (quoted >= 0) {
                    a = d.copyOfRange(from, quoted)
                    b = headerPath(quoted + 1, to)
                } else {
                    val length = to - from
                    if (length % 2 == 0) return null
                    val half = length / 2
                    if (d[from + half] != SPACE) return null
                    a = d.copyOfRange(from, from + half)
                    b = d.copyOfRange(from + half + 1, to)
                    if (!a.startsWith(A_PREFIX, 0, a.size) || !b.startsWith(B_PREFIX, 0, b.size)) return null
                    for (i in 2 until a.size) if (a[i] != b[i]) return null
                }
            }
            if (!a.startsWith(A_PREFIX, 0, a.size) || a.size == 2 || !b.startsWith(B_PREFIX, 0, b.size) || b.size == 2) {
                fail("unexpected path prefix", from)
            }
            return a.copyOfRange(2, a.size) to b.copyOfRange(2, b.size)
        }

        /** The first ` "` in the range; a raw name never holds `"`, since git would quote it. */
        private fun indexOfSpaceQuote(from: Int, to: Int): Int {
            var i = from
            while (i + 1 < to) {
                if (d[i] == SPACE && d[i + 1] == QUOTE) return i
                i++
            }
            return -1
        }

        /** Decodes a C-style quoted string starting at [from]; returns the bytes and the index after the closing quote. */
        private fun unquote(from: Int, to: Int): Pair<ByteArray, Int> {
            val out = ByteArrayOutputStream(to - from)
            var i = from + 1
            while (true) {
                if (i >= to) fail("unterminated quoted path", from)
                val c = d[i]
                when (c) {
                    QUOTE -> return out.toByteArray() to i + 1
                    BACKSLASH -> {
                        if (i + 1 >= to) fail("unterminated escape", i)
                        val esc = d[i + 1].toInt().toChar()
                        val simple = when (esc) {
                            'a' -> 7
                            'b' -> 8
                            't' -> 9
                            'n' -> 10
                            'v' -> 11
                            'f' -> 12
                            'r' -> 13
                            '"' -> '"'.code
                            '\\' -> '\\'.code
                            else -> -1
                        }
                        if (simple >= 0) {
                            out.write(simple)
                            i += 2
                        } else {
                            if (esc !in '0'..'3' || i + 3 >= to) fail("invalid escape in quoted path", i)
                            var value = 0
                            for (k in i + 1..i + 3) {
                                val digit = d[k] - '0'.code.toByte()
                                if (digit !in 0..7) fail("invalid escape in quoted path", i)
                                value = value * 8 + digit
                            }
                            out.write(value)
                            i += 4
                        }
                    }
                    else -> {
                        out.write(c.toInt())
                        i++
                    }
                }
            }
        }

        /** Parses a hunk whose header is `[pos, end)` and returns the position after it. */
        private fun hunk(pos: Int, end: Int, out: MutableList<DiffHunk>): Int {
            if (!d.startsWith(HUNK, pos, end)) fail("malformed hunk header", pos)
            var p = pos + HUNK.size
            val (oldStart, oldCount, afterOld) = range(p, end)
            p = afterOld
            if (p + 2 > end || d[p] != SPACE || d[p + 1] != '+'.code.toByte()) fail("malformed hunk header", p)
            val (newStart, newCount, afterNew) = range(p + 2, end)
            p = afterNew
            if (p + 3 > end || d[p] != SPACE || d[p + 1] != '@'.code.toByte() || d[p + 2] != '@'.code.toByte()) {
                fail("malformed hunk header", p)
            }
            p += 3
            val section = when {
                p == end -> ""
                d[p] == SPACE -> decodeUtf8(d, p + 1, end)
                else -> fail("malformed hunk header", p)
            }

            val lines = ArrayList<DiffLine>()
            var oldLeft = oldCount
            var newLeft = newCount
            var oldNo = oldStart
            var newNo = newStart
            p = end + 1
            while (oldLeft > 0 || newLeft > 0) {
                if (p >= n) fail("truncated hunk", p)
                val e = lineEnd(p)
                val marker = if (e == p) ' '.code.toByte() else d[p]
                val content = if (e == p) ByteArray(0) else d.copyOfRange(p + 1, e)
                when (marker) {
                    SPACE -> {
                        if (oldLeft == 0 || newLeft == 0) fail("hunk longer than its header", p)
                        lines += DiffLine(DiffLineKind.CONTEXT, oldNo++, newNo++, content, false)
                        oldLeft--
                        newLeft--
                    }
                    '-'.code.toByte() -> {
                        if (oldLeft == 0) fail("hunk longer than its header", p)
                        lines += DiffLine(DiffLineKind.REMOVED, oldNo++, null, content, false)
                        oldLeft--
                    }
                    '+'.code.toByte() -> {
                        if (newLeft == 0) fail("hunk longer than its header", p)
                        lines += DiffLine(DiffLineKind.ADDED, null, newNo++, content, false)
                        newLeft--
                    }
                    BACKSLASH -> markNoNewline(lines, p)
                    else -> fail("unexpected line in hunk", p)
                }
                p = e + 1
            }
            if (p < n && d[p] == BACKSLASH) {
                markNoNewline(lines, p)
                p = lineEnd(p) + 1
            }
            out += DiffHunk(oldStart, oldCount, newStart, newCount, section, lines)
            return p
        }

        /** Applies `\ No newline at end of file` (git never translates it) to the previous line. */
        private fun markNoNewline(lines: MutableList<DiffLine>, pos: Int) {
            if (!d.rangeEquals(pos, lineEnd(pos), NO_NEWLINE)) fail("unexpected backslash line in hunk", pos)
            if (lines.isEmpty()) fail("no-newline marker before any line", pos)
            val last = lines[lines.size - 1]
            if (last.noNewlineAtEnd) fail("repeated no-newline marker", pos)
            lines[lines.size - 1] = last.withNoNewlineAtEnd()
        }

        /** `start[,count]` ending at a space; the count defaults to 1. */
        private fun range(from: Int, to: Int): Triple<Int, Int, Int> {
            val space = d.indexOf(SPACE, from, to)
            if (space < 0) fail("malformed hunk header", from)
            val comma = d.indexOf(','.code.toByte(), from, space)
            val start = d.parseCount(from, if (comma < 0) space else comma, "hunk start")
            val count = if (comma < 0) 1 else d.parseCount(comma + 1, space, "hunk length")
            if (start.toLong() + count > Int.MAX_VALUE) fail("hunk range out of range", from)
            return Triple(start, count, space)
        }
    }
}
