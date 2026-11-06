package io.github.thewisekd.phopencode.core.git.parse

/**
 * Parses `git log -z` with the format [FORMAT].
 *
 * Expected command (see [ARGUMENTS]): `git log --no-color --no-show-signature --encoding=UTF-8 -z <FORMAT>`,
 * where `--encoding=UTF-8` overrides a non-UTF-8 `i18n.logOutputEncoding`, since [parse] decodes UTF-8;
 * followed
 * by any revision selection such as `--max-count=50`, `--skip=100`, `<branch>` or `-- <path>`.
 * Options that add output of their own (`--stat`, `--name-status`, `--graph`, `-p`) are not supported.
 *
 * Each commit is ten NUL-separated fields: hash, parent hashes separated by spaces, author name,
 * author email, author time, committer name, committer email, commit time, subject, body; `-z` puts
 * a NUL after every commit, the last one included, so output that does not end in NUL is truncated.
 * Git refuses NUL bytes in commit messages, so the fields cannot contain it.
 */
public object LogParser {
    /** The `--format` argument; times are Unix seconds. */
    public const val FORMAT: String = "--format=%H%x00%P%x00%an%x00%ae%x00%at%x00%cn%x00%ce%x00%ct%x00%s%x00%b"

    /** The arguments after `git` whose output [parse] expects, before any revision selection. */
    public val ARGUMENTS: List<String> =
        listOf("log", "--no-color", "--no-show-signature", "--encoding=UTF-8", "-z", FORMAT)

    /** Parses the raw stdout; empty output is no commits. Throws [GitParseException] when malformed. */
    public fun parse(stdout: ByteArray): List<GitCommit> {
        val commits = ArrayList<GitCommit>()
        val starts = IntArray(FIELDS)
        val ends = IntArray(FIELDS)
        var pos = 0
        while (pos < stdout.size) {
            for (i in 0 until FIELDS) {
                // `-z` terminates every commit, the last one too, so a missing NUL means truncation.
                val end = stdout.indexOf(NUL, pos)
                if (end < 0) fail("truncated commit record", pos)
                starts[i] = pos
                ends[i] = end
                pos = end + 1
            }
            commits += commit(stdout, starts, ends)
        }
        return commits
    }

    private const val FIELDS = 10

    private fun commit(d: ByteArray, s: IntArray, e: IntArray): GitCommit {
        fun text(i: Int) = decodeUtf8(d, s[i], e[i])
        var bodyEnd = e[9]
        while (bodyEnd > s[9] && d[bodyEnd - 1] == LF) bodyEnd--
        return GitCommit(
            hash = d.parseFullHash(s[0], e[0]),
            parents = parents(d, s[1], e[1]),
            authorName = text(2),
            authorEmail = text(3),
            authorTime = d.parseLong(s[4], e[4], "author time"),
            committerName = text(5),
            committerEmail = text(6),
            commitTime = d.parseLong(s[7], e[7], "commit time"),
            subject = text(8),
            body = decodeUtf8(d, s[9], bodyEnd),
        )
    }

    private fun parents(d: ByteArray, from: Int, to: Int): List<String> {
        if (from == to) return emptyList()
        val parents = ArrayList<String>(2)
        var p = from
        while (true) {
            val space = d.indexOf(SPACE, p, to)
            val end = if (space < 0) to else space
            parents += d.parseFullHash(p, end)
            if (space < 0) return parents
            p = space + 1
        }
    }
}
