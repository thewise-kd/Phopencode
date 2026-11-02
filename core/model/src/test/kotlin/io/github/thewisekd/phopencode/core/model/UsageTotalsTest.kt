package io.github.thewisekd.phopencode.core.model

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageTotalsTest {
    private val session = SessionId("session-1")

    private fun cost(text: String): Cost = Cost.parse(text)!!

    private fun assistant(id: String, usage: TokenUsage? = null, steps: List<TokenUsage> = emptyList()) =
        Message.Assistant(id = MessageId(id), sessionId = session, usage = usage, steps = steps)

    private fun user(id: String) = Message.User(id = MessageId(id), sessionId = session, text = "hello")

    private fun sessionWith(usage: TokenUsage?) = Session(id = session, usage = usage)

    @Test
    fun `an assistant total comes from its steps when no total was reported`() {
        val message = assistant(
            "m1",
            steps = listOf(TokenUsage.of(input = 1, cost = cost("0.1")), TokenUsage.of(input = 2, cost = cost("0.2"))),
        )

        assertEquals(TokenUsage.of(input = 3, cost = cost("0.3")), message.totalUsage)
    }

    @Test
    fun `a reported assistant total wins over its steps`() {
        val reported = TokenUsage.of(input = 100, cost = cost("9"))
        val message = assistant("m1", usage = reported, steps = listOf(TokenUsage.of(input = 1)))

        assertEquals(reported, message.totalUsage)
    }

    @Test
    fun `a step without a cost shows up as a gap in the assistant total`() {
        val message = assistant("m1", steps = listOf(TokenUsage.of(cost = cost("0.1")), TokenUsage.of()))

        assertEquals(cost("0.1"), message.totalUsage.cost)
        assertTrue(message.totalUsage.hasMissingCost)
    }

    @Test
    fun `an assistant with no usage at all counts as missing, not as free`() {
        val total = assistant("m1").totalUsage

        assertNull(total.cost)
        assertTrue(total.hasMissingCost)
    }

    @Test
    fun `a list total counts assistant messages only`() {
        val messages = listOf(
            user("m1"),
            assistant("m2", usage = TokenUsage.of(input = 1, cost = cost("0.1"))),
            Message.Idle(id = MessageId("m3"), sessionId = session),
            assistant("m4", usage = TokenUsage.of(input = 2, cost = cost("0.2"))),
        )

        assertEquals(TokenUsage.of(input = 3, cost = cost("0.3")), messages.totalUsage())
    }

    @Test
    fun `a list without assistant messages totals to EMPTY`() {
        assertEquals(TokenUsage.EMPTY, emptyList<Message>().totalUsage())
        assertEquals(TokenUsage.EMPTY, listOf(user("m1")).totalUsage())
    }

    @Test
    fun `a streaming assistant makes the list total incomplete`() {
        val total = listOf(assistant("m1", usage = TokenUsage.of(cost = cost("1"))), assistant("m2")).totalUsage()

        assertEquals(cost("1"), total.cost)
        assertTrue(total.hasMissingCost)
    }

    @Test
    fun `a session total comes from its messages when none was reported`() {
        val messages = listOf(assistant("m1", usage = TokenUsage.of(output = 4, cost = cost("0.4"))))

        assertEquals(TokenUsage.of(output = 4, cost = cost("0.4")), sessionWith(null).totalUsage(messages))
    }

    @Test
    fun `a reported session total wins, because the message list may be only one page`() {
        val reported = TokenUsage.of(input = 1000, cost = cost("12"))
        val page = listOf(assistant("m1", usage = TokenUsage.of(input = 1, cost = cost("0.1"))))

        assertEquals(reported, sessionWith(reported).totalUsage(page))
    }

    @Test
    fun `a reported zero cost survives to the session total`() {
        val total = sessionWith(TokenUsage.of(input = 1, cost = Cost.ZERO)).totalUsage(emptyList())

        assertEquals(Cost.ZERO, total.cost)
        assertFalse(total.hasMissingCost)
    }

    @Test
    fun `a parent and its subagent sessions can be summed`() {
        val parent = sessionWith(TokenUsage.of(input = Long.MAX_VALUE, cost = cost("0.1")))
        val child = sessionWith(TokenUsage.of(input = Long.MAX_VALUE, cost = cost("0.2")))

        val total = listOf(parent, child).map { it.totalUsage(emptyList()) }.sumUsage()

        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO, total.input)
        assertEquals(cost("0.3"), total.cost)
    }
}
