package io.github.thewisekd.phopencode.core.model

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenUsageTest {
    private fun cost(text: String): Cost = Cost.parse(text)!!

    @Test
    fun `every counter is added independently`() {
        val total = TokenUsage.of(1, 2, 3, 4, 5, cost("0.1")) + TokenUsage.of(10, 20, 30, 40, 50, cost("0.2"))

        assertEquals(TokenUsage.of(11, 22, 33, 44, 55, cost("0.3")), total)
    }

    @Test
    fun `totalTokens counts all five counters`() {
        assertEquals(BigInteger.valueOf(15), TokenUsage.of(1, 2, 3, 4, 5).totalTokens)
    }

    @Test
    fun `summing an empty list has no tokens and no cost, and nothing is missing`() {
        val total = emptyList<TokenUsage>().sumUsage()

        assertEquals(TokenUsage.EMPTY, total)
        assertEquals(BigInteger.ZERO, total.totalTokens)
        assertNull(total.cost)
        assertFalse(total.hasMissingCost)
    }

    @Test
    fun `summing one entry returns that entry`() {
        val only = TokenUsage.of(input = 7, cost = cost("0.5"))

        assertEquals(only, listOf(only).sumUsage())
    }

    @Test
    fun `EMPTY is the identity on both sides, for known and missing costs`() {
        val known = TokenUsage.of(input = 7, cost = cost("0.5"))
        val missing = TokenUsage.of(input = 7)

        assertEquals(known, TokenUsage.EMPTY + known)
        assertEquals(known, known + TokenUsage.EMPTY)
        assertEquals(missing, TokenUsage.EMPTY + missing)
        assertEquals(missing, missing + TokenUsage.EMPTY)
    }

    @Test
    fun `summing is associative, so a running total equals a full recount`() {
        val a = TokenUsage.of(input = 1, cost = cost("0.1"))
        val b = TokenUsage.of(input = 2)
        val c = TokenUsage.of(input = 3, cost = cost("0.2"))

        assertEquals((a + b) + c, a + (b + c))
        assertEquals((a + b) + c, listOf(a, b, c).sumUsage())
    }

    @Test
    fun `a usage without a cost is marked missing by default`() {
        val usage = TokenUsage.of(input = 1)

        assertNull(usage.cost)
        assertTrue(usage.hasMissingCost)
        assertTrue(TokenUsage().hasMissingCost)
    }

    @Test
    fun `a zero cost is not a missing cost`() {
        val free = TokenUsage.of(input = 1, cost = Cost.ZERO)
        val unknown = TokenUsage.of(input = 1)

        assertNotEquals(free, unknown)
        assertEquals(Cost.ZERO, free.cost)
        assertFalse(free.hasMissingCost)
    }

    @Test
    fun `zero costs sum to zero, not to missing`() {
        val total = listOf(TokenUsage.of(cost = Cost.ZERO), TokenUsage.of(cost = Cost.ZERO)).sumUsage()

        assertEquals(Cost.ZERO, total.cost)
        assertFalse(total.hasMissingCost)
    }

    @Test
    fun `when every cost is missing, the total cost stays missing`() {
        val total = listOf(TokenUsage.of(input = 1), TokenUsage.of(input = 2)).sumUsage()

        assertNull(total.cost)
        assertTrue(total.hasMissingCost)
        assertEquals(BigInteger.valueOf(3), total.input)
    }

    @Test
    fun `when some costs are missing, the known part is kept and the gap is flagged`() {
        val total = listOf(TokenUsage.of(cost = cost("1.5")), TokenUsage.of(), TokenUsage.of(cost = cost("0.25"))).sumUsage()

        assertEquals(cost("1.75"), total.cost)
        assertTrue(total.hasMissingCost)
    }

    @Test
    fun `a zero cost next to a missing one stays a known zero with a gap`() {
        val total = TokenUsage.of(cost = Cost.ZERO) + TokenUsage.of()

        assertEquals(Cost.ZERO, total.cost)
        assertTrue(total.hasMissingCost)
    }

    @Test
    fun `totals past Long range stay exact and positive`() {
        val huge = TokenUsage.of(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        val total = listOf(huge, huge, huge).sumUsage()
        val expectedPerCounter = BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.valueOf(3)

        assertEquals(expectedPerCounter, total.input)
        assertEquals(expectedPerCounter, total.output)
        assertEquals(expectedPerCounter, total.reasoning)
        assertEquals(expectedPerCounter, total.cacheRead)
        assertEquals(expectedPerCounter, total.cacheWrite)
        assertEquals(expectedPerCounter * BigInteger.valueOf(5), total.totalTokens)
    }

    @Test
    fun `a counter wider than Long is representable`() {
        val wide = BigInteger("123456789012345678901234567890")

        assertEquals(wide + BigInteger.ONE, (TokenUsage(input = wide) + TokenUsage.of(input = 1)).input)
    }

    @Test
    fun `many small costs sum exactly`() {
        val total = List(10_000) { TokenUsage.of(cost = cost("0.0001")) }.sumUsage()

        assertEquals(cost("1"), total.cost)
    }
}
