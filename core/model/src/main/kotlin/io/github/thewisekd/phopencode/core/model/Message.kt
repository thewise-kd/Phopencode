package io.github.thewisekd.phopencode.core.model

import kotlin.time.Instant

/** One entry of a session's history. A type this app does not know yet becomes [Unknown]. */
sealed interface Message {
    val id: MessageId
    val sessionId: SessionId
    val createdAt: Instant?

    data class User(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val text: String,
        val files: List<String> = emptyList(),
        val agents: List<String> = emptyList(),
        val skills: List<String> = emptyList(),
    ) : Message

    /**
     * One step of a turn: a turn with three tool rounds gives four of these.
     *
     * @property usage What the server reported, if it did. Null while the message streams.
     */
    data class Assistant(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val completedAt: Instant? = null,
        val agent: String? = null,
        val model: ModelRef? = null,
        val blocks: List<AssistantBlock> = emptyList(),
        val usage: TokenUsage? = null,
        val finishReason: String? = null,
        val error: StructuredError? = null,
    ) : Message {
        /** The reported usage. Without one, the cost counts as missing rather than as free. */
        val totalUsage: TokenUsage
            get() = usage ?: TokenUsage()
    }

    data class Synthetic(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val text: String,
    ) : Message

    data class System(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val text: String,
    ) : Message

    data class SkillInvoked(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val name: String,
    ) : Message

    data class Shell(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val command: String,
        val output: String? = null,
    ) : Message

    /**
     * @property status The server's word: `running`, `completed` or `failed`. Open.
     * @property usage What it cost: the session's totals include this. Null until it ended.
     */
    data class Compaction(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val status: String? = null,
        val reason: String? = null,
        val model: ModelRef? = null,
        val summary: String? = null,
        val usage: TokenUsage? = null,
        val error: StructuredError? = null,
    ) : Message

    /** Marks where an execution ended. */
    data class Idle(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val outcome: SessionOutcome? = null,
        val error: StructuredError? = null,
    ) : Message

    data class AgentSwitched(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val agent: String,
        val previous: String? = null,
    ) : Message

    data class ModelSwitched(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val model: ModelRef,
        val previous: ModelRef? = null,
    ) : Message

    data class LocationSwitched(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val directory: String,
    ) : Message

    data class Unknown(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val rawType: String,
    ) : Message
}

/**
 * The usage of all assistant and compaction messages here, which is what a session total adds up.
 * Other message types consume no tokens.
 */
fun Iterable<Message>.totalUsage(): TokenUsage = mapNotNull { message ->
    when (message) {
        is Message.Assistant -> message.totalUsage
        is Message.Compaction -> message.usage ?: TokenUsage()
        else -> null
    }
}.sumUsage()
