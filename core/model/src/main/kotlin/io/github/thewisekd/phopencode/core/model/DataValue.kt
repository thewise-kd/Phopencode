package io.github.thewisekd.phopencode.core.model

import java.math.BigDecimal

/**
 * A free-form structured value, for data whose shape the server owns: tool input, metadata, form
 * answers and `when` conditions. Keeps this module independent of any JSON library.
 */
sealed interface DataValue {
    data object Null : DataValue

    data class Bool(val value: Boolean) : DataValue

    /** Compared by numeric value, so `1` equals `1.0`: an answer and a condition may differ in scale. */
    class Num(val value: BigDecimal) : DataValue {
        override fun equals(other: Any?): Boolean = other is Num && value.compareTo(other.value) == 0

        /**
         * Hashes the digits without their trailing zeros, which equal numbers share. The scale is
         * left out: stripping zeros from [value] itself throws when its scale is near the minimum.
         */
        override fun hashCode(): Int =
            BigDecimal(value.unscaledValue()).stripTrailingZeros().unscaledValue().hashCode()

        override fun toString(): String = "Num($value)"
    }

    data class Text(val value: String) : DataValue

    data class Items(val values: List<DataValue>) : DataValue

    data class Fields(val values: Map<String, DataValue>) : DataValue
}
