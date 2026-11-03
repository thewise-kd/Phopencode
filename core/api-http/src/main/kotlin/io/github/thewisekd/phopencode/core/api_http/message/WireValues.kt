package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * Small wire shapes that messages, inbox items and requests share. Wire classes are plain classes,
 * not data classes, so that printing one never shows text a user typed or a model wrote.
 */

/** The server's `time` object: epoch milliseconds, which the spec types as any number. */
@Serializable
internal class TimeWire(
    val created: Double? = null,
    val streamed: Double? = null,
    val ran: Double? = null,
    val completed: Double? = null,
)

internal fun instantOf(millis: Double?): Instant? =
    millis?.takeIf { it.isFinite() }?.let { Instant.fromEpochMilliseconds(it.toLong()) }

/** `Model.Ref`. The same shape goes out in a model switch, so the field order is the spec's. */
@Serializable
internal class ModelRefWire(
    val id: String,
    @SerialName("providerID") val providerId: String,
    val variant: String? = null,
) {
    fun toModel(): ModelRef = ModelRef(providerId = providerId, modelId = id, variant = variant)
}

internal fun ModelRef.toWire(): ModelRefWire = ModelRefWire(id = modelId, providerId = providerId, variant = variant)

/** `Session.StructuredError`. */
@Serializable
internal class StructuredErrorWire(
    val type: String,
    val message: String? = null,
    val status: Int? = null,
    val response: ResponseWire? = null,
    val retryAfterMs: Long? = null,
) {
    @Serializable
    class ResponseWire(val body: String? = null)

    fun toModel(): StructuredError = StructuredError(
        type = type,
        message = message,
        status = status,
        responseBody = response?.body,
        retryAfterMs = retryAfterMs,
    )
}

/**
 * `TokenUsage.Info`. Each counter stays a JSON primitive until mapping, so a large count keeps
 * every digit rather than passing through `Double` or overflowing `Long`.
 */
@Serializable
internal class TokensWire(
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    val reasoning: JsonElement? = null,
    val cache: CacheWire? = null,
) {
    @Serializable
    class CacheWire(val read: JsonElement? = null, val write: JsonElement? = null)
}

/**
 * The usage a message reports, or null when it reports none. The cost is read from the number's
 * own text, so `0.0004725` stays exactly that.
 *
 * A counter that is not a plain non-negative integer of sane size makes the whole report unusable:
 * null, which callers already treat as "not reported", rather than a made-up zero.
 */
internal fun usageOf(tokens: TokensWire?, cost: JsonElement?): TokenUsage? {
    if (tokens == null && (cost == null || cost is JsonNull)) return null
    val counters = listOf(tokens?.input, tokens?.output, tokens?.reasoning, tokens?.cache?.read, tokens?.cache?.write)
        .map { counterOf(it) ?: return null }
    return TokenUsage(
        input = counters[0],
        output = counters[1],
        reasoning = counters[2],
        cacheRead = counters[3],
        cacheWrite = counters[4],
        cost = costOf(cost),
    )
}

/** Null for anything but a JSON number in [Cost]'s range: a missing cost is not a free one. */
private fun costOf(cost: JsonElement?): Cost? {
    val number = cost as? JsonPrimitive ?: return null
    if (number is JsonNull || number.isString) return null
    return Cost.parse(number.content)
}

/** Longest counter text taken: far beyond any real count, and cheap to parse. */
private const val MAX_COUNTER_LENGTH = 40

private val INTEGER = Regex("[0-9]+(\\.0*)?")

/** Zero when absent; null when present but not a non-negative integer. */
private fun counterOf(value: JsonElement?): BigInteger? {
    if (value == null || value is JsonNull) return BigInteger.ZERO
    val number = value as? JsonPrimitive ?: return null
    if (number.isString) return null
    val text = number.content
    if (text.length > MAX_COUNTER_LENGTH || !INTEGER.matches(text)) return null
    return BigDecimal(text).toBigIntegerExact()
}

/** Any JSON value as a [DataValue], keeping numbers exact. */
internal fun JsonElement.toDataValue(): DataValue = when (this) {
    is JsonNull -> DataValue.Null
    is JsonObject -> DataValue.Fields(mapValues { (_, value) -> value.toDataValue() })
    is JsonArray -> DataValue.Items(map { it.toDataValue() })
    is JsonPrimitive -> when {
        isString -> DataValue.Text(content)
        booleanOrNull != null -> DataValue.Bool(booleanOrNull!!)
        else -> content.toBigDecimalOrNull()?.let(DataValue::Num) ?: DataValue.Text(content)
    }
}
