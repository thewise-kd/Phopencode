package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.EventDurable
import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Endings follow event order, and a revert never removes a message on a time a later event gave it. */
class EndingOrderAndRevertTimeTest {
    private fun at(n: Int): EventMeta =
        EventMeta(id = "evt_at_$n", createdAt = EventScript.at(n), durable = EventDurable(SESSION.value, n.toLong(), 1))

    private val reducer = SessionReducer(SESSION)

    // Finding 1

    @Test
    fun `an older input end does not replace a newer one`() {
        reducer.apply(ServerEvent.ToolInputStarted(SESSION, MSG_A, "call_1", "read", at(1)))
        reducer.apply(ServerEvent.ToolInputEnded(SESSION, MSG_A, "call_1", "{\"path\":\"new\"}", at(10)))
        reducer.apply(ServerEvent.ToolInputEnded(SESSION, MSG_A, "call_1", "{\"path\":\"old\"}", at(5)))

        assertEquals(DataValue.Text("{\"path\":\"new\"}"), reducer.tool(MSG_A, "call_1").input)
    }

    // Found on the sweep: a named compaction failure took the first to arrive

    @Test
    fun `of two failures of a compaction the newer wins whatever their arrival order`() {
        val newer = StructuredError("compaction.failed", "newer")
        val older = StructuredError("compaction.failed", "older")
        val usage = TokenUsage.of(input = 2, cost = Cost.ZERO)
        val olderFirst = MessageId("msg_compaction_1")
        val newerFirst = MessageId("msg_compaction_2")
        reducer.apply(ServerEvent.CompactionStarted(SESSION, "manual", olderFirst, at(1)))
        reducer.apply(ServerEvent.CompactionFailed(SESSION, "manual", older, olderFirst, null, at(5)))
        reducer.apply(ServerEvent.CompactionFailed(SESSION, "manual", newer, olderFirst, usage, at(10)))
        reducer.apply(ServerEvent.CompactionStarted(SESSION, "manual", newerFirst, at(20)))
        reducer.apply(ServerEvent.CompactionFailed(SESSION, "manual", newer, newerFirst, usage, at(30)))
        reducer.apply(ServerEvent.CompactionFailed(SESSION, "manual", older, newerFirst, null, at(25)))

        for (compaction in reducer.state.messages.filterIsInstance<Message.Compaction>()) {
            assertEquals(newer, compaction.error, compaction.id.value)
            assertEquals(usage, compaction.usage, compaction.id.value)
        }
        assertEquals(2, reducer.state.messages.size)
    }

    // Finding 2

    private val boundary = MessageId("msg_boundary")

    private fun deliverBoundary() {
        reducer.apply(ServerEvent.InboxEnqueued(InboxItem(boundary, SESSION, null, InboxContent.User("undo"), null), at(3)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, boundary, at(4)))
    }

    @Test
    fun `a message before the boundary whose start arrives after the revert is kept`() {
        reducer.apply(ServerEvent.StepEnded(SESSION, MSG_A, TokenUsage.of(input = 1, cost = Cost.ZERO), "stop", meta = at(6)))
        deliverBoundary()
        reducer.apply(ServerEvent.RevertCommitted(SESSION, boundary, at(50)))

        // Its only known time, from its end, falls after the boundary: it is hidden for now.
        assertEquals(emptyList(), reducer.state.messages.map { it.id })

        reducer.apply(ServerEvent.StepStarted(SESSION, MSG_A, agent = "build", startedAt = EventScript.at(2), meta = at(2)))
        reducer.apply(ServerEvent.TextEnded(SESSION, MSG_A, 0, "Kept.", at(5)))

        assertEquals(listOf(MSG_A), reducer.state.messages.map { it.id })
        assertEquals(listOf("Kept."), reducer.texts(MSG_A))
        assertEquals("build", reducer.assistant(MSG_A).agent)
    }

    @Test
    fun `a message inside the reverted range stays out when its start arrives`() {
        deliverBoundary()
        reducer.apply(ServerEvent.StepEnded(SESSION, MSG_A, TokenUsage.of(input = 1, cost = Cost.ZERO), "stop", meta = at(9)))
        reducer.apply(ServerEvent.RevertCommitted(SESSION, boundary, at(50)))
        reducer.apply(ServerEvent.StepStarted(SESSION, MSG_A, startedAt = EventScript.at(6), meta = at(6)))
        reducer.apply(ServerEvent.TextDelta(SESSION, MSG_A, 0, "late", at(7)))

        assertEquals(emptyList(), reducer.state.messages.map { it.id })
        assertEquals(TokenUsage.EMPTY, reducer.state.messageUsage)
    }
}
