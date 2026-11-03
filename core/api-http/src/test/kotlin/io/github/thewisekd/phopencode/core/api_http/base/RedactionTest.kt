package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The password, and every encoding of it a log could carry, must stay out of logs and errors. */
class RedactionTest {
    private val encoded = Base64.getEncoder().encodeToString("opencode:$TEST_PASSWORD".toByteArray())

    /** Server text that echoes the request back, as a server may. */
    private val echo = """{"_tag":"BadRequest","message":"bad header Basic $encoded for $TEST_PASSWORD"}"""

    private fun assertClean(text: String, where: String) {
        for (secret in listOf(TEST_PASSWORD, encoded, "Authorization", "Basic ")) {
            assertFalse(text.contains(secret, ignoreCase = true), "$where contains `$secret`: $text")
        }
    }

    @Test
    fun `logs carry method, path and status, and never the credentials or a body`() = runTest {
        TestServer().use { t ->
            val providers = fixture("catalog/http/05-providers.json")
            t.enqueue(body = providers)
            t.server.enqueue(json(401, echo))
            t.server.enqueue(json(400, echo))
            t.server.enqueue(json(500, echo))

            val location = Location("/workspace/project")
            t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "provider"), location = location))
            val body = buildJsonObject { put("text", JsonPrimitive(TEST_PASSWORD)) }
            repeat(3) {
                runCatching { t.transport.execute(ApiRequest(HttpMethod.POST, listOf("api", "session"), body = body)) }
            }
            t.dropEveryConnection()
            runCatching { t.transport.execute(ApiRequest(HttpMethod.POST, listOf("api", "session"), body = body)) }
            t.transport.close()
            runCatching { t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info"))) }

            val log = t.logger.lines.joinToString("\n")
            assertTrue(log.contains("GET /api/provider"), log)
            assertTrue(log.contains("401"), log)
            assertTrue(log.contains("POST /api/session"), log)
            assertTrue(log.contains("failed POST /api/session"), log)
            assertClean(log, "the log")
            assertFalse(log.contains("placeholder"), "a body reached the log: $log")
            assertFalse(log.contains("workspace"), "a query reached the log: $log")
        }
    }

    @Test
    fun `errors built from a reply that echoes the credentials print none of it`() = runTest {
        for (status in listOf(400, 401, 403, 404, 409, 418, 500)) {
            TestServer().use { t ->
                t.server.enqueue(json(status, echo))
                try {
                    t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info")))
                    fail("expected a failure for $status")
                } catch (e: ApiException) {
                    assertClean(e.message.orEmpty(), "the message for $status")
                    assertClean(e.toString(), "the exception for $status")
                    assertClean(e.error.toString(), "the error for $status")
                    assertClean(e.stackTraceToString(), "the stack trace for $status")
                }
            }
        }
    }

    @Test
    fun `printing the credentials and the transport shows no secret`() {
        val credentials = BasicCredentials(TEST_PASSWORD)
        assertEquals("BasicCredentials(***)", credentials.toString())
        TestServer().use { t ->
            assertClean(t.transport.toString(), "the transport")
            assertFalse(t.transport.toString().contains(t.server.hostName), "the transport printed its host")
        }
    }

    @Test
    fun `a call that fails before the server answers keeps the credentials out of the error`() = runTest {
        TestServer().use { t ->
            t.dropEveryConnection()
            try {
                t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info")))
                fail("expected a failure")
            } catch (e: ApiException) {
                assertEquals(ApiError.Network, e.error)
                assertClean(e.stackTraceToString(), "the stack trace")
            }
        }
    }
}
