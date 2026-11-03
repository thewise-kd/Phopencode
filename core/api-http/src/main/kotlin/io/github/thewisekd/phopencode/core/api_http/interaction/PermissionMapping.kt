package io.github.thewisekd.phopencode.core.api_http.interaction

import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.PermissionSource
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `Permission.Request`: `{id, sessionID, action, resources, save?, source?, message?, metadata?}`.
 * A source missing any of its three keys is dropped, as the `permission.asked` event's is.
 */
internal fun permissionRequestOf(data: JsonObject): PermissionRequest = PermissionRequest(
    id = PermissionRequestId(data.requireStr("id")),
    sessionId = SessionId(data.requireStr("sessionID")),
    action = data.requireStr("action"),
    resources = data.strings("resources"),
    message = data.str("message"),
    save = data.strings("save"),
    source = data.obj("source")?.let(::permissionSourceOf),
    metadata = data.obj("metadata")?.let(::dataMapOf).orEmpty(),
)

private fun permissionSourceOf(source: JsonObject): PermissionSource? {
    val type = source.str("type") ?: return null
    val messageId = source.str("messageID") ?: return null
    val id = source.str("id") ?: return null
    return PermissionSource(type, MessageId(messageId), id)
}

/** `{decision, message?}`; no message leaves the key out rather than sending `null`. */
internal fun permissionReplyBody(decision: PermissionDecision, message: String?): JsonObject = JsonObject(
    buildMap {
        put(
            "decision",
            JsonPrimitive(
                when (decision) {
                    PermissionDecision.Once -> "once"
                    PermissionDecision.Always -> "always"
                    PermissionDecision.Reject -> "reject"
                },
            ),
        )
        message?.let { put("message", JsonPrimitive(it)) }
    },
)
