package io.github.thewisekd.phopencode.core.model

import java.math.BigDecimal

/**
 * An exact amount of money, in the currency the server reports.
 *
 * Backed by [BigDecimal] so that sums never drift. There is deliberately no way in from `Double`:
 * by then the rounding has already happened, so mappers pass the number's text to [parse].
 *
 * Instances are normalized, so `1.5` equals `1.50`, unlike two plain [BigDecimal]s. Absurd amounts
 * are refused up front: adding `1e999999999` to `1` would allocate a billion digits, and the value
 * comes from a server.
 */
class Cost private constructor(val amount: BigDecimal) : Comparable<Cost> {
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

        val ZERO: Cost = Cost(BigDecimal.ZERO)

        /** Returns null for an amount outside the supported range. */
        fun of(amount: BigDecimal): Cost? {
            if (amount.signum() == 0) return ZERO
            // Stripping zeros only lowers the scale, and throws if it falls below Int.MIN_VALUE. So a
            // scale already under the range is refused here, while one above it may still come in.
            if (amount.scale() < -MAX_SCALE || amount.precision() > MAX_PRECISION) return null
            val normalized = amount.stripTrailingZeros()
            return if (normalized.scale() in -MAX_SCALE..MAX_SCALE) Cost(normalized) else null
        }

        /** Returns null for text that is not a finite number in the supported range. */
        fun parse(text: String): Cost? {
            if (text.length > MAX_TEXT_LENGTH) return null
            return text.toBigDecimalOrNull()?.let(::of)
        }
    }
}
