package io.github.thewisekd.phopencode.core.ssh.tunnel

import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import javax.net.SocketFactory

/**
 * A tunnel pinned to one [port] on the SSH server's loopback, for a live preview of what a
 * developer serves there. Its sockets are [TunnelSocket]s, so nothing is bound or listened on
 * locally, and each connect opens one `direct-tcpip` channel; a target is refused before anything
 * is sent unless it is `127.0.0.1`, `::1` or `localhost` on [port]. That is narrower than
 * [LoopbackTarget], which the session's general [TunnelSocketFactory] keeps using.
 *
 * The session tracks the tunnel, so closing the session closes it; the tunnel tracks its sockets
 * until they close, so closing it closes them. A closed tunnel refuses to make sockets.
 */
internal class PinnedTunnel private constructor(
    private val session: SshjSession,
    override val port: Int,
) : SshSession.LoopbackTunnel {
    private val sockets = LinkedHashSet<TunnelSocket>()

    /** Guarded by [sockets]. */
    private var closed = false

    private val factory = TunnelSocketFactory(::newSocket)

    init {
        require(port in PORTS) { "Port is out of range" }
    }

    override fun socketFactory(): SocketFactory = factory

    /** Idempotent. Closes every socket of the tunnel; none of these closes blocks. */
    override fun close() {
        val open = synchronized(sockets) {
            if (closed) return
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        session.untrack(this)
        open.forEach { runCatching { it.close() } }
    }

    /** Names no port. */
    override fun toString(): String = "PinnedTunnel(closed=${synchronized(sockets) { closed }})"

    private fun newSocket(): Socket {
        val socket = TunnelSocket(session, ::hostFor, TARGET_REFUSED) { closing -> forget(closing) }
        synchronized(sockets) {
            if (!closed) {
                sockets += socket
                return socket
            }
        }
        socket.close()
        throw SocketException(TUNNEL_CLOSED)
    }

    private fun forget(socket: TunnelSocket) {
        synchronized(sockets) { sockets.remove(socket) }
    }

    /** The host to name to the server, or null when [address] is not a loopback name on [port]. */
    private fun hostFor(address: InetSocketAddress): String? {
        if (address.port != port) return null
        return LoopbackTarget.hostFor(address)?.takeIf { it in HOSTS }
    }

    companion object {
        private val PORTS = 1..65535
        private val HOSTS = setOf("127.0.0.1", "::1", "localhost")
        private const val TARGET_REFUSED = "Tunnel target refused: only the preview's loopback port is allowed"
        private const val TUNNEL_CLOSED = "Tunnel is closed"

        /**
         * Opens a tunnel that closes with [session].
         *
         * @throws IllegalArgumentException when [port] is outside 1..65535.
         * @throws SshException.Disconnected once the session is closed or lost.
         */
        fun open(session: SshjSession, port: Int): PinnedTunnel {
            require(port in PORTS) { "Port is out of range" }
            session.requireConnected()
            val tunnel = PinnedTunnel(session, port)
            // A session that closed in between closes the tunnel at once.
            if (!session.track(tunnel)) throw SshException.Disconnected()
            return tunnel
        }
    }
}
