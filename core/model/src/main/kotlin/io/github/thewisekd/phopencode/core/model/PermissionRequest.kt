package io.github.thewisekd.phopencode.core.model

/**
 * A request to let the agent do something.
 *
 * @property sessionId May be a child session.
 * @property action What is asked, for example `bash`. Open: the server may add actions.
 * @property resources What the action applies to, for example paths or commands.
 * @property save What an `always` decision would remember, in the server's own shape.
 * @property source Where the request came from, in the server's own shape.
 */
data class PermissionRequest(
    val id: PermissionRequestId,
    val sessionId: SessionId,
    val action: String,
    val resources: List<String> = emptyList(),
    val message: String? = null,
    val save: DataValue? = null,
    val source: DataValue? = null,
    val metadata: Map<String, DataValue> = emptyMap(),
)

/** The user's answer. The app sends this, so unlike server states it needs no unknown case. */
enum class PermissionDecision {
    Once,
    Always,
    Reject,
}
