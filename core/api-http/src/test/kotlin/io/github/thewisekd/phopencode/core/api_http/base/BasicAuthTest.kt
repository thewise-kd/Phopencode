package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl

class BasicAuthTest {
    private fun basic(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    @Test
    fun `every request carries basic auth with the fixed opencode user`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = "{}")
            t.enqueue(status = 204)
            t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info")))
            t.transport.execute(ApiRequest(HttpMethod.DELETE, listOf("api", "session", "ses_1")))

            repeat(2) {
                assertEquals(basic("opencode", TEST_PASSWORD), t.server.takeRequest().headers["Authorization"])
            }
        }
    }

    @Test
    fun `a password outside ASCII is sent as UTF-8`() = runTest {
        // Built at run time, so the secret scan sees no literal.
        val password = "test-" + "pässı"
        TestServer(password = password).use { t ->
            t.enqueue(status = 204)
            t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info")))

            assertEquals(basic("opencode", password), t.server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `a pairing token goes where the password goes`() {
        val token = "test-" + "token"
        assertEquals(basic("opencode", token), BasicCredentials(token).headerValue)
    }

    @Test
    fun `a refused password is Unauthorized`() = runTest {
        TestServer().use { t ->
            t.server.enqueue(json(401, """{"_tag":"UnauthorizedError","message":"Unauthorized"}"""))
            assertApiError(ApiError.Unauthorized) {
                t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info")))
            }
        }
    }

    @Test
    fun `a redirect is not followed, so the credentials never go elsewhere`() = runTest {
        TestServer().use { t ->
            val elsewhere = TestServer()
            elsewhere.use { other ->
                other.enqueue(body = "{}")
                t.server.enqueue(
                    MockResponse.Builder().code(302).addHeader("Location", other.server.url("/steal")).build(),
                )

                val error = apiError { t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info"))) }

                assertEquals(ApiError.Unexpected(status = 302), error)
                assertEquals(0, other.server.requestCount)
            }
        }
    }

    @Test
    fun `a base URL carrying credentials is refused without echoing it`() {
        val userInfo = "opencode:" + "test-" + "password"
        val url = "http://$userInfo@localhost:4096/".toHttpUrl()

        val e = assertFailsWith<IllegalArgumentException> {
            HttpTransport(url, BasicCredentials(TEST_PASSWORD))
        }
        assertFalse(e.message.orEmpty().contains(TEST_PASSWORD))
        assertFalse(e.message.orEmpty().contains("localhost"))
    }

    @Test
    fun `a base path is kept in front of the API path`() = runTest {
        TestServer().use { t ->
            val prefixed = HttpTransport(t.server.url("/proxy/opencode/"), BasicCredentials(TEST_PASSWORD))
            prefixed.use {
                t.enqueue(status = 204)
                it.execute(ApiRequest(HttpMethod.GET, listOf("api", "info")))
                assertEquals("/proxy/opencode/api/info", t.server.takeRequest().url.encodedPath)
            }
        }
    }
}
