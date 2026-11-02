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
     * @property id The server's key for this call.
     * @property input The parsed input. While [ToolStatus.Streaming] it is the raw text so far.
     * @property createdAt The server's `time.created`.
     * @property startedAt The server's `time.ran`.
     * @property childSessionId The session a subagent tool runs in, from `metadata.sessionID`.
     */
    data class Tool(
        val id: String,
        val name: String,
        val status: ToolStatus,
        val input: DataValue? = null,
        val content: List<ToolContent> = emptyList(),
        val error: StructuredError? = null,
        val createdAt: Instant? = null,
        val startedAt: Instant? = null,
        val completedAt: Instant? = null,
        val childSessionId: SessionId? = null,
        val executed: Boolean? = null,
        val metadata: Map<String, DataValue> = emptyMap(),
    ) : AssistantBlock

    /** A content type this app does not know yet. */
    data class Unknown(val rawType: String) : AssistantBlock
}

/** The server's `state.status`. [Failed] is its `error`. */
sealed interface ToolStatus {
    /** The model is still writing the tool's input. */
    data object Streaming : ToolStatus

    data object Running : ToolStatus

    data object Completed : ToolStatus

    data object Failed : ToolStatus

    data class Unknown(val raw: String) : ToolStatus
}

/** One item of a tool's result. */
sealed interface ToolContent {
    data class Text(val text: String) : ToolContent

    data class File(
        val uri: String,
        val mime: String,
        val name: String? = null,
    ) : ToolContent

    /** A content type this app does not know yet. */
    data class Unknown(val rawType: String) : ToolContent
}
