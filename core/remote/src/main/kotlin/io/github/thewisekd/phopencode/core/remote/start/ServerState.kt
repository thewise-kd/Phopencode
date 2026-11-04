package io.github.thewisekd.phopencode.core.remote.start

/**
 * What the server keeps about the `opencode serve` it started, so that a later connection can find
 * it again. It holds no password.
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
 * Format: exactly four ASCII lines, each ended by `\n`, in this order and nothing else:
 *
 * ```
 * phopencode-server-state 1
 * url=http://127.0.0.1:<port>
 * port=<port>
 * pid=<pid>
 * ```
 *
 * `<port>` is a decimal in 1..65535 without leading zeros and is the same in both lines; `<pid>` is
 * a decimal greater than 1 without leading zeros. The URL is always plain HTTP on 127.0.0.1. The
 * PID is the process group leader of the server, which runs in its own session.
 */
data class ServerState(val port: Int, val pid: Long) {
    init {
        require(port in PORTS) { "Port is out of range" }
        require(pid > 1) { "Not a process ID" }
    }

    val url: String get() = loopbackUrl(port)

    fun encode(): ByteArray = "$HEADER\nurl=$url\nport=$port\npid=$pid\n".encodeToByteArray()

    companion object {
        const val HEADER = "phopencode-server-state 1"

        /** The state folder relative to the state base; see the class description. */
        const val DIRECTORY = "phopencode"
        const val FILE_NAME = "server.state"

        /** The log of the server with [pid]. */
        fun logName(pid: Long): String = "$LOG_PREFIX$pid$LOG_SUFFIX"

        const val LOG_PREFIX = "server-"
        const val LOG_SUFFIX = ".log"

        /** No valid file is longer; a reader never needs more. */
        const val MAX_BYTES = 128

        private val PORTS = 1..65535
        private val NUMBER = Regex("[1-9][0-9]{0,18}")

        internal fun loopbackUrl(port: Int) = "http://127.0.0.1:$port"

        /** Reads the format above. Anything else, including any extra byte, gives null. */
        fun parse(bytes: ByteArray): ServerState? {
            if (bytes.size > MAX_BYTES) return null
            if (bytes.any { it < 0x20 && it != '\n'.code.toByte() || it >= 0x7f }) return null
            val text = bytes.decodeToString()
            if (!text.endsWith('\n')) return null
            val lines = text.removeSuffix("\n").split('\n')
            if (lines.size != 4 || lines[0] != HEADER) return null
            val port = lines[2].value("port=")?.toIntOrNull()?.takeIf { it in PORTS } ?: return null
            val pid = lines[3].value("pid=")?.toLongOrNull()?.takeIf { it > 1 } ?: return null
            if (lines[1] != "url=" + loopbackUrl(port)) return null
            return ServerState(port, pid)
        }

        private fun String.value(prefix: String): String? =
            if (startsWith(prefix)) removePrefix(prefix).takeIf { NUMBER.matches(it) } else null
    }
}
