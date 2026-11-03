package io.github.thewisekd.phopencode.core.api_http.session

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.ForkBoundary
import io.github.thewisekd.phopencode.core.model.ForkBoundaryType
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionEffect
import io.github.thewisekd.phopencode.core.model.PermissionRule
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionFork
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.SessionRevert
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Wire to model and model to wire. A value that cannot be what the server promised fails the call
 * with ApiError.MalformedResponse; a value that is merely new, such as an unknown outcome, is kept
 * raw in the model's Unknown case.
 */

/** Token counts with more integer digits than this are refused: no real count comes near it. */
private const val MAX_COUNT_DIGITS = 40

/** More digits than a `Long` of milliseconds can hold. */
private const val MAX_MILLIS_DIGITS = 19

/**
 * Bounds on any JSON number read here, checked before any BigDecimal arithmetic. A scale far from
 * zero, such as that of `1e-100000000`, makes `setScale` or an exact conversion build a power of ten
 * with that many digits.
 */
private const val MAX_NUMBER_LENGTH = 100
private const val MAX_NUMBER_SCALE = 100

private fun malformed(): Nothing = throw ApiException(ApiError.MalformedResponse)

/** The number's value, or null when it is no number or outside the bounds above. Cheap for any input. */
private fun JsonPrimitive.boundedDecimal(): BigDecimal? {
    if (isString || content.length > MAX_NUMBER_LENGTH) return null
    val number = content.toBigDecimalOrNull() ?: return null
    return number.takeIf { it.scale() in -MAX_NUMBER_SCALE..MAX_NUMBER_SCALE }
}

internal fun SessionWire.toModel(): Session = Session(
    id = SessionId(id),
    parentId = parentId?.let(::SessionId),
    title = title,
    directory = location?.directory,
    projectId = projectId,
    subpath = subpath,
    agent = agent,
    model = model?.toModel(),
    createdAt = time?.created.toInstant(),
    updatedAt = time?.updated.toInstant(),
    archived = time?.archived.isPresent(),
    outcome = outcome?.toOutcome(),
    fork = fork?.let { SessionFork(SessionId(it.sessionId), it.boundary.toModel()) },
    revert = revert?.toModel(),
    usage = usageOf(tokens, cost),
    metadata = metadata?.let(::fieldsOf).orEmpty(),
)

private fun ModelRefWire.toModel() = ModelRef(providerId = providerId, modelId = id, variant = variant)

internal fun ModelRef.toWire() = ModelRefWire(id = modelId, providerId = providerId, variant = variant)

private fun String.toOutcome(): SessionOutcome = when (this) {
    "succeeded" -> SessionOutcome.Succeeded
    "failed" -> SessionOutcome.Failed
    "interrupted" -> SessionOutcome.Interrupted
    else -> SessionOutcome.Unknown(this)
}

private fun ForkBoundaryWire.toModel(): ForkBoundary {
    val kind = when (type) {
        "through" -> ForkBoundaryType.Through
        "before" -> ForkBoundaryType.Before
        else -> ForkBoundaryType.Unknown(type)
    }
    return ForkBoundary(kind, MessageId(messageId))
}

private fun RevertWire.toModel() = SessionRevert(
    messageId = MessageId(messageId),
    snapshot = snapshot,
    files = files.map { FileDiff(VcsFile(it.file, it.status.toFileStatus(), it.additions, it.deletions), it.patch) },
)

private fun String.toFileStatus(): VcsFileStatus = when (this) {
    "added" -> VcsFileStatus.Added
    "modified" -> VcsFileStatus.Modified
    "deleted" -> VcsFileStatus.Deleted
    "renamed" -> VcsFileStatus.Renamed
    "untracked" -> VcsFileStatus.Untracked
    else -> VcsFileStatus.Unknown(this)
}

/**
 * The session's reported total: null when the server reported neither tokens nor cost. A cost that
 * is no number in range is missing, not zero; see [TokenUsage].
 */
private fun usageOf(tokens: TokensWire?, cost: JsonPrimitive?): TokenUsage? {
    if (tokens == null && !cost.isPresent()) return null
    return TokenUsage(
        input = tokens?.input.toCount(),
        output = tokens?.output.toCount(),
        reasoning = tokens?.reasoning.toCount(),
        cacheRead = tokens?.cache?.read.toCount(),
        cacheWrite = tokens?.cache?.write.toCount(),
        cost = cost?.takeIf { it.isPresent() }?.boundedDecimal()?.let(Cost::of),
    )
}

private fun JsonPrimitive?.isPresent(): Boolean = this != null && this !is JsonNull

/** A count: a whole number, zero or more. Absent counts nothing. */
private fun JsonPrimitive?.toCount(): BigInteger {
    if (!isPresent()) return BigInteger.ZERO
    val number = this!!.boundedDecimal() ?: malformed()
    if (number.signum() < 0 || number.precision() - number.scale() > MAX_COUNT_DIGITS) malformed()
    return try {
        number.toBigIntegerExact()
    } catch (_: ArithmeticException) {
        malformed()
    }
}

/** Milliseconds since the epoch, rounded down; absent gives no time, anything but a number fails. */
private fun JsonPrimitive?.toInstant(): Instant? {
    if (!isPresent()) return null
    val millis = this!!.boundedDecimal() ?: malformed()
    if (millis.precision() - millis.scale() > MAX_MILLIS_DIGITS) malformed()
    return try {
        Instant.fromEpochMilliseconds(millis.setScale(0, RoundingMode.FLOOR).longValueExact())
    } catch (_: ArithmeticException) {
        malformed()
    }
}

internal fun InboxItemWire.toModel() = InboxItem(
    id = MessageId(id),
    sessionId = SessionId(sessionId),
    createdAt = time?.created.toInstant(),
    content = when (type) {
        "compaction" -> InboxContent.Compaction
        else -> InboxContent.Unknown(type)
    },
    delivery = when (delivery) {
        "steer" -> PromptDelivery.Steer
        "queue" -> PromptDelivery.Queue
        else -> null
    },
)

internal fun ActiveWire.toModel(): SessionActivity = when (type) {
    "running" -> SessionActivity.Running
    else -> SessionActivity.Unknown(type)
}

internal fun PermissionRule.toWire(): JsonObject = buildJsonObject {
    put("action", action)
    put("resource", resource)
    put(
        "effect",
        when (val e = effect) {
            PermissionEffect.Allow -> "allow"
            PermissionEffect.Deny -> "deny"
            PermissionEffect.Ask -> "ask"
            is PermissionEffect.Unknown -> e.raw
        },
    )
}

/** Free-form JSON, such as metadata. Deep nesting is fine: neither direction uses the call stack. */
internal fun fieldsOf(json: JsonObject): Map<String, DataValue> =
    (jsonToData(json) as DataValue.Fields).values

internal fun Map<String, DataValue>.toJson(): JsonObject = dataToJson(DataValue.Fields(this)) as JsonObject

private val jsonToData = DeepRecursiveFunction<JsonElement, DataValue> { element ->
    when (element) {
        JsonNull -> DataValue.Null
        is JsonPrimitive -> when {
            element.isString -> DataValue.Text(element.content)
            else -> element.booleanOrNull?.let(DataValue::Bool)
                ?: DataValue.Num(element.boundedDecimal() ?: malformed())
        }
        is JsonArray -> DataValue.Items(element.map { callRecursive(it) })
        is JsonObject -> DataValue.Fields(element.mapValues { callRecursive(it.value) })
    }
}

/** A number keeps its exact text: `JsonPrimitive(BigDecimal)` would round it through a `Double`. */
@OptIn(ExperimentalSerializationApi::class)
private val dataToJson = DeepRecursiveFunction<DataValue, JsonElement> { value ->
    when (value) {
        DataValue.Null -> JsonNull
        is DataValue.Bool -> JsonPrimitive(value.value)
        is DataValue.Num -> JsonUnquotedLiteral(value.value.toString())
        is DataValue.Text -> JsonPrimitive(value.value)
        is DataValue.Items -> JsonArray(value.values.map { callRecursive(it) })
        is DataValue.Fields -> JsonObject(value.values.mapValues { callRecursive(it.value) })
    }
}
