package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.model.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutOfOrderTest {
    private val script = EventScript()
    private val reducer = SessionReducer(SESSION)

    private fun userItem(id: MessageId, text: String) =
        InboxItem(id, SESSION, null, InboxContent.User(text), PromptDelivery.Steer)

    @Test
    fun `an ended text before any delta or start gives the final text`() {
        reducer.apply(ServerEvent.TextEnded(SESSION, MSG_A, 0, "Done.", script.durable()))
        reducer.apply(ServerEvent.TextStarted(SESSION, MSG_A, 0, script.durable()))
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "Do", script.live()))

        assertEquals(listOf("Done."), reducer.texts(MSG_A))
    }

    @Test
    fun `a step start after the step end does not reopen the message`() {
        val start = script.durable()
        val end = script.durable()
        val usage = TokenUsage.of(input = 1, cost = Cost.ZERO)
        reducer.apply(ServerEvent.StepEnded(SESSION, MSG_A, usage, "stop", meta = end))
        reducer.apply(ServerEvent.StepStarted(SESSION, MSG_A, agent = "build", startedAt = EventScript.at(0), meta = start))

        val message = reducer.assistant(MSG_A)
        assertEquals(end.createdAt, message.completedAt)
        assertEquals("stop", message.finishReason)
        assertEquals(usage, message.usage)
        assertEquals("build", message.agent)
    }

    @Test
    fun `an older rename does not overwrite a newer one`() {
        val older = script.durable()
        val newer = script.durable()
        reducer.apply(ServerEvent.SessionRenamed(SESSION, "New", newer))
        reducer.apply(ServerEvent.SessionRenamed(SESSION, "Old", older))

        assertEquals("New", reducer.state.session.title)
    }

    @Test
    fun `an older execution start does not undo a newer end`() {
        val started = script.durable()
        val succeeded = script.durable()
        reducer.apply(ServerEvent.ExecutionSucceeded(SESSION, succeeded))
        reducer.apply(ServerEvent.ExecutionStarted(SESSION, started))

        assertEquals(SessionStatus.Idle, reducer.state.status)
        assertEquals(SessionOutcome.Succeeded, reducer.state.session.outcome)
    }

    @Test
    fun `an older archive does not undo a newer unarchive`() {
        val archived = script.durable()
        val unarchived = script.durable()
        reducer.apply(ServerEvent.SessionUnarchived(SESSION, unarchived))
        reducer.apply(ServerEvent.SessionArchived(SESSION, archived))

        assertFalse(reducer.state.session.archived)
    }

    @Test
    fun `a delivery before its enqueue still gives the user message, at the delivery time`() {
        val enqueued = script.durable()
        val delivered = script.durable()
        reducer.apply(ServerEvent.InboxDelivered(SESSION, MSG_A, delivered))
        assertEquals(emptyList(), reducer.state.messages)

        reducer.apply(ServerEvent.InboxEnqueued(userItem(MSG_A, "Hi"), enqueued))

        assertEquals(listOf<Message>(Message.User(MSG_A, SESSION, delivered.createdAt, "Hi")), reducer.state.messages)
        assertEquals(emptyList(), reducer.state.inbox)
    }

    @Test
    fun `an item cancelled before its enqueue never becomes pending`() {
        val enqueued = script.durable()
        reducer.apply(ServerEvent.InboxCancelled(SESSION, MSG_A, script.durable()))
        reducer.apply(ServerEvent.InboxEnqueued(userItem(MSG_A, "Hi"), enqueued))

        assertEquals(emptyList(), reducer.state.inbox)
        assertEquals(emptyList(), reducer.state.messages)
    }

    @Test
    fun `a form answered before it was created never becomes pending`() {
        val form = Form(FormId("frm_1"), SESSION, FormKind.Question)
        val created = script.durable()
        reducer.apply(ServerEvent.FormReplied(SESSION, form.id, emptyMap(), script.durable()))
        reducer.apply(ServerEvent.FormCreated(form, created))

        assertEquals(emptyList(), reducer.state.forms)
    }

    @Test
    fun `a permission answered before it was asked never becomes pending`() {
        val request = PermissionRequest(PermissionRequestId("per_1"), SESSION, "read")
        val asked = script.durable()
        reducer.apply(ServerEvent.PermissionReplied(SESSION, request.id, PermissionDecision.Once, script.durable()))
        reducer.apply(ServerEvent.PermissionAsked(request, asked))

        assertEquals(emptyList(), reducer.state.permissions)
    }

    @Test
    fun `messages are ordered by their own time, not by when their first event arrived`() {
        reducer.apply(ServerEvent.StepStarted(SESSION, MSG_B, startedAt = EventScript.at(50), meta = script.durable()))
        reducer.apply(ServerEvent.InboxEnqueued(userItem(MSG_A, "first"), script.durable()))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, MSG_A, script.durable()))
        reducer.apply(ServerEvent.StepStarted(SESSION, MessageId("msg_0"), startedAt = EventScript.at(1), meta = script.durable()))

        assertEquals(listOf("msg_0", "msg_a", "msg_b"), reducer.state.messages.map { it.id.value })
    }

    @Test
    fun `unknown, connection and other sessions' events change nothing`() {
        val before = reducer.state
        val changes = listOf(
            ServerEvent.Unknown("session.instructions.updated", script.live()),
            ServerEvent.Unknown("plugin.updated"),
            ServerEvent.Connected(script.live()),
            ServerEvent.Disconnected(ApiError.Closed, script.live()),
            ServerEvent.TextDelta(OTHER_SESSION, MSG_A, 0, "elsewhere", script.live()),
            ServerEvent.SessionRenamed(OTHER_SESSION, "Other", script.durable()),
        ).map { reducer.apply(it) }

        assertEquals(List(changes.size) { false }, changes)
        assertEquals(before, reducer.state)
    }

    @Test
    fun `session status from the spec's status event is applied`() {
        reducer.apply(ServerEvent.SessionStatusChanged(SESSION, SessionStatus.Running, script.durable()))
        assertEquals(SessionStatus.Running, reducer.state.status)
    }

    @Test
    fun `a revert commit removes the message and everything after it, and late events do not bring them back`() {
        reducer.apply(ServerEvent.InboxEnqueued(userItem(MSG_A, "keep"), script.durable()))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, MSG_A, script.durable()))
        val undone = MessageId("msg_undone")
        reducer.apply(ServerEvent.InboxEnqueued(userItem(undone, "undo me"), script.durable()))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, undone, script.durable()))
        // Started after the boundary's delivery (event 4) and before the commit (event 6).
        reducer.apply(ServerEvent.StepStarted(SESSION, MSG_B, startedAt = EventScript.at(5), meta = script.durable()))

        reducer.apply(ServerEvent.RevertCommitted(SESSION, undone, script.durable()))
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_B, 0, "late", script.live()))

        assertEquals(listOf(MSG_A), reducer.state.messages.map { it.id })
        assertNull(reducer.state.session.revert)
    }

    @Test
    fun `every recorded event applied twice gives the same state as once`() {
        for (name in REPLAYED_FLOWS) {
            val flow = RecordedFlow(name)
            val session = flow.sessionOf("final-messages")
            val once = SessionReducer(session).apply { applyAll(flow.events) }.state
            val twice = SessionReducer(session).apply { applyAll(flow.events.flatMap { listOf(it, it) }) }.state

            assertEquals(once, twice, name)
        }
    }

    @Test
    fun `a recorded stream applied backwards gives the same messages, status and usage`() {
        for (name in listOf("text", "reasoning", "tool", "subagent", "permission")) {
            val flow = RecordedFlow(name)
            val session = flow.sessionOf("final-messages")
            val forward = SessionReducer(session).apply { applyAll(flow.events) }.state
            val backward = SessionReducer(session).apply { applyAll(flow.events.reversed()) }.state

            assertEquals(forward.messages, backward.messages, name)
            assertEquals(forward.status, backward.status, name)
            assertEquals(forward.session, backward.session, name)
            assertTrue(backward.inbox.isEmpty() && backward.forms.isEmpty() && backward.permissions.isEmpty(), name)
        }
    }

    companion object {
        val REPLAYED_FLOWS = listOf(
            "text", "reasoning", "tool", "subagent", "permission", "form-question", "interrupt", "error", "retry",
            "compaction", "delivery", "auto-title", "commands", "diff-revert",
        )
    }
}
