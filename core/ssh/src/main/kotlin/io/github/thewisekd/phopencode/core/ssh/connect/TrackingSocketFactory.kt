package io.github.thewisekd.phopencode.core.ssh.connect

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory

/**
 * Plain sockets that can all be closed from another thread, so that giving up on a connect also
 * ends a TCP connect or a handshake read that is still blocked.
 *
 * Every socket's output fails safe: see [GuardedSocket].
 *
 * @param beforeWrite For tests: runs before every write and flush to a socket, on the writing
 *   thread, inside the guard, so that it can fail a write the way the platform might.
 */
internal class TrackingSocketFactory(private val beforeWrite: () -> Unit = {}) : SocketFactory() {
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var closed = false

    override fun createSocket(): Socket = track(GuardedSocket(beforeWrite))

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

/**
 * A socket whose output turns any [RuntimeException] of a write or flush into an [IOException]
 * and closes the socket first.
 *
 * sshj encrypts a packet, which moves the cipher state and the sequence number on, before it
 * writes it, and it handles only [IOException] from the write. A write that fails otherwise (on
 * Android, a socket write from the main thread throws `NetworkOnMainThreadException`) would leave
 * the transport running with the packet counted but never sent, and the server would read every
 * later packet as garbage. Closed here, the connection ends instead, as any lost connection does.
 */
internal class GuardedSocket(private val beforeWrite: () -> Unit) : Socket() {
    private val lock = Any()
    private var output: OutputStream? = null

    override fun getOutputStream(): OutputStream = synchronized(lock) {
        output ?: GuardedOutput(super.getOutputStream()).also { output = it }
    }

    private inner class GuardedOutput(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: Int) = guarded { out.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = guarded { out.write(b, off, len) }

        override fun flush() = guarded { out.flush() }

        private inline fun guarded(block: () -> Unit) {
            try {
                beforeWrite()
                block()
            } catch (failure: RuntimeException) {
                runCatching { this@GuardedSocket.close() }
                throw IOException("Socket write failed", failure)
            }
        }
    }
}
