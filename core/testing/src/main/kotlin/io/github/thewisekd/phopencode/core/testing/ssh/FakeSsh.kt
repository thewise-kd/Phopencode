package io.github.thewisekd.phopencode.core.testing.ssh

import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Objects
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The policy of [HostKeyVerifier]: a key [trust]ed for its target is accepted, an unknown key goes to
 * [askUser], and a target's different key is rejected without asking.
 *
 * @param askUser Plays the user. It may suspend, as one reading a fingerprint does.
 */
class FakeHostKeyVerifier(
    private val askUser: suspend (SshTarget, HostKey) -> HostKeyDecision = { _, _ -> HostKeyDecision.Reject },
) : HostKeyVerifier {
    private val known = HashMap<SshTarget, HostKey>()
    private val verifiedKeys = mutableListOf<Pair<SshTarget, HostKey>>()
    private val askedKeys = mutableListOf<Pair<SshTarget, HostKey>>()

    /** Everything [verify] received, oldest first. */
    val verified: List<Pair<SshTarget, HostKey>>
        get() = synchronized(known) { verifiedKeys.toList() }

    /** What went to the user, oldest first. */
    val asked: List<Pair<SshTarget, HostKey>>
        get() = synchronized(known) { askedKeys.toList() }

    fun trust(target: SshTarget, key: HostKey) {
        synchronized(known) { known[target] = key }
    }

    override suspend fun verify(target: SshTarget, key: HostKey): HostKeyDecision {
        val stored = synchronized(known) {
            verifiedKeys += target to key
            known[target].also { if (it == null) askedKeys += target to key }
        }
        return when (stored) {
            null -> askUser(target, key)
            key -> HostKeyDecision.Accept
            else -> HostKeyDecision.Reject
        }
    }
}

/** One [SshConnector.connect] call. It records no credential, as [SshConnector] keeps none. */
data class FakeConnectAttempt(val target: SshTarget, val options: SshConnectOptions)

/**
 * A connector with servers a test adds with [addServer]. It follows the order of the real thing:
 * the network, then the host key, then the credential.
 *
 * @param serverKey The key every server presents.
 * @param accepts Decides on a credential during `connect`. It must not keep it: the caller wipes it.
 * @param newSession Makes the session of a successful connect, which a test scripts through
 *   [sessions] or here.
 * @property silent The server accepts the connection and says nothing, until `connectTimeout`.
 */
class FakeSshConnector(
    val serverKey: HostKey,
    private val newSession: () -> FakeSshSession = { FakeSshSession() },
    private val accepts: (SshTarget, SshCredential) -> Boolean = { _, _ -> true },
) : SshConnector {
    private val servers = HashSet<SshTarget>()
    private val failures = ArrayDeque<SshException>()
    private val attemptList = mutableListOf<FakeConnectAttempt>()
    private val sessionList = mutableListOf<FakeSshSession>()
    private var received = 0
    private var halfOpen = 0

    @Volatile
    var silent: Boolean = false

    val attempts: List<FakeConnectAttempt>
        get() = synchronized(servers) { attemptList.toList() }

    /** The sessions handed out, oldest first. */
    val sessions: List<FakeSshSession>
        get() = synchronized(servers) { sessionList.toList() }

    /** How many credentials reached a server. */
    val credentialsReceived: Int
        get() = synchronized(servers) { received }

    /** Connections that are open and did not become a session. */
    val halfOpenConnections: Int
        get() = synchronized(servers) { halfOpen }

    fun addServer(target: SshTarget) {
        synchronized(servers) { servers += target }
    }

    /** The next connect fails with [failure], before any verification. */
    fun failNext(failure: SshException) {
        synchronized(servers) { failures.addLast(failure) }
    }

    override suspend fun connect(
        target: SshTarget,
        credential: SshCredential,
        hostKeyVerifier: HostKeyVerifier,
        options: SshConnectOptions,
    ): SshSession {
        val (known, failure) = synchronized(servers) {
            attemptList += FakeConnectAttempt(target, options)
            (target in servers) to failures.removeFirstOrNull()
        }
        failure?.let { throw it }
        if (!known) throw SshException.ConnectionFailed()
        synchronized(servers) { halfOpen++ }
        try {
            if (silent) {
                delay(options.connectTimeout)
                throw SshException.Timeout()
            }
            verify(target, hostKeyVerifier)
            currentCoroutineContext().ensureActive()
            synchronized(servers) { received++ }
            if (!accepts(target, credential)) throw SshException.AuthenticationFailed()
            return newSession().also { session -> synchronized(servers) { sessionList += session } }
        } finally {
            synchronized(servers) { halfOpen-- }
        }
    }

    private suspend fun verify(target: SshTarget, verifier: HostKeyVerifier) {
        val decision = try {
            verifier.verify(target, serverKey)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            HostKeyDecision.Reject
        }
        if (decision != HostKeyDecision.Accept) throw SshException.HostKeyRejected(serverKey)
    }
}

/**
 * A logged-in session a test scripts through [exec] and [files].
 *
 * Use [loseConnection] for a connection that ends by itself. Either way, the processes, SFTP clients
 * and sockets of the session end with it.
 */
class FakeSshSession(
    override val exec: FakeRemoteExec = FakeRemoteExec(),
    val files: FakeRemoteFiles = FakeRemoteFiles(),
) : SshSession {
    private val isConnected = MutableStateFlow(true)
    private val clients = mutableListOf<FakeSftpClient>()
    private val socketList = mutableListOf<FakeTunnelSocket>()
    private val addresses = mutableListOf<Pair<String, Int>>()

    private val factory = object : SocketFactory() {
        override fun createSocket(): Socket = newSocket()

        override fun createSocket(host: String, port: Int): Socket =
            newSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createSocket(host, port)

        override fun createSocket(host: InetAddress, port: Int): Socket =
            newSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            createSocket(address, port)
    }

    override val connected: StateFlow<Boolean> = isConnected.asStateFlow()

    /** The address of each tunnel opened, oldest first: one `direct-tcpip` channel each. */
    val tunnels: List<Pair<String, Int>>
        get() = synchronized(socketList) { addresses.toList() }

    /** Every socket of [socketFactory], oldest first. */
    val sockets: List<FakeTunnelSocket>
        get() = synchronized(socketList) { socketList.toList() }

    private fun newSocket(): FakeTunnelSocket {
        val socket = FakeTunnelSocket { address -> openTunnel(address) }
        synchronized(socketList) {
            socketList += socket
            if (!isConnected.value) socket.close()
        }
        return socket
    }

    private fun openTunnel(address: InetSocketAddress) = synchronized(socketList) {
        if (!isConnected.value) throw SshException.Disconnected()
        addresses += address.hostString to address.port
    }

    override suspend fun openSftp(root: String): SftpClient {
        if (!isConnected.value) throw SshException.Disconnected()
        val segments = FakeRemoteFiles.segments(root)
        if (!root.startsWith('/') || segments.any { it == "." || it == ".." } || '\u0000' in root) {
            throw SshException.Sftp(SftpFailure.PathRejected)
        }
        val real = files.realPath(segments, followLast = true)
        when (files.atomically { files.node(real) }) {
            null -> throw SshException.Sftp(SftpFailure.NotFound)
            is FakeRemoteFiles.Directory -> Unit
            // PathRejected means escaping or malformed, and a file is neither.
            else -> throw SshException.Sftp(SftpFailure.Failed)
        }
        // Under the lock loseConnection takes after turning the flag off: either it sees this client,
        // or this sees the flag.
        return synchronized(clients) {
            if (!isConnected.value) throw SshException.Disconnected()
            FakeSftpClient(root, files).also { clients += it }
        }
    }

    override fun socketFactory(): SocketFactory = factory

    /** The connection ends by itself, as with lost keep-alives. */
    fun loseConnection() {
        isConnected.value = false
        exec.disconnect()
        synchronized(clients) { clients.forEach(FakeSftpClient::disconnect) }
        synchronized(socketList) { socketList.toList() }.forEach(Socket::close)
    }

    override fun close() = loseConnection()
}

/**
 * A socket with a test at the far end, reachable with [respond] and [written]. Its streams work in
 * memory, so no local port is ever listened on.
 */
class FakeTunnelSocket internal constructor(private val onConnect: (InetSocketAddress) -> Unit) : Socket() {
    private val incoming = LinkedBlockingQueue<Int>()
    private val sent = ByteArrayOutputStream()

    private val open = AtomicBoolean(false)

    @Volatile
    private var ended = false

    @Volatile
    private var readTimeoutMillis = 0

    private val input = object : InputStream() {
        override fun read(): Int {
            val timeout = readTimeoutMillis
            val next = if (timeout == 0) {
                incoming.take()
            } else {
                incoming.poll(timeout.toLong(), TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException("Read timed out")
            }
            if (next == END) incoming.put(END)
            return next
        }

        /** Blocks for the first byte only, as a socket does: a short reply must not wait for more. */
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Objects.checkFromIndexSize(offset, length, buffer.size)
            if (length == 0) return 0
            val first = read()
            if (first == END) return END
            buffer[offset] = first.toByte()
            var count = 1
            while (count < length) {
                val next = incoming.peek()
                if (next == null || next == END) break
                buffer[offset + count++] = incoming.remove().toByte()
            }
            return count
        }

        /** As [Socket] documents: closing a returned stream closes the socket. */
        override fun close() = this@FakeTunnelSocket.close()
    }

    private val output = object : OutputStream() {
        override fun write(byte: Int) {
            if (ended) throw IOException("Socket closed")
            synchronized(sent) { sent.write(byte) }
        }

        /** As [Socket] documents: closing a returned stream closes the socket. */
        override fun close() = this@FakeTunnelSocket.close()
    }

    /** Bytes the far end sends. Once the socket is closed they are lost, and the end of stream stays last. */
    fun respond(bytes: ByteArray) {
        synchronized(incoming) {
            if (ended) return
            bytes.forEach { incoming.put(it.toInt() and BYTE_MASK) }
        }
    }

    /** Everything written to the socket so far. */
    fun written(): ByteArray = synchronized(sent) { sent.toByteArray() }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    /** Checks in the order [Socket.connect] does, and leaves the socket unconnected on any failure. */
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        requireNotNull(endpoint) { "connect: The address can't be null" }
        if (ended) throw SocketException("Socket is closed")
        if (open.get()) throw SocketException("Already connected")
        require(endpoint is InetSocketAddress) { "Unsupported address type" }
        if (!open.compareAndSet(false, true)) throw SocketException("Already connected")
        var connected = false
        try {
            onConnect(endpoint)
            connected = true
        } finally {
            if (!connected) open.set(false)
        }
    }

    override fun isConnected(): Boolean = open.get()

    override fun setSoTimeout(timeout: Int) {
        if (ended) throw SocketException("Socket is closed")
        require(timeout >= 0) { "Timeout can't be negative" }
        readTimeoutMillis = timeout
    }

    override fun getSoTimeout(): Int {
        if (ended) throw SocketException("Socket is closed")
        return readTimeoutMillis
    }

    override fun isClosed(): Boolean = ended

    override fun getInputStream(): InputStream = input.also { checkStreamsAvailable() }

    override fun getOutputStream(): OutputStream = output.also { checkStreamsAvailable() }

    /** As [Socket]: a stream is handed out only by a socket that is connected and not closed. */
    private fun checkStreamsAvailable() {
        if (ended) throw SocketException("Socket is closed")
        if (!open.get()) throw SocketException("Socket is not connected")
    }

    override fun close() {
        synchronized(incoming) {
            if (ended) return
            ended = true
            incoming.put(END)
        }
        super.close()
    }

    private companion object {
        const val END = -1
        const val BYTE_MASK = 0xFF
    }
}
