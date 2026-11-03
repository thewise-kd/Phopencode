package io.github.thewisekd.phopencode.core.api_http.event.decode

import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Readers over a parsed event. The rule throughout: a required field that is missing or of the wrong
 * type throws [MalformedEvent], which turns the whole event into a malformed result; an optional
 * field that is missing or of the wrong type reads as absent.
 */

/**
 * Thrown when a known event lacks something it cannot do without. It has no message and no stack
 * trace, so it can never carry the event's text.
 */
internal class MalformedEvent : RuntimeException(null, null, false, false)

/** Longest number text read. A JSON number is unbounded, and parsing a huge one costs time. */
private const val MAX_NUMBER_TEXT = 1_100

internal fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.requireStr(key: String): String = str(key) ?: throw MalformedEvent()

internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

internal fun JsonObject.requireObj(key: String): JsonObject = obj(key) ?: throw MalformedEvent()

internal fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

/** The literal text of a JSON number, as the server wrote it; null for anything else. */
internal fun JsonObject.numberText(key: String): String? = numberText(get(key))

private fun numberText(element: JsonElement?): String? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull) return null
    val text = primitive.content
    if (text == "true" || text == "false" || text.length > MAX_NUMBER_TEXT) return null
    return text
}

internal fun JsonObject.int(key: String): Int? = numberText(key)?.toIntOrNull()

internal fun JsonObject.requireInt(key: String): Int = int(key) ?: throw MalformedEvent()

internal fun JsonObject.long(key: String): Long? = numberText(key)?.toLongOrNull()

internal fun JsonObject.bigInteger(key: String): BigInteger? = numberText(key)?.toBigIntegerOrNull()

internal fun JsonObject.decimal(key: String): BigDecimal? = numberText(key)?.toBigDecimalOrNull()

internal fun JsonObject.bool(key: String): Boolean? {
    val primitive = get(key) as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toBooleanStrictOrNull()
}

/** Milliseconds since the epoch. */
internal fun JsonObject.instant(key: String): Instant? = long(key)?.let(Instant::fromEpochMilliseconds)

/** The strings of a list; other items are left out. */
internal fun JsonObject.strings(key: String): List<String> =
    array(key).orEmpty().mapNotNull { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.content }

/** The objects of a list; other items are left out. */
internal fun JsonObject.objects(key: String): List<JsonObject> = array(key).orEmpty().filterIsInstance<JsonObject>()

internal fun JsonObject.dataMap(key: String): Map<String, DataValue> = obj(key)?.let(::dataMapOf).orEmpty()

internal fun JsonObject.data(key: String): DataValue? = get(key)?.let { toDataValue(it) }

internal fun dataMapOf(fields: JsonObject): Map<String, DataValue> = fields.mapValues { (_, value) -> toDataValue(value) }

/** Recurses on the heap, so a deeply nested value from the server cannot overflow the stack. */
private val toDataValueFunction = DeepRecursiveFunction<JsonElement, DataValue> { element ->
    when (element) {
        is JsonObject -> DataValue.Fields(element.mapValues { (_, value) -> callRecursive(value) })
        is JsonArray -> DataValue.Items(element.map { callRecursive(it) })
        is JsonPrimitive -> primitiveValue(element)
    }
}

internal fun toDataValue(element: JsonElement): DataValue = toDataValueFunction(element)

/** A number too long to read becomes [DataValue.Null]. */
private fun primitiveValue(primitive: JsonPrimitive): DataValue = when {
    primitive is JsonNull -> DataValue.Null
    primitive.isString -> DataValue.Text(primitive.content)
    primitive.content == "true" -> DataValue.Bool(true)
    primitive.content == "false" -> DataValue.Bool(false)
    else -> numberText(primitive)?.toBigDecimalOrNull()?.let(DataValue::Num) ?: DataValue.Null
}

// Shared shapes

internal fun JsonObject.sessionId(key: String = "sessionID"): SessionId = SessionId(requireStr(key))

internal fun JsonObject.optionalSessionId(key: String): SessionId? = str(key)?.let(::SessionId)

internal fun JsonObject.messageId(key: String): MessageId? = str(key)?.let(::MessageId)

internal fun JsonObject.requireMessageId(key: String): MessageId = MessageId(requireStr(key))

/** `{id, providerID, variant?}`; null unless both IDs are there. */
internal fun modelRefOf(model: JsonObject?): ModelRef? {
    if (model == null) return null
    val modelId = model.str("id") ?: return null
    val providerId = model.str("providerID") ?: return null
    return ModelRef(providerId = providerId, modelId = modelId, variant = model.str("variant"))
}

/** `error{type, message, status?, response{body}?, retryAfterMs?}`; null with no `type`. */
internal fun JsonObject.error(key: String = "error"): StructuredError? {
    val error = obj(key) ?: return null
    val type = error.str("type") ?: return null
    return StructuredError(
        type = type,
        message = error.str("message"),
        status = error.int("status"),
        responseBody = error.obj("response")?.str("body"),
        retryAfterMs = error.long("retryAfterMs"),
    )
}

/**
 * `tokens{input, output, reasoning, cache{read, write}}` with `cost` beside it; null with no `tokens`.
 * The cost is parsed from the number's own text, so it is never rounded through a `Double`. A cost
 * that is missing or out of range stays null, which [TokenUsage] counts as missing rather than zero.
 */
internal fun JsonObject.usage(): TokenUsage? {
    val tokens = obj("tokens") ?: return null
    val cache = tokens.obj("cache")
    return TokenUsage(
        input = tokens.count("input"),
        output = tokens.count("output"),
        reasoning = tokens.count("reasoning"),
        cacheRead = cache?.count("read") ?: BigInteger.ZERO,
        cacheWrite = cache?.count("write") ?: BigInteger.ZERO,
        cost = numberText("cost")?.let(Cost::parse),
    )
}

private fun JsonObject.count(key: String): BigInteger = bigInteger(key) ?: BigInteger.ZERO
