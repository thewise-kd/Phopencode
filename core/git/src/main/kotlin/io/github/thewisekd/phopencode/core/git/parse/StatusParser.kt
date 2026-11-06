package io.github.thewisekd.phopencode.core.git.parse

/**
 * Parses `git status --porcelain=v2 -z`.
 *
 * Expected command (see [ARGUMENTS]), run in the repository root (`git -C <root>`):
 * `git status --porcelain=v2 -z --branch --ahead-behind --find-renames --untracked-files=all`,
 * optionally with `--ignored=matching` to list ignored files and `--show-stash` for
 * [GitStatus.stashCount]. `--ahead-behind`, `--find-renames` and `--untracked-files=all` override
 * `status.aheadBehind`, `status.renames` and `status.showUntrackedFiles` (git 2.47 already ignores
 * `status.aheadBehind` for porcelain output; the flag guards other versions). Uncounted output
 * (`# branch.ab +? -?`, from `--no-ahead-behind`) still parses, as [StatusBranch.aheadBehindUnknown].
 * Without `--branch`, [GitStatus.branch] is null. `-z` is required: entries are NUL-terminated and
 * paths are printed raw, never quoted. Unknown `#` headers are skipped, so newer git versions still
 * parse. Output cut exactly between two entries cannot be told from complete output.
 */
public object StatusParser {
    /** The arguments after `git` whose output [parse] expects. */
    public val ARGUMENTS: List<String> = listOf(
        "status",
        "--porcelain=v2",
        "-z",
        "--branch",
        "--ahead-behind",
        "--find-renames",
        "--untracked-files=all",
    )

    private val HEADER = ascii("# ")
    private val OID = ascii("branch.oid ")
    private val HEAD = ascii("branch.head ")
    private val UPSTREAM = ascii("branch.upstream ")
    private val AB = ascii("branch.ab ")
    private val STASH = ascii("stash ")
    private val INITIAL = ascii("(initial)")
    private val DETACHED = ascii("(detached)")
    private val UNKNOWN_COUNTS = ascii("+? -?")

    /** Parses the raw stdout. Throws [GitParseException] when it is malformed or truncated. */
    public fun parse(stdout: ByteArray): GitStatus = Parse(stdout).run()

    private class Parse(private val d: ByteArray) {
        private var oidSeen = false
        private var oid: String? = null
        private var headSeen = false
        private var head: String? = null
        private var upstream: String? = null
        private var ahead: Int? = null
        private var behind: Int? = null
        private var countsUnknown = false
        private var stash: Int? = null
        private val entries = ArrayList<StatusEntry>()

        fun run(): GitStatus {
            var pos = 0
            while (pos < d.size) pos = entry(pos)
            if ((ahead != null || countsUnknown) && upstream == null) fail("ahead/behind header without upstream", d.size)
            val branch = when {
                oidSeen && headSeen -> StatusBranch(oid, head, upstream, ahead, behind, countsUnknown)
                oidSeen || headSeen || upstream != null || ahead != null || countsUnknown ->
                    fail("incomplete branch headers", d.size)
                else -> null
            }
            return GitStatus(branch, entries, stash)
        }

        /** Parses the entry at [pos] and returns the position after it. */
        private fun entry(pos: Int): Int {
            val end = d.indexOf(NUL, pos)
            if (end < 0) fail("unterminated status entry", pos)
            if (end == pos) fail("empty status entry", pos)
            val type = d[pos].toInt().toChar()
            if (end == pos + 1 || d[pos + 1] != SPACE) fail("malformed status entry", pos)
            val body = pos + 2
            when (type) {
                '#' -> header(pos, end)
                '1' -> entries += changed(body, end)
                '2' -> {
                    val origEnd = d.indexOf(NUL, end + 1)
                    if (origEnd < 0) fail("missing original path of a rename", end + 1)
                    if (origEnd == end + 1) fail("empty original path of a rename", end + 1)
                    entries += renamed(body, end, GitPath(d.copyOfRange(end + 1, origEnd)))
                    return origEnd + 1
                }
                'u' -> entries += unmerged(body, end)
                '?' -> entries += UntrackedEntry(path(body, end))
                '!' -> entries += IgnoredEntry(path(body, end))
                else -> fail("unknown status entry type", pos)
            }
            return end + 1
        }

        private fun path(from: Int, to: Int): GitPath {
            if (from >= to) fail("empty path", from)
            return GitPath(d.copyOfRange(from, to))
        }

        private fun header(pos: Int, end: Int) {
            if (!d.startsWith(HEADER, pos, end)) fail("malformed header", pos)
            val p = pos + HEADER.size
            when {
                d.startsWith(OID, p, end) -> {
                    val v = p + OID.size
                    oid = if (d.rangeEquals(v, end, INITIAL)) null else d.parseFullHash(v, end)
                    oidSeen = true
                }
                d.startsWith(HEAD, p, end) -> {
                    val v = p + HEAD.size
                    if (v >= end) fail("empty branch name", v)
                    head = if (d.rangeEquals(v, end, DETACHED)) null else decodeUtf8(d, v, end)
                    headSeen = true
                }
                d.startsWith(UPSTREAM, p, end) -> {
                    val v = p + UPSTREAM.size
                    if (v >= end) fail("empty upstream name", v)
                    upstream = decodeUtf8(d, v, end)
                }
                d.startsWith(AB, p, end) -> aheadBehind(p + AB.size, end)
                d.startsWith(STASH, p, end) -> stash = d.parseCount(p + STASH.size, end, "stash count")
                else -> Unit // A header this parser does not know; newer git may add some.
            }
        }

        private fun aheadBehind(from: Int, to: Int) {
            if (d.rangeEquals(from, to, UNKNOWN_COUNTS)) {
                countsUnknown = true
                return
            }
            val space = d.indexOf(SPACE, from, to)
            if (space < 0 || d[from] != '+'.code.toByte() || space + 1 >= to || d[space + 1] != '-'.code.toByte()) {
                fail("malformed ahead/behind header", from)
            }
            ahead = d.parseCount(from + 1, space, "ahead count")
            behind = d.parseCount(space + 2, to, "behind count")
        }

        private fun xy(range: IntRange): Pair<StatusCode, StatusCode> {
            if (range.last - range.first != 1) fail("malformed XY field", range.first)
            val x = StatusCode.of(d[range.first].toInt().toChar()) ?: fail("unknown status code", range.first)
            val y = StatusCode.of(d[range.last].toInt().toChar()) ?: fail("unknown status code", range.last)
            return x to y
        }

        private fun submodule(range: IntRange): SubmoduleStatus? {
            if (range.last - range.first != 3) fail("malformed submodule field", range.first)
            val s = String(d, range.first, 4, Charsets.ISO_8859_1)
            if (s == "N...") return null
            if (s[0] != 'S' || s[1] !in "C." || s[2] !in "M." || s[3] !in "U.") fail("malformed submodule field", range.first)
            return SubmoduleStatus(s[1] == 'C', s[2] == 'M', s[3] == 'U')
        }

        private fun changed(from: Int, to: Int): ChangedEntry {
            val f = FieldReader(d, from, to)
            val (x, y) = xy(f.next("XY field"))
            val sub = submodule(f.next("submodule field"))
            val mH = f.mode()
            val mI = f.mode()
            val mW = f.mode()
            val hH = f.hash()
            val hI = f.hash()
            val path = f.rest("path")
            return ChangedEntry(x, y, sub, mH, mI, mW, hH, hI, path(path.first, to))
        }

        private fun renamed(from: Int, to: Int, original: GitPath): RenamedEntry {
            val f = FieldReader(d, from, to)
            val (x, y) = xy(f.next("XY field"))
            val sub = submodule(f.next("submodule field"))
            val mH = f.mode()
            val mI = f.mode()
            val mW = f.mode()
            val hH = f.hash()
            val hI = f.hash()
            val scoreField = f.next("rename score")
            val kind = when (d[scoreField.first].toInt().toChar()) {
                'R' -> RenameKind.RENAME
                'C' -> RenameKind.COPY
                else -> fail("malformed rename score", scoreField.first)
            }
            val score = d.parseCount(scoreField.first + 1, scoreField.last + 1, "rename score")
            if (score > 100) fail("rename score out of range", scoreField.first)
            val path = f.rest("path")
            return RenamedEntry(x, y, sub, mH, mI, mW, hH, hI, kind, score, path(path.first, to), original)
        }

        private fun unmerged(from: Int, to: Int): UnmergedEntry {
            val f = FieldReader(d, from, to)
            val xyRange = f.next("XY field")
            if (xyRange.last - xyRange.first != 1) fail("malformed XY field", xyRange.first)
            val conflict = ConflictKind.of(String(d, xyRange.first, 2, Charsets.ISO_8859_1))
                ?: fail("unknown conflict code", xyRange.first)
            val sub = submodule(f.next("submodule field"))
            val m1 = f.mode()
            val m2 = f.mode()
            val m3 = f.mode()
            val mW = f.mode()
            val h1 = f.hash()
            val h2 = f.hash()
            val h3 = f.hash()
            val path = f.rest("path")
            return UnmergedEntry(conflict, sub, m1, m2, m3, mW, h1, h2, h3, path(path.first, to))
        }
    }
}
