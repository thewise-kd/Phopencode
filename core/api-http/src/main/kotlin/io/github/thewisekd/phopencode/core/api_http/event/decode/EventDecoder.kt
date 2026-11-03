package io.github.thewisekd.phopencode.core.api_http.event.decode

import io.github.thewisekd.phopencode.core.api.EventDurable
import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import kotlinx.serialization.json.JsonObject

/** What [EventDecoder.decodeResult] made of one event. */
internal sealed interface DecodeResult {
    data class Decoded(val event: ServerEvent) : DecodeResult

    /**
     * An event this app knows, or an envelope it cannot read at all, that does not have the shape it
     * needs. Holds the type and the event ID only: never any of the event's text.
     *
     * @property rawType The envelope's `type`, else the SSE `event:` value, else empty.
     */
    data class Malformed(val rawType: String, val eventId: String?) : DecodeResult
}

/**
 * Turns one item of the event stream into a [ServerEvent]. It never throws.
 *
 * The server sends no `event:` or `id:` lines: the type and ID are inside the JSON envelope,
 * `{id, created?, type, data, location?, metadata?, durable?}`. Those win, and the SSE values are
 * only used when the envelope lacks them.
 *
 * - A type with no decoder here becomes [ServerEvent.Unknown], whatever its `data` holds. Its `data`
 *   is not read at all, so `settings`, `headers` and keys in catalog events are never decoded.
 * - A known type that lacks a required field, or has one of the wrong type, is [DecodeResult.Malformed].
 *   [decode] turns that into [ServerEvent.Unknown] with the raw type and only the event ID, so a
 *   collector skips it as it skips any unknown event. Optional fields of the wrong type read as absent.
 * - `*.delta` and `*.ended` events decode to different types, and each requires its own field
 *   (`delta` or `text`), so one is never taken for the other.
 * - Costs are parsed from the JSON number's own text.
 *
 * `session.status` has no decoder: it was never recorded, so its shape is unknown.
 */
internal object EventDecoder {
    private val decoders: Map<String, (JsonObject, EventMeta) -> ServerEvent> =
        mapOf<String, (JsonObject, EventMeta) -> ServerEvent>(
            "server.connected" to { _, m -> ServerEvent.Connected(m) },
        ) + sessionDecoders + interactionDecoders

    /**
     * @param type The SSE `event:` value, if the stream had one.
     * @param data The SSE `data:` value: the JSON envelope.
     * @param id The SSE `id:` value, if the stream had one.
     */
    fun decode(type: String?, data: String, id: String?): ServerEvent =
        when (val result = decodeResult(type, data, id)) {
            is DecodeResult.Decoded -> result.event
            is DecodeResult.Malformed -> ServerEvent.Unknown(result.rawType, EventMeta(id = result.eventId))
        }

    /** As [decode], but tells a malformed known event apart from an unknown one. */
    fun decodeResult(type: String?, data: String, id: String?): DecodeResult {
        val envelope = try {
            ApiJson.parseToJsonElement(data) as? JsonObject
        } catch (_: RuntimeException) {
            null
        } ?: return DecodeResult.Malformed(type.orEmpty(), id)

        val rawType = envelope.str("type") ?: type.orEmpty()
        val eventId = envelope.str("id") ?: id
        val meta = metaOf(envelope, eventId)
        val decoder = decoders[rawType] ?: return DecodeResult.Decoded(ServerEvent.Unknown(rawType, meta))
        val payload = when (val payload = envelope["data"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> payload
            else -> return DecodeResult.Malformed(rawType, eventId)
        }
        return try {
            DecodeResult.Decoded(decoder(payload, meta))
        } catch (_: RuntimeException) {
            // MalformedEvent, or anything a reader missed. Nothing of the exception is kept: it could
            // quote the body.
            DecodeResult.Malformed(rawType, eventId)
        }
    }

    /** The envelope's own fields. Each one that is unreadable is left out, never failing the event. */
    private fun metaOf(envelope: JsonObject, eventId: String?): EventMeta = EventMeta(
        id = eventId,
        createdAt = envelope.instant("created"),
        location = envelope.obj("location")?.str("directory")?.let(::Location),
        metadata = envelope.dataMap("metadata"),
        durable = envelope.obj("durable")?.let(::durableOf),
    )

    private fun durableOf(durable: JsonObject): EventDurable? {
        val aggregateId = durable.str("aggregateID") ?: return null
        val seq = durable.long("seq") ?: return null
        val version = durable.int("version") ?: return null
        return EventDurable(aggregateId = aggregateId, seq = seq, version = version)
    }
}
