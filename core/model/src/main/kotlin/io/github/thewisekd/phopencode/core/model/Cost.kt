package io.github.thewisekd.phopencode.core.model

import java.math.BigDecimal
import java.math.BigInteger

/**
 * An exact amount of money, in the currency the server reports.
 *
 * Backed by [BigDecimal] so that sums never drift. There is deliberately no way in from `Double`:
 * by then the rounding has already happened, so mappers pass the number's text to [parse].
 *
 * Instances are normalized, so `1.5` equals `1.50`, unlike two plain [BigDecimal]s. Absurd amounts
 * are refused up front: adding `1e999999999` to `1` would allocate a billion digits, and the value
 * comes from a server. An amount in range has at most 1000 digits once normalized, and a scale from
 * -400 to 400.
 */
class Cost private constructor(val amount: BigDecimal) : Comparable<Cost> {
    /** The exact sum, which is not checked against the supported range. */
    operator fun plus(other: Cost): Cost = Cost((amount + other.amount).stripTrailingZeros())

    override fun compareTo(other: Cost): Int = amount.compareTo(other.amount)

    override fun equals(other: Any?): Boolean = other is Cost && amount == other.amount

    override fun hashCode(): Int = amount.hashCode()

    /** Plain decimal, never scientific notation. */
    override fun toString(): String = amount.toPlainString()

    companion object {
        /** Wider than `Double`, which is what a JavaScript server prints: 5e-324 to 1.8e308. */
        private const val MAX_SCALE = 400
        private const val MAX_PRECISION = 1000
        private const val MAX_TEXT_LENGTH = MAX_PRECISION + 32

        /**
         * Every count below 10^1806 fits. Above that a charge alone is past the range: the largest amount
         * is under 10^1400 and the smallest price is 10^-400, per 10^6 tokens. Only a negative price
         * could bring the total back, and a price is not negative.
         */
        private const val MAX_TOKEN_BITS = 6000

        /** Prices are per 10^6 tokens. */
        private const val TOKENS_PER_PRICE_DIGITS = 6

        val ZERO: Cost = Cost(BigDecimal.ZERO)

        /** Returns null for an amount outside the supported range. */
        fun of(amount: BigDecimal): Cost? {
            if (amount.signum() == 0) return ZERO
            // Stripping zeros only lowers the scale, and throws if it falls below Int.MIN_VALUE. So a
            // scale already under the range is refused here, while one above it may still come in.
            if (amount.scale() < -MAX_SCALE || amount.precision() > MAX_PRECISION) return null
            return normalized(amount)
        }

        /**
         * The exact total of prices per million tokens, each paired with its count. Only the total has to
         * be in the supported range, so a charge too small to be a [Cost] alone still counts. Null when
         * the total is outside it, or when a count above zero has no price: unknown is not free.
         */
        internal fun totalPerMillionTokens(charges: List<Pair<Cost?, BigInteger>>): Cost? {
            var total = BigDecimal.ZERO
            for ((price, tokens) in charges) {
                if (tokens.signum() == 0) continue
                if (price == null) return null
                if (price.amount.signum() == 0) continue
                if (tokens.bitLength() > MAX_TOKEN_BITS) return null
                total += price.amount.multiply(BigDecimal(tokens))
            }
            return normalized(total.scaleByPowerOfTen(-TOKENS_PER_PRICE_DIGITS))
        }

        /** For amounts computed from values in range, whose size and scale are known to be bounded. */
        private fun normalized(amount: BigDecimal): Cost? {
            if (amount.signum() == 0) return ZERO
            val value = amount.stripTrailingZeros()
            val inRange = value.precision() <= MAX_PRECISION && value.scale() in -MAX_SCALE..MAX_SCALE
            return if (inRange) Cost(value) else null
        }

        /** Returns null for text that is not a finite number in the supported range. */
        fun parse(text: String): Cost? {
            if (text.length > MAX_TEXT_LENGTH) return null
            return text.toBigDecimalOrNull()?.let(::of)
        }
    }
}
