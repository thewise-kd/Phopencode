package io.github.thewisekd.phopencode.core.remote.detect

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A listening TCP socket the scan saw.
 *
 * @property pid Null when no tool tied the socket to a process.
 * @property host Without brackets or `%interface`; a loopback address in the plain form of
 *   [ListenerAddress.loopbackHost], anything else as the tool printed it.
 */
internal data class Listener(val pid: Long?, val host: String, val port: Int)

/**
 * What [DetectCommands.SCAN] printed, one tagged line each:
 *
 * - `H <tool>`: a tool ran. `proc` and `ps` find processes; `ss`, `procnet` and `lsof` find listeners.
 * - `X <tool>`: a tool was there but failed. It counts as missing, so a failed process listing
 *   reads as an unknown answer, never as "no server".
 * - `P <pid>`: an `opencode serve` process of the login user. Matching happens on the server, so no
 *   command line ever leaves it.
 * - `S <line>`: a line of `ss -ltnp`.
 * - `T <pid> <inode>`: a socket that process holds open.
 * - `N4|N6 <hex address>:<hex port> <inode>`: a listening row of `/proc/net/tcp` or `tcp6`.
 * - `L <field>`: an `lsof -F pn` field line.
 *
 * Lines that do not fit are skipped.
 */
internal class ScanReport(
    val processToolSeen: Boolean,
    val listenerToolSeen: Boolean,
    val processes: Set<Long>,
    val listeners: List<Listener>,
) {
    companion object {
        private val PROCESS_TOOLS = setOf("proc", "ps")
        private val LISTENER_TOOLS = setOf("ss", "procnet", "lsof")
        private val SS_PID = Regex("""pid=([0-9]{1,10})[,)]""")
        private val WHITESPACE = Regex("""\s+""")

        fun parse(output: String): ScanReport {
            val tools = mutableSetOf<String>()
            val processes = linkedSetOf<Long>()
            val listeners = mutableListOf<Listener>()
            val inodeOwners = HashMap<String, Long>()
            val tableRows = mutableListOf<Pair<Listener, String>>()
            var lsofPid: Long? = null

            for (raw in output.lineSequence()) {
                val line = raw.trimEnd('\r')
                val tag = line.substringBefore(' ')
                val rest = line.substringAfter(' ', "")
                when (tag) {
                    "H" -> tools += rest.trim()
                    "P" -> pid(rest)?.let { processes += it }
                    "S" -> listeners += ssListeners(rest)
                    "T" -> {
                        val parts = rest.split(' ')
                        val pid = pid(parts.getOrNull(0).orEmpty())
                        val inode = parts.getOrNull(1)?.takeIf(::isInode)
                        if (parts.size == 2 && pid != null && inode != null) inodeOwners[inode] = pid
                    }
                    "N4", "N6" -> tableRow(rest, tag == "N6")?.let { tableRows += it }
                    "L" -> when {
                        rest.startsWith('p') -> lsofPid = pid(rest.substring(1))
                        rest.startsWith('n') -> endpoint(rest.substring(1))?.let { (host, port) ->
                            listeners += Listener(lsofPid, host, port)
                        }
                    }
                }
            }
            tableRows.forEach { (listener, inode) -> listeners += listener.copy(pid = inodeOwners[inode]) }
            return ScanReport(
                processToolSeen = tools.any { it in PROCESS_TOOLS },
                listenerToolSeen = tools.any { it in LISTENER_TOOLS },
                processes = processes,
                listeners = listeners,
            )
        }

        private fun pid(text: String): Long? =
            text.takeIf { it.length in 1..MAX_PID_DIGITS && it.all { c -> c in '0'..'9' } }?.toLong()?.takeIf { it > 0 }

        private fun isInode(text: String): Boolean = text.length in 1..20 && text.all { it in '0'..'9' }

        /** `ss -ltnp` prints State, Recv-Q, Send-Q, local, peer, then the processes; some versions drop State. */
        private fun ssListeners(line: String): List<Listener> {
            val fields = line.trim().split(WHITESPACE)
            val local = when {
                fields.firstOrNull() == "LISTEN" -> fields.getOrNull(3)
                fields.firstOrNull()?.all { it in '0'..'9' } == true -> fields.getOrNull(2)
                else -> null
            } ?: return emptyList()
            val (host, port) = endpoint(local) ?: return emptyList()
            val pids = SS_PID.findAll(line).mapNotNull { pid(it.groupValues[1]) }.distinct().toList()
            return if (pids.isEmpty()) listOf(Listener(null, host, port)) else pids.map { Listener(it, host, port) }
        }

        /** `host:port`, where the host may be bracketed, carry `%interface`, or be `*`. */
        private fun endpoint(text: String): Pair<String, Int>? {
            val colon = text.lastIndexOf(':')
            if (colon <= 0) return null
            val portText = text.substring(colon + 1)
            if (portText.length !in 1..5 || !portText.all { it in '0'..'9' }) return null
            val port = portText.toInt().takeIf { it in 1..65535 } ?: return null
            val host = ListenerAddress.clean(text.substring(0, colon)) ?: return null
            return host to port
        }

        /** Little-endian words, as Linux prints them on the machines this app meets. */
        private fun tableRow(rest: String, ipv6: Boolean): Pair<Listener, String>? {
            val parts = rest.split(' ')
            if (parts.size != 2 || !isInode(parts[1])) return null
            val (addressHex, portHex) = parts[0].split(':').takeIf { it.size == 2 } ?: return null
            if (addressHex.length != if (ipv6) 32 else 8) return null
            if (portHex.length != 4) return null
            val bytes = hexBytes(addressHex) ?: return null
            val port = portHex.toIntOrNull(16)?.takeIf { it in 1..65535 } ?: return null
            for (word in bytes.indices step 4) bytes.reverse(word, word + 4)
            val address = try {
                InetAddress.getByAddress(bytes)
            } catch (_: UnknownHostException) {
                return null
            }
            val host = ListenerAddress.clean(address.hostAddress) ?: return null
            return Listener(null, host, port) to parts[1]
        }

        private fun hexBytes(hex: String): ByteArray? {
            val digits = hex.map { Character.digit(it, 16) }
            if (digits.any { it < 0 }) return null
            return ByteArray(hex.length / 2) { ((digits[it * 2] shl 4) or digits[it * 2 + 1]).toByte() }
        }

        private const val MAX_PID_DIGITS = 10
    }
}

/** Reads listener addresses without ever looking a name up. */
internal object ListenerAddress {
    private val IPV6_LITERAL = Regex("""[0-9A-Fa-f:.]+""")

    /**
     * The loopback address [text] names, as `127.x.y.z` or `::1`, or null for anything else,
     * including wildcards, names and malformed literals. Brackets and `%interface` are allowed; an
     * IPv4-mapped loopback reads as its IPv4 form.
     */
    fun loopbackHost(text: String): String? {
        val bare = strip(text) ?: return null
        if (':' !in bare) return bare.takeIf { isIpv4Loopback(it) }
        if (!IPV6_LITERAL.matches(bare)) return null
        val address = try {
            // A literal with a colon is parsed, never resolved.
            InetAddress.getByName(bare)
        } catch (_: UnknownHostException) {
            return null
        }
        return when {
            !address.isLoopbackAddress -> null
            address is Inet4Address -> address.hostAddress.takeIf(::isIpv4Loopback)
            address is Inet6Address -> "::1"
            else -> null
        }
    }

    /** [text] without brackets or zone: the loopback form when it is one, else as printed. */
    fun clean(text: String): String? {
        loopbackHost(text)?.let { return it }
        return strip(text)?.takeIf { it.isNotEmpty() }
    }

    private fun strip(text: String): String? {
        var host = text
        if (host.startsWith('[')) {
            if (!host.endsWith(']')) return null
            host = host.substring(1, host.length - 1)
        }
        host = host.substringBefore('%')
        if (host.isEmpty() || host.length > MAX_LENGTH || host.any { it <= ' ' || it == '\u007F' }) return null
        return host
    }

    /** Four decimal parts without leading zeros, each at most 255, the first one 127. */
    private fun isIpv4Loopback(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        val values = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return false
            if (part.length > 1 && part[0] == '0') return false
            part.toInt().takeIf { it <= 255 } ?: return false
        }
        return values[0] == 127
    }

    private const val MAX_LENGTH = 64
}
