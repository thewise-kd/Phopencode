package io.github.thewisekd.phopencode.core.remote.detect

import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.SocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Checks that something accepts TCP connections on a port of the server's loopback. */
fun interface PortProbe {
    /**
     * True when a connection to [host]:[port], as seen from the server, opens; false when it is
     * refused or times out. An answer only shows that something listens, not what.
     *
     * @throws SshException.Disconnected when the SSH connection itself is gone.
     */
    suspend fun answers(host: String, port: Int): Boolean
}

/**
 * Opens and closes one connection through [factory], such as an SSH session's tunnel factory, which
 * reaches the server's loopback without listening on a port of the phone.
 *
 * @param connected Whether the SSH connection still stands. A connection failure while it does
 *   not is [SshException.Disconnected], not a closed port.
 */
class SocketPortProbe(
    private val factory: SocketFactory,
    private val timeout: Duration = 5.seconds,
    private val connected: () -> Boolean = { true },
) : PortProbe {
    init {
        require(timeout.isPositive() && timeout.inWholeMilliseconds in 1..Int.MAX_VALUE) { "Timeout is out of range" }
    }

    /** @throws IllegalArgumentException for a [host] that is not a plain loopback address. */
    override suspend fun answers(host: String, port: Int): Boolean {
        require(ListenerAddress.loopbackHost(host) == host) { "Host is not a plain loopback address" }
        require(port in 1..65535) { "Port is out of range" }
        // A literal, so nothing is looked up.
        val address = InetSocketAddress(InetAddress.getByName(host), port)
        return runInterruptible(Dispatchers.IO) {
            try {
                factory.createSocket().use { socket -> socket.connect(address, timeout.inWholeMilliseconds.toInt()) }
                true
            } catch (lost: SshException) {
                throw lost
            } catch (failure: IOException) {
                if (!connected()) throw SshException.Disconnected(failure)
                false
            }
        }
    }
}
