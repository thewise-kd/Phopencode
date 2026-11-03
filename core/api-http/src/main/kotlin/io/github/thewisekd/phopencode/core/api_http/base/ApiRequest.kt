package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.Location
import kotlinx.serialization.json.JsonElement

internal enum class HttpMethod { GET, POST, PUT, PATCH, DELETE }

/**
 * One call, before it meets a server address.
 *
 * @property path Path segments after the base URL, each encoded on its own: an ID holding `/` or `?`
 *   stays one segment. For example `listOf("api", "session", id)`.
 * @property query In order; the location parameter, if any, comes after them.
 * @property location Sent as the `location[directory]` query parameter. Routes that take the
 *   location in the body put [LocationWire] in [body] instead and leave this null.
 * @property body Sent as JSON. A `POST`, `PUT` or `PATCH` without one sends an empty body.
 * @property repeatable False for a call the server must not get twice, such as a prompt: OkHttp then
 *   never resends it after a dropped connection, and the call fails with `ApiError.Network`
 *   instead. The caller decides whether to try again.
 */
internal data class ApiRequest(
    val method: HttpMethod,
    val path: List<String>,
    val query: List<Pair<String, String>> = emptyList(),
    val location: Location? = null,
    val body: JsonElement? = null,
    val repeatable: Boolean = true,
) {
    init {
        // A GET is safe to repeat by definition, and OkHttp cannot give it the one-shot body that
        // keeps it to one attempt.
        require(repeatable || method != HttpMethod.GET) { "A GET request is always repeatable." }
    }

    /** Leaves out the query, the location and the body, so it is safe to print. */
    override fun toString(): String = "ApiRequest($method /${path.joinToString("/")})"

    companion object {
        /** The query name the server reads a folder from. */
        const val LOCATION_PARAMETER: String = "location[directory]"
    }
}
