package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect

/** A call that is not safe to repeat, such as a prompt, must never be resent by OkHttp on its own. */
class RetryTest {
    private val body = buildJsonObject { put("text", JsonPrimitive("hello")) }

    /**
     * A first call leaves a pooled connection. The next request on it is read whole, then the
     * server closes it without answering; a request after that would get an answer. OkHttp
     * resends a request that fails on a reused connection, which is the case to stop.
     */
    private suspend fun TestServer.dropPooledConnectionAfterReading() {
        enqueue(body = "{}")
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        enqueue(body = "{}")
        transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info")))
    }

    @Test
    fun `a request that is not repeatable reaches the server once and fails with Network`() = runTest {
        TestServer().use { t ->
            t.dropPooledConnectionAfterReading()
            val prompt = ApiRequest(
                HttpMethod.POST,
                listOf("api", "session", "ses_1", "prompt"),
                body = body,
                repeatable = false,
            )

            assertApiError(ApiError.Network) { t.transport.execute(prompt) }
            // The priming call, then the prompt once.
            assertEquals(2, t.server.requestCount)
        }
    }

    @Test
    fun `a normal request is still retried after a dropped connection`() = runTest {
        TestServer().use { t ->
            t.dropPooledConnectionAfterReading()
            val request = ApiRequest(HttpMethod.POST, listOf("api", "session"), body = body)

            assertEquals(200, t.transport.execute(request).status)
            assertEquals(3, t.server.requestCount)
        }
    }

    @Test
    fun `requests are repeatable unless marked`() {
        assertTrue(ApiRequest(HttpMethod.POST, listOf("api")).repeatable)
    }

    @Test
    fun `both kinds of call share one dispatcher, so close stops both`() = runTest {
        TestServer().use { t ->
            t.transport.close()
            assertApiError(ApiError.Closed) {
                t.transport.execute(ApiRequest(HttpMethod.POST, listOf("api"), repeatable = false))
            }
            assertEquals(0, t.server.requestCount)
        }
    }

    private fun once(method: HttpMethod = HttpMethod.POST, withBody: Boolean = true) = ApiRequest(
        method,
        listOf("api", "session", "ses_1", "prompt"),
        body = if (withBody) body else null,
        repeatable = false,
    )

    private fun TestServer.answerThenSucceed(status: Int, vararg headers: Pair<String, String>) {
        server.enqueue(
            MockResponse.Builder().code(status).apply { headers.forEach { (n, v) -> addHeader(n, v) } }
                .body("""{"message":"try again"}""").build(),
        )
        enqueue(body = "{}")
    }

    @Test
    fun `503 with Retry-After 0 is not resent for a request that is not repeatable`() = runTest {
        for (request in listOf(once(), once(withBody = false), once(HttpMethod.DELETE, withBody = false))) {
            TestServer().use { t ->
                t.answerThenSucceed(503, "Retry-After" to "0")
                assertEquals(ApiError.Server(503, "try again"), apiError { t.transport.execute(request) }, "$request")
                assertEquals(1, t.server.requestCount, "$request")
            }
        }
    }

    @Test
    fun `503 with Retry-After 0 is still resent for a normal request`() = runTest {
        TestServer().use { t ->
            t.answerThenSucceed(503, "Retry-After" to "0")
            assertEquals(200, t.transport.execute(ApiRequest(HttpMethod.POST, listOf("api"), body = body)).status)
            assertEquals(2, t.server.requestCount)
        }
    }

    @Test
    fun `408 is not resent for a request that is not repeatable`() = runTest {
        TestServer().use { t ->
            t.answerThenSucceed(408)
            assertEquals(ApiError.Unexpected(408, "try again"), apiError { t.transport.execute(once()) })
            assertEquals(1, t.server.requestCount)
        }
    }

    @Test
    fun `an authenticator follow-up is not sent for a request that is not repeatable`() = runTest {
        val retrying = TestServer(configure = {
            authenticator { _, response -> response.request.newBuilder().header("X-Again", "1").build() }
        })
        retrying.use { t ->
            t.answerThenSucceed(401)
            assertEquals(ApiError.Unauthorized, apiError { t.transport.execute(once()) })
            assertEquals(1, t.server.requestCount)
        }
    }

    @Test
    fun `a GET is always safe to repeat, so it cannot be marked otherwise`() {
        assertFailsWith<IllegalArgumentException> {
            ApiRequest(HttpMethod.GET, listOf("api"), repeatable = false)
        }
    }

    @Test
    fun `the network guard allows one attempt per call`() {
        // The backstop behind the one-shot body: a second network attempt is refused before the wire.
        val guard = SingleAttemptGuard()
        assertTrue(guard.firstAttempt())
        assertFalse(guard.firstAttempt())
        assertFalse(guard.firstAttempt())
    }
}
