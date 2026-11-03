package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/** An ID is always one path segment: it can never move the call to another route. */
class PathSegmentTest {
    private fun sessionPath(id: String) = ApiRequest(HttpMethod.DELETE, listOf("api", "session", id))

    @Test
    fun `dot segments and empty segments are refused before anything is sent`() = runTest {
        val bad = listOf(
            "", ".", "..", "%2e", "%2E", "%2e%2e", "%2E%2e", "%2e.", ".%2E", "%252e", "%252E%252e",
        )
        TestServer().use { t ->
            for (segment in bad) {
                val error = apiError { t.transport.execute(sessionPath(segment)) }
                assertIs<ApiError.BadRequest>(error, "`$segment`")
                // Shorter values are too common to search for: a sentence ends with `.`.
                if (segment.length >= 3) {
                    assertFalse(error.detail.orEmpty().contains(segment), "the error echoed `$segment`")
                }
            }
            assertEquals(0, t.server.requestCount)
        }
    }

    @Test
    fun `the event stream path refuses them too`() = runTest {
        TestServer().use { t ->
            val error = apiError { t.transport.toOkHttp(sessionPath("..")) }
            assertIs<ApiError.BadRequest>(error)
        }
    }

    @Test
    fun `IDs with dots inside stay one segment`() = runTest {
        val good = listOf("a..b", ".hidden", "v1.2", "...", "..a", "%2e%2e%2e", "ses_1")
        TestServer().use { t ->
            for (segment in good) {
                t.enqueue(status = 204)
                t.transport.execute(sessionPath(segment))
                assertEquals(listOf("api", "session", segment), t.server.takeRequest().url.pathSegments, segment)
            }
        }
    }
}
