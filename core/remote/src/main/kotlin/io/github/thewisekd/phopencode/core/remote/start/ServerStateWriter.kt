package io.github.thewisekd.phopencode.core.remote.start

import io.github.thewisekd.phopencode.core.remote.state.ServerState

/**
 * Where server startup keeps the [ServerState] of the `opencode serve` it started, so that a later
 * connection can find it again. The format and its parser are in [ServerState]; this side writes.
 *
 * Location on the server: `$XDG_STATE_HOME/phopencode/server.state` when `XDG_STATE_HOME` is an
 * absolute path, otherwise `$HOME/.local/state/phopencode/server.state`. The folder is mode 0700,
 * the file 0600; the file is written to a temporary name under `umask 077` and renamed into place,
 * so a reader sees either the old file or the new one. A state path that is a directory or a
 * symlink is refused rather than replaced.
 *
 * Each server's output goes to its own log in the same folder, also 0600: `start-<n>.log` while
 * the start that launched it runs (`<n>` is that start's shell PID), renamed to `server-<pid>.log`
 * once the server's PID is known. A start reads only its own log, so concurrent starts cannot take
 * each other's ready line. A start removes logs whose process is no longer running. With
 * concurrent starts, the last state file written wins.
 *
 * The PID in the file is the process group leader of the server, which runs in its own session.
 */
object StateFolder {
    /** The state folder relative to the state base; see the object description. */
    const val DIRECTORY = "phopencode"
    const val FILE_NAME = "server.state"

    const val LOG_PREFIX = "server-"
    const val LOG_SUFFIX = ".log"

    /** The log of the server with [pid]. */
    fun logName(pid: Long): String = "$LOG_PREFIX$pid$LOG_SUFFIX"
}

/** The state file's exact content, as [ServerState.parse] reads it. */
internal fun ServerState.encode(): ByteArray =
    "${ServerState.HEADER}\nurl=$url\nport=$port\npid=$pid\n".encodeToByteArray()
