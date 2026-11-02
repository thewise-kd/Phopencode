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
     * @property usage The total the server reported, if it did. Null while the message streams.
     * @property steps The usage of each finished step, in order.
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
        val steps: List<TokenUsage> = emptyList(),
        val finishReason: String? = null,
        val error: String? = null,
    ) : Message {
        /**
         * The reported total, or else the total of [steps]. A message with neither has reported no
         * cost, so it counts as missing rather than as free.
         */
        val totalUsage: TokenUsage
            get() = usage ?: if (steps.isEmpty()) TokenUsage() else steps.sumUsage()
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

    data class Compaction(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
    ) : Message

    data class Idle(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
    ) : Message

    data class AgentSwitched(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val agent: String,
    ) : Message

    data class ModelSwitched(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant? = null,
        val model: ModelRef,
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

/** The usage of all assistant messages here; other message types consume no tokens. */
fun Iterable<Message>.totalUsage(): TokenUsage =
    filterIsInstance<Message.Assistant>().map { it.totalUsage }.sumUsage()
