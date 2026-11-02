package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.model.TokenUsage
import kotlin.time.Instant

/**
 * What every event carries next to its payload.
 *
 * @property id Tells a repeated event from a new one.
 * @property createdAt When the server created it. Events can arrive out of order.
 */
data class EventMeta(
    val id: String? = null,
    val createdAt: Instant? = null,
    val location: Location? = null,
    val metadata: Map<String, DataValue> = emptyMap(),
) {
    /** Leaves out the folder and the metadata, which are the server's free-form data. */
    override fun toString(): String = "EventMeta(id=$id, createdAt=$createdAt)"
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

    data class SessionCreated(
        override val sessionId: SessionId,
        val session: Session? = null,
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

    /** @property sessionId The new session. */
    data class SessionForked(
        override val sessionId: SessionId,
        val forkedFrom: SessionId? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Execution

    /** `session.status`. */
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
        val error: String? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ExecutionInterrupted(
        override val sessionId: SessionId,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class RetryScheduled(
        override val sessionId: SessionId,
        val attempt: Int? = null,
        val message: String? = null,
        val nextAttemptAt: Instant? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Steps

    data class StepStarted(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class StepStreamed(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class StepEnded(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val usage: TokenUsage? = null,
        val finishReason: String? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class StepFailed(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val error: String? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Text

    /** @property ordinal The block's place in the assistant message. */
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

    /** @property ordinal The block's place in the assistant message. */
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

    // Tools

    data class ToolInputStarted(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val callId: String,
        val name: String? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ToolInputDelta(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val callId: String,
        val delta: String,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ToolInputEnded(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val callId: String,
        val input: DataValue? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ToolCalled(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val callId: String,
        val name: String? = null,
        val input: DataValue? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /**
     * @property sessionId The session that called the tool.
     * @property childSessionId The session a subagent tool runs in.
     * @property status The child's status, as the server words it. Open: the server may add more.
     */
    data class ToolProgress(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val callId: String,
        val childSessionId: SessionId? = null,
        val status: String? = null,
        val metadata: Map<String, DataValue> = emptyMap(),
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ToolSucceeded(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val callId: String,
        val output: String? = null,
        val metadata: Map<String, DataValue> = emptyMap(),
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ToolFailed(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
        val callId: String,
        val error: String? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    // Usage

    /** `session.usage.updated`: the live total of the session. */
    data class UsageUpdated(
        override val sessionId: SessionId,
        val usage: TokenUsage,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /** `session.usage.recorded`: a final amount, booked for one message. */
    data class UsageRecorded(
        override val sessionId: SessionId,
        val messageId: MessageId? = null,
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

    /**
     * A `session.compaction.*` event.
     *
     * @property phase The part after the prefix, for example `started`.
     */
    data class CompactionChanged(
        override val sessionId: SessionId,
        val phase: String,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    /**
     * A `session.revert.*` event.
     *
     * @property phase The part after the prefix, for example `staged`.
     */
    data class RevertChanged(
        override val sessionId: SessionId,
        val phase: String,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ShellStarted(
        override val sessionId: SessionId,
        val command: String? = null,
        override val meta: EventMeta = EventMeta(),
    ) : SessionEvent()

    data class ShellEnded(
        override val sessionId: SessionId,
        val output: String? = null,
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
