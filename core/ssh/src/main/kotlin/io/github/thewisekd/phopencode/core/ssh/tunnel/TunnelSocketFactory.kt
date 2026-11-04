package io.github.thewisekd.phopencode.core.ssh.tunnel

import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import javax.net.SocketFactory

/**
 * Sockets that reach the SSH server's loopback through [session], for an HTTP client talking to
 * `opencode serve` on the server: each socket's connect opens one `direct-tcpip` channel. No local
 * port is ever bound or listened on, so other apps on the phone cannot reach the tunnel.
 *
 * Targets are restricted to loopback ([LoopbackTarget]); anything else is refused before a byte is
 * sent, so the tunnel cannot be used to reach the server's network. Every socket is tracked by the
 * session and closes when the session closes, including sockets in use.
 *
 * Give it to OkHttp with `OkHttpClient.Builder.socketFactory`. OkHttp resolves the URL's host
 * itself before connecting, so URLs should name `127.0.0.1` (or `localhost`, which resolves to a
 * loopback address on the phone too); a host that resolves elsewhere is refused.
 */
internal class TunnelSocketFactory(private val session: SshjSession) : SocketFactory() {
    override fun createSocket(): Socket = TunnelSocket(session)

    /** [host] is not looked up locally; the SSH server resolves it. */
    override fun createSocket(host: String, port: Int): Socket = connected(InetSocketAddress.createUnresolved(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        requireNoLocalBinding(localHost, localPort)
        return createSocket(host, port)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket = connected(InetSocketAddress(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        requireNoLocalBinding(localAddress, localPort)
        return createSocket(address, port)
    }

    /** A failed connect closes the socket. */
    private fun connected(endpoint: InetSocketAddress): Socket = createSocket().apply { connect(endpoint) }

    private fun requireNoLocalBinding(localAddress: InetAddress?, localPort: Int) {
        if (localAddress != null || localPort != 0) throw SocketException("Tunnel sockets bind nothing locally")
    }

    override fun toString(): String = "TunnelSocketFactory"
}
