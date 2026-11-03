package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl

class ErrorMappingTest {
    private val info = ApiRequest(HttpMethod.GET, listOf("api", "info"))

    private suspend fun errorFor(status: Int, body: String): ApiError = TestServer().use { t ->
        t.server.enqueue(json(status, body))
        apiError { t.transport.execute(info) }
    }

    @Test
    fun `401 is Unauthorized`() = runTest {
        assertEquals(ApiError.Unauthorized, errorFor(401, ""))
    }

    @Test
    fun `403 is Forbidden`() = runTest {
        assertEquals(ApiError.Forbidden, errorFor(403, "{}"))
    }

    @Test
    fun `recorded 404 bodies are NotFound`() = runTest {
        for (path in listOf(
            "startup/http/03-unknown-session.json",
            "lifecycle/http/18-delete-again.json",
            "permission/http/08-reply-again.json",
        )) {
            assertEquals(ApiError.NotFound, errorFor(404, fixture(path)), path)
        }
    }

    @Test
    fun `the recorded 409 is a Conflict with the server's message`() = runTest {
        assertEquals(
            ApiError.Conflict("Form already settled: frm_00000000000000000000000001"),
            errorFor(409, fixture("form-question/http/09-reply-again.json")),
        )
    }

    @Test
    fun `the recorded 400 is a BadRequest with the server's message`() = runTest {
        assertEquals(
            ApiError.BadRequest("Expected string for form field: q0"),
            errorFor(400, fixture("form-question/http/06-reply-invalid.json")),
        )
    }

    @Test
    fun `5xx is Server with its status`() = runTest {
        assertEquals(ApiError.Server(500, "boom"), errorFor(500, """{"_tag":"UnknownError","message":"boom"}"""))
        assertEquals(ApiError.Server(502, null), errorFor(502, "<html>bad gateway</html>"))
        assertEquals(
            ApiError.Server(503, "Service starting"),
            errorFor(503, """{"_tag":"ServiceUnavailableError","code":"service_starting","message":"Service starting"}"""),
        )
        assertEquals(ApiError.Server(599, null), errorFor(599, ""))
    }

    @Test
    fun `any other status is Unexpected`() = runTest {
        assertEquals(ApiError.Unexpected(418, "teapot"), errorFor(418, """{"message":"teapot"}"""))
        assertEquals(ApiError.Unexpected(429, null), errorFor(429, ""))
    }

    @Test
    fun `a message that is not text, or a body that is not an object, gives no detail`() = runTest {
        assertEquals(ApiError.Conflict(null), errorFor(409, """{"message":{"nested":true}}"""))
        assertEquals(ApiError.Conflict(null), errorFor(409, """["Form already settled"]"""))
        assertEquals(ApiError.Conflict(null), errorFor(409, "Form already settled"))
    }

    @Test
    fun `a very long server message is cut`() = runTest {
        val error = errorFor(409, """{"message":"${"x".repeat(100_000)}"}""")
        assertIs<ApiError.Conflict>(error)
        assertTrue(error.detail!!.length <= MAX_DETAIL_LENGTH)
    }

    @Test
    fun `a refused connection is Network`() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        HttpTransport("http://127.0.0.1:$port/".toHttpUrl(), BasicCredentials(TEST_PASSWORD)).use {
            assertApiError(ApiError.Network) { it.execute(info) }
        }
    }

    @Test
    fun `a dropped connection is Network`() = runTest {
        TestServer().use { t ->
            t.dropEveryConnection()
            assertApiError(ApiError.Network) { t.transport.execute(info) }
        }
    }

    @Test
    fun `a server that does not answer in time is Timeout`() = runTest {
        TestServer(configure = { readTimeout(200, TimeUnit.MILLISECONDS) }).use { t ->
            t.server.enqueue(MockResponse.Builder().body("{}").headersDelay(5, TimeUnit.SECONDS).build())
            val error = withContext(Dispatchers.IO) { apiError { t.transport.execute(info) } }
            assertEquals(ApiError.Timeout, error)
        }
    }

    @Serializable
    private data class Info(val version: String)

    @Test
    fun `a reply that is not the expected JSON is MalformedResponse`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = "not json")
            t.enqueue(body = """{"pid":1}""")
            t.enqueue(body = "")
            t.enqueue(body = """{"version":12}""")
            repeat(4) {
                assertApiError(ApiError.MalformedResponse) { t.transport.receive(info, Info.serializer()) }
            }
        }
    }

    @Test
    fun `a call after close is Closed and reaches no server`() = runTest {
        TestServer().use { t ->
            t.transport.close()
            t.transport.close()
            assertApiError(ApiError.Closed) { t.transport.execute(info) }
            assertEquals(0, t.server.requestCount)
        }
    }

    @Test
    fun `a running call fails with Closed when the transport closes`() = runTest {
        TestServer().use { t ->
            t.server.enqueue(MockResponse.Builder().body("{}").headersDelay(30, TimeUnit.SECONDS).build())
            val error = withContext(Dispatchers.IO) {
                val call = async { apiError { t.transport.execute(info) } }
                t.server.takeRequest(5, TimeUnit.SECONDS)
                t.transport.close()
                withTimeout(5_000) { call.await() }
            }
            assertEquals(ApiError.Closed, error)
        }
    }

    @Test
    fun `cancelling the coroutine cancels the call and stays a CancellationException`() = runTest {
        TestServer().use { t ->
            t.server.enqueue(MockResponse.Builder().body("{}").headersDelay(30, TimeUnit.SECONDS).build())
            withContext(Dispatchers.IO) {
                var thrown: Throwable? = null
                val call = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        t.transport.receive(info, String.serializer())
                    } catch (e: Throwable) {
                        thrown = e
                        throw e
                    }
                }
                t.server.takeRequest(5, TimeUnit.SECONDS)
                call.cancel()
                call.join()
                assertIs<CancellationException>(thrown)
                withTimeout(5_000) {
                    while (t.transport.runningCalls() > 0) kotlinx.coroutines.delay(10)
                }
            }
            assertEquals(0, t.transport.runningCalls())
        }
    }

    @Test
    fun `failure mapping never wraps a cause`() {
        val mapped = listOf(
            java.io.IOException("x"),
            java.net.SocketTimeoutException("x"),
            java.net.UnknownHostException("x"),
            kotlinx.serialization.SerializationException("x"),
            IllegalStateException("x"),
        ).map { ErrorMapping.forFailure(it) }

        assertEquals(
            listOf(
                ApiError.Network,
                ApiError.Timeout,
                ApiError.Network,
                ApiError.MalformedResponse,
                ApiError.Unexpected(),
            ),
            mapped,
        )
    }
}
