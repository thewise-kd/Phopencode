package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect

/** A body still arriving when the transport closes ends the call with Closed, error body or not. */
class BodyReadTest {
    private val info = ApiRequest(HttpMethod.GET, listOf("api", "info"))

    /** Headers at once, then the body at 64 bytes a second: reading it takes far longer than the test. */
    private fun slow(status: Int): MockResponse = MockResponse.Builder()
        .code(status)
        .body("""{"message":"${"x".repeat(10_000)}"}""")
        .throttleBody(64, 1, TimeUnit.SECONDS)
        .build()

    private suspend fun TestServer.closeWhileReading(status: Int): ApiError = withContext(Dispatchers.IO) {
        server.enqueue(slow(status))
        val call = async { apiError { transport.execute(info) } }
        server.takeRequest(5, TimeUnit.SECONDS)
        delay(300) // The headers are in; the body is being read.
        transport.close()
        withTimeout(5_000) { call.await() }
    }

    @Test
    fun `closing while an error body is read gives Closed`() = runTest {
        for (status in listOf(400, 404, 409, 500)) {
            TestServer().use { t -> assertEquals(ApiError.Closed, t.closeWhileReading(status), "status $status") }
        }
    }

    @Test
    fun `closing while a reply body is read gives Closed`() = runTest {
        TestServer().use { t -> assertEquals(ApiError.Closed, t.closeWhileReading(200)) }
    }

    @Test
    fun `cancelling the coroutine while an error body is read stays a CancellationException`() = runTest {
        TestServer().use { t ->
            withContext(Dispatchers.IO) {
                t.server.enqueue(slow(500))
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
                delay(300)
                call.cancel()
                withTimeout(5_000) { call.join() }
                assertIs<CancellationException>(thrown)
            }
        }
    }

    @Test
    fun `an error body that breaks off on an open transport keeps the status error`() = runTest {
        TestServer().use { t ->
            t.server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .body("""{"message":"${"x".repeat(100_000)}"}""")
                    .onResponseBody(SocketEffect.CloseSocket())
                    .build(),
            )
            assertEquals(ApiError.Conflict(null), apiError { t.transport.execute(info) })
        }
    }
}
