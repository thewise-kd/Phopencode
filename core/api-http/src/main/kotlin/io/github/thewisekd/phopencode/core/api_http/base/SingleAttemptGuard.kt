package io.github.thewisekd.phopencode.core.api_http.base

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * Marks one call that must reach the server at most once. Travels as the request's tag, so it
 * stays on every follow-up OkHttp builds from that request.
 */
internal class SingleAttemptGuard {
    private val attempted = AtomicBoolean(false)

    /** True only the first time. */
    fun firstAttempt(): Boolean = attempted.compareAndSet(false, true)
}

/** A second network attempt of a guarded call; maps to `ApiError.Network`. */
internal class RepeatRefusedException : IOException("A request that is not repeatable was not sent again.")

/**
 * The backstop for calls that are not repeatable. A network interceptor runs once per attempt on
 * the wire, after every retry and follow-up decision, so a second attempt of the same call fails
 * here, before anything is written. The one-shot body stops OkHttp earlier, in the common case.
 */
internal object SingleAttemptInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val guard = chain.request().tag(SingleAttemptGuard::class.java)
        if (guard != null && !guard.firstAttempt()) throw RepeatRefusedException()
        return chain.proceed(chain.request())
    }
}

/**
 * Wraps a body as one-shot. OkHttp then follows up on no response of this request, and does not
 * resend it after a failure once sending started: that covers `503` with `Retry-After: 0`, `408`,
 * `421`, authenticator `401` and `407`, and redirects.
 */
internal class OneShotBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)

    override fun isOneShot(): Boolean = true
}
