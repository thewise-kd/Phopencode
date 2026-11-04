package io.github.thewisekd.phopencode.core.remote.detect

/**
 * What the server state file says about an `opencode serve` the app started. The file is written
 * by server startup and holds exactly four lines, each ending in `\n`, with decimal numbers without
 * leading zeros:
 *
 * ```
 * phopencode-server-state 1
 * url=http://127.0.0.1:<port>
 * port=<port>
 * pid=<pid>
 * ```
 *
 * Anything else, such as another version, another host, an extra line or a URL whose port differs,
 * makes the file invalid. Detection does not trust a valid file either: it checks that [pid] is a
 * live `opencode serve` of the login user and that this process listens on [host]:[port].
 */
data class ServerState(val pid: Long, val port: Int) {
    init {
        require(pid > 0) { "Process ID must be positive" }
        require(port in PORTS) { "Port is out of range" }
    }

    /** The server always binds the IPv4 loopback. */
    val host: String get() = HOST

    /** The file's exact content. */
    fun toText(): String = "$HEADER\nurl=http://$HOST:$port\nport=$port\npid=$pid\n"

    companion object {
        /** A larger file is refused unread. */
        const val MAX_BYTES: Int = 256

        private const val HEADER = "phopencode-server-state 1"
        private const val HOST = "127.0.0.1"
        private val PORTS = 1..65535
        private val FORMAT = Regex(
            "phopencode-server-state 1\n" +
                "url=http://127\\.0\\.0\\.1:([1-9][0-9]{0,4})\n" +
                "port=([1-9][0-9]{0,4})\n" +
                "pid=([1-9][0-9]{0,17})\n",
        )

        /** Null unless [text] is exactly the format described on [ServerState]. */
        fun parse(text: String): ServerState? {
            if (text.length > MAX_BYTES) return null
            val match = FORMAT.matchEntire(text) ?: return null
            val (urlPort, port, pid) = match.destructured
            if (urlPort != port) return null
            val portNumber = port.toInt().takeIf { it in PORTS } ?: return null
            return ServerState(pid.toLong(), portNumber)
        }
    }
}

/**
 * Where the state file lives, relative to the login user's XDG state folder: `$XDG_STATE_HOME`
 * when that is set to an absolute path, else `~/.local/state`. Plain names separated by `/`, no `.`
 * or `..`, nothing absolute. It reaches the shell only through `ShellQuote`.
 */
data class StateFileLocation(val relativePath: String) {
    init {
        require(relativePath.length in 1..MAX_LENGTH) { "State file path is empty or too long" }
        require(!relativePath.startsWith('/')) { "State file path must be relative to the state folder" }
        require(relativePath.none { it < ' ' || it == '\u007F' }) { "State file path contains a control character" }
        require(relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "State file path must consist of plain names"
        }
    }

    companion object {
        private const val MAX_LENGTH = 1024

        /** `phopencode/server.state` in the XDG state folder, where server startup writes it. */
        val DEFAULT: StateFileLocation = StateFileLocation("phopencode/server.state")
    }
}
