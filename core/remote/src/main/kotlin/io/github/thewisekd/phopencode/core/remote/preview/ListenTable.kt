package io.github.thewisekd.phopencode.core.remote.preview

import io.github.thewisekd.phopencode.core.remote.detect.ListenerAddress
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Reads listening ports from the output of [PortListCommands], keeping only those reachable on the
 * server's loopback: bound to a loopback address or to a wildcard. No name is ever looked up, and
 * lines that do not fit are skipped.
 */
internal object ListenTable {
    private val WHITESPACE = Regex("""\s+""")
    private const val IPV4_ANY = "0.0.0.0"
    private val IPV6_LITERAL = Regex("""[0-9A-Fa-f:.]+""")
    private const val LOOPBACK_ZONE = "lo"
    private const val MAX_HOST_LENGTH = 64

    /**
     * Lines of `ss -ltnH`: State, Recv-Q, Send-Q, local, peer; some versions drop State. `ss` shows a
     * socket bound to a device (`SO_BINDTODEVICE`) as `%dev`, so a wildcard or loopback bind to any
     * device other than `lo`, such as `0.0.0.0%eth0` or `[::]%eth0`, is dropped here.
     */
    fun fromSs(output: String): Set<Int> {
        val ports = linkedSetOf<Int>()
        for (raw in output.lineSequence()) {
            val fields = raw.trim().split(WHITESPACE)
            val local = when {
                fields.firstOrNull() == "LISTEN" -> fields.getOrNull(3)
                fields.firstOrNull()?.let { it.isNotEmpty() && it.all { c -> c in '0'..'9' } } == true -> fields.getOrNull(2)
                else -> null
            } ?: continue
            val colon = local.lastIndexOf(':')
            if (colon <= 0) continue
            val port = port(local.substring(colon + 1)) ?: continue
            if (reachableOnLoopback(local.substring(0, colon))) ports += port
        }
        return ports
    }

    /**
     * Lines of [PortListCommands.PROC_NET]: one `E <order>` line, then `4|6 <hex address>:<hex port>`.
     * The kernel prints each 32-bit word of an address in the server's own byte order, which `E`
     * gives: `0001` little-endian, `0100` big-endian. Null when the order is missing, unknown or
     * stated more than once differently, because then no address can be read safely.
     *
     * Limitation: these tables do not show device binding (`SO_BINDTODEVICE`), so a wildcard socket
     * bound to a device other than `lo` is kept here, while [fromSs] drops it. No guess or extra
     * command is made for it: such a port fails safely when opened, because the preview tunnel only
     * reaches loopback, and the preview reports that nothing listens on it.
     */
    fun fromProcNet(output: String): Set<Int>? {
        val lines = output.lineSequence().map { it.trimEnd('\r') }.toList()
        val orders = lines.filter { it == "E" || it.startsWith("E ") }.map { it.removePrefix("E").trim() }.toSet()
        val littleEndian = when (orders.singleOrNull()) {
            LITTLE_ENDIAN -> true
            BIG_ENDIAN -> false
            else -> return null
        }
        val ports = linkedSetOf<Int>()
        for (line in lines) {
            val parts = line.split(' ')
            if (parts.size != 2) continue
            val length = when (parts[0]) {
                "4" -> 8
                "6" -> 32
                else -> continue
            }
            val pieces = parts[1].split(':')
            if (pieces.size != 2 || pieces[0].length != length || pieces[1].length != 4) continue
            val bytes = hexBytes(pieces[0]) ?: continue
            val port = hexBytes(pieces[1])?.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
                ?.takeIf { it in 1..MAX_PORT } ?: continue
            if (littleEndian) for (word in bytes.indices step 4) bytes.reverse(word, word + 4)
            val address = try {
                InetAddress.getByAddress(bytes)
            } catch (_: UnknownHostException) {
                continue
            }
            if (address.isLoopbackAddress || address.isAnyLocalAddress) ports += port
        }
        return ports
    }

    /**
     * Whether a socket bound to [host], as `ss` prints it, takes connections to loopback: a loopback
     * address or a wildcard (`*`, `0.0.0.0`, `::`), bracketed or not, IPv4-mapped or not. A scope
     * (`%name`, inside or after the brackets) other than `lo` binds the socket to another
     * interface, so it does not count.
     */
    fun reachableOnLoopback(host: String): Boolean {
        var text = host
        var zone: String? = null
        if (text.startsWith('[')) {
            val close = text.indexOf(']')
            if (close < 0) return false
            val after = text.substring(close + 1)
            if (after.isNotEmpty()) {
                if (!after.startsWith('%')) return false
                zone = after.substring(1)
            }
            text = text.substring(1, close)
        }
        if ('%' in text) {
            if (zone != null) return false
            zone = text.substringAfter('%')
            text = text.substringBefore('%')
        }
        if (zone != null && zone != LOOPBACK_ZONE) return false
        if (text.isEmpty() || text.length > MAX_HOST_LENGTH || ']' in text || '[' in text) return false
        if (text == "*" || text == IPV4_ANY) return true
        if (ListenerAddress.loopbackHost(text) != null) return true
        return ':' in text && IPV6_LITERAL.matches(text) && isIpv6Any(text)
    }

    private fun isIpv6Any(literal: String): Boolean = try {
        // A literal with a colon is parsed, never resolved.
        InetAddress.getByName(literal).isAnyLocalAddress
    } catch (_: UnknownHostException) {
        false
    }

    private fun port(text: String): Int? =
        text.takeIf { it.length in 1..5 && it.all { c -> c in '0'..'9' } }?.toInt()?.takeIf { it in 1..MAX_PORT }

    private fun hexBytes(hex: String): ByteArray? {
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val digits = hex.map { Character.digit(it, 16) }
        return ByteArray(hex.length / 2) { ((digits[it * 2] shl 4) or digits[it * 2 + 1]).toByte() }
    }

    private const val MAX_PORT = 65535

    /** What `od -An -tx2` prints for the bytes `01 00` on each kind of machine. */
    private const val LITTLE_ENDIAN = "0001"
    private const val BIG_ENDIAN = "0100"
}
