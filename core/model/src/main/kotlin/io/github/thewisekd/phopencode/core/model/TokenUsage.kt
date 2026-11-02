package io.github.thewisekd.phopencode.core.model

import java.math.BigInteger

/**
 * Tokens and cost of one step, one message, or a total of those.
 *
 * Counters are [BigInteger]: totals add up values a server sends, and a `Long` would either wrap to
 * a negative number or, with exact arithmetic, throw on the UI's way to showing a sum.
 *
 * [cost] and [hasMissingCost] together keep a missing cost apart from a zero cost:
 *
 * | [cost] | [hasMissingCost] | Meaning |
 * |---|---|---|
 * | set | false | Every entry reported a cost; the amount is exact, and may be zero |
 * | set | true | Some entries reported no cost; the amount is a lower bound |
 * | null | true | No entry reported a cost |
 * | null | false | Nothing to count: [EMPTY] |
 *
 * [plus] is associative with [EMPTY] as its identity, so a running total equals a recount.
 */
data class TokenUsage(
    val input: BigInteger = BigInteger.ZERO,
    val output: BigInteger = BigInteger.ZERO,
    val reasoning: BigInteger = BigInteger.ZERO,
    val cacheRead: BigInteger = BigInteger.ZERO,
    val cacheWrite: BigInteger = BigInteger.ZERO,
    val cost: Cost? = null,
    val hasMissingCost: Boolean = cost == null,
) {
    /** All five counters added up. This is not the context fill level. */
    val totalTokens: BigInteger
        get() = input + output + reasoning + cacheRead + cacheWrite

    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        input = input + other.input,
        output = output + other.output,
        reasoning = reasoning + other.reasoning,
        cacheRead = cacheRead + other.cacheRead,
        cacheWrite = cacheWrite + other.cacheWrite,
        cost = sumCosts(cost, other.cost),
        hasMissingCost = hasMissingCost || other.hasMissingCost,
    )

    companion object {
        /** The total of nothing: no tokens, no cost, and no cost missing. */
        val EMPTY: TokenUsage = TokenUsage(hasMissingCost = false)

        /** Builds a usage from `Long` counters, for callers that do not need wider ones. */
        fun of(
            input: Long = 0,
            output: Long = 0,
            reasoning: Long = 0,
            cacheRead: Long = 0,
            cacheWrite: Long = 0,
            cost: Cost? = null,
        ): TokenUsage = TokenUsage(
            input = BigInteger.valueOf(input),
            output = BigInteger.valueOf(output),
            reasoning = BigInteger.valueOf(reasoning),
            cacheRead = BigInteger.valueOf(cacheRead),
            cacheWrite = BigInteger.valueOf(cacheWrite),
            cost = cost,
        )

        private fun sumCosts(first: Cost?, second: Cost?): Cost? = when {
            first == null -> second
            second == null -> first
            else -> first + second
        }
    }
}

/** The total of all entries; [TokenUsage.EMPTY] when there are none. */
fun Iterable<TokenUsage>.sumUsage(): TokenUsage = fold(TokenUsage.EMPTY, TokenUsage::plus)
