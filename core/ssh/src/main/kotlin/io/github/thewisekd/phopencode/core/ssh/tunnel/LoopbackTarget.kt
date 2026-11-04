package io.github.thewisekd.phopencode.core.ssh.tunnel

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress

/**
 * Which addresses a tunnel may reach: only the SSH server's own loopback, where `opencode serve`
 * listens. A tunnel to anything else would turn the phone into a way into the server's network,
 * so it is refused before a byte is sent.
 *
 * No name is ever looked up here. A resolved address must be a loopback address; an unresolved one
 * must be `localhost` or a plain loopback literal (`127.x.y.z` in decimal, or `::1`), which the SSH
 * server resolves on its side.
 */
internal object LoopbackTarget {
    /** The host to name in the `direct-tcpip` request, or null when [address] is refused. */
    fun hostFor(address: InetSocketAddress): String? {
        if (address.port !in PORTS) return null
        val resolved = address.address
        if (resolved != null) {
            return when {
                !resolved.isLoopbackAddress -> null
                resolved is Inet4Address -> resolved.hostAddress
                resolved is Inet6Address -> IPV6_LOOPBACK
                else -> null
            }
        }
        val name = address.hostString.orEmpty()
        return when {
            name.equals(LOCALHOST, ignoreCase = true) -> LOCALHOST
            name == IPV6_LOOPBACK || name == "[$IPV6_LOOPBACK]" -> IPV6_LOOPBACK
            isIpv4Loopback(name) -> name
            else -> null
        }
    }

    /** Four decimal parts without leading zeros, each at most 255, the first one 127. */
    private fun isIpv4Loopback(name: String): Boolean {
        val parts = name.split('.')
        if (parts.size != 4) return false
        val values = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return false
            if (part.length > 1 && part[0] == '0') return false
            part.toInt().takeIf { it <= 255 } ?: return false
        }
        return values[0] == 127
    }

    private val PORTS = 1..65535
    private const val LOCALHOST = "localhost"
    private const val IPV6_LOOPBACK = "::1"
}
