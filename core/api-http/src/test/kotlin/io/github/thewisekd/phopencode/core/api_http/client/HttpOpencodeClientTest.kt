package io.github.thewisekd.phopencode.core.api_http.client

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api_http.base.BasicCredentials
import io.github.thewisekd.phopencode.core.api_http.base.TEST_PASSWORD
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.apiError
import io.github.thewisekd.phopencode.core.api_http.base.assertApiError
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import io.github.thewisekd.phopencode.core.model.DataValue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import okhttp3.Credentials

class HttpOpencodeClientTest {
    private fun TestServer.client() = HttpOpencodeClient(transport, FAST_EVENTS)

    @Test
    fun `serverInfo reads the recorded api info reply`() = runTest {
        TestServer().use { s ->
            s.enqueue(body = fixture("startup/http/01-info.json"))
            val info = s.client().serverInfo()
            assertEquals("2.2.5", info.version)
            assertFalse(info.isNewerMajor)
            assertEquals(DataValue.Fields(mapOf("persistentPty" to DataValue.Bool(true))), info.capabilities)

            val request = checkNotNull(s.server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/api/info", request.url.encodedPath)
            assertEquals(Credentials.basic("opencode", TEST_PASSWORD), request.headers["Authorization"])
        }
    }

    @Test
    fun `the server's addresses, host name and paths are not kept`() = runTest {
        TestServer().use { s ->
            val recorded = fixture("startup/http/01-info.json")
            s.enqueue(body = recorded)
            val info = s.client().serverInfo()
            val printed = info.toString()
            listOf("fixture-host", "127.0.0.1", "4096", "/tmp/capture", "4242").forEach {
                assertTrue(recorded.contains(it), "the fixture holds <$it>")
                assertFalse(printed.contains(it), "ServerInfo must not hold <$it>")
            }
            assertFalse(s.logger.lines.any { line -> listOf("fixture-host", TEST_PASSWORD).any { line.contains(it) } })
        }
    }

    @Test
    fun `below 2_2 serverInfo fails with VersionTooOld`() = runTest {
        TestServer().use { s ->
            s.enqueue(body = """{"version":"2.1.9","pid":1,"urls":[],"paths":{"tmp":"/t"}}""")
            assertApiError(ApiError.VersionTooOld("2.1.9")) { s.client().serverInfo() }
        }
    }

    @Test
    fun `a V1 server, which has no api info route, fails with NotFound`() = runTest {
        TestServer().use { s ->
            s.enqueue(status = 404, body = "Not Found")
            assertApiError(ApiError.NotFound) { s.client().serverInfo() }
        }
    }

    @Test
    fun `a newer major is reported as a warning`() = runTest {
        TestServer().use { s ->
            s.enqueue(body = """{"version":"3.0.1","pid":1,"urls":[],"paths":{"tmp":"/t"},"capabilities":null}""")
            val info = s.client().serverInfo()
            assertTrue(info.isNewerMajor)
            assertEquals("3.0.1", info.version)
            assertNull(info.capabilities)
        }
    }

    @Test
    fun `an unparseable version fails with VersionTooOld of unknown`() = runTest {
        TestServer().use { s ->
            s.enqueue(body = """{"version":"local","pid":1,"urls":[],"paths":{"tmp":"/t"}}""")
            assertApiError(ApiError.VersionTooOld(VersionGate.UNKNOWN_VERSION)) { s.client().serverInfo() }
        }
    }

    @Test
    fun `a reply without a version string is malformed`() = runTest {
        listOf(
            """{"pid":1}""",
            """{"version":null}""",
            """{"version":2.2}""",
            """{"version":true}""",
            """{"version":["2.2.5"]}""",
            """{"version":{"major":2}}""",
            """["2.2.5"]""",
            """not json""",
            "",
        ).forEach { body ->
            TestServer().use { s ->
                s.enqueue(body = body)
                assertEquals(ApiError.MalformedResponse, apiError { s.client().serverInfo() }, body)
            }
        }
    }

    @Test
    fun `odd capabilities do not fail serverInfo`() = runTest {
        TestServer().use { s ->
            s.enqueue(body = """{"version":"2.2.5","capabilities":{"persistentPty":null,"new":[1,"a",{"b":false}]}}""")
            val capabilities = s.client().serverInfo().capabilities
            assertEquals(
                DataValue.Fields(
                    mapOf(
                        "persistentPty" to DataValue.Null,
                        "new" to DataValue.Items(
                            listOf(
                                DataValue.Num(1.toBigDecimal()),
                                DataValue.Text("a"),
                                DataValue.Fields(mapOf("b" to DataValue.Bool(false))),
                            ),
                        ),
                    ),
                ),
                capabilities,
            )
        }
    }

    @Test
    fun `an error reply keeps its typed error`() = runTest {
        TestServer().use { s ->
            s.enqueue(status = 401, body = """{"_tag":"UnauthorizedError","message":"no"}""")
            assertApiError(ApiError.Unauthorized) { s.client().serverInfo() }
        }
    }

    @Test
    fun `close closes the transport, and every API then fails with Closed`() = runTest {
        TestServer().use { s ->
            val client = s.client()
            client.close()
            assertTrue(s.transport.isClosed)
            assertApiError(ApiError.Closed) { client.serverInfo() }
            assertApiError(ApiError.Closed) { client.sessions.list() }
            assertApiError(ApiError.Closed) { client.events.events().collect {} }
            assertEquals(0, s.server.requestCount)
        }
    }

    @Test
    fun `calls waiting behind the per-host limit also fail with Closed on close`() = runTest {
        // The production transport, with OkHttp's per-host limit as the client sets it.
        FullHarness(maxCallsPerHost = 16).use { h ->
            h.server.stall()
            val calls = List(24) { async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { runCatching { h.client.sessions.list() } } }
            withContext(Dispatchers.Default) { withTimeout(10_000) { while (h.server.waitingCalls < 16) delay(10) } }
            h.client.close()
            calls.forEach { call ->
                val failure = withContext(Dispatchers.Default) { withTimeout(10_000) { call.await() } }.exceptionOrNull()
                assertEquals(ApiError.Closed, (failure as? ApiException)?.error, "failed with <$failure>")
            }
        }
    }

    @Test
    fun `create builds a working client from an address and credentials`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(mockwebserver3.MockResponse.Builder().body("""{"version":"2.2.5"}""").build())
            HttpOpencodeClient.create(server.url("/").toString(), BasicCredentials(TEST_PASSWORD)).use { client ->
                assertEquals("2.2.5", client.serverInfo().version)
                assertFalse(client.toString().contains(server.port.toString()), "toString must not show the address")
                assertFalse(client.toString().contains(server.hostName))
            }
            val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(Credentials.basic("opencode", TEST_PASSWORD), request.headers["Authorization"])
        }
    }

    @Test
    fun `create refuses a bad address without quoting it`() {
        val secretish = "private-host.example.invalid"
        listOf("ftp://$secretish/", "$secretish:4096", "", "http://", "https://user:${TEST_PASSWORD}@$secretish/").forEach { address ->
            val failure = assertFailsWith<IllegalArgumentException> {
                HttpOpencodeClient.create(address, BasicCredentials(TEST_PASSWORD))
            }
            val message = failure.message.orEmpty()
            assertFalse(message.contains(secretish), "the error must not quote the address: $message")
            assertFalse(message.contains(TEST_PASSWORD), "the error must not quote the password: $message")
            assertNull(failure.cause, "a cause could quote the address")
        }
    }
}
