package io.github.thewisekd.phopencode.core.remote.state

/**
 * What the server state file says about an `opencode serve` the app started. Server startup
 * (`start/`) writes the file and discovery (`detect/`) reads it; this is the one parser both use.
 * It holds no password.
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
 * a decimal greater than 1 of at most 18 digits without leading zeros. The URL is always plain HTTP
 * on 127.0.0.1. Anything else, such as another version, another host, an extra line or byte, a
 * control or non-ASCII byte, or a file over [MAX_BYTES], makes the file invalid.
 *
 * A valid file is still only a claim: discovery checks that [pid] is a live `opencode serve` of the
 * login user listening on [host]:[port] before it trusts it.
 */
data class ServerState(val port: Int, val pid: Long) {
    init {
        require(port in PORTS) { "Port is out of range" }
        require(pid > 1) { "Not a process ID" }
    }

    /** The server always binds the IPv4 loopback. */
    val host: String get() = HOST

    val url: String get() = loopbackUrl(port)

    companion object {
        const val HEADER: String = "phopencode-server-state 1"

        /** No valid file is longer; a reader never needs more, and a longer one is refused unread. */
        const val MAX_BYTES: Int = 128

        private const val HOST = "127.0.0.1"
        private val PORTS = 1..65535
        private val PORT_TEXT = Regex("[1-9][0-9]{0,4}")
        private val PID_TEXT = Regex("[1-9][0-9]{0,17}")

        fun loopbackUrl(port: Int): String = "http://$HOST:$port"

        /** Null unless [bytes] are exactly the format described on [ServerState]. */
        fun parse(bytes: ByteArray): ServerState? {
            if (bytes.size > MAX_BYTES) return null
            if (bytes.any { it < 0x20 && it != NEWLINE || it >= 0x7f }) return null
            return parseAscii(bytes.decodeToString())
        }

        /** As the byte form, for text read through a shell. */
        fun parse(text: String): ServerState? {
            if (text.length > MAX_BYTES) return null
            return parse(text.encodeToByteArray())
        }

        private fun parseAscii(text: String): ServerState? {
            if (!text.endsWith('\n')) return null
            val lines = text.removeSuffix("\n").split('\n')
            if (lines.size != 4 || lines[0] != HEADER) return null
            val port = lines[2].value("port=", PORT_TEXT)?.toIntOrNull()?.takeIf { it in PORTS } ?: return null
            val pid = lines[3].value("pid=", PID_TEXT)?.toLongOrNull()?.takeIf { it > 1 } ?: return null
            if (lines[1] != "url=" + loopbackUrl(port)) return null
            return ServerState(port, pid)
        }

        private fun String.value(prefix: String, format: Regex): String? =
            if (startsWith(prefix)) removePrefix(prefix).takeIf(format::matches) else null

        private const val NEWLINE = '\n'.code.toByte()
    }
}
