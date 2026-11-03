package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.Page
import io.github.thewisekd.phopencode.core.api.PageCursor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one JSON setup for the opencode API.
 *
 * - Unknown keys are ignored: V2 adds fields often, and a new field must not break the app. It is
 *   also how wire types drop `settings`, `headers` and API keys: they simply do not declare them.
 * - A `null` for a field with a default takes the default.
 * - Requests leave out `null` fields rather than sending `null`, and do send other defaults, so
 *   that a value the server would treat as "not given" is never sent by accident.
 */
internal val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** The body form of a [Location]: `{"location": {"directory": ...}}` in a request body. */
@Serializable
internal data class LocationWire(val directory: String)

internal fun Location.toWire(): LocationWire = LocationWire(directory)

/** `{data}`, and `{location, data}`: most location-bound routes wrap their result this way. */
@Serializable
internal class DataEnvelope<T>(val data: T)

/** The server's `cursor` object. Both values are opaque. */
@Serializable
internal class CursorWire(val previous: String? = null, val next: String? = null)

/** A list route's `{data, cursor}`. */
@Serializable
internal class PageEnvelope<T>(val data: List<T>, val cursor: CursorWire? = null) {
    fun <R> toPage(transform: (T) -> R): Page<R> = Page(
        items = data.map(transform),
        previousCursor = cursor?.previous?.let(::PageCursor),
        nextCursor = cursor?.next?.let(::PageCursor),
    )
}
