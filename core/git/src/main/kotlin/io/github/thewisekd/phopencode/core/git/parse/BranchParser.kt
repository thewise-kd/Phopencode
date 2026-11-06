package io.github.thewisekd.phopencode.core.git.parse

/**
 * Parses `git for-each-ref` with the format [FORMAT].
 *
 * Expected command (see [ARGUMENTS]): `git for-each-ref <FORMAT> refs/heads refs/remotes`, run with
 * `LC_ALL=C` is advisable though `for-each-ref` does not translate its tracking words. Each ref is one
 * line of six NUL-separated fields: ref name, `*` or a space for the current branch, object name,
 * upstream ref (empty when none), `%(upstream:track,nobracket)` (`ahead 1, behind 2`, `ahead 1`,
 * `behind 2`, `gone`, or empty when in sync or without upstream), and the symbolic target (empty for
 * ordinary refs). Ref names cannot contain newlines or NUL, so lines are unambiguous.
 *
 * A repository with no commits has no branches here; its branch name comes from [StatusParser].
 */
public object BranchParser {
    /** The `--format` argument. */
    public const val FORMAT: String =
        "--format=%(refname)%00%(HEAD)%00%(objectname)%00%(upstream)%00%(upstream:track,nobracket)%00%(symref)"

    /** The arguments after `git` whose output [parse] expects. */
    public val ARGUMENTS: List<String> = listOf("for-each-ref", FORMAT, "refs/heads", "refs/remotes")

    /** Parses the raw stdout; empty output is no branches. Throws [GitParseException] when malformed. */
    public fun parse(stdout: ByteArray): List<GitBranch> {
        val branches = ArrayList<GitBranch>()
        var pos = 0
        while (pos < stdout.size) {
            val end = stdout.indexOf(LF, pos)
            if (end < 0) fail("unterminated ref line", pos)
            branches += line(stdout, pos, end)
            pos = end + 1
        }
        return branches
    }

    private const val FIELDS = 6
    private val HEADS = ascii("refs/heads/")
    private val REMOTES = ascii("refs/remotes/")
    private val REFS = ascii("refs/")

    private fun line(d: ByteArray, from: Int, to: Int): GitBranch {
        val starts = IntArray(FIELDS)
        val ends = IntArray(FIELDS)
        var p = from
        for (i in 0 until FIELDS) {
            val nul = d.indexOf(NUL, p, to)
            val end = if (i == FIELDS - 1) {
                if (nul >= 0) fail("too many fields in ref line", nul)
                to
            } else {
                if (nul < 0) fail("too few fields in ref line", p)
                nul
            }
            starts[i] = p
            ends[i] = end
            p = end + 1
        }
        val kind = when {
            d.startsWith(HEADS, starts[0], ends[0]) && ends[0] > starts[0] + HEADS.size -> BranchKind.LOCAL
            d.startsWith(REMOTES, starts[0], ends[0]) && ends[0] > starts[0] + REMOTES.size -> BranchKind.REMOTE
            else -> fail("not a branch ref", starts[0])
        }
        val current = when {
            ends[1] - starts[1] != 1 -> fail("malformed HEAD marker", starts[1])
            d[starts[1]] == '*'.code.toByte() -> true
            d[starts[1]] == SPACE -> false
            else -> fail("malformed HEAD marker", starts[1])
        }
        val hash = d.parseFullHash(starts[2], ends[2])
        val upstream = optionalRef(d, starts[3], ends[3])
        val state = if (upstream == null) {
            if (ends[4] > starts[4]) fail("tracking state without upstream", starts[4])
            null
        } else {
            track(decodeUtf8(d, starts[4], ends[4]), starts[4])
        }
        return GitBranch(
            refName = decodeUtf8(d, starts[0], ends[0]),
            kind = kind,
            isCurrent = current,
            hash = hash,
            upstream = upstream,
            upstreamState = state,
            symrefTarget = optionalRef(d, starts[5], ends[5]),
        )
    }

    private fun optionalRef(d: ByteArray, from: Int, to: Int): String? = when {
        from == to -> null
        d.startsWith(REFS, from, to) && to > from + REFS.size -> decodeUtf8(d, from, to)
        else -> fail("malformed ref name", from)
    }

    private val TRACK = Regex("""(?:ahead ([0-9]{1,9}))?(?:, )?(?:behind ([0-9]{1,9}))?""")

    private fun track(text: String, offset: Int): UpstreamState {
        if (text == "gone") return UpstreamState.Gone
        val match = TRACK.matchEntire(text) ?: fail("unknown tracking state", offset)
        val ahead = match.groupValues[1]
        val behind = match.groupValues[2]
        val wellFormed = when {
            ahead.isEmpty() && behind.isEmpty() -> text.isEmpty()
            ahead.isNotEmpty() && behind.isNotEmpty() -> text == "ahead $ahead, behind $behind"
            else -> !text.contains(',')
        }
        if (!wellFormed) fail("unknown tracking state", offset)
        return UpstreamState.Tracking(ahead.ifEmpty { "0" }.toInt(), behind.ifEmpty { "0" }.toInt())
    }
}
