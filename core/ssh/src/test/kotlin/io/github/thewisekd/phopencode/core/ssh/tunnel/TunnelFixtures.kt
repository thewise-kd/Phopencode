package io.github.thewisekd.phopencode.core.ssh.tunnel

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.ssh.connect.NoSessionFeatures
import io.github.thewisekd.phopencode.core.ssh.connect.SessionFeatures
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import io.github.thewisekd.phopencode.core.ssh.connect.TestKeys
import io.github.thewisekd.phopencode.core.ssh.connect.TestSecrets
import io.github.thewisekd.phopencode.core.ssh.connect.password
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelListener
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.ForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.apache.sshd.server.forward.TcpipServerChannel

internal val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

/**
 * An embedded MINA server on 127.0.0.1 that allows `direct-tcpip`. It counts the forwarding
 * channels it holds open, so tests can see channels that the client failed to close.
 * [holdConnects] makes the server stall every forwarding request until [releaseConnects].
 */
internal class TunnelSshServer : AutoCloseable {
    private val openChannels = ConcurrentHashMap.newKeySet<Channel>()
    private val opened = AtomicInteger()
    private val connectsRequested = AtomicInteger()

    @Volatile
    private var connectGate: CountDownLatch? = null

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(TestKeys.ed25519())
        passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
            user == TestSecrets.USER && password == TestSecrets.PASSWORD
        }
        keyboardInteractiveAuthenticator = null
        forwardingFilter = object : ForwardingFilter by AcceptAllForwardingFilter.INSTANCE {
            override fun canListen(address: SshdSocketAddress?, session: Session?): Boolean = false

            override fun canConnect(type: TcpForwardingFilter.Type?, address: SshdSocketAddress?, session: Session?): Boolean {
                connectsRequested.incrementAndGet()
                connectGate?.await()
                return true
            }
        }
        addChannelListener(object : ChannelListener {
            override fun channelOpenSuccess(channel: Channel) {
                if (channel is TcpipServerChannel) {
                    opened.incrementAndGet()
                    openChannels += channel
                }
            }

            override fun channelClosed(channel: Channel, reason: Throwable?) {
                openChannels -= channel
            }
        })
        start()
    }

    val target: SshTarget get() = SshTarget("127.0.0.1", server.port, TestSecrets.USER)

    /** Forwarding channels open on the server right now. */
    fun openChannels(): Int = openChannels.size

    /** Forwarding channels ever opened. */
    fun channelsOpened(): Int = opened.get()

    /** `direct-tcpip` requests that reached the forwarding filter. */
    fun connectsRequested(): Int = connectsRequested.get()

    fun holdConnects() {
        connectGate = CountDownLatch(1)
    }

    fun releaseConnects() {
        connectGate?.countDown()
        connectGate = null
    }

    override fun close() {
        releaseConnects()
        server.stop(true)
    }
}

/** Connects with this package's socket factory plugged into the session. */
internal suspend fun connectWithTunnel(
    server: TunnelSshServer,
    options: SshConnectOptions = SshConnectOptions(keepAliveInterval = null),
    target: SshTarget = server.target,
): SshSession {
    val features = object : SessionFeatures by NoSessionFeatures {
        override fun socketFactory(session: SshjSession) = TunnelSocketFactory(session)
    }
    val accept = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }
    return SshjConnector(features).connect(target, password(), accept, options)
}

/**
 * A loopback TCP echo server. It counts the connections a client has not closed yet: a connection
 * counts as closed once its input ends.
 */
internal class EchoServer : AutoCloseable {
    private val server = ServerSocket(0, 50, LOOPBACK)
    private val accepted = CopyOnWriteArrayList<Socket>()
    private val open = AtomicInteger()

    private val acceptor = thread(isDaemon = true, name = "test-echo-acceptor") {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            accepted += socket
            open.incrementAndGet()
            thread(isDaemon = true, name = "test-echo-worker") {
                runCatching {
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                    // The client finished sending; finish too, which ends the client's input.
                    socket.shutdownOutput()
                    while (input.read() >= 0) Unit
                }
                open.decrementAndGet()
                runCatching { socket.close() }
            }
        }
    }

    val port: Int get() = server.localPort

    fun openConnections(): Int = open.get()

    fun connectionsAccepted(): Int = accepted.size

    override fun close() {
        server.close()
        accepted.forEach { runCatching { it.close() } }
        acceptor.join(1_000)
    }
}

/** Accepts on loopback and never sends a byte. Counts connections the client has not closed. */
internal class SilentTarget : AutoCloseable {
    private val server = ServerSocket(0, 50, LOOPBACK)
    private val accepted = CopyOnWriteArrayList<Socket>()
    private val open = AtomicInteger()

    private val acceptor = thread(isDaemon = true, name = "test-silent-target") {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            accepted += socket
            open.incrementAndGet()
            thread(isDaemon = true, name = "test-silent-target-reader") {
                runCatching { socket.getInputStream().use { input -> while (input.read() >= 0) Unit } }
                open.decrementAndGet()
            }
        }
    }

    val port: Int get() = server.localPort

    fun openConnections(): Int = open.get()

    fun connectionsAccepted(): Int = accepted.size

    override fun close() {
        server.close()
        accepted.forEach { runCatching { it.close() } }
        acceptor.join(1_000)
    }
}

/** A loopback port nothing listens on. */
internal fun unusedLoopbackPort(): Int = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

/** Polls [condition] until it holds or [limit] passes; returns the last result. */
internal fun eventually(limit: Duration = 5.seconds, condition: () -> Boolean): Boolean {
    val started = TimeSource.Monotonic.markNow()
    while (started.elapsedNow() < limit) {
        if (condition()) return true
        Thread.sleep(20)
    }
    return condition()
}

internal fun tunnelThreads(): List<Thread> =
    Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith(TUNNEL_THREAD_PREFIX) }

internal const val TUNNEL_THREAD_PREFIX = "phopencode-tunnel"

/**
 * The listening TCP sockets this process holds, as kernel socket inodes; null where `/proc` is
 * missing. Linux lists every socket of the process under `/proc/self/fd` and every listening TCP
 * socket in `/proc/net/tcp` and `tcp6` with state `0A`.
 */
internal fun listeningSocketsOfThisProcess(): Set<String>? {
    val fdDir = File("/proc/self/fd")
    if (!fdDir.isDirectory) return null
    val own = fdDir.listFiles().orEmpty().mapNotNullTo(HashSet()) { fd ->
        runCatching { Files.readSymbolicLink(fd.toPath()).toString() }.getOrNull()
            ?.takeIf { it.startsWith("socket:[") && it.endsWith("]") }
            ?.removePrefix("socket:[")?.removeSuffix("]")
    }
    val listening = HashSet<String>()
    for (table in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
        val path = Paths.get(table)
        if (!Files.exists(path)) continue
        Files.readAllLines(path).drop(1).forEach { line ->
            val columns = line.trim().split(Regex("\\s+"))
            // Columns: sl local remote st tx:rx tr:when retrnsmt uid timeout inode ...
            if (columns.size > 9 && columns[3] == "0A") listening += columns[9]
        }
    }
    return own intersect listening
}
