package io.github.thewisekd.phopencode.core.api_http.interaction

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FieldCondition
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormAnswers
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.FormOption
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TextFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/*
 * `Form.Info` to [Form], and [FormAnswers] to `Form.Reply`. The field mapping follows the
 * `form.created` event's, so a form reads the same whether it came by event or by list.
 */

/**
 * `{id, sessionID, title, metadata?, fields}`. List routes send no `state`, so it stays null. A
 * field with no `key` cannot be answered, so it makes the whole form malformed.
 */
internal fun formOf(form: JsonObject): Form {
    val metadata = form.obj("metadata")
    return Form(
        id = FormId(form.requireStr("id")),
        sessionId = SessionId(form.requireStr("sessionID")),
        kind = when (val kind = (metadata?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "question" -> FormKind.Question
            else -> FormKind.Other(kind)
        },
        title = form.str("title"),
        fields = (form.array("fields") ?: malformed()).map { fieldOf(it as? JsonObject ?: malformed()) },
        metadata = metadata?.let(::dataMapOf).orEmpty(),
        state = null,
    )
}

/** A field whose type is unknown, or an `external` one with no `url`, becomes [FormField.Unknown]. */
private fun fieldOf(field: JsonObject): FormField {
    val key = field.requireStr("key")
    val title = field.str("title")
    val description = field.str("description")
    val required = field.bool("required") ?: false
    val hidden = field.bool("hidden") ?: false
    val visibleWhen = field.objects("when").map(::conditionOf)
    val type = field.str("type").orEmpty()
    return when (type) {
        "string" -> if (field.array("options") != null) {
            FormField.SingleChoice(
                key = key, title = title, description = description, required = required, hidden = hidden,
                visibleWhen = visibleWhen,
                options = optionsOf(field),
                allowCustom = field.bool("custom") ?: false,
                defaultValue = field.str("default"),
            )
        } else {
            FormField.FreeText(
                key = key, title = title, description = description, required = required, hidden = hidden,
                visibleWhen = visibleWhen,
                format = field.str("format")?.let(::textFormatOf),
                minLength = field.count("minLength"),
                maxLength = field.count("maxLength"),
                pattern = field.str("pattern"),
                placeholder = field.str("placeholder"),
                defaultValue = field.str("default"),
            )
        }
        "multiselect" -> FormField.MultipleChoice(
            key = key, title = title, description = description, required = required, hidden = hidden,
            visibleWhen = visibleWhen,
            options = optionsOf(field),
            allowCustom = field.bool("custom") ?: false,
            minSelected = field.count("minItems"),
            maxSelected = field.count("maxItems"),
            defaultValue = field.strings("default"),
        )
        "number", "integer" -> FormField.Number(
            key = key, title = title, description = description, required = required, hidden = hidden,
            visibleWhen = visibleWhen,
            integerOnly = type == "integer",
            min = field.decimalOrNonFinite("minimum"),
            max = field.decimalOrNonFinite("maximum"),
            defaultValue = field.decimalOrNonFinite("default"),
        )
        "boolean" -> FormField.YesNo(
            key = key, title = title, description = description, required = required, hidden = hidden,
            visibleWhen = visibleWhen,
            defaultValue = field.bool("default"),
        )
        "external" -> field.str("url")?.let { url -> FormField.External(key, title, description, url) }
        else -> null
    } ?: FormField.Unknown(
        key = key, title = title, description = description, required = required, hidden = hidden,
        visibleWhen = visibleWhen, rawType = type,
    )
}

/** Options with no `value` cannot be answered with, so they are left out. */
private fun optionsOf(field: JsonObject): List<FormOption> = field.objects("options").mapNotNull { option ->
    val value = option.str("value") ?: return@mapNotNull null
    FormOption(value = value, label = option.str("label") ?: value, description = option.str("description"))
}

/** `{key, op, value}`. Anything else is unknown and described only by its operator, never its value. */
private fun conditionOf(condition: JsonObject): FieldCondition {
    val op = condition.str("op").orEmpty()
    val key = condition.str("key")
    val value = condition.scalar("value")
    if (key == null || value == null) return FieldCondition.Unknown(op)
    return when (op) {
        "eq" -> FieldCondition.Equals(key, value)
        "neq" -> FieldCondition.NotEquals(key, value)
        else -> FieldCondition.Unknown(op)
    }
}

private fun textFormatOf(raw: String): TextFormat = when (raw) {
    "email" -> TextFormat.Email
    "uri" -> TextFormat.Uri
    "date" -> TextFormat.Date
    "date-time" -> TextFormat.DateTime
    else -> TextFormat.Unknown(raw)
}

/** The error names neither key nor value: answers are what the user typed, and errors get logged. */
private val unsendable = ApiError.BadRequest("A form answer is not text, a number, a boolean or a list of text.")

/**
 * `{answer: {key: value}}`, in the answers' own order. `Form.Value` is a string, a number, a boolean
 * or a list of strings: a choice or typed text is a string, as `q0` of a `string` question; several
 * picks are a list, as `q0` of a `multiselect` one. Anything else cannot be sent, and fails with
 * [ApiError.BadRequest] before the call starts.
 */
internal fun replyBody(answers: FormAnswers): JsonObject =
    JsonObject(mapOf("answer" to JsonObject(answers.mapValues { (_, value) -> answerValue(value) })))

/** A number keeps its exact text: `JsonPrimitive(BigDecimal)` would round it through a `Double`. */
@OptIn(ExperimentalSerializationApi::class)
private fun answerValue(value: DataValue): JsonElement = when (value) {
    is DataValue.Text -> JsonPrimitive(value.value)
    is DataValue.Num -> JsonUnquotedLiteral(value.value.toString())
    is DataValue.Bool -> JsonPrimitive(value.value)
    is DataValue.Items -> JsonArray(value.values.map { (it as? DataValue.Text)?.let { text -> JsonPrimitive(text.value) } ?: refuse() })
    DataValue.Null, is DataValue.Fields -> refuse()
}

private fun refuse(): Nothing = throw ApiException(unsendable)
