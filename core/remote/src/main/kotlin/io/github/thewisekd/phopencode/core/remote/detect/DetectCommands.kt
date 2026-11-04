package io.github.thewisekd.phopencode.core.remote.detect

import io.github.thewisekd.phopencode.core.remote.state.ServerState
import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote

/**
 * The shell text detection runs, for POSIX `sh`. Everything is fixed except the binary path and
 * the state file location, which pass through [ShellQuote]. Nothing here takes or prints a secret:
 * process command lines are matched on the server and only process IDs come back.
 */
internal object DetectCommands {
    /**
     * Where opencode's installers and package managers put the binary, after the home install
     * folder of the official script (`~/.opencode/bin`), which a non-interactive SSH shell often
     * lacks on its `PATH`.
     */
    private val KNOWN_LOCATIONS = listOf(
        "\"\$HOME/.opencode/bin/opencode\"",
        "\"\$HOME/bin/opencode\"",
        "\"\$HOME/.local/bin/opencode\"",
        "\"\$HOME/.bun/bin/opencode\"",
        "\"\$HOME/.npm-global/bin/opencode\"",
        "/usr/local/bin/opencode",
        "/usr/bin/opencode",
        "/opt/homebrew/bin/opencode",
        "/home/linuxbrew/.linuxbrew/bin/opencode",
    )

    /** `path <p>` for opencode on the command shell's `PATH`, then `known <p>` per executable file found. */
    val LOCATE: String = """
        p=${'$'}(command -v opencode 2>/dev/null) && [ -n "${'$'}p" ] && printf 'path %s\n' "${'$'}p"
        for f in ${KNOWN_LOCATIONS.joinToString(" ")}; do
          if [ -f "${'$'}f" ] && [ -x "${'$'}f" ]; then printf 'known %s\n' "${'$'}f"; fi
        done
        exit 0
    """.trimIndent()

    /**
     * `command -v opencode` in the account's login shell, which reads the profile files a plain
     * command shell skips. Profiles may print, so the answer is the last line.
     */
    val LOGIN_LOCATE: String = """
        [ -n "${'$'}{SHELL:-}" ] && [ -x "${'$'}SHELL" ] || exit 1
        exec "${'$'}SHELL" -lc 'command -v opencode' </dev/null 2>/dev/null
    """.trimIndent()

    fun version(path: String): String = ShellQuote.quote(path) + " --version"

    /**
     * Prints `absent`, `invalid` (a link, not a regular file, not owned by the login user, or open
     * to group or others), or `present` and then at most [ServerState.MAX_BYTES] + 1 bytes of the
     * file. The state folder is `$XDG_STATE_HOME` when that is absolute, else `~/.local/state`.
     */
    fun readState(location: StateFileLocation): String = """
        case "${'$'}{XDG_STATE_HOME:-}" in /*) d="${'$'}XDG_STATE_HOME" ;; *) d="${'$'}HOME/.local/state" ;; esac
        f="${'$'}d"/${ShellQuote.quote(location.relativePath)}
        if [ ! -e "${'$'}f" ] && [ ! -L "${'$'}f" ]; then printf 'absent\n'; exit 0; fi
        if [ -L "${'$'}f" ] || [ ! -f "${'$'}f" ] || [ ! -O "${'$'}f" ]; then printf 'invalid\n'; exit 0; fi
        m=${'$'}(ls -ld -- "${'$'}f" 2>/dev/null | cut -c5-10)
        if [ "${'$'}m" != '------' ]; then printf 'invalid\n'; exit 0; fi
        printf 'present\n'
        exec head -c ${ServerState.MAX_BYTES + 1} -- "${'$'}f"
    """.trimIndent()

    /*
     * What counts as an `opencode serve` process, in both matchers below:
     * - The program is `opencode` (last path part), or a launcher (`node`, `nodejs`, `bun`,
     *   `python`, `python3`) whose first argument is `opencode`. A launcher with its own options
     *   before the script does not match.
     * - Its subcommand, the first argument after it that does not start with `-`, is `serve`. The
     *   value of `--log-level`, opencode's one global option that takes a separate value, is
     *   skipped. So `opencode run serve` is no server, while `opencode --print-logs serve` is.
     *
     * A process that does not match is ignored; a wrong miss is safe, a wrong match is not.
     */

    /** Reads one NUL-separated command line on stdin; exits 0 when it is an `opencode serve`. */
    val PROC_MATCH: String =
        """tr '\000\n' '\n\001' | awk 'NR == 1 { if ($0 ~ /(^|\/)opencode$/) { o = 1; next } if ($0 ~ /(^|\/)(node|nodejs|bun|python3?)$/) { l = 1; next } exit } NR == 2 && l && !o { if ($0 ~ /(^|\/)opencode$/) { o = 1; next } exit } !o { exit } k { k = 0; next } $0 == "--log-level" { k = 1; next } /^-/ { next } { s = ($0 == "serve"); exit } END { exit s ? 0 : 1 }'"""

    /**
     * Reads `ps -o pid= -o args=` lines; prints the IDs of `opencode serve` processes, by the rule
     * of [PROC_MATCH]. `ps` joins arguments with spaces, so boundaries are lost: a line counts only
     * when, after `serve`, every word is an option or the value right after an `--option` without
     * `=`. So `/tmp/opencode serve helper`, one program whose name holds spaces, does not match.
     * The cost: a server whose path holds a space is missed where there is no `/proc`.
     */
    val PS_MATCH: String =
        """awk '{ o = 0; if ($2 ~ /(^|\/)opencode$/) o = 3; else if ($2 ~ /(^|\/)(node|nodejs|bun|python3?)$/ && $3 ~ /(^|\/)opencode$/) o = 4; if (!o) next; k = 0; for (i = o; i <= NF; i++) { if (k) { k = 0; continue } if (${'$'}i == "--log-level") { k = 1; continue } if (${'$'}i ~ /^-/) continue; break } if (i > NF || ${'$'}i != "serve") next; v = 0; for (j = i + 1; j <= NF; j++) { if (${'$'}j ~ /^-/) { v = (${'$'}j ~ /^--?[A-Za-z0-9-]+$/); continue } if (!v) next; v = 0 } print $1 }'"""

    /**
     * Adds the IDs of `opencode serve` processes to `pids`, matching each NUL-separated
     * `/proc/<pid>/cmdline` with [PROC_MATCH]. The scan counts as working only if it could read
     * its own shell's command line: then it prints `H proc` and sets `found`, else it prints
     * `X proc`, drops what it found and leaves the answer to `ps`.
     */
    val PROC_SCAN: String = """
        seen=''
        for d in /proc/[0-9]*; do
          [ -O "${'$'}d" ] || continue
          if [ "${'$'}{d#/proc/}" = "${'$'}${'$'}" ] && [ -r "${'$'}d/cmdline" ]; then seen=1; fi
          if { $PROC_MATCH; } < "${'$'}d/cmdline" 2>/dev/null; then
            pids="${'$'}pids ${'$'}{d#/proc/}"
          fi
        done
        if [ -n "${'$'}seen" ]; then printf 'H proc\n'; found=1; else printf 'X proc\n'; pids=''; fi
    """.trimIndent()

    /**
     * Sets `pids` from `ps` and [PS_MATCH]. It asks for unlimited width (`-ww`) so a long command
     * keeps its subcommand, and retries without it where `ps` refuses the flag.
     *
     * `ps` loses argument boundaries, so a program named `opencode serve` could pass [PS_MATCH].
     * The process's own name (`comm`, set by the system from the executable, a path on macOS) must
     * therefore be exactly `opencode`. A server run through a launcher such as `node` is missed
     * here; only `/proc` finds those.
     *
     * A `ps` that exits non-zero prints `X ps` and finds nothing, so the scan reads as failed
     * rather than as "no server".
     */
    val PS_SCAN: String = """
        u=${'$'}(id -u)
        if { out=${'$'}(ps -ww -U "${'$'}u" -o pid= -o args= 2>/dev/null) || out=${'$'}(ps -U "${'$'}u" -o pid= -o args= 2>/dev/null); } &&
          { names=${'$'}(ps -U "${'$'}u" -o pid= -o comm= 2>/dev/null); }; then
          printf 'H ps\n'
          found=1
          cands=" ${'$'}(printf '%s\n' "${'$'}out" | $PS_MATCH | tr '\n' ' ') "
          pids=${'$'}(printf '%s\n' "${'$'}names" | awk -v c="${'$'}cands" '{ p = ${'$'}1; n = ${'$'}0; sub(/^ *[0-9]+ /, "", n); sub(/.*\//, "", n); if (n == "opencode" && index(c, " " p " ")) print p }')
        else
          printf 'X ps\n'
        fi
    """.trimIndent()

    /**
     * Prints the listening sockets of the processes in `pids`. A tool counts (`H`) only after it
     * ran successfully; a failed one prints `X`, so the scan never reads "no listener" from a tool
     * that did not work. `lsof` exits 1 both when it finds nothing and on some errors; only the
     * case without an `lsof:` message counts as a run.
     */
    val LISTEN_SCAN: String = """
        if command -v ss >/dev/null 2>&1; then
          if out=${'$'}(ss -ltnp 2>/dev/null); then
            printf 'H ss\n'
            printf '%s\n' "${'$'}{out}" | awk 'NF { print "S " ${'$'}0 }'
          else
            printf 'X ss\n'
          fi
        fi
        if [ -r /proc/net/tcp ]; then
          if rows=${'$'}(awk '${'$'}4 == "0A" { print "N4 " ${'$'}2 " " ${'$'}10 }' /proc/net/tcp 2>/dev/null); then
            printf 'H procnet\n'
            for p in ${'$'}pids; do
              ls -l "/proc/${'$'}p/fd" 2>/dev/null | sed -n 's/.*socket:\[\([0-9][0-9]*\)\]${'$'}/\1/p' | while read -r i; do printf 'T %s %s\n' "${'$'}p" "${'$'}i"; done
            done
            [ -z "${'$'}rows" ] || printf '%s\n' "${'$'}rows"
            if [ -r /proc/net/tcp6 ]; then awk '${'$'}4 == "0A" { print "N6 " ${'$'}2 " " ${'$'}10 }' /proc/net/tcp6 2>/dev/null; fi
          else
            printf 'X procnet\n'
          fi
        fi
        if command -v lsof >/dev/null 2>&1; then
          out=${'$'}(lsof -nP -a -p "${'$'}(echo ${'$'}pids | tr ' ' ',')" -iTCP -sTCP:LISTEN -Fpn 2>&1)
          rc=${'$'}?
          if [ "${'$'}rc" -eq 0 ] || { [ "${'$'}rc" -eq 1 ] && ! printf '%s\n' "${'$'}out" | grep -q '^lsof:'; }; then
            printf 'H lsof\n'
            printf '%s\n' "${'$'}out" | awk '/^[pfn]/ { print "L " ${'$'}0 }'
          else
            printf 'X lsof\n'
          fi
        fi
    """.trimIndent()

    /**
     * Finds the login user's `opencode serve` processes and the TCP sockets they listen on; see
     * [ScanReport] for the output. Processes are matched by [PROC_MATCH], or by [PS_MATCH] where
     * there is no `/proc`. In [PROC_MATCH], `tr` turns each argument into one line, a newline inside
     * an argument into byte 1, so a multi-line argument cannot pose as several.
     */
    val SCAN: String = """
        pids=''
        found=''
        if [ -r /proc/self/cmdline ]; then
        $PROC_SCAN
        fi
        if [ -z "${'$'}found" ] && command -v ps >/dev/null 2>&1; then
        $PS_SCAN
        fi
        for p in ${'$'}pids; do printf 'P %s\n' "${'$'}p"; done
        [ -n "${'$'}pids" ] || exit 0
        $LISTEN_SCAN
        exit 0
    """.trimIndent()
}
