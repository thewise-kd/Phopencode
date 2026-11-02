package io.github.thewisekd.phopencode.core.model

import kotlin.time.Instant

/** One item of an assistant message's content, in order. */
sealed interface AssistantBlock {
    data class Text(
        val text: String,
        val createdAt: Instant? = null,
        val completedAt: Instant? = null,
    ) : AssistantBlock

    /** The model's thinking. [text] may be empty: visibility depends on the provider. */
    data class Reasoning(
        val text: String,
        val createdAt: Instant? = null,
        val completedAt: Instant? = null,
    ) : AssistantBlock

    /**
     * A tool call. [name] is open: any tool the server has, known to this app or not.
     *
     * @property childSessionId The session a subagent tool runs in.
     */
    data class Tool(
        val callId: String,
        val name: String,
        val status: ToolStatus,
        val input: DataValue? = null,
        val output: String? = null,
        val error: String? = null,
        val startedAt: Instant? = null,
        val completedAt: Instant? = null,
        val childSessionId: SessionId? = null,
        val metadata: Map<String, DataValue> = emptyMap(),
    ) : AssistantBlock

    /** A content type this app does not know yet. */
    data class Unknown(val rawType: String) : AssistantBlock
}

sealed interface ToolStatus {
    data object Pending : ToolStatus

    data object Running : ToolStatus

    data object Completed : ToolStatus

    data object Failed : ToolStatus

    data class Unknown(val raw: String) : ToolStatus
}
