package io.github.thewisekd.phopencode.core.model

/**
 * A request to let the agent do something.
 *
 * @property sessionId May be a child session.
 * @property action What is asked, for example `bash`. Open: the server may add actions.
 * @property resources What the action applies to, for example paths or commands.
 * @property save What an `always` decision would remember. In a recording `["*"]` for reading `.env`
 *   saved a rule for all reads, so warn before sending [PermissionDecision.Always].
 * @property source Where the request came from.
 */
data class PermissionRequest(
    val id: PermissionRequestId,
    val sessionId: SessionId,
    val action: String,
    val resources: List<String> = emptyList(),
    val message: String? = null,
    val save: List<String> = emptyList(),
    val source: PermissionSource? = null,
    val metadata: Map<String, DataValue> = emptyMap(),
)

/**
 * @property type The source's kind; `tool` is the only one in the spec today.
 * @property id The tool call, as in [AssistantBlock.Tool.id].
 */
data class PermissionSource(
    val type: String,
    val messageId: MessageId,
    val id: String,
)

/** What a session or an agent may do. [action] and [resource] are patterns, and `*` is common. */
data class PermissionRule(
    val action: String,
    val resource: String,
    val effect: PermissionEffect,
)

sealed interface PermissionEffect {
    data object Allow : PermissionEffect

    data object Deny : PermissionEffect

    data object Ask : PermissionEffect

    data class Unknown(val raw: String) : PermissionEffect
}

/** The user's answer. The app sends this, so unlike server states it needs no unknown case. */
enum class PermissionDecision {
    Once,
    Always,
    Reject,
}
