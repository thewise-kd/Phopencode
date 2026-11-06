package io.github.thewisekd.phopencode.core.git.command

/**
 * Checks the names that reach git as plain arguments, where `--` cannot protect them because they
 * come before it. A name that passes can never be read as an option, a refspec that forces or
 * deletes, a URL or a path. The checks are pure, so the UI can use them to validate input early.
 *
 * Callers that build commands do not need to call these: every [GitCommands] function checks its
 * own input and throws [IllegalArgumentException] with a fixed message.
 */
object GitNames {
    /** Longest name accepted, in UTF-16 units: far beyond any real ref, short of abuse. */
    const val MAX_LENGTH: Int = 1024

    private const val REF_FORBIDDEN = " ~^:?*[\\"

    /**
     * True for a branch name that git's `check-ref-format --branch` rules allow and that is safe as
     * an argument: no leading `-` (an option) or `+` (a forcing refspec), no `:` (a refspec that
     * deletes or renames), no whitespace or control characters, no `..` or `@{` (ranges and
     * reflog syntax), not `HEAD` or `@`, and no component that starts with `.` or ends with `.lock`.
     */
    fun isValidBranchName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_LENGTH) return false
        if (name[0] == '-' || name[0] == '+') return false
        if (name == "HEAD" || name == "@") return false
        if (name.any { it.isAsciiControl() || it in REF_FORBIDDEN }) return false
        if (hasLoneSurrogate(name)) return false
        if (".." in name || "@{" in name || "//" in name) return false
        if (name.startsWith('/') || name.endsWith('/') || name.endsWith('.')) return false
        return name.split('/').none { it.startsWith('.') || it.endsWith(".lock") }
    }

    /**
     * True for a remote name such as `origin` that cannot be read as a path or URL. On top of the
     * rules of [isValidBranchName] (which already exclude `:`, `\`, `~`, `..` and a leading `.`),
     * it rejects `/`, so a name is a single path component and never `host:path`, `ext::`, an
     * absolute or a relative path. Remotes whose names contain `/` are therefore unsupported.
     *
     * A valid name can still be the name of a folder inside the repository; only a configured
     * remote is safe, so callers take names from [GitCommands.remoteList].
     */
    fun isValidRemoteName(name: String): Boolean =
        isValidBranchName(name) && '/' !in name && name[0] != '.' && name[0] != '~'

    /**
     * True for a revision expression: a ref, a hash, `HEAD~2`, `a..b`, `rev:path` and so on. It
     * must not start with `-`, so git can never read it as an option, and holds no control
     * characters. Anything else is git's to resolve; it reaches git quoted as one word.
     */
    fun isValidRevision(revision: String): Boolean =
        revision.isNotEmpty() &&
            revision.length <= MAX_LENGTH &&
            revision[0] != '-' &&
            revision.none { it.isAsciiControl() } &&
            !hasLoneSurrogate(revision)

    /**
     * True for a path that can reach git as one argument after `--`: not empty, no NUL byte and
     * representable in UTF-8. Every other character, newlines and a leading `-` included, is fine.
     */
    fun isValidPath(path: String): Boolean = path.isNotEmpty() && '\u0000' !in path && !hasLoneSurrogate(path)

    internal fun hasLoneSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char.isHighSurrogate() && value.getOrNull(index + 1)?.isLowSurrogate() == true) {
                index += 2
            } else {
                if (char.isSurrogate()) return true
                index++
            }
        }
        return false
    }

    private fun Char.isAsciiControl(): Boolean = code < 0x20 || code == 0x7f
}
