package io.github.thewisekd.phopencode.feature.preview.logic.transport

import io.github.thewisekd.phopencode.feature.preview.logic.contract.BridgeEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.BridgeProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridge
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridgeFactory
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnel
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Opens [LoopbackBridge]s (D9, option (b)): each one listens on `127.0.0.1` only, on a port the
 * system picks, with a new secret of [BridgeSecret.BYTES] bytes from a [SecureRandom].
 *
 * This file is the only place in the app that listens on a socket; see `docs/dev-setup/preview-transport.md`.
 */
class LoopbackBridgeFactory internal constructor(
    private val limits: BridgeLimits,
    private val random: SecureRandom = SecureRandom(),
    private val listen: () -> ServerSocket = ::listenOnLoopback,
    private val startThread: (name: String, body: () -> Unit) -> Thread = ::startDaemon,
) : PreviewBridgeFactory {
    constructor() : this(BridgeLimits())

    override fun open(tunnel: PreviewTunnel): PreviewBridge {
        val server = try {
            listen()
        } catch (_: Exception) {
            throw PreviewException(PreviewFailure.BridgeFailed)
        }
        val address = server.inetAddress
        if (!server.isBound || address !is Inet4Address || address != LOOPBACK) {
            runCatching { server.close() }
            throw PreviewException(PreviewFailure.BridgeFailed)
        }
        val secret = BridgeSecret.draw(random, server.localPort)
        val bridge = LoopbackBridge(server, tunnel, secret, limits, startThread)
        try {
            bridge.start()
        } catch (_: Throwable) {
            // A thread that cannot be created: close the server socket and the watchdog, if started.
            runCatching { bridge.close() }
            throw PreviewException(PreviewFailure.BridgeFailed)
        }
        return bridge
    }

    override fun toString(): String = "LoopbackBridgeFactory"

    private companion object {
        /** `127.0.0.1` from its bytes: no name is looked up, and never a wildcard. */
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        const val BACKLOG = 16

        /** Port 0: the system picks a free port at random. */
        fun listenOnLoopback(): ServerSocket = ServerSocket(0, BACKLOG, LOOPBACK)
    }
}

/**
 * The bridge itself: accepts connections on [server] until [close] and serves each on its own
 * thread with a [BridgeConnection]. At most [BridgeLimits.maxConnections] are served at once;
 * more are closed as they arrive.
 */
internal class LoopbackBridge(
    private val server: ServerSocket,
    private val tunnel: PreviewTunnel,
    private val secret: BridgeSecret,
    private val limits: BridgeLimits,
    private val startThread: (name: String, body: () -> Unit) -> Thread = ::startDaemon,
) : PreviewBridge {
    override val origin = PreviewOrigin(server.localPort)

    override val entry = BridgeEntry(origin, "${origin.url}/?${BridgeSecret.PARAM}=${secret.encoded}")

    private val reports = Channel<BridgeProblem>(PROBLEM_BUFFER, BufferOverflow.DROP_OLDEST)

    override val problems: Flow<BridgeProblem> = reports.receiveAsFlow()

    private val rewriter = HeadRewriter(origin.bridgePort, tunnel.port, secret)

    /** Started by [start], with the accept thread; closed with the bridge. */
    private var watchdog: WriteWatchdog? = null

    /** Every open socket, client and tunnel, guarded by itself. */
    private val sockets = HashSet<Socket>()
    private var clients = 0
    private var closed = false

    /** The address the server socket is bound to. */
    internal val boundAddress: InetSocketAddress
        get() = InetSocketAddress(server.inetAddress, server.localPort)

    fun start() {
        val watching = WriteWatchdog(limits.clientWriteStallMillis)
        val started = synchronized(sockets) {
            if (!closed) watchdog = watching
            !closed
        }
        if (!started) {
            watching.close()
            return
        }
        startThread(ACCEPT_THREAD) { acceptAll(watching) }
    }

    private fun acceptAll(watchdog: WriteWatchdog) {
        while (true) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            if (!admit(client)) {
                runCatching { client.close() }
                continue
            }
            try {
                thread(isDaemon = true, name = CONNECTION_THREAD) { serve(client, watchdog) }
            } catch (_: Throwable) {
                leave(client)
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket, watchdog: WriteWatchdog) {
        try {
            BridgeConnection(client, tunnel, secret, rewriter, limits, ::report, ::hold, ::release, watchdog).serve()
        } catch (_: Exception) {
            // The connection's streams could not be had: it ended before it was served.
        } finally {
            runCatching { client.close() }
            leave(client)
        }
    }

    private fun admit(client: Socket): Boolean = synchronized(sockets) {
        if (closed || clients >= limits.maxConnections) return false
        clients++
        sockets += client
        true
    }

    private fun leave(client: Socket) = synchronized(sockets) {
        if (sockets.remove(client)) clients--
    }

    private fun hold(socket: Socket): Boolean = synchronized(sockets) {
        if (closed) return false
        sockets += socket
        true
    }

    private fun release(socket: Socket) {
        synchronized(sockets) { sockets.remove(socket) }
    }

    private fun report(problem: BridgeProblem) {
        reports.trySend(problem)
    }

    override fun close() {
        val open = synchronized(sockets) {
            if (closed) return
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
        synchronized(sockets) { watchdog }?.close()
        reports.close()
    }

    override fun toString(): String = "LoopbackBridge(origin=$origin, closed=${synchronized(sockets) { closed }})"

    private companion object {
        const val PROBLEM_BUFFER = 64
        const val ACCEPT_THREAD = "phopencode-bridge-accept"
        const val CONNECTION_THREAD = "phopencode-bridge-connection"
    }
}

/** Starts [body] on a new daemon thread named [name]. */
private fun startDaemon(name: String, body: () -> Unit): Thread = thread(isDaemon = true, name = name, block = body)
