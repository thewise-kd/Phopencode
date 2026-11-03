package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient

/** A placeholder-shaped password; see `scripts/check_boundaries.py`. */
internal const val TEST_PASSWORD = "test-password"

/** Reads a recorded fixture from `core/testing`. */
internal fun fixture(path: String): String =
    checkNotNull(BasicAuthTest::class.java.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

/** Every line a transport logged. */
internal class RecordingLogger : HttpLogger {
    val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun log(line: String) {
        lines += line
    }
}

/** A scripted server and a transport pointed at it. Close it with `use`. */
internal class TestServer(
    password: String = TEST_PASSWORD,
    configure: OkHttpClient.Builder.() -> Unit = {},
) : AutoCloseable {
    val server = MockWebServer().apply { start() }
    val logger = RecordingLogger()
    val transport = HttpTransport(
        baseUrl = server.url("/"),
        credentials = BasicCredentials(password),
        logger = logger,
        configure = configure,
    )

    fun enqueue(status: Int = 200, body: String = "") {
        server.enqueue(MockResponse.Builder().code(status).body(body).build())
    }

    /**
     * Every connection closes before an answer. Not a single queued drop: OkHttp retries a dropped
     * connection on its own, and the retry must fail too. Pooled connections are dropped first,
     * because the server applies this only to new ones.
     */
    fun dropEveryConnection() {
        transport.client.connectionPool.evictAll()
        val drop = MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = drop

            override fun peek(): MockResponse = drop
        }
    }

    override fun close() {
        transport.close()
        server.close()
    }
}

internal fun json(status: Int, body: String): MockResponse =
    MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

/** Runs [block], expecting [ApiException]; checks it carries no cause, and returns its error. */
internal suspend fun apiError(block: suspend () -> Unit): ApiError {
    try {
        block()
    } catch (e: ApiException) {
        assertNull(e.cause, "an ApiException must not carry a low-level cause")
        return e.error
    }
    fail("expected ApiException")
}

internal suspend fun assertApiError(expected: ApiError, block: suspend () -> Unit) {
    assertEquals(expected, apiError(block))
}
