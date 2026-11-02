package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.ForkBoundary
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormAnswers
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionRevert
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.ToolContent
import kotlin.time.Instant

/**
 * What every event carries next to its payload.
 *
 * @property id Tells a repeated event from a new one.
 * @property createdAt When the server created it. Events can arrive out of order.
 * @property durable Set on events the server persists.
 */
data class EventMeta(
    val id: String? = null,
    val createdAt: Instant? = null,
    val location: Location? = null,
    val metadata: Map<String, DataValue> = emptyMap(),
    val durable: EventDurable? = null,
) {
    /** Leaves out the folder and the metadata, which are the server's free-form data. */
    override fun toString(): String = "EventMeta(id=$id, createdAt=$createdAt, durable=$durable)"
}

/**
 * An event's place in what the server stored.
 *
 * @property aggregateId The session it belongs to, for session events.
 * @property seq The position within [aggregateId].
 */
data class EventDurable(
    val aggregateId: String,
    val seq: Long,
    val version: Int,
)

/** Why an execution was interrupted. */
sealed interface InterruptReason {
    /** Someone called [SessionApi.stop]. */
    data object User : InterruptReason

    /** A question was dismissed, which ends the whole execution. */
    data object Shutdown : InterruptReason

    data class Unknown(val raw: String) : InterruptReason
}

/**
 * One item of the event stream. See [EventApi.events].
 *
 * `*Delta` events are live only and arrive in pieces. The definitive values are in the matching
 * `*Ended` event and in the message list, so the two are separate types.
 *
 * Events can hold prompts, model output, tool input and file contents, any of which can contain a
 * credential. [toString] is final and prints only the event's name, ID and session, so a data class
 * here cannot print its fields. Read a field only to use it, not to log it.
 *
 * Fields the V2 spec may leave out are nullable, so that a sparse event still decodes.
 *
 * `messageId` is the server's `assistantMessageID`. An assistant message is one step, so a turn with
 * three tool rounds gives four assistant messages. Blocks overlap and tools finish in any order: match
 * events on `messageId`, `ordinal` and `id`, never on arrival order.
 *
 * Failures carry [StructuredError], whose text is the server's and which prints without it.
 */
sealed class ServerEvent {
    abstract val meta: EventMeta

    final override fun toString(): String {
        val session = (this as? SessionEvent)?.let { ", session=${it.sessionId.value}" }.orEmpty()
        return "ServerEvent.${javaClass.simpleName}(id=${meta.id}$session)"
    }

    // The stream

    /** `server.connected`: the first event of every connection. */
    data class Connected(override val meta: EventMeta = EventMeta()) : ServerEvent()

    /** Not sent by the server: the client emits this when the stream drops, before reconnecting. */
    data class Disconnected(
        val reason: ApiError,
        override val meta: EventMeta = EventMeta(),
    ) : ServerEvent()

    /** An event type this app does not know yet. Collectors skip it. */
    data class Unknown(
        val rawType: String,
        override val meta: EventMeta = EventMeta(),
    ) : ServerEvent()

    // Session lifecycle

    /** Flat on the wire: there is no nested session, so a full one needs [SessionApi.get]. */
    data class SessionCreated(
        override val sessionId: SessionId,
        val slug: String? = null,
        val version: String? = null,
        val projectId: String? = null,
        val location: Location? = null,
        val subpath: String? = null,
        val title: String? = null,
        val parentId: SessionId? = null,
        val agent: String? = null,
        val metadata: Map<String, DataValue> = emptyMap(),
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class SessionDeleted(
        override val sessionId: SessionId,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class SessionRenamed(
        override val sessionId: SessionId,
        val title: String? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class SessionArchived(
        override val sessionId: SessionId,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /** `session.unarchived`: a separate event from [SessionArchived], not a flag on it. */
    data class SessionUnarchived(
        override val sessionId: SessionId,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /**
     * @property sessionId The new session.
     * @property parentId The session it was forked from.
     * @property boundary Where in [parentId]'s history the copy ends.
     */
    data class SessionForked(
        override val sessionId: SessionId,
        val parentId: SessionId? = null,
        val boundary: ForkBoundary? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Execution

    /**
     * `session.status`. The spec lists it, but opencode 2.2.5 never sent it in any recording, so this
     * must not be relied on. Busy and idle come from `session.execution.*` ([ExecutionStarted] and its
     * three endings) and from `/api/session/active` ([SessionApi.activeStatuses]).
     */
    data class SessionStatusChanged(
        override val sessionId: SessionId,
        val status: SessionStatus,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ExecutionStarted(
        override val sessionId: SessionId,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ExecutionSucceeded(
        override val sessionId: SessionId,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ExecutionFailed(
        override val sessionId: SessionId,
        val error: StructuredError? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ExecutionInterrupted(
        override val sessionId: SessionId,
        val reason: InterruptReason? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /**
     * @property attempt The attempt to come, so the first retry is 2.
     * @property nextAttemptAt The server's `at`.
     */
    data class RetryScheduled(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val attempt: Int? = null,
        val nextAttemptAt: Instant? = null,
        val error: StructuredError? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Steps

    /** @property startedAt The server's `started`. */
    data class StepStarted(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val agent: String? = null,
        val model: ModelRef? = null,
        val snapshot: String? = null,
        val startedAt: Instant? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class StepStreamed(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /**
     * @property usage The step's own `tokens` and `cost`. A step with no usage from the model has zeros.
     * @property finishReason The server's `finish`.
     * @property rawFinish The provider's own wording.
     * @property files The paths the step changed.
     */
    data class StepEnded(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val usage: TokenUsage? = null,
        val finishReason: String? = null,
        val rawFinish: String? = null,
        val snapshot: String? = null,
        val files: List<String> = emptyList(),
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /**
     * @property usage Absent on some failures.
     * @property files The paths the step changed.
     */
    data class StepFailed(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val error: StructuredError? = null,
        val usage: TokenUsage? = null,
        val rawFinish: String? = null,
        val snapshot: String? = null,
        val files: List<String> = emptyList(),
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Text

    /** @property ordinal Counts per block kind: a message's first text and first reasoning are both 0. */
    data class TextStarted(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val ordinal: Int,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class TextDelta(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val ordinal: Int,
        val delta: String,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class TextEnded(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val ordinal: Int,
        val text: String,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Reasoning

    /** @property ordinal Counts per block kind: a message's first text and first reasoning are both 0. */
    data class ReasoningStarted(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val ordinal: Int,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ReasoningDelta(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val ordinal: Int,
        val delta: String,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /** @property text May be empty: visibility depends on the provider. */
    data class ReasoningEnded(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val ordinal: Int,
        val text: String,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Tools. `id` is the server's key for a call, and not an event ID: that one is in `meta`.

    /** @property name The tool's name. Only this event has it, so the others are matched on [id]. */
    data class ToolInputStarted(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val id: String,
        val name: String? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /**
     * The spec lists this, but no recording has it, even with chunks 400 ms apart. That may be specific
     * to the simulated route, so the event stays. Nothing may wait for it.
     */
    data class ToolInputDelta(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val id: String,
        val delta: String,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /** @property text The whole input as the raw JSON string. [ToolCalled] has it parsed. */
    data class ToolInputEnded(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val id: String,
        val text: String? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /** Has no name: take it from [ToolInputStarted]. */
    data class ToolCalled(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val id: String,
        val input: DataValue? = null,
        val executed: Boolean? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /**
     * @property sessionId The session that called the tool.
     * @property childSessionId The session a subagent tool runs in, from `metadata.sessionID`.
     * @property status The child's status, from `metadata.status`. Open: the server may add more.
     */
    data class ToolProgress(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val id: String,
        val childSessionId: SessionId? = null,
        val status: String? = null,
        val metadata: Map<String, DataValue> = emptyMap(),
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ToolSucceeded(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val id: String,
        val content: List<ToolContent> = emptyList(),
        val metadata: Map<String, DataValue> = emptyMap(),
        val executed: Boolean? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ToolFailed(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val id: String,
        val error: StructuredError? = null,
        val executed: Boolean? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Usage

    /** `session.usage.updated`: the session's running total, not a step's values. Never add these up. */
    data class UsageUpdated(
        override val sessionId: SessionId,
        val usage: TokenUsage,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Interaction

    data class FormCreated(
        val form: Form,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent() {
        override val sessionId: SessionId
            get() = form.sessionId
    }

    data class FormReplied(
        override val sessionId: SessionId,
        val formId: FormId,
        val answer: FormAnswers? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class FormCancelled(
        override val sessionId: SessionId,
        val formId: FormId,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class PermissionAsked(
        val request: PermissionRequest,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent() {
        override val sessionId: SessionId
            get() = request.sessionId
    }

    /** @property decision Null when the server sent a decision this app does not know. */
    data class PermissionReplied(
        override val sessionId: SessionId,
        val requestId: PermissionRequestId,
        val decision: PermissionDecision? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Other

    /** @property inputId The inbox item that asked for it. */
    data class CompactionStarted(
        override val sessionId: SessionId,
        val reason: String? = null,
        val inputId: MessageId? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class CompactionDelta(
        override val sessionId: SessionId,
        val text: String,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /** @property text The whole summary. */
    data class CompactionEnded(
        override val sessionId: SessionId,
        val reason: String? = null,
        val model: ModelRef? = null,
        val text: String? = null,
        val usage: TokenUsage? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /** @property usage What it cost: a failed compaction is billed too. */
    data class CompactionFailed(
        override val sessionId: SessionId,
        val reason: String? = null,
        val error: StructuredError? = null,
        val inputId: MessageId? = null,
        val usage: TokenUsage? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class RevertStaged(
        override val sessionId: SessionId,
        val revert: SessionRevert,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class RevertCleared(
        override val sessionId: SessionId,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /** @property to The message the revert was staged on: it and all after it are gone. */
    data class RevertCommitted(
        override val sessionId: SessionId,
        val to: MessageId? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /** The fields come from the server's `shell` object. */
    data class ShellStarted(
        override val sessionId: SessionId,
        val shellId: String? = null,
        val command: String? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /**
     * The fields come from the server's `shell` object, and [output] from `output.output`.
     *
     * @property exitCode The server's `exit`, if it gave one.
     */
    data class ShellEnded(
        override val sessionId: SessionId,
        val shellId: String? = null,
        val command: String? = null,
        val output: String? = null,
        val exitCode: Int? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /** `session.inbox.enqueued`. The wire item lacks `id` and `sessionID`, which sit beside it. */
    data class InboxEnqueued(
        val item: InboxItem,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent() {
        override val sessionId: SessionId
            get() = item.sessionId
    }

    data class InboxDelivered(
        override val sessionId: SessionId,
        val inboxId: MessageId,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class InboxCancelled(
        override val sessionId: SessionId,
        val inboxId: MessageId,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /** @property delivery Null when the server sent a delivery this app does not know. */
    data class InboxDeliveryChanged(
        override val sessionId: SessionId,
        val inboxId: MessageId,
        val delivery: PromptDelivery? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class AgentSelected(
        override val sessionId: SessionId,
        val agent: String,
        val previous: String? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ModelSelected(
        override val sessionId: SessionId,
        val model: ModelRef,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()
}

/**
 * An event about one session, which may be a child session: those arrive on the same stream with
 * their own [sessionId].
 */
sealed class SessionEvent : ServerEvent() {
    abstract val sessionId: SessionId
}
