package io.github.thewisekd.phopencode.core.model

import kotlin.time.Instant

/**
 * An opencode session. A subagent runs in a child session that carries [parentId].
 *
 * @property directory The folder the session works in.
 * @property outcome How the last execution ended. This is not whether it runs now: see
 *   [SessionActivity].
 * @property fork What this session was forked from, if it was.
 * @property revert The revert staged on this session, if there is one.
 * @property usage The total the server reported, if it did.
 */
data class Session(
    val id: SessionId,
    val parentId: SessionId? = null,
    val title: String? = null,
    val directory: String? = null,
    val projectId: String? = null,
    val subpath: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val archived: Boolean = false,
    val outcome: SessionOutcome? = null,
    val fork: SessionFork? = null,
    val revert: SessionRevert? = null,
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

sealed interface SessionOutcome {
    data object Succeeded : SessionOutcome

    data object Failed : SessionOutcome

    data object Interrupted : SessionOutcome

    data class Unknown(val raw: String) : SessionOutcome
}

/** The session this one was copied from, and how far the copy goes. */
data class SessionFork(
    val sessionId: SessionId,
    val boundary: ForkBoundary,
)

/** The point in the parent's history where a fork ends. */
data class ForkBoundary(
    val type: ForkBoundaryType,
    val messageId: MessageId,
)

sealed interface ForkBoundaryType {
    /** The copy ends with this message. */
    data object Through : ForkBoundaryType

    /** The copy ends just before this message. */
    data object Before : ForkBoundaryType

    data class Unknown(val raw: String) : ForkBoundaryType
}

/**
 * A staged revert.
 *
 * @property messageId The user message it undoes, with all that follows.
 * @property files What the revert would change.
 */
data class SessionRevert(
    val messageId: MessageId,
    val snapshot: String? = null,
    val files: List<FileDiff> = emptyList(),
)

/**
 * An entry of `/api/session/active`. The server has only `running` today, and leaves idle sessions out.
 */
sealed interface SessionActivity {
    data object Running : SessionActivity

    data class Unknown(val raw: String) : SessionActivity
}

/**
 * The status of the server's `session.status` event, which no recording has. Busy and idle come from
 * the `session.execution.*` events and [SessionActivity] instead.
 */
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
