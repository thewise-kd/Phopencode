package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials

/**
 * [OpencodeClientFactory.Default] sends everything through the session's tunnel.
 *
 * The base URL names a loopback port where nothing listens, as the manager's address names the SSH
 * server's loopback. [TunnelStandIn] plays the tunnel and carries each socket to the scripted
 * server, so any connection made without it is refused and the server never sees it.
 */
class OpencodeClientFactoryTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    /** Answers `/api/info` and opens an event stream that sends `server.connected`. */
    private class Opencode : Dispatcher() {
        val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            return when (request.url.encodedPath) {
                "/api/info" -> MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("""{"version":"2.2.5"}""")
                    .build()
                "/api/event" -> MockResponse.Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: {\"id\":\"evt_1\",\"type\":\"server.connected\",\"data\":{}}\n\n")
                    .build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
    }

    private inner class Setup : AutoCloseable {
        val opencode = Opencode()
        val web = MockWebServer().apply {
            dispatcher = opencode
            start(loopback, 0)
        }
        val serverSidePort: Int = ServerSocket(0, 1, loopback).use { it.localPort }
        val baseUrl = "http://${loopback.hostAddress}:$serverSidePort"
        val tunnel = TunnelStandIn(InetSocketAddress(loopback, web.port))

        override fun close() = web.close()
    }

    private fun test(block: suspend Setup.() -> Unit) = runBlocking {
        Setup().use { withContext(Dispatchers.Default) { withTimeout(20_000) { it.block() } } }
    }

    @Test
    fun `requests and the event stream go through the session's socket factory`() = test {
        val client = SecretValue.ofText(OPENCODE_PASSWORD).use { OpencodeClientFactory.Default.create(baseUrl, it, tunnel) }
        client.use {
            assertEquals("2.2.5", it.serverInfo().version)
            assertEquals<ServerEvent>(ServerEvent.Connected(), it.events.events().first())
            assertEquals("2.2.5", it.serverInfo().version)
        }
        val paths = opencode.requests.map { it.url.encodedPath }
        // The stream's body ends after one event, so a reconnect may add more event requests.
        assertEquals(2, paths.count { it == "/api/info" })
        assertTrue("/api/event" in paths)
        assertTrue(tunnel.sockets.get() >= 1)
        assertEquals(listOf<SocketAddress>(InetSocketAddress(loopback, serverSidePort)), tunnel.requested.distinct())
    }

    @Test
    fun `the client keeps its own copy of the password, which the manager closes after create`() = test {
        val password = SecretValue.ofText(OPENCODE_PASSWORD)
        val client = OpencodeClientFactory.Default.create(baseUrl, password, tunnel)
        password.close()
        client.use { it.serverInfo() }
        assertEquals(Credentials.basic("opencode", OPENCODE_PASSWORD), opencode.requests.single().headers["Authorization"])
    }

    @Test
    fun `nothing is sent before the first call`() = test {
        SecretValue.ofText(OPENCODE_PASSWORD).use { OpencodeClientFactory.Default.create(baseUrl, it, tunnel) }.close()
        assertEquals(0, tunnel.sockets.get())
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `without the tunnel the address is the phone's own loopback and nothing reaches the server`() = test {
        val direct = SocketFactory.getDefault()
        val client = SecretValue.ofText(OPENCODE_PASSWORD).use { OpencodeClientFactory.Default.create(baseUrl, it, direct) }
        client.use { assertFailsWith<ApiException> { it.serverInfo() } }
        assertEquals(0, web.requestCount)
    }
}

/**
 * Stands in for the SSH tunnel: every socket reaches [target], whatever address it is asked for, as
 * a direct-tcpip channel reaches the server's loopback. Records the addresses asked for.
 */
private class TunnelStandIn(private val target: InetSocketAddress) : SocketFactory() {
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
