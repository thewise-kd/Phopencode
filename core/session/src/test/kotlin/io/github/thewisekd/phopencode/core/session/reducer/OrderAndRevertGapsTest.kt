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
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.ToolContent
import io.github.thewisekd.phopencode.core.model.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Writes that must follow event order, and reverts that must leave nothing a late event cannot settle. */
class OrderAndRevertGapsTest {
    private fun at(n: Int): EventMeta =
        EventMeta(id = "evt_at_$n", createdAt = EventScript.at(n), durable = EventDurable(SESSION.value, n.toLong(), 1))

    private fun liveAt(n: Int): EventMeta = EventMeta(id = "evt_live_$n", createdAt = EventScript.at(n))

    private val reducer = SessionReducer(SESSION)

    // Finding 1

    @Test
    fun `a retry older than the step that followed it does not leave the session retrying`() {
        reducer.apply(ServerEvent.ExecutionStarted(SESSION, at(1)))
        reducer.apply(ServerEvent.StepStarted(SESSION, MSG_A, startedAt = EventScript.at(5), meta = at(5)))
        reducer.apply(ServerEvent.RetryScheduled(SESSION, MSG_A, attempt = 2, meta = at(3)))

        assertEquals(SessionStatus.Running, reducer.state.status)
    }

    // Finding 2

    @Test
    fun `older tool progress does not overwrite newer progress`() {
        val newer = mapOf("step" to DataValue.Text("two"))
        reducer.apply(ServerEvent.ToolProgress(SESSION, MSG_A, "call_1", null, null, newer, liveAt(5)))
        reducer.apply(ServerEvent.ToolProgress(SESSION, MSG_A, "call_1", null, null, mapOf("step" to DataValue.Text("one")), liveAt(3)))

        assertEquals(newer, reducer.tool(MSG_A, "call_1").metadata)
    }

    // Found on the sweep: the same order check for ended text and tool endings

    @Test
    fun `an older ended text does not replace a newer one`() {
        reducer.apply(ServerEvent.TextEnded(SESSION, MSG_A, 0, "new", at(10)))
        reducer.apply(ServerEvent.TextEnded(SESSION, MSG_A, 0, "old", at(5)))

        assertEquals(listOf("new"), reducer.texts(MSG_A))
    }

    @Test
    fun `of two tool endings the newer wins whatever their arrival order`() {
        val error = StructuredError("tool.execution")
        val done = listOf(ToolContent.Text("ok"))
        reducer.apply(ServerEvent.ToolSucceeded(SESSION, MSG_A, "first", done, meta = at(10)))
        reducer.apply(ServerEvent.ToolFailed(SESSION, MSG_A, "first", error, meta = at(5)))
        // Same order as the first call's events, but events of their own.
        reducer.apply(ServerEvent.ToolFailed(SESSION, MSG_A, "second", error, meta = at(5).copy(id = "evt_second_5")))
        reducer.apply(ServerEvent.ToolSucceeded(SESSION, MSG_A, "second", done, meta = at(10).copy(id = "evt_second_10")))

        for (call in listOf("first", "second")) {
            val tool = reducer.tool(MSG_A, call)
            assertEquals(ToolStatus.Completed, tool.status, call)
            assertEquals(done, tool.content, call)
            assertEquals(null, tool.error, call)
        }
    }

    // Finding 3

    @Test
    fun `a prompt reverted before its delivery is no longer pending, and its late delivery adds nothing`() {
        val prompt = MessageId("msg_prompt")
        reducer.apply(ServerEvent.InboxEnqueued(InboxItem(prompt, SESSION, null, InboxContent.User("undo"), PromptDelivery.Queue), at(3)))
        reducer.apply(ServerEvent.RevertCommitted(SESSION, prompt, at(10)))

        assertEquals(emptyList(), reducer.state.inbox)

        reducer.apply(ServerEvent.InboxDelivered(SESSION, prompt, at(4)))
        reducer.apply(ServerEvent.InboxDeliveryChanged(SESSION, prompt, PromptDelivery.Steer, at(5)))

        assertEquals(emptyList(), reducer.state.inbox)
        assertEquals(emptyList(), reducer.state.messages)
    }

    // Finding 4

    @Test
    fun `the end of a reverted compaction is dropped, not given to the one before it`() {
        val first = MessageId("msg_compaction_1")
        val second = MessageId("msg_compaction_2")
        val boundary = MessageId("msg_boundary")
        val firstUsage = TokenUsage.of(input = 10, cost = Cost.parse("0.01"))
        fun compactionItem(id: MessageId) = InboxItem(id, SESSION, null, InboxContent.Compaction, null)

        reducer.apply(ServerEvent.InboxEnqueued(compactionItem(first), at(10)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, first, at(11)))
        reducer.apply(ServerEvent.CompactionStarted(SESSION, "manual", first, at(12)))
        reducer.apply(ServerEvent.CompactionEnded(SESSION, "manual", null, "First.", firstUsage, at(20)))
        reducer.apply(ServerEvent.InboxEnqueued(InboxItem(boundary, SESSION, null, InboxContent.User("undo"), null), at(24)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, boundary, at(25)))
        reducer.apply(ServerEvent.RevertCommitted(SESSION, boundary, at(50)))
        // The reverted compaction's events arrive after the commit.
        reducer.apply(ServerEvent.InboxEnqueued(compactionItem(second), at(30)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, second, at(31)))
        reducer.apply(ServerEvent.CompactionStarted(SESSION, "manual", second, at(32)))
        reducer.apply(ServerEvent.CompactionDelta(SESSION, "Second", liveAt(35)))
        reducer.apply(ServerEvent.CompactionEnded(SESSION, "manual", null, "Second.", TokenUsage.of(input = 99, cost = Cost.ZERO), at(40)))

        val compaction = reducer.state.messages.filterIsInstance<Message.Compaction>().single()
        assertEquals(first, compaction.id)
        assertEquals("First.", compaction.summary)
        assertEquals(firstUsage, compaction.usage)
        assertEquals(listOf(first), reducer.state.messages.map { it.id })
    }

    @Test
    fun `the end of a compaction reverted after it was held is dropped too`() {
        val first = MessageId("msg_compaction_1")
        val second = MessageId("msg_compaction_2")
        val boundary = MessageId("msg_boundary")
        val firstUsage = TokenUsage.of(input = 10, cost = Cost.parse("0.01"))

        reducer.apply(ServerEvent.CompactionStarted(SESSION, "manual", first, at(12)))
        reducer.apply(ServerEvent.CompactionEnded(SESSION, "manual", null, "First.", firstUsage, at(20)))
        reducer.apply(ServerEvent.InboxEnqueued(InboxItem(boundary, SESSION, null, InboxContent.User("undo"), null), at(24)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, boundary, at(25)))
        reducer.apply(ServerEvent.CompactionStarted(SESSION, "manual", second, at(32)))
        reducer.apply(ServerEvent.CompactionEnded(SESSION, "manual", null, "Second.", TokenUsage.of(input = 99, cost = Cost.ZERO), at(40)))
        reducer.apply(ServerEvent.RevertCommitted(SESSION, boundary, at(50)))

        val compaction = reducer.state.messages.filterIsInstance<Message.Compaction>().single()
        assertEquals("First.", compaction.summary)
        assertEquals(firstUsage, compaction.usage)
    }
}
