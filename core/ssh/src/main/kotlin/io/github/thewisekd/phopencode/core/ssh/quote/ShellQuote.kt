package io.github.thewisekd.phopencode.core.ssh.quote

/**
 * Quotes arguments for a command line that a remote POSIX shell will parse, so that each one
 * reaches the command as exactly one word, byte for byte: no word splitting, no expansion of `$()`,
 * backticks, variables, globs or `~`, and no chance to end the command.
 *
 * What it does not do:
 * - **It cannot take away the meaning of a leading `-`.** The shell removes the quotes and the
 *   command still receives `-rf`, which it reads as an option. Quoting protects against the shell,
 *   not against the command's own parser. Put a `--` separator before such arguments at the call
 *   site: `ShellQuote.join(listOf("git", "add", "--", name))`.
 * - It quotes for POSIX shells such as sh, dash, bash and zsh. SSH hands a command line to the
 *   account's login shell, and csh and fish have other rules: fish, for one, reads `\\` inside
 *   single quotes as one backslash.
 * - The result is safe as one word of a command line, and only there. Inside double quotes or
 *   another layer of `sh -c`, it needs quoting again.
 *
 * The command line must reach the server as UTF-8, as it does through
 * [RemoteExec][io.github.thewisekd.phopencode.core.ssh.api.RemoteExec].
 */
object ShellQuote {
    /**
     * Returns [value] as one shell word. Every value is quoted, even a harmless one: single quotes
     * have one rule, and a list of safe characters could only ever be wrong.
     *
     * @throws IllegalArgumentException For a value that cannot arrive unchanged: a NUL byte, which
     *   ends a C string and so cannot be in an argument, or half a surrogate pair, which has no
     *   UTF-8 form and would turn into `?`. The message does not repeat the value.
     */
    fun quote(value: String): String {
        requireRepresentable(value)
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /** Quotes every argument and joins them into a command line. See [quote]. */
    fun join(arguments: Iterable<String>): String = arguments.joinToString(" ", transform = ::quote)

    private fun requireRepresentable(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            require(char != '\u0000') { "Argument contains a NUL byte" }
            if (char.isHighSurrogate() && value.getOrNull(index + 1)?.isLowSurrogate() == true) {
                index += 2
            } else {
                require(!char.isSurrogate()) { "Argument contains half a surrogate pair" }
                index++
            }
        }
    }
}
