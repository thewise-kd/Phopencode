package io.github.thewisekd.phopencode.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SENTINEL = "SENTINEL-server-text"

class ApiErrorTest {
    private val samples: List<ApiError> = listOf(
        ApiError.Unauthorized,
        ApiError.Forbidden,
        ApiError.NotFound,
        ApiError.Conflict(SENTINEL),
        ApiError.BadRequest(SENTINEL),
        ApiError.Server(status = 500, detail = SENTINEL),
        ApiError.Network,
        ApiError.Timeout,
        ApiError.VersionTooOld(found = "2.1.0"),
        ApiError.MalformedResponse,
        ApiError.Unexpected(status = 418, detail = SENTINEL),
        ApiError.Closed,
    )

    /** Has no `else`: adding an error fails compilation here until it is handled. */
    private fun describe(error: ApiError): String = when (error) {
        ApiError.Unauthorized -> "unauthorized"
        ApiError.Forbidden -> "forbidden"
        ApiError.NotFound -> "not-found"
        is ApiError.Conflict -> "conflict"
        is ApiError.BadRequest -> "bad-request"
        is ApiError.Server -> "server"
        ApiError.Network -> "network"
        ApiError.Timeout -> "timeout"
        is ApiError.VersionTooOld -> "version-too-old"
        ApiError.MalformedResponse -> "malformed-response"
        is ApiError.Unexpected -> "unexpected"
        ApiError.Closed -> "closed"
    }

    private fun leaves(type: Class<*>): Set<Class<*>> =
        if (type.isSealed) type.permittedSubclasses.flatMap(::leaves).toSet() else setOf(type)

    @Test
    fun `every error has a sample and a description of its own`() {
        assertEquals(leaves(ApiError::class.java), samples.map { it.javaClass }.toSet())
        assertEquals(samples.size, samples.map(::describe).toSet().size)
    }

    @Test
    fun `every error has its own readable message`() {
        samples.forEach { assertTrue(it.message.isNotBlank(), "no message: ${describe(it)}") }
        assertEquals(samples.size, samples.map { it.message }.toSet().size)
    }

    @Test
    fun `a server error message gives the status`() {
        assertTrue("503" in ApiError.Server(status = 503).message)
        assertTrue("418" in ApiError.Unexpected(status = 418).message)
    }

    @Test
    fun `an unexpected error can have no status`() {
        assertFalse("null" in ApiError.Unexpected().message)
    }

    @Test
    fun `a version error gives both versions`() {
        val error = ApiError.VersionTooOld(found = "2.1.7")

        assertEquals(OpencodeClient.MIN_SERVER_VERSION, error.required)
        assertTrue("2.1.7" in error.message)
        assertTrue(OpencodeClient.MIN_SERVER_VERSION in error.message)
    }

    @Test
    fun `server text stays readable on the error`() {
        assertEquals("busy", ApiError.Conflict("busy").detail)
    }

    @Test
    fun `server text never reaches toString or message`() {
        samples.forEach { error ->
            assertFalse(SENTINEL in error.toString(), "toString leaked: ${describe(error)}")
            assertFalse(SENTINEL in error.message, "message leaked: ${describe(error)}")
        }
    }

    @Test
    fun `server text never reaches the exception or its stack trace`() {
        samples.map(::ApiException).forEach { exception ->
            assertFalse(SENTINEL in exception.toString())
            assertFalse(SENTINEL in exception.stackTraceToString())
        }
    }

    @Test
    fun `toString names the error`() {
        assertTrue("Conflict" in ApiError.Conflict(SENTINEL).toString())
        assertTrue("Unauthorized" in ApiError.Unauthorized.toString())
    }

    @Test
    fun `masking changes printing only, not equality`() {
        assertEquals(ApiError.Conflict("a"), ApiError.Conflict("a"))
        assertNotEquals(ApiError.Conflict("a"), ApiError.Conflict("b"))
    }

    @Test
    fun `an exception carries the typed error and no cause that could print a secret`() {
        val exception = ApiException(ApiError.NotFound)

        assertEquals(ApiError.NotFound, exception.error)
        assertEquals(ApiError.NotFound.message, exception.message)
        assertNull(exception.cause)
    }
}
