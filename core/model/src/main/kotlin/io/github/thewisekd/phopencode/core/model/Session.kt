package io.github.thewisekd.phopencode.core.model

import kotlin.time.Instant

/**
 * An opencode session. A subagent runs in a child session that carries [parentId].
 *
 * @property directory The folder the session works in.
 * @property usage The total the server reported, if it did.
 */
data class Session(
    val id: SessionId,
    val parentId: SessionId? = null,
    val title: String? = null,
    val directory: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val archived: Boolean = false,
    val usage: TokenUsage? = null,
    val metadata: Map<String, DataValue> = emptyMap(),
) {
    val isRoot: Boolean
        get() = parentId == null

    /**
     * The reported total, or else the total of [messages]. The report wins because [messages] may
     * be a single page of the history.
     */
    fun totalUsage(messages: Iterable<Message>): TokenUsage = usage ?: messages.totalUsage()
}

/** What a session is doing now. */
sealed interface SessionStatus {
    data object Idle : SessionStatus

    data object Running : SessionStatus

    data class Retrying(
        val attempt: Int? = null,
        val message: String? = null,
        val nextAttemptAt: Instant? = null,
    ) : SessionStatus

    data class Failed(val message: String? = null) : SessionStatus

    data class Unknown(val raw: String) : SessionStatus
}
