package io.github.thewisekd.phopencode.core.api_http.interaction

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.model.DataValue
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Readers over a form or permission the server listed. A value that cannot be what the server
 * promised fails the call with ApiError.MalformedResponse: a required key that is missing, or any
 * key of the wrong type. A key that is absent or `null` reads as absent. A value that is merely
 * new, such as an unknown field type, is the mapping's business, not a failure here.
 *
 * Numbers outside the bounds below are the exception: every number the model takes is optional,
 * so such a number reads as absent, as in the `form.*` and `permission.*` event decoder. One odd
 * limit or metadata value then cannot hide every pending form or permission. A number of the right
 * kind is never a reason to fail; text where a number belongs still is.
 */

/**
 * Bounds on any JSON number read here, checked before any BigDecimal arithmetic. A JSON number is
 * unbounded: parsing very long text costs time, and a scale far from zero, such as that of
 * `1e-100000000`, makes later arithmetic on it build a power of ten with that many digits. Only the
 * text length and the parsed scale are looked at, both cheap for any input.
 */
private const val MAX_NUMBER_LENGTH = 100
private const val MAX_NUMBER_SCALE = 100

/** Thrown for every misfit; it names nothing the server sent. */
internal fun malformed(): Nothing = throw ApiException(ApiError.MalformedResponse)

private fun JsonObject.present(key: String): JsonElement? = get(key)?.takeUnless { it is JsonNull }

internal fun JsonObject.str(key: String): String? {
    val value = present(key) ?: return null
    if (value !is JsonPrimitive || !value.isString) malformed()
    return value.content
}

internal fun JsonObject.requireStr(key: String): String = str(key) ?: malformed()

internal fun JsonObject.bool(key: String): Boolean? {
    val value = present(key) ?: return null
    if (value !is JsonPrimitive || value.isString) malformed()
    return value.content.toBooleanStrictOrNull() ?: malformed()
}

internal fun JsonObject.obj(key: String): JsonObject? {
    val value = present(key) ?: return null
    return value as? JsonObject ?: malformed()
}

internal fun JsonObject.array(key: String): JsonArray? {
    val value = present(key) ?: return null
    return value as? JsonArray ?: malformed()
}

internal fun JsonObject.objects(key: String): List<JsonObject> =
    array(key).orEmpty().map { it as? JsonObject ?: malformed() }

internal fun JsonObject.strings(key: String): List<String> =
    array(key).orEmpty().map { item ->
        if (item !is JsonPrimitive || !item.isString) malformed()
        item.content
    }

/** True for a JSON number: not a string, `null` or a boolean. */
private fun JsonPrimitive.isNumber(): Boolean =
    !isString && this !is JsonNull && content != "true" && content != "false"

/**
 * The value of a JSON number, or null when it lies outside the bounds above. The length is checked
 * before parsing, and parsing reads an exponent without expanding it, so this is cheap for any text.
 *
 * Call it on a JSON number only. The parser hands over nothing else unquoted, so text that does not
 * parse is a number [BigDecimal] cannot hold, such as `1e2147483648` with its exponent beyond an
 * `Int`: that is out of bounds too, not malformed.
 */
private fun JsonPrimitive.boundedDecimalOrNull(): BigDecimal? {
    if (content.length > MAX_NUMBER_LENGTH) return null
    val number = content.toBigDecimalOrNull() ?: return null
    return number.takeIf { it.scale() in -MAX_NUMBER_SCALE..MAX_NUMBER_SCALE }
}

/** The number at [key], if present; text or a boolean there is malformed. */
private fun JsonObject.number(key: String): JsonPrimitive? {
    val value = present(key) ?: return null
    if (value !is JsonPrimitive || !value.isNumber()) malformed()
    return value
}

/**
 * A number, where the spec also allows the strings `Infinity`, `-Infinity` and `NaN`: those have no
 * [BigDecimal], so they read as no limit, as does a number outside the bounds.
 */
internal fun JsonObject.decimalOrNonFinite(key: String): BigDecimal? {
    val value = present(key) ?: return null
    if (value is JsonPrimitive && value.isString) {
        if (value.content in NON_FINITE) return null
        malformed()
    }
    return number(key)?.boundedDecimalOrNull()
}

private val NON_FINITE = setOf("Infinity", "-Infinity", "NaN")

/**
 * A count such as a length or a number of picks: a non-negative integer. The spec allows up to
 * 2^53 - 1, beyond [Int]; such a limit is clamped to [Int.MAX_VALUE], which nothing typed or picked
 * on a phone comes near. A number outside the bounds reads as no limit; a negative or fractional
 * one is no count at all, and so malformed.
 */
internal fun JsonObject.count(key: String): Int? {
    val number = number(key)?.boundedDecimalOrNull() ?: return null
    if (number.signum() < 0) malformed()
    val whole = try {
        // Cheap: the scale is bounded, so no huge power of ten is built.
        number.toBigIntegerExact()
    } catch (_: ArithmeticException) {
        malformed()
    }
    return if (whole.bitLength() < Int.SIZE_BITS) whole.toInt() else Int.MAX_VALUE
}

/**
 * A plain JSON value of a condition: text, a number or a boolean. Absent, anything else, or a number
 * outside the bounds is null, which leaves the condition unknown rather than comparing with nothing.
 */
internal fun JsonObject.scalar(key: String): DataValue? {
    val value = present(key) as? JsonPrimitive ?: return null
    if (value.isNumber()) return value.boundedDecimalOrNull()?.let(DataValue::Num)
    return primitiveValue(value)
}

/**
 * Free-form JSON, such as metadata. Recurses on the heap, so deep nesting cannot overflow the stack.
 * A number outside the bounds becomes [DataValue.Null], as in the event decoder, so that every key
 * and every list position stays where the server put it.
 */
internal fun dataMapOf(fields: JsonObject): Map<String, DataValue> = (toData(fields) as DataValue.Fields).values

private val toData = DeepRecursiveFunction<JsonElement, DataValue> { element ->
    when (element) {
        is JsonObject -> DataValue.Fields(element.mapValues { (_, value) -> callRecursive(value) })
        is JsonArray -> DataValue.Items(element.map { callRecursive(it) })
        is JsonPrimitive -> primitiveValue(element)
    }
}

private fun primitiveValue(primitive: JsonPrimitive): DataValue = when {
    primitive is JsonNull -> DataValue.Null
    primitive.isString -> DataValue.Text(primitive.content)
    primitive.content == "true" -> DataValue.Bool(true)
    primitive.content == "false" -> DataValue.Bool(false)
    else -> primitive.boundedDecimalOrNull()?.let(DataValue::Num) ?: DataValue.Null
}
