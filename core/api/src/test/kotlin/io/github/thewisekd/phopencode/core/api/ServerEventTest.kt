package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.model.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val SENTINEL = "SENTINEL-payload-text"

class ServerEventTest {
    private val session = SessionId("ses_1")
    private val message = MessageId("msg_1")
    private val sensitive = DataValue.Text(SENTINEL)
    private val sensitiveMap = mapOf("key" to sensitive)

    /** One of each event, with the sentinel in every field that holds free text or server data. */
    private val samples: List<ServerEvent> = listOf(
        ServerEvent.Connected(),
        ServerEvent.Disconnected(reason = ApiError.Conflict(SENTINEL)),
        ServerEvent.Unknown(rawType = SENTINEL),
        ServerEvent.SessionCreated(session, Session(session, title = SENTINEL)),
        ServerEvent.SessionDeleted(session),
        ServerEvent.SessionRenamed(session, title = SENTINEL),
        ServerEvent.SessionArchived(session),
        ServerEvent.SessionForked(session, forkedFrom = SessionId("ses_0")),
        ServerEvent.SessionStatusChanged(session, SessionStatus.Failed(SENTINEL)),
        ServerEvent.ExecutionStarted(session),
        ServerEvent.ExecutionSucceeded(session),
        ServerEvent.ExecutionFailed(session, error = SENTINEL),
        ServerEvent.ExecutionInterrupted(session),
        ServerEvent.RetryScheduled(session, attempt = 1, message = SENTINEL),
        ServerEvent.StepStarted(session, message),
        ServerEvent.StepStreamed(session, message),
        ServerEvent.StepEnded(session, message, TokenUsage.of(input = 1), finishReason = SENTINEL),
        ServerEvent.StepFailed(session, message, error = SENTINEL),
        ServerEvent.TextStarted(session, message, ordinal = 0),
        ServerEvent.TextDelta(session, message, ordinal = 0, delta = SENTINEL),
        ServerEvent.TextEnded(session, message, ordinal = 0, text = SENTINEL),
        ServerEvent.ReasoningStarted(session, message, ordinal = 0),
        ServerEvent.ReasoningDelta(session, message, ordinal = 0, delta = SENTINEL),
        ServerEvent.ReasoningEnded(session, message, ordinal = 0, text = SENTINEL),
        ServerEvent.ToolInputStarted(session, message, callId = "call_1", name = SENTINEL),
        ServerEvent.ToolInputDelta(session, message, callId = "call_1", delta = SENTINEL),
        ServerEvent.ToolInputEnded(session, message, callId = "call_1", input = sensitive),
        ServerEvent.ToolCalled(session, message, callId = "call_1", name = SENTINEL, input = sensitive),
        ServerEvent.ToolProgress(
            session,
            message,
            callId = "call_1",
            childSessionId = SessionId("ses_2"),
            status = SENTINEL,
            metadata = sensitiveMap,
        ),
        ServerEvent.ToolSucceeded(session, message, callId = "call_1", output = SENTINEL, metadata = sensitiveMap),
        ServerEvent.ToolFailed(session, message, callId = "call_1", error = SENTINEL),
        ServerEvent.UsageUpdated(session, TokenUsage.of(input = 1)),
        ServerEvent.UsageRecorded(session, message, TokenUsage.of(input = 1)),
        ServerEvent.FormCreated(Form(FormId("frm_1"), session, FormKind.Question, title = SENTINEL)),
        ServerEvent.FormReplied(session, FormId("frm_1")),
        ServerEvent.FormCancelled(session, FormId("frm_1")),
        ServerEvent.PermissionAsked(
            PermissionRequest(PermissionRequestId("per_1"), session, action = SENTINEL, message = SENTINEL),
        ),
        ServerEvent.PermissionReplied(session, PermissionRequestId("per_1"), PermissionDecision.Once),
        ServerEvent.CompactionChanged(session, phase = SENTINEL),
        ServerEvent.RevertChanged(session, phase = SENTINEL),
        ServerEvent.ShellStarted(session, command = SENTINEL),
        ServerEvent.ShellEnded(session, output = SENTINEL),
    )

    /** Has no `else`: adding an event fails compilation here until it is handled. */
    private fun describe(event: ServerEvent): String = when (event) {
        is ServerEvent.Connected -> "connected"
        is ServerEvent.Disconnected -> "disconnected"
        is ServerEvent.Unknown -> "unknown:${event.rawType}"
        is ServerEvent.SessionCreated -> "session.created"
        is ServerEvent.SessionDeleted -> "session.deleted"
        is ServerEvent.SessionRenamed -> "session.renamed"
        is ServerEvent.SessionArchived -> "session.archived"
        is ServerEvent.SessionForked -> "session.forked"
        is ServerEvent.SessionStatusChanged -> "session.status"
        is ServerEvent.ExecutionStarted -> "session.execution.started"
        is ServerEvent.ExecutionSucceeded -> "session.execution.succeeded"
        is ServerEvent.ExecutionFailed -> "session.execution.failed"
        is ServerEvent.ExecutionInterrupted -> "session.execution.interrupted"
        is ServerEvent.RetryScheduled -> "session.retry.scheduled"
        is ServerEvent.StepStarted -> "session.step.started"
        is ServerEvent.StepStreamed -> "session.step.streamed"
        is ServerEvent.StepEnded -> "session.step.ended"
        is ServerEvent.StepFailed -> "session.step.failed"
        is ServerEvent.TextStarted -> "session.text.started"
        is ServerEvent.TextDelta -> "session.text.delta"
        is ServerEvent.TextEnded -> "session.text.ended"
        is ServerEvent.ReasoningStarted -> "session.reasoning.started"
        is ServerEvent.ReasoningDelta -> "session.reasoning.delta"
        is ServerEvent.ReasoningEnded -> "session.reasoning.ended"
        is ServerEvent.ToolInputStarted -> "session.tool.input.started"
        is ServerEvent.ToolInputDelta -> "session.tool.input.delta"
        is ServerEvent.ToolInputEnded -> "session.tool.input.ended"
        is ServerEvent.ToolCalled -> "session.tool.called"
        is ServerEvent.ToolProgress -> "session.tool.progress"
        is ServerEvent.ToolSucceeded -> "session.tool.success"
        is ServerEvent.ToolFailed -> "session.tool.failed"
        is ServerEvent.UsageUpdated -> "session.usage.updated"
        is ServerEvent.UsageRecorded -> "session.usage.recorded"
        is ServerEvent.FormCreated -> "form.created"
        is ServerEvent.FormReplied -> "form.replied"
        is ServerEvent.FormCancelled -> "form.cancelled"
        is ServerEvent.PermissionAsked -> "permission.asked"
        is ServerEvent.PermissionReplied -> "permission.replied"
        is ServerEvent.CompactionChanged -> "session.compaction.*"
        is ServerEvent.RevertChanged -> "session.revert.*"
        is ServerEvent.ShellStarted -> "session.shell.started"
        is ServerEvent.ShellEnded -> "session.shell.ended"
    }

    private fun leaves(type: Class<*>): Set<Class<*>> =
        if (type.isSealed) type.permittedSubclasses.flatMap(::leaves).toSet() else setOf(type)

    @Test
    fun `every event has a sample, so the tests below cover all of them`() {
        assertEquals(leaves(ServerEvent::class.java), samples.map { it.javaClass }.toSet())
    }

    @Test
    fun `every event has its own description`() {
        assertEquals(samples.size, samples.map(::describe).toSet().size)
    }

    @Test
    fun `an unknown type is an ordinary event that keeps the raw type`() {
        val event: ServerEvent = ServerEvent.Unknown(rawType = "plugin.added")

        assertEquals("unknown:plugin.added", describe(event))
    }

    @Test
    fun `delta and ended are different events`() {
        val delta = ServerEvent.TextDelta(session, message, ordinal = 0, delta = "same")
        val ended = ServerEvent.TextEnded(session, message, ordinal = 0, text = "same")

        assertNotEquals<Any>(delta, ended)
        assertNotEquals(describe(delta), describe(ended))
        assertNotEquals(
            describe(ServerEvent.ReasoningDelta(session, message, ordinal = 0, delta = "same")),
            describe(ServerEvent.ReasoningEnded(session, message, ordinal = 0, text = "same")),
        )
        assertNotEquals(
            describe(ServerEvent.ToolInputDelta(session, message, callId = "call_1", delta = "same")),
            describe(ServerEvent.ToolInputEnded(session, message, callId = "call_1", input = null)),
        )
    }

    @Test
    fun `toString never prints the payload`() {
        samples.forEach { event ->
            assertFalse(SENTINEL in event.toString(), "payload leaked: $event")
        }
    }

    @Test
    fun `toString names the event, its id and its session`() {
        val event = ServerEvent.TextDelta(
            session,
            message,
            ordinal = 0,
            delta = SENTINEL,
            meta = EventMeta(id = "evt_1"),
        )

        assertEquals("ServerEvent.TextDelta(id=evt_1, session=ses_1)", event.toString())
    }

    @Test
    fun `event metadata is masked, by itself too`() {
        val meta = EventMeta(
            id = "evt_1",
            location = Location(directory = SENTINEL),
            metadata = sensitiveMap,
        )

        assertFalse(SENTINEL in meta.toString(), "metadata leaked: $meta")
        assertFalse(SENTINEL in ServerEvent.Connected(meta).toString())
    }

    @Test
    fun `masking changes printing only, not equality or the fields`() {
        val first = ServerEvent.TextDelta(session, message, ordinal = 0, delta = "a")

        assertEquals(first, first.copy())
        assertNotEquals(first, first.copy(delta = "b"))
        assertEquals("a", first.delta)
    }

    @Test
    fun `events of a session expose the session, including those wrapping a domain object`() {
        val form = ServerEvent.FormCreated(Form(FormId("frm_1"), session, FormKind.Question))
        val permission = ServerEvent.PermissionAsked(
            PermissionRequest(PermissionRequestId("per_1"), SessionId("ses_child"), action = "bash"),
        )

        assertEquals(session, form.sessionId)
        assertEquals(SessionId("ses_child"), permission.sessionId)
        assertTrue(samples.count { it is SessionEvent } == samples.size - 3)
        assertIs<SessionEvent>(ServerEvent.TextDelta(session, message, ordinal = 0, delta = "a"))
    }
}
