package io.github.thewisekd.phopencode.core.ssh.exec

import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * A process without a terminal survives the end of its channel, so ending it takes its process ID.
 * Every command therefore runs inside a small wrapper that prints its shell's ID as the first line
 * of stdout and then replaces itself with the command, keeping that ID:
 *
 * ```
 * exec sh -c 'printf "phopencode-exec-pid=%s\n" "$$"; exec sh -c "$1" sh' phopencode-exec '<command>'
 * ```
 *
 * OpenSSH starts the login shell of a command without a terminal in a new session, so that ID also
 * names the process group, and killing the group reaches the command's children too.
 *
 * The command runs in `sh`, whatever the login shell is, which matches what
 * [ShellQuote] quotes for. It arrives as one quoted word and is parsed by the inner `sh` only.
 */
internal object ProcessHeader {
    private const val PREFIX = "phopencode-exec-pid="
    private val prefix = PREFIX.encodeToByteArray()
    private const val MAX_DIGITS = 10
    private const val NEWLINE = '\n'.code

    fun wrap(command: String): String =
        "exec sh -c 'printf \"$PREFIX%s\\n\" \"\$\$\"; exec sh -c \"\$1\" sh' phopencode-exec " +
            ShellQuote.quote(command)

    /**
     * Kills the process group led by [pid], in whatever shell the account has. The POSIX form with
     * `--` comes first; shells whose `kill` builtin does not take `--` (some older dash releases)
     * get the XSI form `-KILL -<group>`. Only if the group cannot be signalled at all is the single
     * process killed. The ID is a number parsed by [read], never text from a caller.
     */
    fun killCommand(pid: Long): String {
        require(pid > 1) { "Not a process ID" }
        return "kill -s KILL -- -$pid 2>/dev/null || kill -KILL -$pid 2>/dev/null || kill -s KILL $pid 2>/dev/null"
    }

    /**
     * @property pid Null when stdout did not start with the header, for example on a server whose
     *   shell is not POSIX; such a process can only be ended by closing its channel.
     * @property leftover Bytes read while looking for the header that are the command's own output.
     */
    class Header(val pid: Long?, val leftover: ByteArray)

    /** Reads the header from the start of stdout, at most a few dozen bytes. Blocks until then. */
    fun read(input: InputStream): Header {
        val parser = Parser()
        while (true) parser.feed(input.read())?.let { return it }
    }

    /**
     * Decides the header one byte at a time. It keeps its place, so a read that was interrupted
     * can be continued later by another thread without losing a byte.
     */
    class Parser {
        private val seen = ByteArrayOutputStream(prefix.size + MAX_DIGITS + 1)
        private var matched = 0
        private var pid = 0L
        private var digits = 0

        /** Takes the next byte of stdout, or -1 at its end; returns the header once it is decided. */
        fun feed(next: Int): Header? {
            if (next < 0) return Header(null, seen.toByteArray())
            seen.write(next)
            if (matched < prefix.size) {
                if (next.toByte() != prefix[matched]) return Header(null, seen.toByteArray())
                matched++
                return null
            }
            if (next == NEWLINE && digits > 0) return Header(pid.takeIf { it > 1 }, ByteArray(0))
            if (next !in '0'.code..'9'.code || digits == MAX_DIGITS) return Header(null, seen.toByteArray())
            pid = pid * 10 + (next - '0'.code)
            digits++
            return null
        }
    }
}
