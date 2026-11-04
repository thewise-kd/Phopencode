package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    /** Made only after close, on a path of its own, so the server can tell it from the racing calls. */
    private val late = ApiRequest(HttpMethod.GET, listOf("api", "late"))

    @Test
    fun `close racing with many starting calls leaves every call finished or Closed`() = runTest {
        withContext(Dispatchers.IO) {
            repeat(ROUNDS) { round ->
                TestServer().use { t ->
                    val lateArrivals = AtomicInteger()
                    t.server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            if (request.url.encodedPath == "/api/late") lateArrivals.incrementAndGet()
                            return MockResponse.Builder().body("{}").headersDelay(20, TimeUnit.MILLISECONDS).build()
                        }
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

                    // A racing call may have written its request before close and still land on the
                    // server later, however late, so only the calls made after close are counted.
                    val lateErrors = List(CALLS) { async { apiError { t.transport.execute(late) } } }.awaitAll()
                    assertTrue(lateErrors.all { it == ApiError.Closed }, "round $round: $lateErrors")
                    // Gives a wrongly sent late call time to land. The wait only decides how surely a
                    // bug is seen: without one, nothing arrives however long this takes.
                    delay(SETTLE_MS)
                    // OkHttp counts a call as running until its callback has returned.
                    withTimeout(5_000) { while (t.transport.runningCalls() > 0) delay(5) }
                    assertEquals(0, lateArrivals.get(), "round $round: a call made after close reached the server")
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
