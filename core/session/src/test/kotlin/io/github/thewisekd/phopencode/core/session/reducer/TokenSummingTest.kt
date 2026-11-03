package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenSummingTest {
    private val script = EventScript()
    private val reducer = SessionReducer(SESSION)

    private fun usage(input: Long, output: Long, cost: String?) =
        TokenUsage.of(input = input, output = output, cost = cost?.let { Cost.parse(it)!! })

    @Test
    fun `each step's usage lands on its message and the messages add up exactly`() {
        reducer.apply(ServerEvent.StepEnded(SESSION, MSG_A, usage(100, 10, "0.1"), "tool-calls", meta = script.durable()))
        reducer.apply(ServerEvent.StepEnded(SESSION, MSG_B, usage(200, 20, "0.2"), "stop", meta = script.durable()))

        assertEquals(usage(100, 10, "0.1"), reducer.assistant(MSG_A).usage)
        assertEquals("tool-calls", reducer.assistant(MSG_A).finishReason)
        assertEquals(usage(300, 30, "0.3"), reducer.state.messageUsage)
        assertEquals(Cost.parse("0.3"), reducer.state.messageUsage.cost)
    }

    @Test
    fun `counters wider than a Long are summed without loss`() {
        val huge = BigInteger.valueOf(Long.MAX_VALUE)
        val wide = TokenUsage(input = huge, cost = Cost.ZERO)
        reducer.apply(ServerEvent.StepEnded(SESSION, MSG_A, wide, meta = script.durable()))
        reducer.apply(ServerEvent.StepEnded(SESSION, MSG_B, wide, meta = script.durable()))

        assertEquals(huge + huge, reducer.state.messageUsage.input)
    }

    @Test
    fun `a repeated step end is not counted twice`() {
        val event = ServerEvent.StepEnded(SESSION, MSG_A, usage(100, 10, "0.1"), meta = script.durable())
        reducer.apply(event)
        reducer.apply(event)
        reducer.apply(event.copy(meta = event.meta.copy(id = "evt_again")))

        assertEquals(usage(100, 10, "0.1"), reducer.state.messageUsage)
    }

    @Test
    fun `a step with no reported cost counts as missing, not free`() {
        reducer.apply(ServerEvent.StepEnded(SESSION, MSG_A, usage(1, 1, "0.5"), meta = script.durable()))
        reducer.apply(ServerEvent.StepEnded(SESSION, MSG_B, usage(1, 1, null), meta = script.durable()))

        val total = reducer.state.messageUsage
        assertEquals(Cost.parse("0.5"), total.cost)
        assertTrue(total.hasMissingCost)
    }

    @Test
    fun `a failed step keeps the usage it reported, or none`() {
        val error = StructuredError("provider.transport")
        reducer.apply(ServerEvent.StepFailed(SESSION, MSG_A, error, usage(5, 0, "0"), meta = script.durable()))
        reducer.apply(ServerEvent.StepFailed(SESSION, MSG_B, error, null, meta = script.durable()))

        assertEquals(usage(5, 0, "0"), reducer.assistant(MSG_A).usage)
        assertEquals("error", reducer.assistant(MSG_A).finishReason)
        assertEquals(error, reducer.assistant(MSG_A).error)
        assertNull(reducer.assistant(MSG_B).usage)
    }

    @Test
    fun `compaction cost counts toward the messages' total, failed or not`() {
        val failedId = MessageId("msg_c1")
        val doneId = MessageId("msg_c2")
        for (id in listOf(failedId, doneId)) {
            reducer.apply(ServerEvent.InboxEnqueued(InboxItem(id, SESSION, null, InboxContent.Compaction, null), script.durable()))
            reducer.apply(ServerEvent.InboxDelivered(SESSION, id, script.durable()))
            reducer.apply(ServerEvent.CompactionStarted(SESSION, "manual", id, script.durable()))
            if (id == failedId) {
                reducer.apply(ServerEvent.CompactionFailed(SESSION, "manual", StructuredError("compaction.failed"), id, usage(10, 1, "0.01"), script.durable()))
            } else {
                reducer.apply(ServerEvent.CompactionEnded(SESSION, "manual", null, "summary", usage(20, 2, "0.02"), script.durable()))
            }
        }

        assertEquals(usage(30, 3, "0.03"), reducer.state.messageUsage)
    }

    @Test
    fun `the reported session total replaces the last one and an older report is ignored`() {
        val older = script.live()
        val newer = script.live()
        reducer.apply(ServerEvent.UsageUpdated(SESSION, usage(10, 1, "0.01"), older))
        reducer.apply(ServerEvent.UsageUpdated(SESSION, usage(30, 3, "0.03"), newer))
        reducer.apply(ServerEvent.UsageUpdated(SESSION, usage(10, 1, "0.01"), older.copy(id = "evt_resent")))

        assertEquals(usage(30, 3, "0.03"), reducer.state.session.usage)
    }

    @Test
    fun `a report without a time still replaces the total`() {
        reducer.apply(ServerEvent.UsageUpdated(SESSION, usage(10, 1, "0.01"), EventMeta()))
        reducer.apply(ServerEvent.UsageUpdated(SESSION, usage(20, 2, "0.02"), EventMeta()))

        assertEquals(usage(20, 2, "0.02"), reducer.state.session.usage)
    }

    private fun replayed(name: String): SessionState {
        val flow = RecordedFlow(name)
        return SessionReducer(flow.sessionOf("final-messages")).apply { applyAll(flow.events) }.state
    }

    @Test
    fun `in a recording, the steps add up exactly to the server's reported total`() {
        for (name in listOf("text", "reasoning", "tool", "subagent")) {
            val state = replayed(name)

            assertEquals(state.session.usage, state.messageUsage, name)
        }
    }

    /**
     * The server adds costs in binary floating point: in `compaction` it reports 0.016739999999999998
     * for steps that cost 0.0087, 0 and 0.00804. The exact sum is 0.01674, and the counters match.
     */
    @Test
    fun `where the server's own total drifts, the exact sum differs from it only by that rounding`() {
        val state = replayed("compaction")
        val reported = state.session.usage!!
        val summed = state.messageUsage

        assertEquals(reported.copy(cost = null), summed.copy(cost = null))
        assertEquals(Cost.parse("0.01674"), summed.cost)
        assertEquals(Cost.parse("0.016739999999999998"), reported.cost)
        assertTrue((reported.cost!!.amount - summed.cost!!.amount).abs() < BigDecimal("1e-15"))
    }
}
