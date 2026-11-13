package io.github.thewisekd.phopencode.feature.preview.logic.transport

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridge
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/**
 * Who can use the bridge (D9, option (b)): it listens on `127.0.0.1` only, on a random port, while
 * it is open; every connection must present the per-session secret, or gets 403 and is closed
 * before anything reaches the tunnel.
 */
@Timeout(30)
class BridgeAccessTest {
    private val seen = CopyOnWriteArrayList<String>()
    private val dev = DevServer(echoHandler(seen))
    private val tunnel = TestTunnel(dev.port)
    private val factory = LoopbackBridgeFactory()
    private val bridges = mutableListOf<PreviewBridge>()

    private fun open(): PreviewBridge = factory.open(tunnel).also { bridges += it }

    @AfterTest
    fun tearDown() {
        bridges.forEach { it.close() }
        dev.close()
    }

    /** The entry URL's path and query, as the WebView sends them. */
    private fun entryTarget(bridge: PreviewBridge) = bridge.entry.url.removePrefix(bridge.origin.url)

    private fun secretOf(bridge: PreviewBridge): String = entryTarget(bridge).substringAfter("=")

    /** Loads the entry URL and returns the `name=value` the bridge set as its cookie. */
    private fun cookieOf(bridge: PreviewBridge): String = Client(bridge.origin.bridgePort).use { client ->
        client.send("GET ${entryTarget(bridge)} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        val reply = client.reply()
        assertEquals(302, reply.status)
        reply.header("Set-Cookie")!!.substringBefore(';')
    }

    private fun assertRefused(client: Client) {
        val reply = client.reply()
        assertEquals(403, reply.status)
        assertTrue(client.isClosedByPeer(), "A refused connection stays open")
    }

    private fun assertNothingReachedTheTunnel() {
        assertEquals(0, tunnel.created.get(), "A socket was made on the tunnel")
        assertEquals(0, dev.accepted.get(), "The dev server was reached")
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `the bridge listens on 127 0 0 1 only, on a port the system chose`() {
        val bridge = open() as LoopbackBridge
        val bound = bridge.boundAddress

        assertEquals("127.0.0.1", bound.address.hostAddress)
        assertTrue(bound.address is Inet4Address && bound.address.isLoopbackAddress)
        assertFalse(bound.address.isAnyLocalAddress)
        assertEquals(bridge.origin, PreviewOrigin(bound.port))
        assertEquals("127.0.0.1", PreviewOrigin.HOST)
    }

    @Test
    fun `the bridge cannot be reached on any other address of the phone`() {
        val bridge = open()
        val others = NetworkInterface.networkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        others.forEach { address ->
            assertFailsWith<IOException>("Reachable on $address") {
                Socket().use { it.connect(InetSocketAddress(address, bridge.origin.bridgePort), 1_000) }
            }
        }
    }

    @Test
    fun `the entry URL is on the origin and carries at least 128 random bits once`() {
        val bridge = open()
        val entry = bridge.entry

        assertTrue(entry.url.startsWith("http://127.0.0.1:${bridge.origin.bridgePort}/?"))
        val secret = secretOf(bridge)
        val bits = Base64.getUrlDecoder().decode(secret).size * 8
        assertTrue(bits >= PreviewBridge.MIN_SECRET_BITS, "Only $bits bits")
        assertEquals(1, Regex(Regex.escape(secret)).findAll(entry.url).count())
    }

    @Test
    fun `the secret never prints`() {
        val bridge = open()
        val secret = secretOf(bridge)

        listOf(bridge, bridge.entry, bridge.origin, factory).forEach {
            assertFalse(secret in it.toString(), "$it shows the secret")
        }
    }

    @Test
    fun `a connection without the secret gets 403 and is closed before anything reaches the tunnel`() {
        val bridge = open()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: 127.0.0.1:${bridge.origin.bridgePort}\r\n\r\n")
            assertRefused(client)
        }
        assertNothingReachedTheTunnel()
    }

    @Test
    fun `a HEAD without the secret gets the 403 head with its Content-Length and no body`() {
        val bridge = open()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("HEAD / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            val reply = client.reply(head = true)
            assertEquals(403, reply.status)
            assertEquals("Forbidden".length.toString(), reply.header("Content-Length"))
            assertEquals(-1, client.input.read(), "A body followed the head")
        }
        assertNothingReachedTheTunnel()
    }

    @Test
    fun `a wrong secret in the cookie gets 403`() {
        val bridge = open()
        val name = cookieOf(bridge).substringBefore('=')
        val wrong = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: $name=$wrong\r\n\r\n")
            assertRefused(client)
        }
        assertNothingReachedTheTunnel()
    }

    @Test
    fun `a wrong secret in the URL gets 403`() {
        val bridge = open()
        val target = entryTarget(bridge)
        val wrong = target.dropLast(1) + (if (target.last() == 'A') 'B' else 'A')
        listOf(wrong, target.substringBefore('=') + "=", target + "x", target.dropLast(1)).forEach { attempt ->
            Client(bridge.origin.bridgePort).use { client ->
                client.send("GET $attempt HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                assertRefused(client)
            }
        }
        assertNothingReachedTheTunnel()
    }

    @Test
    fun `the secret of another bridge gets 403`() {
        val first = open()
        val second = open()
        val cookie = cookieOf(first).substringAfter('=')
        val name = cookieOf(second).substringBefore('=')
        Client(second.origin.bridgePort).use { client ->
            client.send("GET ${entryTarget(first)} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertRefused(client)
        }
        Client(second.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: $name=$cookie\r\n\r\n")
            assertRefused(client)
        }
        assertNothingReachedTheTunnel()
    }

    @Test
    fun `a connection that sends nothing is closed without reaching the tunnel`() {
        val bridge = factory(headTimeoutMillis = 300).open(tunnel).also { bridges += it }
        Client(bridge.origin.bridgePort).use { client ->
            assertTrue(client.isClosedByPeer())
        }
        assertNothingReachedTheTunnel()
    }

    @Test
    fun `the first URL sets the secret in an HttpOnly SameSite Strict cookie and drops it from the address`() {
        val bridge = open()
        val reply = Client(bridge.origin.bridgePort).use { client ->
            client.send("GET ${entryTarget(bridge).replace("/?", "/app?x=1&")} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            client.reply()
        }

        assertEquals(302, reply.status)
        assertEquals("/app?x=1", reply.header("Location"))
        val cookie = reply.header("Set-Cookie")!!
        val attributes = cookie.split(';').map { it.trim() }
        assertEquals(secretOf(bridge), attributes[0].substringAfter('='))
        assertContains(attributes, "HttpOnly")
        assertContains(attributes, "SameSite=Strict")
        assertContains(attributes, "Path=/")
        assertEquals("no-store", reply.header("Cache-Control"))
        assertNothingReachedTheTunnel()
    }

    @Test
    fun `a HEAD to the entry URL gets the redirect head with no body and the connection goes on`() {
        val bridge = open()
        Client(bridge.origin.bridgePort).use { client ->
            client.send("HEAD ${entryTarget(bridge)} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            val reply = client.reply(head = true)
            assertEquals(302, reply.status)
            assertEquals("/", reply.header("Location"))
            val cookie = reply.header("Set-Cookie")!!.substringBefore(';')
            assertTrue(reply.header("Set-Cookie")!!.contains("HttpOnly"))
            // A body after the head would be read here as the start of the next response.
            client.send("GET /next HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: $cookie\r\n\r\n")
            val next = client.reply()
            assertEquals(200, next.status)
            assertEquals("hello", next.text)
        }
        assertEquals(1, seen.size)
    }

    @Test
    fun `with the cookie the request is relayed and neither the cookie nor the secret reaches the dev server`() {
        val bridge = open()
        val cookie = cookieOf(bridge)
        val secret = secretOf(bridge)
        val reply = Client(bridge.origin.bridgePort).use { client ->
            client.send("GET /a HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: theme=dark; $cookie; lang=tr\r\n\r\n")
            client.reply()
        }

        assertEquals(200, reply.status)
        assertEquals("hello", reply.text)
        val head = seen.single()
        assertFalse(secret in head, "The secret reached the dev server")
        assertFalse(cookie.substringBefore('=') in head)
        assertEquals("theme=dark; lang=tr", headers(head).single { it.first.equals("Cookie", true) }.second)
    }

    @Test
    fun `cookies of other live bridges never reach the dev server`() {
        val first = open()
        val second = open()
        val own = cookieOf(first)
        val other = cookieOf(second)
        Client(first.origin.bridgePort).use { client ->
            // Cookies are not scoped by port: the WebView sends both bridges' cookies to each.
            client.send("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: $own; $other; PHOPENCODE_BRIDGE_1=x; theme=dark\r\n\r\n")
            assertEquals(200, client.reply().status)
        }

        val head = seen.single()
        assertFalse(secretOf(second) in head, "Another bridge's secret reached the dev server")
        assertFalse(other.substringBefore('=') in head)
        assertEquals("theme=dark", headers(head).single { it.first.equals("Cookie", true) }.second)
    }

    @Test
    fun `with the cookie every request on a connection is checked`() {
        val bridge = open()
        val cookie = cookieOf(bridge)
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET /1 HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: $cookie\r\n\r\n")
            assertEquals(200, client.reply().status)
            client.send("GET /2 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertRefused(client)
        }
        assertEquals(1, seen.size)
    }

    @Test
    fun `a connection after close is refused`() {
        val bridge = open()
        val cookie = cookieOf(bridge)
        val port = bridge.origin.bridgePort
        bridge.close()
        bridge.close()

        assertFailsWith<ConnectException> {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 1_000) }
        }
        assertTrue(cookie.isNotEmpty())
        assertNothingReachedTheTunnel()
    }

    @Test
    fun `close ends open connections and the problems`() {
        val bridge = open()
        val cookie = cookieOf(bridge)
        Client(bridge.origin.bridgePort).use { client ->
            client.send("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: $cookie\r\n\r\n")
            assertEquals(200, client.reply().status)
            bridge.close()
            assertTrue(
                try {
                    client.isClosedByPeer()
                } catch (_: SocketTimeoutException) {
                    false
                },
            )
        }
        runBlocking { withTimeout(5_000) { bridge.problems.toList() } }
        assertFalse(tunnel.end.value != null, "Closing the bridge closed the tunnel")
    }

    @Test
    fun `a second preview gets a new port and a new secret`() {
        val first = open()
        val second = open()

        assertNotEquals(first.origin.bridgePort, second.origin.bridgePort)
        assertNotEquals(secretOf(first), secretOf(second))
    }

    private fun factory(headTimeoutMillis: Long) =
        LoopbackBridgeFactory(limits = BridgeLimits(headTimeoutMillis = headTimeoutMillis))
}
