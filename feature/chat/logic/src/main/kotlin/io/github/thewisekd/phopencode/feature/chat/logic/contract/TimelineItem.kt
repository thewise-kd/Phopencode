package io.github.thewisekd.phopencode.feature.chat.logic.contract

import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.StructuredError
import kotlin.time.Instant

/**
 * Names one item of the timeline for as long as it lives: a list key, and how the card presenters
 * ([ReasoningState.cards], [ToolState.cards]) and the timeline agree on an item. Built only by the
 * functions here, so that every presenter derives the same key from the same message.
 */
@JvmInline
value class TimelineKey private constructor(val value: String) {
    companion object {
        /**
         * A whole message: a user prompt, a shell run, a compaction, a notice, an execution's end, an
         * assistant message's error. An inbox item becomes the message of the same ID, so a prompt
         * keeps its key when the server delivers it.
         */
        fun message(id: MessageId): TimelineKey = TimelineKey("message:${id.value}")

        /** The text block at [index] of `Message.Assistant.blocks`. */
        fun text(id: MessageId, index: Int): TimelineKey = TimelineKey("text:${id.value}:$index")

        /** The reasoning block at [index] of `Message.Assistant.blocks`. */
        fun reasoning(id: MessageId, index: Int): TimelineKey = TimelineKey("reasoning:${id.value}:$index")

        /** The tool call [callId] (`AssistantBlock.Tool.id`) of an assistant message. */
        fun tool(id: MessageId, callId: String): TimelineKey = TimelineKey("tool:${id.value}:$callId")
    }
}

/**
 * One item of the chat's timeline, oldest first (P6.3 builds them, P6.4 draws them).
 *
 * Where they come from: `ChatSessionState.session`, that is the messages of `SessionState` and its
 * inbox. A user message, or an inbox item not yet delivered, gives one [User]; an assistant message
 * gives one item per block, in block order ([AssistantText], [Reasoning], [Tool]; an unknown block
 * gives nothing), then [AssistantError] if it failed. The other message types give one item each.
 *
 * [Reasoning] and [Tool] only place a card: what the card shows comes from [ReasoningPresenter] and
 * [ToolPresenter] by [key]. Text a user typed, a model wrote or a tool printed prints masked.
 */
sealed interface TimelineItem {
    val key: TimelineKey
    val messageId: MessageId

    /**
     * A prompt.
     *
     * @property pending How the prompt waits in the inbox while the session runs; null once
     *   delivered.
     */
    data class User(
        override val key: TimelineKey,
        override val messageId: MessageId,
        val text: String,
        val files: List<String> = emptyList(),
        val agents: List<String> = emptyList(),
        val skills: List<String> = emptyList(),
        val createdAt: Instant? = null,
        val pending: PromptDelivery? = null,
    ) : TimelineItem {
        override fun toString(): String =
            "User(key=${key.value}, files=${files.size}, agents=$agents, skills=$skills, createdAt=$createdAt, " +
                "pending=$pending, text=***)"
    }

    /** @property streaming The block has no `completedAt` yet: more text may follow. */
    data class AssistantText(
        override val key: TimelineKey,
        override val messageId: MessageId,
        val text: String,
        val streaming: Boolean = false,
    ) : TimelineItem {
        override fun toString(): String = "AssistantText(key=${key.value}, streaming=$streaming, text=***)"
    }

    /** Where a reasoning card goes; it shows `ReasoningState.cards[key]`, or nothing. */
    data class Reasoning(
        override val key: TimelineKey,
        override val messageId: MessageId,
    ) : TimelineItem

    /** Where a tool card goes; it shows `ToolState.cards[key]`, or nothing. */
    data class Tool(
        override val key: TimelineKey,
        override val messageId: MessageId,
        val callId: String,
        val name: String,
    ) : TimelineItem

    /** The assistant message ended with [error]. [StructuredError] prints no server text. */
    data class AssistantError(
        override val key: TimelineKey,
        override val messageId: MessageId,
        val error: StructuredError,
    ) : TimelineItem

    /** A shell command the user ran in the session; [output] is null while it runs. */
    data class Shell(
        override val key: TimelineKey,
        override val messageId: MessageId,
        val command: String,
        val output: String? = null,
    ) : TimelineItem {
        override fun toString(): String =
            "Shell(key=${key.value}, command=***, output=${if (output == null) "null" else "***"})"
    }

    /** @property status The server's word: `running`, `completed` or `failed`. Open. */
    data class Compaction(
        override val key: TimelineKey,
        override val messageId: MessageId,
        val status: String? = null,
        val summary: String? = null,
    ) : TimelineItem {
        override fun toString(): String =
            "Compaction(key=${key.value}, status=$status, summary=${if (summary == null) "null" else "***"})"
    }

    /** A one-line change in the session: see [TimelineNotice]. */
    data class Notice(
        override val key: TimelineKey,
        override val messageId: MessageId,
        val notice: TimelineNotice,
    ) : TimelineItem

    /** Where an execution ended (`Message.Idle`). */
    data class TurnEnded(
        override val key: TimelineKey,
        override val messageId: MessageId,
        val outcome: TurnOutcome,
        val error: StructuredError? = null,
    ) : TimelineItem

    /** A message type this app does not know yet. */
    data class Unknown(
        override val key: TimelineKey,
        override val messageId: MessageId,
        val rawType: String,
    ) : TimelineItem
}

/** The small messages of a session that are not a turn of the conversation. */
sealed interface TimelineNotice {
    data class AgentSwitched(val agent: String, val previous: String? = null) : TimelineNotice

    data class ModelSwitched(val model: ModelRef, val previous: ModelRef? = null) : TimelineNotice

    data class LocationSwitched(val directory: String) : TimelineNotice

    data class SkillInvoked(val name: String) : TimelineNotice

    /** Text the server added, such as the result of a command. */
    data class Synthetic(val text: String) : TimelineNotice {
        override fun toString(): String = "Synthetic(***)"
    }

    data class System(val text: String) : TimelineNotice {
        override fun toString(): String = "System(***)"
    }
}

/** How an execution ended, from `Message.Idle.outcome`. */
enum class TurnOutcome {
    Succeeded,
    Failed,
    Interrupted,

    /** No outcome, or one this app does not know. */
    Unknown,
}
