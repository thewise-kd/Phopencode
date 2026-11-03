package io.github.thewisekd.phopencode.core.api_http.event.decode

import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.FieldCondition
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.FormOption
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.PermissionSource
import io.github.thewisekd.phopencode.core.model.TextFormat
import kotlinx.serialization.json.JsonObject

/** Decoders for `form.*` and `permission.*` events, keyed by their wire type. */
internal val interactionDecoders: Map<String, (JsonObject, EventMeta) -> ServerEvent> = mapOf(
    "form.created" to { d, m -> ServerEvent.FormCreated(formOf(d.requireObj("form")), m) },
    "form.replied" to { d, m ->
        ServerEvent.FormReplied(d.sessionId(), FormId(d.requireStr("id")), d.obj("answer")?.let(::dataMapOf), m)
    },
    "form.cancelled" to { d, m -> ServerEvent.FormCancelled(d.sessionId(), FormId(d.requireStr("id")), m) },
    "permission.asked" to { d, m -> ServerEvent.PermissionAsked(permissionRequestOf(d), m) },
    "permission.replied" to { d, m ->
        ServerEvent.PermissionReplied(
            sessionId = d.sessionId(),
            requestId = PermissionRequestId(d.requireStr("requestID")),
            decision = d.str("reply")?.let(::decisionOf),
            meta = m,
        )
    },
)

/** `{id, sessionID, title?, metadata?, fields}`. The event carries no `state`: only the detail route does. */
private fun formOf(form: JsonObject): Form = Form(
    id = FormId(form.requireStr("id")),
    sessionId = form.sessionId(),
    kind = when (val kind = form.obj("metadata")?.str("kind")) {
        "question" -> FormKind.Question
        else -> FormKind.Other(kind)
    },
    title = form.str("title"),
    fields = form.array("fields").orEmpty().map { field -> fieldOf(field as? JsonObject ?: throw MalformedEvent()) },
    metadata = form.dataMap("metadata"),
    state = null,
)

/**
 * A field with no `key` cannot be answered, so it makes the whole form malformed. A field whose type
 * is unknown, or an `external` one with no `url`, becomes [FormField.Unknown] for the UI to refuse.
 */
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
                minLength = field.int("minLength"),
                maxLength = field.int("maxLength"),
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
            minSelected = field.int("minItems"),
            maxSelected = field.int("maxItems"),
            defaultValue = field.strings("default"),
        )
        // `Infinity` and `NaN` limits come as strings and so read as null.
        "number", "integer" -> FormField.Number(
            key = key, title = title, description = description, required = required, hidden = hidden,
            visibleWhen = visibleWhen,
            integerOnly = type == "integer",
            min = field.decimal("minimum"),
            max = field.decimal("maximum"),
            defaultValue = field.decimal("default"),
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
private fun optionsOf(field: JsonObject): List<FormOption> = field.objects("options").mapNotNull(::optionOf)

private fun optionOf(option: JsonObject): FormOption? {
    val value = option.str("value") ?: return null
    return FormOption(value = value, label = option.str("label") ?: value, description = option.str("description"))
}

/** `{key, op, value}`. Anything else is unknown and described only by its operator, never its value. */
private fun conditionOf(condition: JsonObject): FieldCondition {
    val op = condition.str("op").orEmpty()
    val key = condition.str("key")
    val value = condition.data("value")
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

/** `{id, sessionID, action, resources, save, source{type, messageID, id}, message?, metadata?}`. */
private fun permissionRequestOf(data: JsonObject): PermissionRequest = PermissionRequest(
    id = PermissionRequestId(data.requireStr("id")),
    sessionId = data.sessionId(),
    action = data.requireStr("action"),
    resources = data.strings("resources"),
    message = data.str("message"),
    save = data.strings("save"),
    source = data.obj("source")?.let(::permissionSourceOf),
    metadata = data.dataMap("metadata"),
)

private fun permissionSourceOf(source: JsonObject): PermissionSource? {
    val type = source.str("type") ?: return null
    val messageId = source.messageId("messageID") ?: return null
    val id = source.str("id") ?: return null
    return PermissionSource(type, messageId, id)
}

private fun decisionOf(raw: String): PermissionDecision? = when (raw) {
    "once" -> PermissionDecision.Once
    "always" -> PermissionDecision.Always
    "reject" -> PermissionDecision.Reject
    else -> null
}
