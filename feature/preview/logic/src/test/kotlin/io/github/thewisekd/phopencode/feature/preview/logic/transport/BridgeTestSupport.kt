package io.github.thewisekd.phopencode.feature.preview.logic.transport

import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.feature.preview.logic.contract.BridgeProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridge
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelEnd
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

/**
 * Stands in for the developer's dev server on the server's loopback: a real listening socket on
 * this machine's `127.0.0.1`, whose [handler] scripts each accepted connection.
 */
internal class DevServer(private val handler: (Socket) -> Unit) : AutoCloseable {
    private val server = ServerSocket(0, 50, LOOPBACK)
    private val sockets = CopyOnWriteArrayList<Socket>()

    val port: Int = server.localPort

    /** Connections the dev server accepted. */
    val accepted = AtomicInteger()

    init {
        thread(isDaemon = true, name = "test-dev-server") {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    return@thread
                }
                accepted.incrementAndGet()
                sockets += socket
                thread(isDaemon = true) {
                    socket.use { runCatching { handler(it) } }
                }
            }
        }
    }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }
}

/** A port nothing listens on: bound once by the system, then released. */
internal fun freePort(): Int = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

/**
 * A [PreviewTunnel] whose sockets reach this machine's loopback on [port], as the pinned tunnel
 * reaches the server's: a target other than a loopback name on [port] is refused before
 * anything connects. Records every target and every socket made, so a test can prove that
 * nothing reached the tunnel.
 */
internal class TestTunnel(override val port: Int) : PreviewTunnel {
    val created = AtomicInteger()
    val targets = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Volatile private var closed = false

    override val end = MutableStateFlow<PreviewTunnelEnd?>(null)

    private val factory = object : SocketFactory() {
        override fun createSocket(): Socket {
            if (closed) throw SocketException("Tunnel is closed")
            created.incrementAndGet()
            return object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) {
                    val address = endpoint as InetSocketAddress
                    val host = address.hostString
                    val pinned = this@TestTunnel.port
                    require(address.port == pinned && host in setOf("localhost", "127.0.0.1", "::1")) { "Target refused" }
                    targets += "$host:${address.port}"
                    super.connect(InetSocketAddress(LOOPBACK, pinned), timeout)
                }
            }.also { sockets += it }
        }

        override fun createSocket(host: String, port: Int): Socket = unsupported()

        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = unsupported()

        override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            unsupported()

        private fun unsupported(): Nothing = throw SocketException("Only createSocket() then connect")
    }

    override val loopback = object : SshSession.LoopbackTunnel {
        override val port: Int = this@TestTunnel.port

        override fun socketFactory(): SocketFactory = factory

        override fun close() = this@TestTunnel.close()
    }

    /** The SSH connection drops: every socket closes and new ones are refused. */
    fun lose() {
        closed = true
        end.value = PreviewTunnelEnd.ConnectionLost
        sockets.forEach { runCatching { it.close() } }
    }

    override fun close() {
        closed = true
        if (end.value == null) end.value = PreviewTunnelEnd.Closed
        sockets.forEach { runCatching { it.close() } }
    }
}

/** A response as a browser would read it. */
internal class Reply(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun all(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    val text: String get() = String(body, Charsets.UTF_8)
}

/** Reads one head, through the blank line; null at a clean end of stream. */
internal fun readHeadText(input: InputStream): String? {
    val out = ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next < 0) {
            if (out.size() == 0) return null
            error("Stream ended inside a head")
        }
        out.write(next)
        val bytes = out.toByteArray()
        val n = bytes.size
        if (n >= 4 && bytes[n - 4] == '\r'.code.toByte() && bytes[n - 3] == '\n'.code.toByte() &&
            bytes[n - 2] == '\r'.code.toByte() && bytes[n - 1] == '\n'.code.toByte()
        ) {
            return String(bytes, Charsets.ISO_8859_1)
        }
    }
}

internal fun headers(head: String): List<Pair<String, String>> =
    head.split("\r\n").drop(1).filter { it.isNotEmpty() }.map { line ->
        val colon = line.indexOf(':')
        line.substring(0, colon) to line.substring(colon + 1).trim()
    }

internal fun InputStream.readExactly(count: Int): ByteArray {
    val out = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(out, read, count - read)
        if (n < 0) error("Stream ended after $read of $count bytes")
        read += n
    }
    return out
}

internal fun InputStream.readToEnd(): ByteArray = readBytes()

/** Reads a chunked body, as a client does. */
internal fun InputStream.readChunked(): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
        val line = readLine(this)
        val size = line.substringBefore(';').trim().toInt(16)
        if (size == 0) {
            while (readLine(this).isNotEmpty()) Unit
            return out.toByteArray()
        }
        out.write(readExactly(size))
        check(readLine(this).isEmpty())
    }
}

internal fun readLine(input: InputStream): String {
    val out = ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        check(next >= 0) { "Stream ended inside a line" }
        if (next == '\n'.code) break
        out.write(next)
    }
    return String(out.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r")
}

/** Reads a response with a body framed as HTTP/1.1 says; [head] for a response to HEAD. */
internal fun readReply(input: InputStream, head: Boolean = false): Reply {
    val text = readHeadText(input) ?: error("No response")
    val status = text.substringBefore("\r\n").split(' ')[1].toInt()
    val fields = headers(text)
    fun field(name: String) = fields.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    val body = when {
        head || status in 100..199 || status == 204 || status == 304 -> ByteArray(0)
        field("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true -> input.readChunked()
        field("Content-Length") != null -> input.readExactly(field("Content-Length")!!.toInt())
        else -> input.readToEnd()
    }
    return Reply(status, fields, body)
}

/** A client of the bridge, as the WebView or another app on the phone would be. */
internal class Client(port: Int, timeoutMillis: Int = 5_000) : AutoCloseable {
    val socket = Socket().apply {
        connect(InetSocketAddress(LOOPBACK, port), timeoutMillis)
        soTimeout = timeoutMillis
    }
    val input: InputStream = socket.getInputStream()

    fun send(text: String) = send(text.toByteArray(Charsets.ISO_8859_1))

    fun send(bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    fun reply(head: Boolean = false): Reply = readReply(input, head)

    /** True when the bridge closed the connection: the next read ends, or the connection resets. */
    fun isClosedByPeer(): Boolean = try {
        input.read() < 0
    } catch (_: SocketException) {
        true
    }

    override fun close() = socket.close()
}

/** The entry URL's path and query, as the WebView sends them. */
internal fun PreviewBridge.entryTarget(): String = entry.url.removePrefix(origin.url)

/** Loads the entry URL, as the WebView does first, and returns the cookie the bridge set, as `name=value`. */
internal fun PreviewBridge.cookie(): String = Client(origin.bridgePort).use { client ->
    client.send("GET ${entryTarget()} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
    val reply = client.reply()
    check(reply.status == 302) { "Entry answered ${reply.status}" }
    reply.header("Set-Cookie")!!.substringBefore(';')
}

/** The next problem the bridge reports, waiting at most a few seconds. */
internal fun PreviewBridge.nextProblem(): BridgeProblem =
    runBlocking { withTimeout(5_000) { problems.first() } }

/** A dev server that answers every request on a connection with [body] and echoes the head it got. */
internal fun echoHandler(seen: MutableList<String>, body: String = "hello"): (Socket) -> Unit = { socket ->
    val input = socket.getInputStream()
    while (true) {
        val head = readHeadText(input) ?: break
        seen += head
        val length = headers(head).firstOrNull { it.first.equals("Content-Length", true) }?.second?.toInt() ?: 0
        if (length > 0) input.readExactly(length)
        val bytes = body.toByteArray()
        socket.getOutputStream().write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray() + bytes,
        )
        socket.getOutputStream().flush()
    }
}
