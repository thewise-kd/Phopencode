package io.github.thewisekd.phopencode.core.remote.start

import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote
import kotlin.time.Duration

/**
 * The shell side of [ServerStarter]. Every script is a constant handed to `sh -c` as one quoted
 * word, and every variable part follows as a separate quoted argument, so nothing a caller passes
 * is ever parsed as shell code. No script ever holds a secret.
 *
 * The scripts are `$$$` raw strings: `$` and `$$` are the shell's, `$$$` is Kotlin's.
 */
internal object StartScript {
    /** The longest ready line the script reports; a longer one is cut and so refused. */
    const val MAX_READY_CHARS = 512

    /**
     * Defines `phopencode_stop <pid>`, which stops a server and everything it started:
     *
     * 1. Refuses anything but a decimal PID above 1.
     * 2. Takes the process tree below the PID from `ps -A -o pid= -o ppid=`, or from
     *    `/proc/<pid>/stat` where `ps` is missing, before any signal, so children that are
     *    reparented once their parent dies are still known.
     * 3. Sends TERM to the children, then to the PID's process group (the server leads one when it
     *    was started with `setsid` or shell job control), then to the PID itself.
     * 4. Waits up to 3 s, polling `kill -0`, and sends KILL the same way to whatever is left.
     *
     * Every `kill` has the forms that dash, bash and busybox ash accept, as in core/ssh's
     * `ProcessHeader`. Without `setsid`, job control, `ps` and `/proc`, only the PID itself and its
     * group can be signalled, and children the server started in other groups may remain.
     */
    private val STOP_FUNCTION: String = $$$"""
        phopencode_stop() {
          s_root=$1
          case $s_root in ''|*[!0-9]*|0|1) return 1 ;; esac
          s_snap=$(ps -A -o pid= -o ppid= 2>/dev/null)
          if [ -z "$s_snap" ]; then
            s_snap=$(awk '{ split(FILENAME, f, "/"); sub(/.*\) /, ""); print f[3], $2 }' /proc/[0-9]*/stat 2>/dev/null)
          fi
          s_all=$s_root s_todo=$s_root s_depth=0
          while [ -n "$s_todo" ] && [ "$s_depth" -lt 64 ]; do
            s_next=
            for s_parent in $s_todo; do
              s_next="$s_next $(printf '%s\n' "$s_snap" | awk -v p="$s_parent" '$2 == p { print $1 }')"
            done
            s_todo=$(echo $s_next)
            s_all="$s_all $s_todo"
            s_depth=$((s_depth + 1))
          done
          if sleep 0.1 2>/dev/null; then s_nap=0.1 s_tries=30; else s_nap=1 s_tries=3; fi
          phopencode_signal TERM
          while [ "$s_tries" -gt 0 ] && phopencode_alive; do
            sleep "$s_nap"
            s_tries=$((s_tries - 1))
          done
          if phopencode_alive; then phopencode_signal KILL; fi
          return 0
        }
        phopencode_signal() {
          for s_pid in $s_all; do
            [ "$s_pid" = "$s_root" ] || kill -s "$1" "$s_pid" 2>/dev/null
          done
          kill -s "$1" -- "-$s_root" 2>/dev/null || kill -"$1" "-$s_root" 2>/dev/null
          kill -s "$1" "$s_root" 2>/dev/null
        }
        phopencode_alive() {
          kill -0 -- "-$s_root" 2>/dev/null && return 0
          kill -0 "-$s_root" 2>/dev/null && return 0
          for s_pid in $s_all; do kill -0 "$s_pid" 2>/dev/null && return 0; done
          return 1
        }
    """.trimIndent() + "\n"

    /** Stops the server whose PID is the first argument; see [STOP_FUNCTION]. */
    val STOP_SCRIPT: String = STOP_FUNCTION + "phopencode_stop \"\$1\"\n"

    /** Sets `dir` to the state folder; see [ServerState]. */
    private val STATE_BASE: String = $$$"""
        case ${XDG_STATE_HOME-} in /*) base=$XDG_STATE_HOME ;; *) base=$HOME/.local/state ;; esac
        dir=$base/$$${ServerState.DIRECTORY}
    """.trimIndent() + "\n"

    /**
     * Starts the server. Arguments: binary, port (empty for the server's default), seconds to wait.
     *
     * 1. Finds the binary: a path is used as given; a bare name is looked up on `PATH`, and for
     *    `opencode` also in `~/.opencode/bin`, which non-interactive SSH often leaves off `PATH`.
     *    A relative answer from `command -v`, from a relative or empty `PATH` entry, is made
     *    absolute against the login folder (`pwd -P`) and used if it is an executable file.
     * 2. Prepares the state folder (0700), removes logs of processes that no longer run, and
     *    creates this start's own log `start-<shell PID>.log` (0600); see [ServerState].
     * 3. Reads the password as one line from stdin into the environment of this shell only; it is
     *    never an argument, so it is not in any process's command line.
     * 4. Starts `opencode serve --hostname 127.0.0.1` in a subshell, under `setsid` where present,
     *    so the server leads its own session and is a child of init: it survives the SSH channel
     *    and the session, and the exec's kill of the wrapper's process group cannot reach it.
     *    Without `setsid` it tries `set -m`, so job control puts the server in its own process
     *    group; shells that refuse job control without a terminal (dash) leave it in the wrapper's
     *    group, still a child of init, and [STOP_FUNCTION] then finds its children by walking the
     *    process tree. Its stdin is /dev/null. Its output goes to this start's log through
     *    descriptor 3, which the wrapper opens before launching and closes after, so the server
     *    inherits an open file: renaming the log to `server-<pid>.log` once the PID is known cannot
     *    race the server opening it, and all output lands in the renamed file.
     * 5. Waits for a line containing `listening on` in that log only, for the server to die, or for
     *    the deadline, and reports one status line. On the deadline it stops the server with
     *    [STOP_FUNCTION]. Only complete lines count: it first counts the newlines in the log, then
     *    searches just that many lines, so a ready line still being written (and a port cut short
     *    in it) is never read; it waits for the rest until the deadline.
     *
     * Output protocol on stdout, one line each: `phopencode-start-pid=<pid>` as soon as the server
     * runs, then exactly one of `phopencode-start-ready=<the log line>`, `phopencode-start=missing`,
     * `phopencode-start=state-dir`, `phopencode-start=port-in-use`, `phopencode-start=exited` or
     * `phopencode-start=timeout`. The ready line is cut to [MAX_READY_CHARS], so the whole output
     * stays far below [ServerStarter.MAX_OUTPUT_BYTES] whatever the server prints. The script
     * always exits 0 after a status line.
     */
    val SCRIPT: String = STOP_FUNCTION + $$$"""
        umask 077
        bin=$1 port=$2 wait=$3
        case $bin in
        */*) [ -f "$bin" ] && [ -x "$bin" ] || bin= ;;
        *) found=$(command -v "$bin" 2>/dev/null)
          case $found in
          /*) bin=$found ;;
          *) relative=$(pwd -P)/$found
            home_bin=$HOME/.opencode/bin/opencode
            if [ -n "$found" ] && [ -f "$relative" ] && [ -x "$relative" ]; then bin=$relative
            elif [ "$bin" = opencode ] && [ -f "$home_bin" ] && [ -x "$home_bin" ]; then bin=$home_bin
            else bin=; fi ;;
          esac ;;
        esac
        if [ -z "$bin" ]; then echo phopencode-start=missing; exit 0; fi
    """.trimIndent() + "\n" + STATE_BASE + $$$"""
        if [ -L "$dir" ] || ! mkdir -p -- "$dir" || ! chmod 700 -- "$dir"; then
          echo phopencode-start=state-dir; exit 0
        fi
        for old in "$dir"/$$${ServerState.LOG_PREFIX}*$$${ServerState.LOG_SUFFIX} "$dir"/start-*.log; do
          [ -f "$old" ] && [ ! -L "$old" ] || continue
          n=${old##*/}; n=${n#$$${ServerState.LOG_PREFIX}}; n=${n#start-}; n=${n%.log}
          case $n in ''|*[!0-9]*) continue ;; esac
          kill -0 "$n" 2>/dev/null || rm -f -- "$old"
        done
        log=$dir/start-$$.log
        rm -f -- "$log"
        if [ -e "$log" ] || [ -L "$log" ] || ! (set -C; : > "$log") || ! chmod 600 -- "$log"; then
          echo phopencode-start=state-dir; exit 0
        fi
        IFS= read -r OPENCODE_SERVER_PASSWORD || { echo phopencode-start=no-password; exit 0; }
        OPENCODE_PASSWORD=$OPENCODE_SERVER_PASSWORD
        export OPENCODE_SERVER_PASSWORD OPENCODE_PASSWORD
        cd "$HOME" 2>/dev/null || cd /
        if [ -n "$port" ]; then set -- serve --hostname 127.0.0.1 --port "$port"
        else set -- serve --hostname 127.0.0.1; fi
        if command -v setsid >/dev/null 2>&1; then detach=setsid; else detach=; fi
        exec 3>>"$log"
        pid=$( (if [ -z "$detach" ]; then set -m 2>/dev/null; fi
          $detach nohup "$bin" "$@" </dev/null >&3 2>&3 3>&- & echo $!) )
        exec 3>&-
        unset OPENCODE_SERVER_PASSWORD OPENCODE_PASSWORD
        case $pid in ''|*[!0-9]*) echo phopencode-start=exited; exit 0 ;; esac
        echo "phopencode-start-pid=$pid"
        final=$dir/$$${ServerState.LOG_PREFIX}$pid$$${ServerState.LOG_SUFFIX}
        rm -f -- "$final"
        if [ -e "$final" ] || [ -L "$final" ] || ! mv -f -- "$log" "$final"; then
          echo phopencode-start=state-dir; exit 0
        fi
        log=$final
        if sleep 0.1 2>/dev/null; then nap=0.1 per=10; else nap=1 per=1; fi
        left=$((wait * per))
        while :; do
          done_lines=$(wc -l < "$log" 2>/dev/null | tr -cd 0-9)
          line=
          if [ "${done_lines:-0}" -gt 0 ]; then
            line=$(sed -n "1,${done_lines}{/listening on /{p;q;};}" "$log" 2>/dev/null | cut -c1-$$${MAX_READY_CHARS})
          fi
          if [ -n "$line" ]; then printf 'phopencode-start-ready=%s\n' "$line"; exit 0; fi
          if ! kill -0 "$pid" 2>/dev/null; then
            if grep -Eiq 'eaddrinuse|address already in use|port.*in use' "$log" 2>/dev/null
            then echo phopencode-start=port-in-use; else echo phopencode-start=exited; fi
            exit 0
          fi
          if [ "$left" -le 0 ]; then
            phopencode_stop "$pid"
            echo phopencode-start=timeout; exit 0
          fi
          left=$((left - 1))
          sleep $nap
        done
    """.trimIndent() + "\n"

    /**
     * Writes [ServerState] from stdin atomically: `umask 077`, a temporary file created with
     * noclobber, `chmod 600`, then `mv` over the old file. A state path that is a symlink or
     * anything but a regular file is refused before the rename, and the result is checked after it,
     * because `mv` onto a directory would move the file into it and still succeed. Exits non-zero
     * if anything fails.
     */
    private val WRITE_STATE_SCRIPT: String = "umask 077\nset -C\n" + STATE_BASE + $$$"""
        if [ -L "$dir" ] || ! mkdir -p -- "$dir" || ! chmod 700 -- "$dir"; then exit 1; fi
        target=$dir/$$${ServerState.FILE_NAME}
        if [ -L "$target" ] || { [ -e "$target" ] && [ ! -f "$target" ]; }; then exit 1; fi
        tmp=$dir/.$$${ServerState.FILE_NAME}.$$
        if cat > "$tmp" && chmod 600 -- "$tmp" && mv -f -- "$tmp" "$target" &&
          [ -f "$target" ] && [ ! -L "$target" ] && [ ! -e "$tmp" ]; then exit 0; fi
        rm -f -- "$tmp"
        exit 1
    """.trimIndent() + "\n"

    val WRITE_STATE_COMMAND: String = ShellQuote.join(listOf("sh", "-c", WRITE_STATE_SCRIPT, "phopencode-state"))

    fun startCommand(options: ServerStartOptions): String = ShellQuote.join(
        listOf(
            "sh",
            "-c",
            SCRIPT,
            "phopencode-start",
            options.binary,
            options.port?.toString() ?: "",
            waitSeconds(options.timeout).toString(),
        ),
    )

    /** Stops the server and what it started, TERM first and KILL after a grace; see [STOP_FUNCTION]. */
    fun stopCommand(pid: Long): String {
        require(pid > 1) { "Not a process ID" }
        return ShellQuote.join(listOf("sh", "-c", STOP_SCRIPT, "phopencode-stop", pid.toString()))
    }

    /** Whole seconds, rounded up, at least one. */
    fun waitSeconds(timeout: Duration): Long = ((timeout.inWholeMilliseconds + MILLIS - 1) / MILLIS).coerceAtLeast(1)

    private const val MILLIS = 1000L

    /**
     * Matches the ready line of opencode 2 (`server listening on <url>`) and 1
     * (`opencode server listening on <url>`) with a URL that is plain HTTP on 127.0.0.1 and nothing
     * else; an optional trailing slash is accepted.
     */
    private val READY = Regex("""(?:opencode )?server listening on http://127\.0\.0\.1:([1-9][0-9]{0,4})/?""")

    /**
     * The port from a ready line, or null if the line is anything other than the exact form above,
     * the port is out of range, or it differs from [expected] when one was requested.
     */
    fun readyPort(line: String, expected: Int?): Int? {
        val match = READY.matchEntire(line.trim()) ?: return null
        val port = match.groupValues[1].toInt()
        if (port !in 1..65535) return null
        if (expected != null && port != expected) return null
        return port
    }
}
