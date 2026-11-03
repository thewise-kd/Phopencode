package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Longest server message kept as [ApiError] `detail`; enough for any sentence a person reads. */
internal const val MAX_DETAIL_LENGTH: Int = 1_000

/**
 * Turns server replies and low-level failures into [ApiError]. Nothing low-level survives: the
 * result holds at most a status number and the server's own `message`, which goes in `detail` only.
 */
internal object ErrorMapping {
    fun forStatus(status: Int, body: String?): ApiError {
        val detail = body?.let(::messageOf)
        return when (status) {
            400 -> ApiError.BadRequest(detail)
            401 -> ApiError.Unauthorized
            403 -> ApiError.Forbidden
            404 -> ApiError.NotFound
            409 -> ApiError.Conflict(detail)
            in 500..599 -> ApiError.Server(status, detail)
            else -> ApiError.Unexpected(status, detail)
        }
    }

    /** For a failure with no server reply. `CancellationException` must not reach this. */
    fun forFailure(failure: Throwable): ApiError = when (failure) {
        is ApiException -> failure.error
        // SocketTimeoutException, and OkHttp's call timeout, are both InterruptedIOException.
        is InterruptedIOException -> ApiError.Timeout
        is IOException -> ApiError.Network
        is SerializationException, is IllegalArgumentException -> ApiError.MalformedResponse
        else -> ApiError.Unexpected()
    }

    /** The `message` of an `{_tag, message}` reply, when it is text; anything else gives null. */
    private fun messageOf(body: String): String? {
        val parsed = try {
            ApiJson.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return null
        }
        val message = (parsed as? JsonObject)?.get("message") as? JsonPrimitive ?: return null
        if (!message.isString) return null
        return message.content.take(MAX_DETAIL_LENGTH).ifBlank { null }
    }
}
