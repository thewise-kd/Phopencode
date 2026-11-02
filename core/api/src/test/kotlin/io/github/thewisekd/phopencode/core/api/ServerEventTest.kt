package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.ForkBoundary
import io.github.thewisekd.phopencode.core.model.ForkBoundaryType
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
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
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SENTINEL = "SENTINEL-payload-text"

class ServerEventTest {
    private val session = SessionId("ses_1")
    private val message = MessageId("msg_1")
    private val sensitive = DataValue.Text(SENTINEL)
    private val sensitiveMap = mapOf("key" to sensitive)
    private val error = StructuredError(type = "provider.transport", message = SENTINEL, responseBody = SENTINEL)
    private val model = ModelRef(providerId = "sim", modelId = "sim-1")
    private val boundary = ForkBoundary(ForkBoundaryType.Through, message)

    /** One of each event, with the sentinel in every field that holds free text or server data. */
    private val samples: List<ServerEvent> = listOf(
        ServerEvent.Connected(),
        ServerEvent.Disconnected(reason = ApiError.Conflict(SENTINEL)),
        ServerEvent.Unknown(rawType = SENTINEL),
        ServerEvent.SessionCreated(
            session,
            slug = SENTINEL,
            version = "2.2.5",
            projectId = SENTINEL,
            location = Location(SENTINEL),
            subpath = SENTINEL,
            title = SENTINEL,
            parentId = SessionId("ses_0"),
            agent = SENTINEL,
            metadata = sensitiveMap,
        ),
        ServerEvent.SessionDeleted(session),
        ServerEvent.SessionRenamed(session, title = SENTINEL),
        ServerEvent.SessionArchived(session),
        ServerEvent.SessionUnarchived(session),
        ServerEvent.SessionForked(session, parentId = SessionId("ses_0"), boundary = boundary),
        ServerEvent.SessionStatusChanged(session, SessionStatus.Failed(SENTINEL)),
        ServerEvent.ExecutionStarted(session),
        ServerEvent.ExecutionSucceeded(session),
        ServerEvent.ExecutionFailed(session, error = error),
        ServerEvent.ExecutionInterrupted(session, reason = InterruptReason.Unknown(SENTINEL)),
        ServerEvent.RetryScheduled(session, messageId = message, attempt = 2, error = error),
        ServerEvent.StepStarted(session, message, agent = SENTINEL, model = model, snapshot = SENTINEL),
        ServerEvent.StepStreamed(session, message),
        ServerEvent.StepEnded(
            session,
            message,
            TokenUsage.of(input = 1),
            finishReason = SENTINEL,
            rawFinish = SENTINEL,
            snapshot = SENTINEL,
            files = listOf(SENTINEL),
        ),
        ServerEvent.StepFailed(
            session,
            message,
            error = error,
            usage = TokenUsage.of(input = 1),
            rawFinish = SENTINEL,
            snapshot = SENTINEL,
            files = listOf(SENTINEL),
        ),
        ServerEvent.TextStarted(session, message, ordinal = 0),
        ServerEvent.TextDelta(session, message, ordinal = 0, delta = SENTINEL),
        ServerEvent.TextEnded(session, message, ordinal = 0, text = SENTINEL),
        ServerEvent.ReasoningStarted(session, message, ordinal = 0),
        ServerEvent.ReasoningDelta(session, message, ordinal = 0, delta = SENTINEL),
        ServerEvent.ReasoningEnded(session, message, ordinal = 0, text = SENTINEL),
        ServerEvent.ToolInputStarted(session, message, id = "call_1", name = SENTINEL),
        ServerEvent.ToolInputDelta(session, message, id = "call_1", delta = SENTINEL),
        ServerEvent.ToolInputEnded(session, message, id = "call_1", text = SENTINEL),
        ServerEvent.ToolCalled(session, message, id = "call_1", input = sensitive, executed = false),
        ServerEvent.ToolProgress(
            session,
            message,
            id = "call_1",
            childSessionId = SessionId("ses_2"),
            status = SENTINEL,
            metadata = sensitiveMap,
        ),
        ServerEvent.ToolSucceeded(
            session,
            message,
            id = "call_1",
            content = listOf(ToolContent.Text(SENTINEL)),
            metadata = sensitiveMap,
            executed = false,
        ),
        ServerEvent.ToolFailed(session, message, id = "call_1", error = error, executed = false),
        ServerEvent.UsageUpdated(session, TokenUsage.of(input = 1)),
        ServerEvent.FormCreated(Form(FormId("frm_1"), session, FormKind.Question, title = SENTINEL)),
        ServerEvent.FormReplied(session, FormId("frm_1"), answer = sensitiveMap),
        ServerEvent.FormCancelled(session, FormId("frm_1")),
        ServerEvent.PermissionAsked(
            PermissionRequest(PermissionRequestId("per_1"), session, action = SENTINEL, message = SENTINEL),
        ),
        ServerEvent.PermissionReplied(session, PermissionRequestId("per_1"), PermissionDecision.Once),
        ServerEvent.CompactionStarted(session, reason = SENTINEL, inputId = message),
        ServerEvent.CompactionDelta(session, text = SENTINEL),
        ServerEvent.CompactionEnded(
            session,
            reason = SENTINEL,
            model = model,
            text = SENTINEL,
            usage = TokenUsage.of(input = 1),
        ),
        ServerEvent.CompactionFailed(
            session,
            reason = SENTINEL,
            error = error,
            inputId = message,
            usage = TokenUsage.of(input = 1),
        ),
        ServerEvent.RevertStaged(
            session,
            SessionRevert(
                message,
                snapshot = SENTINEL,
                files = listOf(FileDiff(VcsFile(SENTINEL, VcsFileStatus.Added), patch = SENTINEL)),
            ),
        ),
        ServerEvent.RevertCleared(session),
        ServerEvent.RevertCommitted(session, to = message),
        ServerEvent.ShellStarted(session, shellId = SENTINEL, command = SENTINEL),
        ServerEvent.ShellEnded(session, shellId = SENTINEL, command = SENTINEL, output = SENTINEL, exitCode = 0),
        ServerEvent.InboxEnqueued(
            InboxItem(message, session, content = InboxContent.User(SENTINEL), delivery = PromptDelivery.Steer),
        ),
        ServerEvent.InboxDelivered(session, inboxId = message),
        ServerEvent.InboxCancelled(session, inboxId = message),
        ServerEvent.InboxDeliveryChanged(session, inboxId = message, delivery = PromptDelivery.Queue),
        ServerEvent.AgentSelected(session, agent = SENTINEL, previous = SENTINEL),
        ServerEvent.ModelSelected(session, model = ModelRef(providerId = SENTINEL, modelId = SENTINEL)),
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
        is ServerEvent.SessionUnarchived -> "session.unarchived"
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
        is ServerEvent.FormCreated -> "form.created"
        is ServerEvent.FormReplied -> "form.replied"
        is ServerEvent.FormCancelled -> "form.cancelled"
        is ServerEvent.PermissionAsked -> "permission.asked"
        is ServerEvent.PermissionReplied -> "permission.replied"
        is ServerEvent.CompactionStarted -> "session.compaction.started"
        is ServerEvent.CompactionDelta -> "session.compaction.delta"
        is ServerEvent.CompactionEnded -> "session.compaction.ended"
        is ServerEvent.CompactionFailed -> "session.compaction.failed"
        is ServerEvent.RevertStaged -> "session.revert.staged"
        is ServerEvent.RevertCleared -> "session.revert.cleared"
        is ServerEvent.RevertCommitted -> "session.revert.committed"
        is ServerEvent.ShellStarted -> "session.shell.started"
        is ServerEvent.ShellEnded -> "session.shell.ended"
        is ServerEvent.InboxEnqueued -> "session.inbox.enqueued"
        is ServerEvent.InboxDelivered -> "session.inbox.delivered"
        is ServerEvent.InboxCancelled -> "session.inbox.cancelled"
        is ServerEvent.InboxDeliveryChanged -> "session.inbox.delivery.changed"
        is ServerEvent.AgentSelected -> "session.agent.selected"
        is ServerEvent.ModelSelected -> "session.model.selected"
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
    fun `no contract remains for an event that does not exist in the server`() {
        val names = leaves(ServerEvent::class.java).map { it.simpleName }

        assertFalse("UsageRecorded" in names)
        assertFalse("session.usage.recorded" in samples.map(::describe))
    }

    @Test
    fun `session created is flat, with no nested session`() {
        val fields = ServerEvent.SessionCreated::class.java.declaredFields.map { it.name }.toSet()

        assertFalse("session" in fields)
        assertTrue(
            fields.containsAll(
                setOf("slug", "version", "projectId", "location", "subpath", "title", "parentId", "agent", "metadata"),
            ),
        )
    }

    @Test
    fun `a fork names its parent and its boundary`() {
        val event = ServerEvent.SessionForked(session, parentId = SessionId("ses_0"), boundary = boundary)

        assertEquals(SessionId("ses_0"), event.parentId)
        assertEquals(message, event.boundary?.messageId)
    }

    @Test
    fun `failure events carry an error object`() {
        assertEquals(error, ServerEvent.ExecutionFailed(session, error = error).error)
        assertEquals(error, ServerEvent.StepFailed(session, message, error = error).error)
        assertEquals(error, ServerEvent.RetryScheduled(session, error = error).error)
        assertEquals(error, ServerEvent.ToolFailed(session, message, id = "c", error = error).error)
    }

    @Test
    fun `an interruption says why it happened`() {
        val reasons = listOf(InterruptReason.User, InterruptReason.Shutdown, InterruptReason.Unknown("x"))

        assertEquals(reasons, reasons.map { ServerEvent.ExecutionInterrupted(session, reason = it).reason })
    }

    @Test
    fun `tool events use id, and a called event has no name`() {
        val toolEvents = leaves(ServerEvent::class.java).filter { it.simpleName.startsWith("Tool") }
        val fields = toolEvents.associate { type -> type.simpleName to type.declaredFields.map { it.name } }

        assertEquals(6 + 1, fields.size)
        fields.forEach { (name, names) ->
            assertTrue("id" in names, "no id on $name")
            assertFalse("callId" in names, "callId on $name")
        }
        assertFalse("name" in fields.getValue("ToolCalled"))
    }

    @Test
    fun `the raw text of tool input is kept as sent`() {
        assertEquals("{\"a\":1}", ServerEvent.ToolInputEnded(session, message, id = "c", text = "{\"a\":1}").text)
    }

    @Test
    fun `an inbox event belongs to the session of its item`() {
        val item = InboxItem(message, SessionId("ses_child"), content = InboxContent.Compaction, delivery = null)

        assertEquals(SessionId("ses_child"), ServerEvent.InboxEnqueued(item).sessionId)
        assertEquals(message, ServerEvent.InboxEnqueued(item).item.id)
        assertNull(item.delivery)
    }

    @Test
    fun `the envelope keeps the durable position of an event`() {
        val durable = EventDurable(aggregateId = "ses_1", seq = 7, version = 1)

        assertEquals(durable, EventMeta(durable = durable).durable)
        assertTrue(EventMeta(durable = durable).toString().contains("seq=7"))
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
            describe(ServerEvent.ToolInputDelta(session, message, id = "call_1", delta = "same")),
            describe(ServerEvent.ToolInputEnded(session, message, id = "call_1", text = "same")),
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
