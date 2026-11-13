package io.github.thewisekd.phopencode.feature.preview.logic.transport

import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridge
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelEnd
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Timeout

/**
 * What the bridge does to an allowed request: the head is rewritten for the dev server behind the
 * pinned tunnel, bodies are relayed by length or in chunks, responses come back with loopback
 * redirects mapped to the preview origin and an added `Content-Security-Policy`, and an upgrade
 * becomes a raw relay.
 */
@Timeout(30)
class BridgeRelayTest {
    private val seen = CopyOnWriteArrayList<String>()
    private val closers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = closers.reversed().forEach { runCatching { it.close() } }

    private fun <T : AutoCloseable> T.closing(): T = also { closers += it }

    /** A dev server that records each head and answers with what [respond] writes. */
    private fun devServer(respond: (head: String, input: InputStream, socket: Socket) -> Unit) = DevServer { socket ->
        val input = socket.getInputStream()
        while (true) {
            val head = readHeadText(input) ?: break
            seen += head
            respond(head, input, socket)
        }
    }.closing()

    private fun bridgeTo(dev: DevServer, limits: BridgeLimits = BridgeLimits()): Pair<PreviewBridge, TestTunnel> {
        val tunnel = TestTunnel(dev.port)
        val bridge = LoopbackBridgeFactory(limits = limits).open(tunnel).closing()
        return bridge to tunnel
    }

    private fun Socket.write(text: String) {
        getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
        getOutputStream().flush()
    }

    private fun ok(body: String = "ok", extra: String = "") =
        "HTTP/1.1 200 OK\r\n${extra}Content-Length: ${body.length}\r\n\r\n$body"

    private fun field(head: String, name: String): String? =
        headers(head).firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    @Test
    fun `requests reach the dev server through the tunnel with Host, Origin and Referer on localhost`() {
        val dev = devServer { _, _, socket -> socket.write(ok()) }
        val (bridge, tunnel) = bridgeTo(dev)
        val cookie = bridge.cookie()
        val origin = bridge.origin.url
        Client(bridge.origin.bridgePort).use { client ->
            client.send(
                "GET /page?q=1 HTTP/1.1\r\nHost: ${origin.removePrefix("http://")}\r\nOrigin: $origin\r\n" +
                    "Referer: $origin/index.html?a=b\r\nCookie: $cookie\r\nAccept: */*\r\n\r\n",
            )
            assertEquals(200, client.reply().status)
        }

        val head = seen.single()
        assertEquals("GET /page?q=1 HTTP/1.1", head.substringBefore("\r\n"))
        assertEquals(listOf("localhost:${dev.port}"), headers(head).filter { it.first.equals("Host", true) }.map { it.second })
        assertEquals("http://localhost:${dev.port}", field(head, "Origin"))
        assertEquals("http://localhost:${dev.port}/index.html?a=b", field(head, "Referer"))
        assertEquals("*/*", field(head, "Accept"))
        assertNull(field(head, "Cookie"))
        assertEquals(listOf("localhost:${dev.port}"), tunnel.targets)
    }

    @Test
    fun `Origin and Referer of another origin are not rewritten`() {
        val dev = devServer { _, _, socket -> socket.write(ok()) }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send(
                "POST / HTTP/1.1\r\nHost: x\r\nOrigin: http://127.0.0.1:1\r\nReferer: http://127.0.0.1:${bridge.origin.bridgePort}0/\r\n" +
                    "Cookie: $cookie\r\nContent-Length: 0\r\n\r\n",
            )
            assertEquals(200, client.reply().status)
        }

        assertEquals("http://127.0.0.1:1", field(seen.single(), "Origin"))
        assertEquals("http://127.0.0.1:${bridge.origin.bridgePort}0/", field(seen.single(), "Referer"))
    }

    @Test
    fun `loopback redirects on the dev port map back to the preview origin, others stay`() {
        lateinit var port: String
        val dev = devServer { head, _, socket ->
            val location = head.substringAfter(' ').substringBefore(' ').removePrefix("/?to=")
            socket.write("HTTP/1.1 302 Found\r\nLocation: ${location.replace("PORT", port)}\r\nContent-Length: 0\r\n\r\n")
        }
        port = dev.port.toString()
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        val origin = bridge.origin.url
        val cases = mapOf(
            "http://localhost:PORT/a?b=c" to "$origin/a?b=c",
            "http://127.0.0.1:PORT/" to "$origin/",
            "http://[::1]:PORT/x" to "$origin/x",
            "HTTP://LocalHost:PORT" to origin,
            "//localhost:PORT/y" to "$origin/y",
            "http://localhost:PORT#f" to "$origin#f",
            "/relative" to "/relative",
            "http://localhost:1/other-port" to "http://localhost:1/other-port",
            "http://localhost/no-port" to "http://localhost/no-port",
            "http://example.com:PORT/" to "http://example.com:PORT/",
            "https://localhost:PORT/" to "https://localhost:PORT/",
            "http://user@localhost:PORT/" to "http://user@localhost:PORT/",
        )
        Client(bridge.origin.bridgePort).use { client ->
            cases.forEach { (from, to) ->
                client.send("GET /?to=$from HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
                val reply = client.reply()
                assertEquals(302, reply.status)
                assertEquals(to.replace("PORT", port), reply.header("Location"), from)
            }
        }
    }

    @Test
    fun `every response carries a Content-Security-Policy that keeps connect-src on the preview origin`() {
        val dev = devServer { _, _, socket ->
            socket.write(ok("<html></html>", "Content-Security-Policy: default-src *\r\n"))
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        val reply = Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            client.reply()
        }

        val policies = reply.all("Content-Security-Policy")
        assertTrue("default-src *" in policies, "The page's own policy was dropped")
        val ours = policies.single { it.startsWith("connect-src") }
        val sources = ours.removePrefix("connect-src").trim().split(' ').toSet()
        val host = "127.0.0.1:${bridge.origin.bridgePort}"
        assertEquals(setOf("http://$host", "ws://$host"), sources)
        assertEquals("<html></html>", reply.text)
    }

    @Test
    fun `the bridge's own answers carry the policy too`() {
        val dev = devServer { _, _, socket -> socket.write(ok()) }
        val (bridge, _) = bridgeTo(dev)
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
            val reply = client.reply()
            assertEquals(403, reply.status)
            assertTrue(reply.all("Content-Security-Policy").any { it.startsWith("connect-src") })
        }
    }

    @Test
    fun `request and response bodies are relayed by Content-Length`() {
        val dev = devServer { head, input, socket ->
            val body = String(input.readExactly(field(head, "Content-Length")!!.toInt()))
            socket.write(ok(body.uppercase()))
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("POST /form HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nContent-Length: 11\r\n\r\nhello world")
            val reply = client.reply()
            assertEquals(200, reply.status)
            assertEquals("HELLO WORLD", reply.text)
        }
    }

    @Test
    fun `request and response bodies are relayed in chunks`() {
        val dev = devServer { head, input, socket ->
            assertEquals("chunked", field(head, "Transfer-Encoding"))
            val body = String(input.readChunked())
            socket.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n${body.length.toString(16)}\r\n$body\r\n0\r\nX-T: 1\r\n\r\n")
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("PUT /x HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nTransfer-Encoding: chunked\r\n\r\n5;ext=1\r\nhello\r\n1\r\n!\r\n0\r\n\r\n")
            val reply = client.reply()
            assertEquals(200, reply.status)
            assertEquals("abchello!", reply.text)
            // The connection is still usable after the trailer.
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
            assertEquals("abc", client.reply().text)
        }
    }

    @Test
    fun `a response to HEAD has no body and the connection goes on`() {
        val dev = devServer { head, _, socket ->
            if (head.startsWith("HEAD")) {
                socket.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n")
            } else {
                socket.write(ok("after"))
            }
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("HEAD / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            assertEquals("1000", client.reply(head = true).header("Content-Length"))
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            assertEquals("after", client.reply().text)
        }
        assertEquals(1, dev.accepted.get())
    }

    @Test
    fun `a response without a length is relayed until the dev server closes`() {
        val dev = devServer { _, _, socket ->
            socket.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nstream until close")
            socket.close()
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            assertEquals("stream until close", client.reply().text)
        }
    }

    @Test
    fun `an idle event stream opens at once and each event arrives as it is sent`() {
        val headSeen = java.util.concurrent.CountDownLatch(1)
        val dev = devServer { _, _, socket ->
            socket.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n\r\n")
            // No body until the client has the head: the stream is idle meanwhile.
            headSeen.await(10, java.util.concurrent.TimeUnit.SECONDS)
            socket.write("data: one\n\n")
            Thread.sleep(5_000)
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort, timeoutMillis = 2_000).use { client ->
            client.send("GET /events HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nAccept: text/event-stream\r\n\r\n")
            val head = readHeadText(client.input)!!
            assertTrue(head.startsWith("HTTP/1.1 200"))
            assertEquals("text/event-stream", field(head, "Content-Type"))
            headSeen.countDown()
            assertEquals("data: one\n\n", String(client.input.readExactly(11)))
        }
    }

    @Test
    fun `an interim 100 Continue reaches the client before the final response`() {
        val dev = devServer { head, input, socket ->
            socket.write("HTTP/1.1 100 Continue\r\n\r\n")
            input.readExactly(field(head, "Content-Length")!!.toInt())
            socket.write(ok("done"))
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("POST / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nContent-Length: 2\r\n\r\nhi")
            var reply = client.reply()
            while (reply.status == 100) reply = client.reply()
            assertEquals("done", reply.text)
        }
    }

    @Test
    fun `an Expect 100-continue request gets its 100 and its body reaches the dev server`() {
        val dev = devServer { head, input, socket ->
            assertNull(field(head, "Expect"))
            socket.write(ok(String(input.readExactly(field(head, "Content-Length")!!.toInt()))))
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("POST / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nExpect: 100-continue\r\nContent-Length: 4\r\n\r\n")
            assertEquals(100, client.reply().status)
            client.send("body")
            assertEquals("body", client.reply().text)
        }
    }

    @Test
    fun `a 101 upgrade turns the connection into a raw relay both ways, as hot reload needs`() {
        val dev = devServer { head, input, socket ->
            assertEquals("websocket", field(head, "Upgrade"))
            assertEquals("Upgrade", field(head, "Connection"))
            socket.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\nserver-hello")
            val got = input.readExactly(5)
            socket.getOutputStream().write(got.reversedArray())
            socket.getOutputStream().flush()
            Thread.sleep(200)
            socket.close()
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send(
                "GET /hmr HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n",
            )
            val head = readHeadText(client.input)!!
            assertTrue(head.startsWith("HTTP/1.1 101"))
            assertEquals("server-hello", String(client.input.readExactly(12)))
            client.send(byteArrayOf(1, 2, 3, 4, 5))
            assertContentEquals(byteArrayOf(5, 4, 3, 2, 1), client.input.readExactly(5))
            assertTrue(client.isClosedByPeer())
        }
    }

    @Test
    fun `a Set-Cookie for any bridge's cookie from the dev server is dropped, others pass`() {
        lateinit var name: String
        val dev = devServer { _, _, socket ->
            socket.write(
                ok(
                    extra = "Set-Cookie: $name=evil; Path=/\r\nSet-Cookie: sid=1; HttpOnly\r\n" +
                        "Set-Cookie: phopencode_bridge_1=evil\r\nSet-Cookie: Phopencode_Bridge_2 = evil\r\n",
                ),
            )
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        name = cookie.substringBefore('=')
        val reply = Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            client.reply()
        }
        assertEquals(listOf("sid=1; HttpOnly"), reply.all("Set-Cookie"))
    }

    @Test
    fun `a connection keeps one tunnel socket, and a request without a body is resent once when the dev server dropped it`() {
        var answered = 0
        val dev = devServer { _, _, socket ->
            answered++
            socket.write(ok("n$answered"))
            // Like Node's keep-alive timeout: the dev server closes idle connections.
            if (answered == 2) socket.close()
        }
        val (bridge, tunnel) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            repeat(3) {
                client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
                assertEquals("n${it + 1}", client.reply().text)
                if (it == 1) Thread.sleep(200)
            }
        }
        assertEquals(2, tunnel.created.get())
    }

    @Test
    fun `a Set-Cookie Domain naming a loopback host is dropped so the cookie is host-only, others stay`() {
        val cookies = listOf(
            "a=1; Domain=localhost; Path=/; HttpOnly" to "a=1; Path=/; HttpOnly",
            "b=2; domain=.127.0.0.1" to "b=2",
            "c=3; Domain=[::1]; Secure" to "c=3; Secure",
            "d=4;Domain=LOCALHOST;SameSite=Lax" to "d=4;SameSite=Lax",
            "e=5; Domain=example.com; Path=/" to "e=5; Domain=example.com; Path=/",
            "f=6; Domain=localhost.example.com" to "f=6; Domain=localhost.example.com",
            "g=7; Path=/" to "g=7; Path=/",
        )
        val dev = devServer { _, _, socket ->
            socket.write(ok(extra = cookies.joinToString("") { "Set-Cookie: ${it.first}\r\n" }))
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        val reply = Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            client.reply()
        }
        assertEquals(cookies.map { it.second }, reply.all("Set-Cookie"))
    }

    /**
     * A dev server that answers the first request, then takes the second one and drops the
     * connection without a byte of an answer, as one that applied it and then went away would.
     */
    private fun dropsSecondRequest() = devServer { _, _, socket ->
        if (seen.size == 1) {
            socket.write(ok("first"))
        } else if (seen.size == 2) {
            socket.close()
        } else {
            socket.write(ok("again"))
        }
    }

    @Test
    fun `a POST without a body is not sent twice when the dev server drops the connection unanswered`() {
        val dev = dropsSecondRequest()
        val (bridge, tunnel) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            assertEquals("first", client.reply().text)
            client.send("POST /delete-all HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nContent-Length: 0\r\n\r\n")
            assertEquals(502, client.reply().status)
        }
        assertEquals(1, seen.count { it.startsWith("POST") }, "The POST was sent again")
        assertEquals(1, tunnel.created.get())
    }

    @Test
    fun `a GET is sent again once when the dev server drops the connection unanswered`() {
        val dev = dropsSecondRequest()
        val (bridge, tunnel) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            assertEquals("first", client.reply().text)
            client.send("GET /again HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            assertEquals("again", client.reply().text)
        }
        assertEquals(2, seen.count { it.startsWith("GET /again") })
        assertEquals(2, tunnel.created.get())
    }

    @Test
    fun `a GET is not sent again when the dev server dropped it after part of an answer`() {
        val dev = devServer { _, _, socket ->
            if (seen.size == 1) {
                socket.write(ok("first"))
            } else {
                socket.write("HTTP/1.1 20")
                socket.close()
            }
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            assertEquals("first", client.reply().text)
            client.send("GET /partial HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            assertEquals(502, client.reply().status)
        }
        assertEquals(1, seen.count { it.startsWith("GET /partial") })
    }

    @Test
    fun `an early answer from a dev server that stops reading the upload reaches the client`() {
        val dev = devServer { _, input, socket ->
            input.readExactly(5)
            socket.write("HTTP/1.1 413 Content Too Large\r\nContent-Length: 8\r\nConnection: close\r\n\r\ntoo much")
            // Takes nothing more: the upload fills every buffer on the way and then blocks.
            Thread.sleep(20_000)
        }
        val (bridge, _) = bridgeTo(dev)
        val cookie = bridge.cookie()
        val size = 48 * 1024 * 1024
        Client(bridge.origin.bridgePort, timeoutMillis = 10_000).use { client ->
            client.send("POST /upload HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nContent-Length: $size\r\n\r\n")
            val writer = Thread {
                runCatching {
                    val piece = ByteArray(64 * 1024)
                    repeat(size / piece.size) { client.socket.getOutputStream().write(piece) }
                }
            }.apply {
                isDaemon = true
                start()
            }
            val started = System.currentTimeMillis()
            val reply = client.reply()
            assertEquals(413, reply.status)
            assertEquals("too much", reply.text)
            assertTrue(System.currentTimeMillis() - started < 10_000)
            client.close()
            writer.join(5_000)
        }
        // The bridge is not stuck: a new connection is served.
        assertEquals(302, Client(bridge.origin.bridgePort).use { c ->
            c.send("GET ${bridge.entryTarget()} HTTP/1.1\r\nHost: x\r\n\r\n")
            c.reply().status
        })
    }

    @Test
    fun `the bridge works over the pinned tunnel fake of core testing`() {
        val session = FakeSshSession()
        val loopback = session.openLoopbackTunnel(5173)
        val tunnel = object : PreviewTunnel {
            override val port = 5173
            override val loopback = loopback
            override val end = MutableStateFlow<PreviewTunnelEnd?>(null)

            override fun close() = loopback.close()
        }
        val bridge = LoopbackBridgeFactory().open(tunnel).closing()
        val cookie = bridge.cookie()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
            val deadline = System.currentTimeMillis() + 5_000
            while (loopback.sockets.isEmpty() || !String(loopback.sockets.single().written()).endsWith("\r\n\r\n")) {
                check(System.currentTimeMillis() < deadline) { "Nothing reached the tunnel" }
                Thread.sleep(10)
            }
            val written = String(loopback.sockets.single().written())
            assertTrue(written.startsWith("GET / HTTP/1.1\r\n"))
            assertTrue("\r\nHost: localhost:5173\r\n" in written)
            assertFalse(cookie.substringAfter('=') in written)
            loopback.sockets.single().respond(ok("fake").toByteArray())
            assertEquals("fake", client.reply().text)
        }
        assertEquals(listOf("localhost" to 5173), session.tunnels)
    }
}
