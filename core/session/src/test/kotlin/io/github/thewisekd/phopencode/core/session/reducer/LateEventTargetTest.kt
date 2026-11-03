package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.EventDurable
import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.SessionRevert
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A late event must reach the target its own IDs, `seq` and time point to, and no other. */
class LateEventTargetTest {
    /** A durable event with time and `seq` [n]. */
    private fun at(n: Int): EventMeta =
        EventMeta(id = "evt_at_$n", createdAt = EventScript.at(n), durable = EventDurable(SESSION.value, n.toLong(), 1))

    /** A live event with time [n] and no `seq`. */
    private fun liveAt(n: Int): EventMeta = EventMeta(id = "evt_live_$n", createdAt = EventScript.at(n))

    // Finding 1

    @Test
    fun `tool input deltas are joined by their time, not their arrival`() {
        val reducer = SessionReducer(SESSION)
        reducer.apply(ServerEvent.ToolInputStarted(SESSION, MSG_A, "call_1", "read", at(1)))
        reducer.apply(ServerEvent.ToolInputDelta(SESSION, MSG_A, "call_1", "\"a\"}", liveAt(4)))
        reducer.apply(ServerEvent.ToolInputDelta(SESSION, MSG_A, "call_1", "{\"path\":", liveAt(2)))

        assertEquals(DataValue.Text("{\"path\":\"a\"}"), reducer.tool(MSG_A, "call_1").input)
    }

    // Finding 2

    @Test
    fun `an input end after the tool finished still gives the complete input when the call had none`() {
        val reducer = SessionReducer(SESSION)
        reducer.apply(ServerEvent.ToolInputStarted(SESSION, MSG_A, "call_1", "read", at(1)))
        reducer.apply(ServerEvent.ToolCalled(SESSION, MSG_A, "call_1", input = null, executed = false, meta = at(3)))
        reducer.apply(ServerEvent.ToolSucceeded(SESSION, MSG_A, "call_1", meta = at(4)))
        reducer.apply(ServerEvent.ToolInputEnded(SESSION, MSG_A, "call_1", "{\"path\":\"a\"}", at(2)))

        val tool = reducer.tool(MSG_A, "call_1")
        assertEquals(ToolStatus.Completed, tool.status)
        assertEquals(DataValue.Text("{\"path\":\"a\"}"), tool.input)
    }

    // Finding 3

    private val first = MessageId("msg_compaction_1")
    private val second = MessageId("msg_compaction_2")
    private val firstUsage = TokenUsage.of(input = 10, cost = Cost.parse("0.01"))

    private fun compactionItem(id: MessageId) = InboxItem(id, SESSION, null, InboxContent.Compaction, null)

    private fun SessionReducer.compaction(id: MessageId): Message.Compaction =
        state.messages.filterIsInstance<Message.Compaction>().single { it.id == id }

    private fun SessionReducer.startCompaction(id: MessageId, n: Int) {
        apply(ServerEvent.InboxEnqueued(compactionItem(id), at(n)))
        apply(ServerEvent.InboxDelivered(SESSION, id, at(n + 1)))
        apply(ServerEvent.CompactionStarted(SESSION, "manual", id, at(n + 2)))
    }

    @Test
    fun `a late end of an earlier compaction completes that one, not the one running now`() {
        val reducer = SessionReducer(SESSION)
        reducer.startCompaction(first, 10)
        reducer.apply(ServerEvent.CompactionDelta(SESSION, "First summary", liveAt(14)))
        reducer.startCompaction(second, 30)
        reducer.apply(ServerEvent.CompactionDelta(SESSION, "Second so far", liveAt(34)))
        reducer.apply(ServerEvent.CompactionEnded(SESSION, "manual", null, "First summary.", firstUsage, at(20)))

        assertEquals("completed", reducer.compaction(first).status)
        assertEquals("First summary.", reducer.compaction(first).summary)
        assertEquals(firstUsage, reducer.compaction(first).usage)
        assertEquals("running", reducer.compaction(second).status)
        assertEquals("Second so far", reducer.compaction(second).summary)
        assertNull(reducer.compaction(second).usage)
    }

    @Test
    fun `an end older than every known compaction is held until its own compaction appears`() {
        val reducer = SessionReducer(SESSION)
        reducer.startCompaction(second, 30)
        reducer.apply(ServerEvent.CompactionEnded(SESSION, "manual", null, "First summary.", firstUsage, at(20)))

        assertEquals("running", reducer.compaction(second).status)

        reducer.startCompaction(first, 10)

        assertEquals("completed", reducer.compaction(first).status)
        assertEquals("running", reducer.compaction(second).status)
    }

    @Test
    fun `an end whose compaction failed is dropped`() {
        val reducer = SessionReducer(SESSION)
        reducer.startCompaction(first, 10)
        val error = StructuredError("compaction.failed")
        reducer.apply(ServerEvent.CompactionFailed(SESSION, "manual", error, first, null, at(15)))
        reducer.apply(ServerEvent.CompactionEnded(SESSION, "manual", null, "Stray.", firstUsage, at(20)))

        assertEquals("failed", reducer.compaction(first).status)
        assertNull(reducer.compaction(first).summary)
    }

    @Test
    fun `an end that cannot be placed against a compaction is dropped`() {
        val reducer = SessionReducer(SESSION)
        reducer.startCompaction(first, 10)
        reducer.apply(ServerEvent.CompactionEnded(SESSION, "manual", null, "No time.", firstUsage, EventMeta(id = "evt_untimed")))

        assertEquals("running", reducer.compaction(first).status)
    }

    // Finding 4

    private val boundary = MessageId("msg_boundary")
    private val laterBoundary = MessageId("msg_later_boundary")

    private fun SessionReducer.deliverUser(id: MessageId, n: Int) {
        apply(ServerEvent.InboxEnqueued(InboxItem(id, SESSION, null, InboxContent.User("text"), PromptDelivery.Steer), at(n)))
        apply(ServerEvent.InboxDelivered(SESSION, id, at(n + 1)))
    }

    @Test
    fun `a commit naming no boundary that arrives before its stage is applied once the stage arrives`() {
        val reducer = SessionReducer(SESSION)
        reducer.deliverUser(MSG_A, 1)
        reducer.deliverUser(boundary, 5)
        reducer.apply(ServerEvent.RevertCommitted(SESSION, null, at(20)))

        assertEquals(listOf(MSG_A, boundary), reducer.state.messages.map { it.id })

        reducer.apply(ServerEvent.RevertStaged(SESSION, SessionRevert(boundary), at(10)))

        assertEquals(listOf(MSG_A), reducer.state.messages.map { it.id })
        assertNull(reducer.state.session.revert)
    }

    @Test
    fun `a commit naming no boundary uses the stage before it, not a newer one`() {
        val reducer = SessionReducer(SESSION)
        reducer.deliverUser(MSG_A, 1)
        reducer.deliverUser(boundary, 5)
        reducer.deliverUser(laterBoundary, 30)
        val newerStage = SessionRevert(laterBoundary)
        reducer.apply(ServerEvent.RevertStaged(SESSION, newerStage, at(40)))
        reducer.apply(ServerEvent.RevertCommitted(SESSION, null, at(20)))

        assertEquals(listOf(MSG_A, boundary, laterBoundary), reducer.state.messages.map { it.id })

        reducer.apply(ServerEvent.RevertStaged(SESSION, SessionRevert(boundary), at(10)))

        assertEquals(listOf(MSG_A, laterBoundary), reducer.state.messages.map { it.id })
        assertEquals(newerStage, reducer.state.session.revert)
    }

    @Test
    fun `a commit naming no boundary is not applied to a stage that was cleared before it`() {
        val reducer = SessionReducer(SESSION)
        reducer.deliverUser(MSG_A, 1)
        reducer.deliverUser(boundary, 5)
        reducer.apply(ServerEvent.RevertCommitted(SESSION, null, at(20)))
        reducer.apply(ServerEvent.RevertCleared(SESSION, at(15)))
        reducer.apply(ServerEvent.RevertStaged(SESSION, SessionRevert(boundary), at(10)))

        assertEquals(listOf(MSG_A, boundary), reducer.state.messages.map { it.id })
    }

    // Finding 5

    @Test
    fun `a late end of the previous execution records its outcome without ending the current one`() {
        val reducer = SessionReducer(SESSION)
        val error = StructuredError("provider.transport")
        reducer.apply(ServerEvent.ExecutionStarted(SESSION, at(1)))
        reducer.apply(ServerEvent.ExecutionStarted(SESSION, at(3)))
        reducer.apply(ServerEvent.ExecutionFailed(SESSION, error, at(2)))

        assertEquals(SessionStatus.Running, reducer.state.status)
        assertEquals(SessionOutcome.Failed, reducer.state.session.outcome)
        assertEquals(error, reducer.state.error)
    }

    @Test
    fun `an end older than the outcome already recorded does not replace it`() {
        val reducer = SessionReducer(SESSION)
        reducer.apply(ServerEvent.ExecutionSucceeded(SESSION, at(4)))
        reducer.apply(ServerEvent.ExecutionFailed(SESSION, StructuredError("x"), at(2)))

        assertEquals(SessionOutcome.Succeeded, reducer.state.session.outcome)
        assertNull(reducer.state.error)
        assertEquals(SessionStatus.Idle, reducer.state.status)
    }
}
