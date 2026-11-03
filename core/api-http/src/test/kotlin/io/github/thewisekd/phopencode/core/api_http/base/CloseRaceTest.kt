package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/** Closing while calls start on other threads leaves no call running and none to start later. */
class CloseRaceTest {
    private val info = ApiRequest(HttpMethod.GET, listOf("api", "info"))

    @Test
    fun `close racing with many starting calls leaves every call finished or Closed`() = runTest {
        withContext(Dispatchers.IO) {
            repeat(ROUNDS) { round ->
                TestServer().use { t ->
                    t.server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            MockResponse.Builder().body("{}").headersDelay(20, TimeUnit.MILLISECONDS).build()
                    }
                    val calls = List(CALLS) {
                        async { runCatching { t.transport.execute(info) } }
                    }
                    delay((round % 4).toLong())
                    t.transport.close()

                    // Every call the transport ever made is cancelled once close returns.
                    assertTrue(t.transport.allCallsCanceled(), "round $round: a call outlived close")

                    val outcomes = withTimeout(10_000) { calls.awaitAll() }
                    for (outcome in outcomes) {
                        val failure = outcome.exceptionOrNull() ?: continue
                        if (failure !is ApiException || failure.error != ApiError.Closed) {
                            fail("round $round: expected success or Closed, got $failure")
                        }
                    }

                    // Requests written before close may still be on their way into the server; let them land.
                    delay(SETTLE_MS)
                    val seen = t.server.requestCount
                    val late = List(CALLS) { async { apiError { t.transport.execute(info) } } }.awaitAll()
                    assertTrue(late.all { it == ApiError.Closed }, "round $round: $late")
                    delay(SETTLE_MS)
                    // OkHttp counts a call as running until its callback has returned.
                    withTimeout(5_000) { while (t.transport.runningCalls() > 0) delay(5) }
                    assertEquals(seen, t.server.requestCount, "round $round: a call reached the server after close")
                }
            }
        }
    }

    @Test
    fun `a call made for a stream before close cannot run after it`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = "{}")
            val call = t.transport.newCall(t.transport.toOkHttp(info))
            t.transport.close()

            assertTrue(call.isCanceled())
            assertTrue(runCatching { call.execute() }.isFailure)
            assertEquals(0, t.server.requestCount)
        }
    }

    private companion object {
        const val ROUNDS = 30
        const val CALLS = 16
        const val SETTLE_MS = 100L
    }
}
