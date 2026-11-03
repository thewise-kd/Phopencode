package io.github.thewisekd.phopencode.core.ssh.connect

import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

/** Accepts connections on loopback and never sends a byte, like a server that hangs. */
internal class SilentServer : AutoCloseable {
    private val server = ServerSocket(0, 50, LOOPBACK)
    private val accepted = CopyOnWriteArrayList<Socket>()
    private val open = AtomicInteger()

    private val acceptor = thread(isDaemon = true, name = "test-silent-server") {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            accepted += socket
            open.incrementAndGet()
            // Reads and discards, so the client never blocks on a full buffer; the end of input
            // means the client closed its side.
            thread(isDaemon = true, name = "test-silent-reader") {
                runCatching { socket.getInputStream().use { input -> while (input.read() >= 0) Unit } }
                open.decrementAndGet()
            }
        }
    }

    val target: SshTarget = SshTarget("127.0.0.1", server.localPort, TestSecrets.USER)

    /** Connections the client has not closed yet. */
    fun openConnections(): Int = open.get()

    fun connectionsAccepted(): Int = accepted.size

    override fun close() {
        server.close()
        accepted.forEach { runCatching { it.close() } }
        acceptor.join(1_000)
    }
}

/**
 * A loopback TCP proxy in front of [upstreamPort]. [freeze] makes it stop forwarding in both
 * directions while keeping every socket open, which is what a server that stops responding looks
 * like to the client.
 */
internal class FreezableProxy(private val upstreamPort: Int) : AutoCloseable {
    private val server = ServerSocket(0, 50, LOOPBACK)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val lock = Object()

    @Volatile
    private var frozen = false

    private val acceptor = thread(isDaemon = true, name = "test-proxy-acceptor") {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            val upstream = Socket()
            runCatching { upstream.connect(InetSocketAddress(LOOPBACK, upstreamPort)) }
                .onFailure { client.close(); continue }
            sockets += client
            sockets += upstream
            pump(client.getInputStream(), upstream.getOutputStream(), upstream)
            pump(upstream.getInputStream(), client.getOutputStream(), client)
        }
    }

    val port: Int get() = server.localPort

    fun target(username: String = TestSecrets.USER) = SshTarget("127.0.0.1", port, username)

    fun freeze() {
        frozen = true
    }

    fun thaw() {
        synchronized(lock) {
            frozen = false
            lock.notifyAll()
        }
    }

    private fun pump(input: InputStream, output: OutputStream, peer: Socket) {
        thread(isDaemon = true, name = "test-proxy-pump") {
            val buffer = ByteArray(8192)
            runCatching {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    synchronized(lock) { while (frozen) lock.wait() }
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
            runCatching { peer.close() }
        }
    }

    override fun close() {
        thaw()
        server.close()
        sockets.forEach { runCatching { it.close() } }
        acceptor.join(1_000)
    }
}

/** A loopback port nothing listens on. */
internal fun unusedPort(): Int = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
