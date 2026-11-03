package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A successful reply: a 2xx status and the whole body. */
internal class HttpReply(val status: Int, val body: ByteArray) {
    override fun toString(): String = "HttpReply($status, ${body.size} bytes)"
}

/**
 * The HTTP layer every API class of this module sends its calls through.
 *
 * It adds Basic auth, encodes paths, queries and the location, and turns every failure into
 * [ApiException] with a typed [ApiError]: no OkHttp, IO or serialization exception leaves it, and
 * `CancellationException` passes through untouched. Cancelling the coroutine cancels the HTTP call.
 *
 * Redirects are not followed: the API never redirects, and following one could send the password
 * to another host or over plain HTTP. A redirect is [ApiError.Unexpected].
 *
 * The password never reaches [logger], an error, or a printed form of this class: see [HttpLogger].
 *
 * @param baseUrl Where `opencode serve` listens, usually a tunnel's local end. A path prefix is
 *   kept. It must not carry a user name or password: those go in [credentials].
 * @param configure Adjusts the client, for example its timeouts. Runs before the auth and log
 *   interceptors are added, and redirects are switched off after it. A network interceptor added
 *   here would see the `Authorization` header, so add none that logs.
 */
internal class HttpTransport(
    baseUrl: HttpUrl,
    credentials: BasicCredentials,
    private val logger: HttpLogger = HttpLogger.None,
    configure: OkHttpClient.Builder.() -> Unit = {},
) : AutoCloseable {
    private val baseUrl: HttpUrl

    /** Guards [closed] and [calls], so that no call starts after [close] cancelled the others. */
    private val lock = Any()

    @Volatile
    private var closed = false

    /** Every call made here and not yet collected; [close] cancels them all. */
    private val calls: MutableSet<Call> = Collections.newSetFromMap(WeakHashMap())

    init {
        // The message names no part of the URL: it may be printed.
        require(baseUrl.username.isEmpty() && baseUrl.password.isEmpty()) {
            "The server address must not contain credentials."
        }
        this.baseUrl = baseUrl.newBuilder().query(null).fragment(null).build()
    }

    /**
     * The client with auth and safe logging. Event streams (P2.2) build their calls on this,
     * through [newCall], so the same rules hold for them.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        // OkHttp allows 5 calls per host by default; an event stream holds one for good.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_CALLS_PER_HOST })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply(configure)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(SafeLogInterceptor(logger))
        .addInterceptor(AuthInterceptor(credentials.headerValue))
        .build()

    /**
     * [client] for calls that are not [ApiRequest.repeatable]. Three layers keep such a call to one
     * attempt on the wire: no retry after a dropped connection; a one-shot body (see [toOkHttp]),
     * which stops every OkHttp follow-up; and [SingleAttemptInterceptor], which refuses a second
     * attempt whatever path leads to it. It shares [client]'s dispatcher, pool and interceptors, so
     * [close] covers it too.
     */
    private val singleAttemptClient: OkHttpClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor(SingleAttemptInterceptor)
        .build()

    val isClosed: Boolean
        get() = closed

    /**
     * The full URL of [request]: base path, then each path segment, the query, the location.
     * Throws [ApiException] with [ApiError.BadRequest] for a segment that could change the route;
     * see [PathSegments].
     */
    fun url(request: ApiRequest): HttpUrl = baseUrl.newBuilder().apply {
        request.path.forEach { addPathSegment(PathSegments.checked(it)) }
        request.query.forEach { (name, value) -> addQueryParameter(name, value) }
        request.location?.let { addQueryParameter(ApiRequest.LOCATION_PARAMETER, it.directory) }
    }.build()

    /**
     * The OkHttp form of [request], without the `Authorization` header: the client adds it. A
     * request that is not repeatable gets a one-shot body, empty if it has none, and a
     * [SingleAttemptGuard] tag.
     */
    fun toOkHttp(request: ApiRequest): Request {
        val json = request.body?.let { ApiJson.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) }
        val body: RequestBody? = when {
            !request.repeatable -> OneShotBody(json ?: EMPTY_BODY)
            json != null -> json
            request.method == HttpMethod.GET || request.method == HttpMethod.DELETE -> null
            else -> EMPTY_BODY
        }
        return Request.Builder()
            .url(url(request))
            .header("Accept", "application/json")
            .method(request.method.name, body)
            .apply { if (!request.repeatable) tag(SingleAttemptGuard::class.java, SingleAttemptGuard()) }
            .build()
    }

    /** A call for a caller that reads the response itself, such as the event stream. */
    fun newCall(request: Request): Call = synchronized(lock) {
        ensureOpen()
        client.newCall(request).also { calls += it }
    }

    /** The [ApiError] for a failure of a call made through [newCall]. */
    fun errorFor(failure: Throwable): ApiError = if (closed) ApiError.Closed else ErrorMapping.forFailure(failure)

    /**
     * The [ApiError] for a reply with a status outside 2xx: [ApiError.Closed] if [close] broke off
     * reading its body.
     */
    fun errorFor(response: Response): ApiError = try {
        ErrorMapping.forStatus(response.code, readErrorBody(response))
    } catch (e: ApiException) {
        e.error
    }

    /**
     * Sends [request] and returns the reply when its status is 2xx; throws [ApiException] otherwise.
     * OkHttp may resend a [ApiRequest.repeatable] request once after a dropped connection; any other
     * is sent at most once.
     */
    suspend fun execute(request: ApiRequest): HttpReply {
        ensureOpen()
        val okHttpRequest = toOkHttp(request)
        val owner = if (request.repeatable) client else singleAttemptClient
        return suspendCancellableCoroutine { continuation ->
            // Made and started under the lock close() takes: either close() sees this call and
            // cancels it, or this sees `closed` and starts nothing.
            synchronized(lock) {
                if (closed) {
                    continuation.resumeWithException(ApiException(ApiError.Closed))
                } else {
                    val call = owner.newCall(okHttpRequest)
                    calls += call
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(ReplyCallback(continuation))
                }
            }
        }
    }

    private inner class ReplyCallback(private val continuation: CancellableContinuation<HttpReply>) : Callback {
        override fun onFailure(call: Call, e: IOException) {
            forget(call)
            continuation.resumeWithException(ApiException(errorFor(e)))
        }

        override fun onResponse(call: Call, response: Response) {
            forget(call)
            runCatching { response.use(::readReply) }.fold(
                onSuccess = { continuation.resume(it) },
                onFailure = { continuation.resumeWithException(it.asApiException()) },
            )
        }
    }

    private fun forget(call: Call) {
        synchronized(lock) { calls -= call }
    }

    /** Sends [request] and decodes its body. A body that does not fit is [ApiError.MalformedResponse]. */
    suspend fun <T> receive(request: ApiRequest, deserializer: DeserializationStrategy<T>): T {
        val reply = execute(request)
        return try {
            ApiJson.decodeFromString(deserializer, reply.body.decodeToString())
        } catch (_: SerializationException) {
            throw ApiException(ApiError.MalformedResponse)
        } catch (_: IllegalArgumentException) {
            throw ApiException(ApiError.MalformedResponse)
        }
    }

    /** How many calls are on the wire now. */
    fun runningCalls(): Int = client.dispatcher.runningCallsCount()

    /**
     * Cancels running calls, which then fail with [ApiError.Closed], and releases connections and
     * threads. Later calls fail with [ApiError.Closed] too. Calling it again does nothing; it throws
     * nothing.
     */
    override fun close() {
        val started = synchronized(lock) {
            if (closed) return
            closed = true
            calls.toList()
        }
        runCatching {
            started.forEach { it.cancel() }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    /** No host or port: the address of a user's server is private. */
    override fun toString(): String = "HttpTransport(closed=$closed)"

    /** For tests: whether every call made here has been cancelled. */
    internal fun allCallsCanceled(): Boolean = synchronized(lock) { calls.all { it.isCanceled() } }

    private fun ensureOpen() {
        if (closed) throw ApiException(ApiError.Closed)
    }

    private fun readReply(response: Response): HttpReply {
        if (!response.isSuccessful) throw ApiException(errorFor(response))
        return HttpReply(response.code, response.body.bytes())
    }

    private fun Throwable.asApiException(): ApiException =
        this as? ApiException ?: ApiException(errorFor(this))

    /**
     * The error body, bounded: it is only read for its message. A read that breaks off gives no
     * body, unless [close] broke it off: then the call ends with [ApiError.Closed], as every other
     * call that close() stops.
     */
    private fun readErrorBody(response: Response): String? = try {
        response.peekBody(MAX_ERROR_BODY_BYTES).string()
    } catch (_: IOException) {
        if (closed) throw ApiException(ApiError.Closed)
        null
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody()
        const val MAX_ERROR_BODY_BYTES: Long = 1024 * 1024
        const val MAX_CALLS_PER_HOST: Int = 16
    }
}

/** Adds the `Authorization` header. Holds only the header value, never prints it. */
private class AuthInterceptor(private val headerValue: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("Authorization", headerValue).build())

    override fun toString(): String = "AuthInterceptor(***)"
}

/**
 * Logs method, path, outcome and time. Runs before [AuthInterceptor], so it never even sees the
 * `Authorization` header; it reads no header, query or body either.
 */
private class SafeLogInterceptor(private val logger: HttpLogger) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val what = "${request.method} ${request.url.encodedPath}"
        val started = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            log("<-- failed $what (${e.javaClass.simpleName}, ${elapsedMs(started)} ms)")
            throw e
        }
        log("<-- ${response.code} $what (${elapsedMs(started)} ms)")
        return response
    }

    /** A failing logger must not fail the call, nor crash OkHttp's thread. */
    private fun log(line: String) {
        try {
            logger.log(line)
        } catch (_: Exception) {
            // Logging is best effort.
        }
    }

    private fun elapsedMs(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
}
