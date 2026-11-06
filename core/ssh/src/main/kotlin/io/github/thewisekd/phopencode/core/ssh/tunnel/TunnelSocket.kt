package io.github.thewisekd.phopencode.core.ssh.tunnel

import io.github.thewisekd.phopencode.core.ssh.connect.RedactedFailure
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketImpl
import java.net.SocketOption
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import net.schmizz.sshj.connection.channel.OpenFailException

/**
 * A socket whose connection is one SSH `direct-tcpip` channel of [session]. It has no operating
 * system socket at all: nothing is bound or listened on locally, and the only network traffic is
 * the SSH connection itself. Only loopback targets are allowed; see [LoopbackTarget].
 *
 * It behaves like a plain TCP socket where HTTP clients depend on it: connect with a timeout,
 * `soTimeout` for every read (also changed while a read waits), half-close in both directions,
 * and an idempotent [close] that wakes blocked readers and also closes the channel. Closing either
 * stream closes the socket, as with `java.net.Socket`. A failed connect closes the socket.
 *
 * A daemon thread per connected socket moves received bytes into an [Inbox], because sshj's reads
 * have no per-read timeout. It ends when the socket closes or the channel ends. The connect
 * timeout covers sending the open request as well; see [TunnelChannel.openWithin].
 *
 * Socket options that only make sense for TCP are accepted and kept, without effect.
 */
internal class TunnelSocket(private val session: SshjSession) : Socket(null as SocketImpl?) {
    private val lock = Any()
    private val inbox = Inbox()
    private val input = Input()
    private val output = Output()

    @Volatile private var state = State.New
    @Volatile private var target: InetSocketAddress? = null
    @Volatile private var channel: TunnelChannel? = null
    @Volatile private var readTimeout = 0
    @Volatile private var inputShutdown = false
    @Volatile private var outputShutdown = false
    @Volatile private var closed = false
    private var reader: Thread? = null

    private var tcpNoDelay = false
    private var keepAlive = false
    private var soLinger = -1
    private var sendBufferSize = DEFAULT_BUFFER
    private var receiveBufferSize = DEFAULT_BUFFER
    private var trafficClass = 0
    private var reuseAddress = false
    private var oobInline = false

    private enum class State { New, Connecting, Connected }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        require(timeout >= 0) { "connect: timeout can't be negative" }
        requireNotNull(endpoint) { "connect: The address can't be null" }
        require(endpoint is InetSocketAddress) { "Unsupported address type" }
        synchronized(lock) {
            if (closed) throw SocketException(SOCKET_IS_CLOSED)
            if (state != State.New) throw SocketException("Already connected")
            state = State.Connecting
        }
        try {
            open(endpoint, timeout)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun open(endpoint: InetSocketAddress, timeout: Int) {
        val host = LoopbackTarget.hostFor(endpoint) ?: throw SocketException(TARGET_REFUSED)
        session.requireConnected()
        // The factory tracked this socket when it made it; a session that closed since closed it.
        if (closed) throw SocketException(SOCKET_IS_CLOSED)
        val opening = TunnelChannel(session.client.connection, host, endpoint.port)
        synchronized(lock) {
            if (closed) throw SocketException(SOCKET_IS_CLOSED)
            channel = opening
        }
        val outcome = try {
            opening.openWithin(timeout.toLong())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Connect interrupted").apply { initCause(interrupted) }
        } catch (failure: IOException) {
            throw connectFailure(failure)
        }
        when (outcome) {
            TunnelChannel.Opening.Opened -> Unit
            TunnelChannel.Opening.TimedOut -> throw SocketTimeoutException("Connect timed out")
            TunnelChannel.Opening.Cancelled -> throw SocketException(SOCKET_IS_CLOSED)
        }
        val source = opening.inputStream
        synchronized(lock) {
            if (closed) throw SocketException(SOCKET_IS_CLOSED)
            target = endpoint
            state = State.Connected
            reader = thread(isDaemon = true, name = READER_THREAD) { pump(source) }
        }
    }

    /** Moves bytes from the channel to the inbox until either ends. */
    private fun pump(source: InputStream) {
        val buffer = ByteArray(CHUNK)
        try {
            while (!closed) {
                val read = try {
                    source.read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (read < 0) {
                    inbox.end()
                    return
                }
                if (read > 0 && !inbox.put(buffer.copyOf(read))) return
            }
        } catch (_: InterruptedException) {
            // Closed.
        } catch (failure: IOException) {
            if (!closed) inbox.fail(SocketException(CONNECTION_RESET).apply { initCause(RedactedFailure.of(failure)) })
        }
    }

    override fun getInputStream(): InputStream {
        requireUsable()
        if (inputShutdown) throw SocketException("Socket input is shutdown")
        return input
    }

    override fun getOutputStream(): OutputStream {
        requireUsable()
        if (outputShutdown) throw SocketException("Socket output is shutdown")
        return output
    }

    override fun shutdownInput() {
        requireUsable()
        if (inputShutdown) throw SocketException("Socket input is already shutdown")
        inputShutdown = true
        inbox.shutdownInput()
    }

    override fun shutdownOutput() {
        requireUsable()
        if (outputShutdown) throw SocketException("Socket output is already shutdown")
        outputShutdown = true
        try {
            // Flushes, then sends the end of file.
            channel?.outputStream?.close()
        } catch (failure: IOException) {
            throw writeFailure(failure)
        }
    }

    /**
     * Idempotent. Wakes blocked readers at once and closes the channel without waiting for the
     * server's answer.
     */
    override fun close() {
        val (opened, pumping) = synchronized(lock) {
            if (closed) return
            closed = true
            channel to reader
        }
        inbox.close()
        pumping?.interrupt()
        session.untrack(this)
        if (opened != null) {
            opened.cancel()
            // requestClose() does not block. A connection that is ending needs no close request:
            // it takes every channel with it.
            if (session.connected.value) opened.requestClose()
        }
    }

    override fun isConnected(): Boolean = state == State.Connected

    override fun isClosed(): Boolean = closed

    /** Nothing local is ever bound. */
    override fun isBound(): Boolean = false

    override fun isInputShutdown(): Boolean = inputShutdown

    override fun isOutputShutdown(): Boolean = outputShutdown

    override fun bind(bindpoint: SocketAddress?): Unit = throw SocketException(NO_LOCAL_BINDING)

    override fun getInetAddress(): InetAddress? = target?.address

    override fun getPort(): Int = target?.port ?: 0

    override fun getRemoteSocketAddress(): SocketAddress? = target

    override fun getLocalAddress(): InetAddress = WILDCARD

    override fun getLocalPort(): Int = -1

    override fun getLocalSocketAddress(): SocketAddress? = null

    override fun setSoTimeout(timeout: Int) {
        requireOpen()
        require(timeout >= 0) { "timeout can't be negative" }
        readTimeout = timeout
        inbox.timeoutChanged()
    }

    override fun getSoTimeout(): Int {
        requireOpen()
        return readTimeout
    }

    override fun setTcpNoDelay(on: Boolean) = synchronized(lock) { requireOpen(); tcpNoDelay = on }

    override fun getTcpNoDelay(): Boolean = synchronized(lock) { requireOpen(); tcpNoDelay }

    override fun setSoLinger(on: Boolean, linger: Int) = synchronized(lock) {
        requireOpen()
        soLinger = if (on) linger.coerceIn(0, MAX_LINGER) else -1
    }

    override fun getSoLinger(): Int = synchronized(lock) { requireOpen(); soLinger }

    override fun sendUrgentData(data: Int): Unit = throw SocketException("Urgent data is not supported")

    override fun setOOBInline(on: Boolean) = synchronized(lock) { requireOpen(); oobInline = on }

    override fun getOOBInline(): Boolean = synchronized(lock) { requireOpen(); oobInline }

    override fun setSendBufferSize(size: Int) = synchronized(lock) {
        require(size > 0) { "negative send size" }
        requireOpen()
        sendBufferSize = size
    }

    override fun getSendBufferSize(): Int = synchronized(lock) { requireOpen(); sendBufferSize }

    override fun setReceiveBufferSize(size: Int) = synchronized(lock) {
        require(size > 0) { "invalid receive size" }
        requireOpen()
        receiveBufferSize = size
    }

    override fun getReceiveBufferSize(): Int = synchronized(lock) { requireOpen(); receiveBufferSize }

    override fun setKeepAlive(on: Boolean) = synchronized(lock) { requireOpen(); keepAlive = on }

    override fun getKeepAlive(): Boolean = synchronized(lock) { requireOpen(); keepAlive }

    override fun setTrafficClass(tc: Int) = synchronized(lock) {
        require(tc in 0..255) { "tc is not in range 0 -- 255" }
        requireOpen()
        trafficClass = tc
    }

    override fun getTrafficClass(): Int = synchronized(lock) { requireOpen(); trafficClass }

    override fun setReuseAddress(on: Boolean) = synchronized(lock) { requireOpen(); reuseAddress = on }

    override fun getReuseAddress(): Boolean = synchronized(lock) { requireOpen(); reuseAddress }

    override fun <T : Any?> setOption(name: SocketOption<T>?, value: T): Socket =
        throw UnsupportedOperationException("Socket options are not supported")

    override fun <T : Any?> getOption(name: SocketOption<T>?): T =
        throw UnsupportedOperationException("Socket options are not supported")

    override fun supportedOptions(): Set<SocketOption<*>> = emptySet()

    /** Names no host or port. */
    override fun toString(): String = "TunnelSocket(connected=${isConnected}, closed=$closed)"

    private fun requireOpen() {
        if (closed) throw SocketException(SOCKET_IS_CLOSED)
    }

    private fun requireUsable() {
        requireOpen()
        if (state != State.Connected) throw SocketException("Socket is not connected")
    }

    private fun connectFailure(failure: IOException): IOException {
        val redacted = RedactedFailure.of(failure)
        return when {
            closed -> SocketException(SOCKET_IS_CLOSED)
            failure.hasCause<OpenFailException>() -> ConnectException("Tunnel target refused the connection")
            else -> SocketException("Tunnel connection failed")
        }.apply { initCause(redacted) }
    }

    private fun writeFailure(failure: IOException): IOException =
        SocketException(if (closed) SOCKET_IS_CLOSED else BROKEN_PIPE).apply { initCause(RedactedFailure.of(failure)) }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { it.cause }.take(MAX_CAUSES).any { it is T }

    /**
     * Throws [IndexOutOfBoundsException] unless [len] bytes from [off] fit in [b]. Written by hand
     * because `Objects.checkFromIndexSize` exists on Android only from API 30, and minSdk is 26.
     * `b.size - off` cannot overflow once `off` is known to be non-negative.
     */
    private fun requireRange(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || len > b.size - off) {
            throw IndexOutOfBoundsException("Range [$off, $off + $len) out of bounds for length ${b.size}")
        }
    }

    private inner class Input : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                val read = read(one, 0, 1)
                if (read < 0) return -1
                if (read == 1) return one[0].toInt() and 0xff
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            requireRange(b, off, len)
            return inbox.read(b, off, len) { readTimeout }
        }

        override fun available(): Int = inbox.available()

        override fun close() = this@TunnelSocket.close()
    }

    private inner class Output : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            requireRange(b, off, len)
            val sink = writable()
            try {
                sink.write(b, off, len)
            } catch (failure: IOException) {
                throw writeFailure(failure)
            }
        }

        override fun flush() {
            val sink = writable()
            try {
                sink.flush()
            } catch (failure: IOException) {
                throw writeFailure(failure)
            }
        }

        override fun close() = this@TunnelSocket.close()

        private fun writable(): OutputStream {
            if (closed) throw SocketException(SOCKET_IS_CLOSED)
            if (outputShutdown) throw SocketException("Socket output is shutdown")
            return channel?.outputStream ?: throw SocketException("Socket is not connected")
        }
    }

    private companion object {
        const val READER_THREAD = "phopencode-tunnel-reader"
        const val CHUNK = 16 * 1024
        const val DEFAULT_BUFFER = 64 * 1024
        const val MAX_LINGER = 65535
        const val MAX_CAUSES = 8
        const val SOCKET_IS_CLOSED = "Socket is closed"
        const val CONNECTION_RESET = "Connection reset"
        const val BROKEN_PIPE = "Broken pipe"
        const val NO_LOCAL_BINDING = "Tunnel sockets bind nothing locally"
        const val TARGET_REFUSED = "Tunnel target refused: only loopback addresses are allowed"

        val WILDCARD: InetAddress = InetAddress.getByAddress(ByteArray(4))
    }
}
