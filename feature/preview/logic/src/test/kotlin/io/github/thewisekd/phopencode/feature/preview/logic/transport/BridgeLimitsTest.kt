package io.github.thewisekd.phopencode.feature.preview.logic.transport

import io.github.thewisekd.phopencode.feature.preview.logic.contract.BridgeProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridge
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridgeFactory
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout

/**
 * The bridge's limits and typed failures: one bad connection, from a client or from the dev server,
 * cannot hang the bridge or grow without bound, and what went wrong on a relayed connection is
 * reported as a [BridgeProblem] without any text.
 */
@Timeout(30)
class BridgeLimitsTest {
    private val seen = CopyOnWriteArrayList<String>()
    private val closers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = closers.reversed().forEach { runCatching { it.close() } }

    private fun <T : AutoCloseable> T.closing(): T = also { closers += it }

    private fun devServer(respond: (head: String, input: InputStream, socket: Socket) -> Unit) = DevServer { socket ->
        val input = socket.getInputStream()
        while (true) {
            val head = readHeadText(input) ?: break
            seen += head
            respond(head, input, socket)
        }
    }.closing()

    private fun Socket.write(text: String) {
        getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
        getOutputStream().flush()
    }

    private fun open(tunnel: TestTunnel, limits: BridgeLimits = BridgeLimits()): PreviewBridge =
        LoopbackBridgeFactory(limits = limits).open(tunnel).closing()

    private fun get(bridge: PreviewBridge, cookie: String): Reply = Client(bridge.origin.bridgePort).use { client ->
        client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
        client.reply()
    }

    @Test
    fun `a port nothing listens on is a typed nothing listens problem`() {
        val tunnel = TestTunnel(freePort())
        val bridge = open(tunnel)

        assertEquals(502, get(bridge, bridge.cookie()).status)
        assertEquals(BridgeProblem.NothingListens, bridge.nextProblem())
    }

    @Test
    fun `a tunnel that ended is a tunnel closed problem`() {
        val dev = devServer { _, _, socket -> socket.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n") }
        val tunnel = TestTunnel(dev.port)
        val bridge = open(tunnel)
        val cookie = bridge.cookie()
        tunnel.lose()

        assertEquals(502, get(bridge, cookie).status)
        assertEquals(BridgeProblem.TunnelClosed, bridge.nextProblem())
    }

    @Test
    fun `a response longer than the limit is refused before it is relayed`() {
        val dev = devServer { _, _, socket -> socket.write("HTTP/1.1 200 OK\r\nContent-Length: 101\r\n\r\n" + "x".repeat(101)) }
        val bridge = open(TestTunnel(dev.port), BridgeLimits(maxResponseBytes = 100))

        assertEquals(502, get(bridge, bridge.cookie()).status)
        assertEquals(BridgeProblem.ResponseTooLarge, bridge.nextProblem())
    }

    @Test
    fun `a chunked response that grows past the limit is cut`() {
        val dev = devServer { _, _, socket ->
            socket.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
            repeat(20) { socket.write("10\r\n${"y".repeat(16)}\r\n") }
            socket.write("0\r\n\r\n")
        }
        val bridge = open(TestTunnel(dev.port), BridgeLimits(maxResponseBytes = 100))
        val cookie = bridge.cookie()

        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            assertTrue(readHeadText(client.input)!!.startsWith("HTTP/1.1 200"))
            // Counted as the bytes arrive, so what came before a reset still counts.
            var relayed = 0
            val buffer = ByteArray(1024)
            try {
                while (true) {
                    val read = client.input.read(buffer)
                    if (read < 0) break
                    relayed += (0 until read).count { buffer[it] == 'y'.code.toByte() }
                }
            } catch (_: java.net.SocketException) {
                // The bridge cut the connection; the count so far stands.
            }
            assertTrue(relayed <= 100, "$relayed bytes of body were relayed, more than the limit")
        }
        assertEquals(BridgeProblem.ResponseTooLarge, bridge.nextProblem())
    }

    @Test
    fun `a response that ends before its Content-Length is malformed and closes the client`() {
        val dev = devServer { _, _, socket ->
            socket.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + "x".repeat(10))
            socket.close()
        }
        val bridge = open(TestTunnel(dev.port))
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            assertEquals("100", headers(readHeadText(client.input)!!).single { it.first == "Content-Length" }.second)
            // Reads to the end: the bridge closes the connection after the 10 bytes it had.
            assertEquals(10, client.input.readBytes().size)
        }
        assertEquals(BridgeProblem.MalformedResponse, bridge.nextProblem())
    }

    @Test
    fun `a response with too many headers is refused`() {
        val dev = devServer { _, _, socket ->
            socket.write("HTTP/1.1 200 OK\r\n" + (1..11).joinToString("") { "X-$it: v\r\n" } + "Content-Length: 0\r\n\r\n")
        }
        val bridge = open(TestTunnel(dev.port), BridgeLimits(maxHeaders = 10))

        assertEquals(502, get(bridge, bridge.cookie()).status)
        assertEquals(BridgeProblem.TooManyHeaders, bridge.nextProblem())
    }

    @Test
    fun `a response head larger than the limit is refused`() {
        val dev = devServer { _, _, socket ->
            socket.write("HTTP/1.1 200 OK\r\nX-Big: ${"z".repeat(2_000)}\r\nContent-Length: 0\r\n\r\n")
        }
        val bridge = open(TestTunnel(dev.port), BridgeLimits(maxHeadBytes = 1_024))

        assertEquals(502, get(bridge, bridge.cookie()).status)
        assertEquals(BridgeProblem.TooManyHeaders, bridge.nextProblem())
    }

    @Test
    fun `a response that is not HTTP is malformed`() {
        listOf(
            "SSH-2.0-OpenSSH\r\n\r\n",
            "HTTP/1.1 abc OK\r\n\r\n",
            "HTTP/1.1 200 OK\r\nBad Header: x\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
            "HTTP/1.1 101 Switching Protocols\r\n\r\n",
        ).forEach { answer ->
            val dev = devServer { _, _, socket -> socket.write(answer) }
            val bridge = open(TestTunnel(dev.port))

            assertEquals(502, get(bridge, bridge.cookie()).status, answer)
            assertEquals(BridgeProblem.MalformedResponse, bridge.nextProblem(), answer)
        }
    }

    @Test
    fun `malformed or oversized request heads are refused without reaching the tunnel`() {
        val dev = devServer { _, _, socket -> socket.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n") }
        val tunnel = TestTunnel(dev.port)
        val bridge = open(tunnel, BridgeLimits(maxHeadBytes = 1_024, maxHeaders = 10))
        val cookie = bridge.cookie()
        val cases = mapOf(
            "NOT HTTP\r\n\r\n" to 400,
            "GET / HTTP/2.0\r\nCookie: $cookie\r\n\r\n" to 505,
            "GET / HTTP/1.0\r\nCookie: $cookie\r\n\r\n" to 505,
            "GET ${bridge.entryTarget()} HTTP/1.0\r\n\r\n" to 505,
            "GET / HTTP/1.0\r\n\r\n" to 505,
            "GET http://example.com/ HTTP/1.1\r\nCookie: $cookie\r\n\r\n" to 400,
            "CONNECT localhost:22 HTTP/1.1\r\nCookie: $cookie\r\n\r\n" to 405,
            "GET / HTTP/1.1\r\nCookie: $cookie\r\nX-Folded: a\r\n b\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\nCookie: $cookie\r\nBad Name: a\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\nCookie: $cookie\r\nX: a\u0000b\r\n\r\n" to 400,
            "POST / HTTP/1.1\r\nCookie: $cookie\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n" to 400,
            "POST / HTTP/1.1\r\nCookie: $cookie\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n" to 400,
            "POST / HTTP/1.1\r\nCookie: $cookie\r\nTransfer-Encoding: gzip\r\n\r\n" to 501,
            "GET / HTTP/1.1\r\nCookie: $cookie\r\nX-Big: ${"a".repeat(2_000)}\r\n\r\n" to 431,
            "GET / HTTP/1.1\r\nCookie: $cookie\r\n" + (1..11).joinToString("") { "X-$it: v\r\n" } + "\r\n" to 431,
        )
        cases.forEach { (request, status) ->
            Client(bridge.origin.bridgePort).use { client ->
                client.send(request)
                assertEquals(status, client.reply().status, request)
                assertTrue(client.isClosedByPeer(), request)
            }
        }
        assertEquals(0, tunnel.created.get())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a request body longer than the limit is refused`() {
        val dev = devServer { _, _, socket -> socket.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n") }
        val tunnel = TestTunnel(dev.port)
        val bridge = open(tunnel, BridgeLimits(maxRequestBytes = 10))
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("POST / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nContent-Length: 11\r\n\r\n")
            assertEquals(413, client.reply().status)
            assertTrue(client.isClosedByPeer())
        }
        assertEquals(0, tunnel.created.get())
    }

    @Test
    fun `a client that dribbles its head is cut at the head deadline while others are served`() {
        val dev = devServer { _, _, socket -> socket.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok") }
        val bridge = open(TestTunnel(dev.port), BridgeLimits(headTimeoutMillis = 600))
        val cookie = bridge.cookie()

        Client(bridge.origin.bridgePort).use { slow ->
            slow.send("GET / HTTP/1.1\r\nCookie: $cookie\r\nX-Slow: ")
            assertEquals("ok", get(bridge, cookie).text, "Another client waited for the slow one")
            val started = System.currentTimeMillis()
            val cut = try {
                while (System.currentTimeMillis() - started < 5_000) {
                    slow.send("a")
                    Thread.sleep(50)
                }
                false
            } catch (_: java.io.IOException) {
                true
            }
            assertTrue(cut, "A slow head was never cut")
        }
        assertEquals("ok", get(bridge, cookie).text)
        assertTrue(seen.none { "X-Slow" in it })
    }

    @Test
    fun `a refused client that trickles bytes is closed within a fixed bound and frees its slot`() {
        val dev = devServer { _, _, socket -> socket.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok") }
        val tunnel = TestTunnel(dev.port)
        val bridge = open(tunnel, BridgeLimits(maxConnections = 1))
        val cookie = bridge.cookie()
        Thread.sleep(200)

        Client(bridge.origin.bridgePort).use { trickler ->
            trickler.send("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
            assertEquals(403, trickler.reply().status)
            val started = System.currentTimeMillis()
            val cut = try {
                // One byte well within any per-read timeout, again and again.
                while (System.currentTimeMillis() - started < 5_000) {
                    trickler.send("a")
                    Thread.sleep(50)
                }
                false
            } catch (_: java.io.IOException) {
                true
            }
            assertTrue(cut, "A refused client held its connection by trickling bytes")
            assertTrue(System.currentTimeMillis() - started < 3_000)
        }
        Thread.sleep(200)
        assertEquals("ok", get(bridge, cookie).text, "The only slot was not freed")
        assertEquals(1, tunnel.created.get())
    }

    @Test
    fun `a client that stops reading a large response is cut within the stall bound and frees its slot`() {
        val big = 32 * 1024 * 1024
        val dev = devServer { head, _, socket ->
            if (head.startsWith("GET /big")) {
                socket.write("HTTP/1.1 200 OK\r\nContent-Length: $big\r\n\r\n")
                val piece = ByteArray(64 * 1024)
                repeat(big / piece.size) { socket.getOutputStream().write(piece) }
            } else {
                socket.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
            }
        }
        val bridge = open(TestTunnel(dev.port), BridgeLimits(maxConnections = 1, clientWriteStallMillis = 500))
        val cookie = bridge.cookie()
        Thread.sleep(200)

        Socket().use { stuck ->
            stuck.receiveBufferSize = 4 * 1024
            stuck.connect(java.net.InetSocketAddress(LOOPBACK, bridge.origin.bridgePort), 5_000)
            stuck.write("GET /big HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            // Never reads: the bridge's write to it blocks once the buffers on the way are full.
            val started = System.currentTimeMillis()
            var served: String? = null
            while (served == null && System.currentTimeMillis() - started < 10_000) {
                Thread.sleep(200)
                // While the only slot is held, a new connection is closed at once and this fails.
                served = runCatching { get(bridge, cookie).text }.getOrNull()
            }
            assertEquals("ok", served, "The stalled client kept the only slot")
            assertTrue(System.currentTimeMillis() - started < 6_000)
        }
    }

    @Test
    fun `a dev server that stops reading an upload is cut within the stall bound and frees the slot`() {
        val dev = devServer { head, _, socket ->
            if (head.startsWith("POST")) {
                // Takes the head, then never reads nor answers.
                Thread.sleep(25_000)
            } else {
                socket.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
            }
        }
        val bridge = open(TestTunnel(dev.port), BridgeLimits(maxConnections = 1, clientWriteStallMillis = 500))
        val cookie = bridge.cookie()
        Thread.sleep(200)

        val size = 48 * 1024 * 1024
        Client(bridge.origin.bridgePort).use { uploader ->
            uploader.send("POST /upload HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nContent-Length: $size\r\n\r\n")
            val writer = Thread {
                runCatching {
                    val piece = ByteArray(64 * 1024)
                    repeat(size / piece.size) { uploader.socket.getOutputStream().write(piece) }
                }
            }.apply {
                isDaemon = true
                start()
            }
            val started = System.currentTimeMillis()
            var served: String? = null
            while (served == null && System.currentTimeMillis() - started < 15_000) {
                Thread.sleep(200)
                // While the only slot is held, a new connection is closed at once and this fails.
                served = runCatching { get(bridge, cookie).text }.getOrNull()
            }
            assertEquals("ok", served, "The stalled upload kept the only slot")
            assertTrue(System.currentTimeMillis() - started < 10_000)
            uploader.close()
            writer.join(5_000)
        }
    }

    @Test
    fun `an HTTP 1 0 request gets 505 and nothing reaches the tunnel`() {
        val dev = devServer { _, _, socket ->
            socket.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n0\r\n\r\n")
        }
        val tunnel = TestTunnel(dev.port)
        val bridge = open(tunnel)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.0\r\nHost: x\r\nCookie: $cookie\r\nConnection: keep-alive\r\n\r\n")
            assertEquals(505, client.reply().status)
            assertTrue(client.isClosedByPeer())
        }
        assertEquals(0, tunnel.created.get())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `connections past the limit are closed at once and served again later`() {
        val dev = devServer { _, _, socket -> socket.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n") }
        val bridge = open(TestTunnel(dev.port), BridgeLimits(maxConnections = 2, headTimeoutMillis = 2_000))
        val cookie = bridge.cookie()
        val idle = List(2) { Client(bridge.origin.bridgePort) }
        Thread.sleep(200)
        Client(bridge.origin.bridgePort).use { extra ->
            assertTrue(
                try {
                    extra.isClosedByPeer()
                } catch (_: SocketTimeoutException) {
                    false
                },
            )
        }
        idle.forEach { it.close() }
        Thread.sleep(200)
        assertEquals(200, get(bridge, cookie).status)
    }

    @Test
    fun `problems complete when the bridge closes`() {
        val bridge = open(TestTunnel(freePort()))
        bridge.close()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(5_000) { bridge.problems.collect {} }
        }
    }

    @Test
    fun `a bridge that cannot listen fails with a typed error`() {
        val factory: PreviewBridgeFactory = LoopbackBridgeFactory(
            limits = BridgeLimits(),
            listen = { throw java.io.IOException("no") },
        )
        val failure = assertFailsWith<PreviewException> { factory.open(TestTunnel(freePort())) }
        assertEquals(PreviewFailure.BridgeFailed, failure.failure)
    }
}
