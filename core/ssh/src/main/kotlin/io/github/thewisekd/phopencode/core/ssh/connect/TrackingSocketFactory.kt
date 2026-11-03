package io.github.thewisekd.phopencode.core.ssh.connect

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory

/**
 * Plain sockets that can all be closed from another thread, so that giving up on a connect also
 * ends a TCP connect or a handshake read that is still blocked.
 */
internal class TrackingSocketFactory : SocketFactory() {
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var closed = false

    override fun createSocket(): Socket = track(Socket())

    override fun createSocket(host: String?, port: Int): Socket = unsupported()

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = unsupported()

    override fun createSocket(host: InetAddress?, port: Int): Socket = unsupported()

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        unsupported()

    fun closeAll() {
        closed = true
        sockets.forEach { runCatching { it.close() } }
    }

    private fun track(socket: Socket): Socket {
        sockets += socket
        // closeAll may have run between the check and the add; this closes the socket either way.
        if (closed) socket.close()
        return socket
    }

    /** sshj creates an unconnected socket and connects it with a timeout; nothing else is needed. */
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Only unconnected sockets")
}
