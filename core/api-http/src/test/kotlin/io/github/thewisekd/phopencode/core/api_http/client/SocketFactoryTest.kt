package io.github.thewisekd.phopencode.core.api_http.client

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api_http.base.BasicCredentials
import io.github.thewisekd.phopencode.core.api_http.base.TEST_PASSWORD
import io.github.thewisekd.phopencode.core.api_http.base.apiError
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import okhttp3.Credentials

/**
 * A tunnel's [SocketFactory] given to [HttpOpencodeClient.create] carries every call.
 *
 * The client is pointed at a loopback port where nothing listens, as a tunnel's "127.0.0.1" names the
 * server's loopback, not the phone's. [RedirectingSocketFactory] stands in for the tunnel: it sends
 * every socket to the scripted server instead, so a call that bypassed it would be refused.
 */
class SocketFactoryTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    /** The scripted server, and a loopback address with nothing behind it. */
    private inner class Setup : AutoCloseable {
        val fake = FullServer("/srv/default")
        val web = MockWebServer().apply {
            dispatcher = fake
            start(loopback, 0)
        }
        val unusedPort: Int = ServerSocket(0, 1, loopback).use { it.localPort }
        val tunnelUrl = "http://${loopback.hostAddress}:$unusedPort/"
        val tunnel = RedirectingSocketFactory(InetSocketAddress(loopback, web.port))

        override fun close() {
            fake.releaseAll()
            web.close()
        }
    }

    private fun test(block: suspend Setup.() -> Unit) = runBlocking {
        Setup().use { withContext(Dispatchers.Default) { withTimeout(20_000) { it.block() } } }
    }

    @Test
    fun `an API call goes through the given socket factory`() = test {
        HttpOpencodeClient.create(tunnelUrl, BasicCredentials(TEST_PASSWORD), socketFactory = tunnel).use { client ->
            assertEquals("2.2.5", client.serverInfo().version)
        }
        assertTrue(tunnel.sockets.get() >= 1, "the call must open its socket through the factory")
        assertEquals(listOf(InetSocketAddress(loopback, unusedPort)), tunnel.requested.distinct())
        val request = checkNotNull(web.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/api/info", request.url.encodedPath)
        assertEquals(Credentials.basic("opencode", TEST_PASSWORD), request.headers["Authorization"])
    }

    @Test
    fun `the event stream goes through the given socket factory`() = test {
        HttpOpencodeClient.create(tunnelUrl, BasicCredentials(TEST_PASSWORD), socketFactory = tunnel).use { client ->
            assertEquals<ServerEvent>(ServerEvent.Connected(), client.events.events().first())
        }
        assertTrue(fake.eventRequests.get() >= 1)
        assertTrue(tunnel.sockets.get() >= 1, "the stream must open its socket through the factory")
        assertEquals(listOf(InetSocketAddress(loopback, unusedPort)), tunnel.requested.distinct())
    }

    @Test
    fun `calls and the stream of one client share the factory`() = test {
        HttpOpencodeClient.create(tunnelUrl, BasicCredentials(TEST_PASSWORD), socketFactory = tunnel).use { client ->
            client.serverInfo()
            assertEquals<ServerEvent>(ServerEvent.Connected(), client.events.events().first())
            client.serverInfo()
        }
        assertEquals(3, web.requestCount)
        assertEquals(listOf(InetSocketAddress(loopback, unusedPort)), tunnel.requested.distinct())
    }

    @Test
    fun `without the factory the same address is the phone's own loopback, where nothing listens`() = test {
        HttpOpencodeClient.create(tunnelUrl, BasicCredentials(TEST_PASSWORD)).use { client ->
            assertEquals(ApiError.Network, apiError { client.serverInfo() })
        }
        assertEquals(0, web.requestCount)
        assertEquals(0, tunnel.sockets.get())
    }

    @Test
    fun `without a factory calls and the stream go straight to the address`() = test {
        HttpOpencodeClient.create(web.url("/").toString(), BasicCredentials(TEST_PASSWORD)).use { client ->
            assertEquals("2.2.5", client.serverInfo().version)
            assertEquals<ServerEvent>(ServerEvent.Connected(), client.events.events().first())
        }
        assertEquals(2, web.requestCount)
        assertEquals(1, fake.eventRequests.get())
    }

    @Test
    fun `the logger stays the third parameter`() = test {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        HttpOpencodeClient.create(web.url("/").toString(), BasicCredentials(TEST_PASSWORD), { lines += it }).use {
            it.serverInfo()
        }
        assertTrue(lines.any { it.contains("/api/info") })
    }
}

/**
 * Stands in for a tunnel: whatever address a socket is asked to reach, it connects to [target], as
 * a direct-tcpip channel reaches the server's loopback. Records every address asked for.
 */
private class RedirectingSocketFactory(private val target: InetSocketAddress) : SocketFactory() {
    val sockets = AtomicInteger()
    val requested: MutableList<SocketAddress> = Collections.synchronizedList(mutableListOf())

    override fun createSocket(): Socket {
        sockets.incrementAndGet()
        return object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                requested += endpoint
                super.connect(target, timeout)
            }
        }
    }

    override fun createSocket(host: String?, port: Int): Socket = connected(InetSocketAddress(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        connected(InetSocketAddress(host, port))

    override fun createSocket(host: InetAddress?, port: Int): Socket = connected(InetSocketAddress(host, port))

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        connected(InetSocketAddress(address, port))

    private fun connected(endpoint: SocketAddress): Socket = createSocket().apply { connect(endpoint, 0) }
}
