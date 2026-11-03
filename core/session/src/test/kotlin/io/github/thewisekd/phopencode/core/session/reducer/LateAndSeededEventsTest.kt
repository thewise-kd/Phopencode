package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.EventDurable
import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Events that arrive late, early, or onto a seeded message list. */
class LateAndSeededEventsTest {
    private val script = EventScript()

    /** A durable event at [n] on [EventScript.at]'s scale, with `seq` [n], so a test can place it anywhere. */
    private fun at(n: Int): EventMeta =
        EventMeta(id = "evt_at_$n", createdAt = EventScript.at(n), durable = EventDurable(SESSION.value, n.toLong(), 1))

    private fun user(id: MessageId, text: String, delivery: PromptDelivery = PromptDelivery.Steer) =
        InboxItem(id, SESSION, null, InboxContent.User(text), delivery)

    // Finding 1

    @Test
    fun `a delta arriving after the input ended does not change the complete input`() {
        val reducer = SessionReducer(SESSION)
        val early = script.live()
        reducer.apply(ServerEvent.ToolInputStarted(SESSION, MSG_A, "call_1", "read", script.durable()))
        reducer.apply(ServerEvent.ToolInputEnded(SESSION, MSG_A, "call_1", "{\"path\":\"a\"}", script.durable()))
        reducer.apply(ServerEvent.ToolInputDelta(SESSION, MSG_A, "call_1", "{\"pa", early))

        assertEquals(DataValue.Text("{\"path\":\"a\"}"), reducer.tool(MSG_A, "call_1").input)
    }

    // Finding 2

    @Test
    fun `a seeded tool still streaming its input keeps it when the next delta arrives`() {
        val seeded = Message.Assistant(
            id = MSG_A,
            sessionId = SESSION,
            blocks = listOf(AssistantBlock.Tool("call_1", "read", ToolStatus.Streaming, input = DataValue.Text("{\"pa"))),
        )
        val reducer = SessionReducer(SESSION, messages = listOf(seeded))
        reducer.apply(ServerEvent.ToolInputDelta(SESSION, MSG_A, "call_1", "th\":\"a\"}", script.live()))

        assertEquals(DataValue.Text("{\"path\":\"a\"}"), reducer.tool(MSG_A, "call_1").input)
    }

    // Finding 3

    private val compactionId = MessageId("msg_compaction")
    private val compactionUsage = TokenUsage.of(input = 20, output = 2, cost = Cost.parse("0.02"))

    private fun ended() = ServerEvent.CompactionEnded(SESSION, "manual", null, "Summary.", compactionUsage, at(40))

    private fun SessionReducer.compaction(): Message.Compaction = state.messages.filterIsInstance<Message.Compaction>().single()

    @Test
    fun `a compaction end completes the running compaction of a seeded list`() {
        val running = Message.Compaction(compactionId, SESSION, EventScript.at(10), status = "running", reason = "manual")
        val reducer = SessionReducer(SESSION, messages = listOf(running))
        reducer.apply(ServerEvent.CompactionDelta(SESSION, "Sum", at(30)))
        reducer.apply(ended())

        assertEquals(running.copy(status = "completed", summary = "Summary.", usage = compactionUsage), reducer.compaction())
    }

    @Test
    fun `a compaction end completes a delivered compaction whose start was not applied`() {
        val reducer = SessionReducer(SESSION)
        reducer.apply(ServerEvent.InboxEnqueued(InboxItem(compactionId, SESSION, null, InboxContent.Compaction, null), at(5)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, compactionId, at(10)))
        reducer.apply(ended())

        assertEquals("completed", reducer.compaction().status)
        assertEquals(compactionUsage, reducer.compaction().usage)
    }

    @Test
    fun `a compaction end that arrives before anything of its compaction is applied once the compaction appears`() {
        val reducer = SessionReducer(SESSION)
        reducer.apply(ended())
        reducer.apply(ServerEvent.CompactionStarted(SESSION, "manual", compactionId, at(11)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, compactionId, at(10)))
        reducer.apply(ServerEvent.InboxEnqueued(InboxItem(compactionId, SESSION, null, InboxContent.Compaction, null), at(5)))

        assertEquals(
            Message.Compaction(compactionId, SESSION, EventScript.at(10), "completed", "manual", summary = "Summary.", usage = compactionUsage),
            reducer.compaction(),
        )
    }

    @Test
    fun `a compaction failure that arrives before its delivery keeps the delivery time`() {
        val reducer = SessionReducer(SESSION)
        val error = StructuredError("compaction.failed")
        reducer.apply(ServerEvent.CompactionFailed(SESSION, "manual", error, compactionId, compactionUsage, at(40)))
        reducer.apply(ServerEvent.InboxEnqueued(InboxItem(compactionId, SESSION, null, InboxContent.Compaction, null), at(5)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, compactionId, at(10)))
        reducer.apply(ServerEvent.CompactionStarted(SESSION, "manual", compactionId, at(11)))

        assertEquals(
            Message.Compaction(compactionId, SESSION, EventScript.at(10), "failed", "manual", usage = compactionUsage, error = error),
            reducer.compaction(),
        )
    }

    // Finding 4

    @Test
    fun `a delivery change that arrives before its enqueue still applies`() {
        val reducer = SessionReducer(SESSION)
        reducer.apply(ServerEvent.InboxDeliveryChanged(SESSION, MSG_A, PromptDelivery.Steer, at(20)))
        reducer.apply(ServerEvent.InboxEnqueued(user(MSG_A, "later", PromptDelivery.Queue), at(10)))

        assertEquals(listOf(PromptDelivery.Steer), reducer.state.inbox.map { it.delivery })
    }

    @Test
    fun `an older delivery change does not undo a newer one`() {
        val reducer = SessionReducer(SESSION)
        reducer.apply(ServerEvent.InboxEnqueued(user(MSG_A, "later", PromptDelivery.Queue), at(10)))
        reducer.apply(ServerEvent.InboxDeliveryChanged(SESSION, MSG_A, PromptDelivery.Queue, at(30)))
        reducer.apply(ServerEvent.InboxDeliveryChanged(SESSION, MSG_A, PromptDelivery.Steer, at(20)))

        assertEquals(listOf(PromptDelivery.Queue), reducer.state.inbox.map { it.delivery })
    }

    // Finding 5

    private val boundary = MessageId("msg_boundary")
    private val reverted = MessageId("msg_reverted")
    private val newer = MessageId("msg_newer")

    private fun SessionReducer.ids() = state.messages.map { it.id }

    @Test
    fun `messages between the boundary and the commit that arrive after it are not brought back`() {
        val reducer = SessionReducer(SESSION)
        reducer.apply(ServerEvent.InboxEnqueued(user(MSG_A, "kept"), at(1)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, MSG_A, at(2)))
        reducer.apply(ServerEvent.InboxEnqueued(user(boundary, "undone"), at(3)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, boundary, at(4)))
        reducer.apply(ServerEvent.RevertCommitted(SESSION, boundary, at(50)))

        reducer.apply(ServerEvent.StepStarted(SESSION, reverted, startedAt = EventScript.at(5), meta = at(6)))
        reducer.apply(ServerEvent.TextDelta(SESSION, reverted, 0, "late", at(7)))
        reducer.apply(ServerEvent.StepStarted(SESSION, newer, startedAt = EventScript.at(60), meta = at(61)))

        assertEquals(listOf(MSG_A, newer), reducer.ids())
    }

    @Test
    fun `a commit before its boundary is known removes the boundary's turn once the boundary shows up`() {
        val reducer = SessionReducer(SESSION)
        reducer.apply(ServerEvent.InboxEnqueued(user(MSG_A, "kept"), at(1)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, MSG_A, at(2)))
        reducer.apply(ServerEvent.RevertCommitted(SESSION, boundary, at(50)))
        reducer.apply(ServerEvent.StepStarted(SESSION, reverted, startedAt = EventScript.at(5), meta = at(6)))
        reducer.apply(ServerEvent.StepStarted(SESSION, newer, startedAt = EventScript.at(60), meta = at(61)))
        reducer.apply(ServerEvent.InboxEnqueued(user(boundary, "undone"), at(3)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, boundary, at(4)))
        reducer.apply(ServerEvent.TextDelta(SESSION, reverted, 0, "late", at(7)))

        assertEquals(listOf(MSG_A, newer), reducer.ids())
    }

    @Test
    fun `a commit before its boundary is known keeps what it cannot place`() {
        val reducer = SessionReducer(SESSION)
        reducer.apply(ServerEvent.RevertCommitted(SESSION, boundary, at(50)))
        reducer.apply(ServerEvent.StepStarted(SESSION, reverted, startedAt = EventScript.at(5), meta = at(6)))

        assertEquals(listOf(reverted), reducer.ids())
    }

    @Test
    fun `a commit with no time removes what is held after the boundary and nothing that comes later`() {
        val reducer = SessionReducer(SESSION)
        reducer.apply(ServerEvent.InboxEnqueued(user(boundary, "undone"), at(3)))
        reducer.apply(ServerEvent.InboxDelivered(SESSION, boundary, at(4)))
        reducer.apply(ServerEvent.StepStarted(SESSION, reverted, startedAt = EventScript.at(5), meta = at(6)))
        reducer.apply(ServerEvent.RevertCommitted(SESSION, boundary, EventMeta(id = "evt_commit")))
        reducer.apply(ServerEvent.StepStarted(SESSION, newer, startedAt = EventScript.at(60), meta = at(61)))
        reducer.apply(ServerEvent.TextDelta(SESSION, reverted, 0, "late", at(7)))

        assertEquals(listOf(newer), reducer.ids())
    }
}
