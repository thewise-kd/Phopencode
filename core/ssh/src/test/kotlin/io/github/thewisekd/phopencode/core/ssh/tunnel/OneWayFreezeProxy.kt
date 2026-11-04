package io.github.thewisekd.phopencode.core.ssh.tunnel

import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.ssh.connect.TestSecrets
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A loopback TCP proxy in front of [upstreamPort] that can stop forwarding the client's bytes to
 * the server while it keeps forwarding the server's bytes to the client. That holds a key exchange
 * the client started, since the server never sees the request, while server messages such as a
 * channel confirmation still arrive.
 */
internal class OneWayFreezeProxy(private val upstreamPort: Int) : AutoCloseable {
    private val server = ServerSocket(0, 50, LOOPBACK)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val lock = Object()

    @Volatile
    private var upstreamFrozen = false

    private val acceptor = thread(isDaemon = true, name = "test-oneway-acceptor") {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            val upstream = Socket()
            runCatching { upstream.connect(InetSocketAddress(LOOPBACK, upstreamPort)) }
                .onFailure { client.close(); continue }
            sockets += client
            sockets += upstream
            pump(client, upstream, freezable = true)
            pump(upstream, client, freezable = false)
        }
    }

    val target: SshTarget get() = SshTarget("127.0.0.1", server.localPort, TestSecrets.USER)

    fun freezeUpstream() {
        upstreamFrozen = true
    }

    fun thaw() {
        synchronized(lock) {
            upstreamFrozen = false
            lock.notifyAll()
        }
    }

    private fun pump(from: Socket, to: Socket, freezable: Boolean) {
        thread(isDaemon = true, name = "test-oneway-pump") {
            val buffer = ByteArray(8192)
            runCatching {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (freezable) synchronized(lock) { while (upstreamFrozen) lock.wait() }
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
            runCatching { to.close() }
        }
    }

    override fun close() {
        thaw()
        server.close()
        sockets.forEach { runCatching { it.close() } }
        acceptor.join(1_000)
    }
}
