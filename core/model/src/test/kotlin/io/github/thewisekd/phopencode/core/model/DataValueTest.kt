package io.github.thewisekd.phopencode.core.model

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DataValueTest {
    @Test
    fun `numbers compare by value, whatever their written scale`() {
        assertEquals(DataValue.Num(BigDecimal("1.0")), DataValue.Num(BigDecimal("1")))
        assertEquals(DataValue.Num(BigDecimal("1.0")).hashCode(), DataValue.Num(BigDecimal("1")).hashCode())
        assertNotEquals(DataValue.Num(BigDecimal("1")), DataValue.Num(BigDecimal("2")))
    }

    @Test
    fun `nested values compare structurally`() {
        fun nested(number: String) = DataValue.Fields(
            mapOf("items" to DataValue.Items(listOf(DataValue.Num(BigDecimal(number)), DataValue.Null, DataValue.Bool(true)))),
        )

        assertEquals(nested("2"), nested("2.00"))
    }

    @Test
    fun `numbers at the limits of the scale hash, compare and print without throwing`() {
        val extremes = listOf(
            BigDecimal("100e2147483647"),
            BigDecimal("-100e2147483647"),
            BigDecimal("1e2147483647"),
            atMinimumScale(100),
            BigDecimal("1e-2147483647"),
            BigDecimal("-1e-2147483647"),
            BigDecimal("100e-2147483647"),
        ).map { DataValue.Num(it) }

        extremes.forEach { number ->
            assertEquals(number, DataValue.Num(number.value))
            assertEquals(number.hashCode(), DataValue.Num(number.value).hashCode())
            assertTrue(number.toString().startsWith("Num("), number.toString())
            extremes.filterNot { it === number }.forEach { assertNotEquals(number, it) }
        }
    }

    @Test
    fun `numerically equal values hash the same, even at the limits of the scale`() {
        assertEqualAndSameHash(BigDecimal("100e2147483647"), atMinimumScale(10))
        assertEqualAndSameHash(BigDecimal("1000e2147483645"), BigDecimal("100e2147483646"))
        assertEqualAndSameHash(BigDecimal("1e-2147483646"), BigDecimal("10e-2147483647"))
        assertEqualAndSameHash(BigDecimal("-1e-2147483646"), BigDecimal("-10e-2147483647"))
        assertEqualAndSameHash(BigDecimal("1.50"), BigDecimal("1.5"))
        assertEqualAndSameHash(BigDecimal("1e2"), BigDecimal("100"))
        assertEqualAndSameHash(BigDecimal("1"), BigDecimal("1.0"))
    }

    @Test
    fun `zero hashes the same at any scale`() {
        listOf("0e2147483647", "0e-2147483647", "0.000", "0").forEach {
            assertEqualAndSameHash(BigDecimal.ZERO, BigDecimal(it))
        }
        assertEqualAndSameHash(BigDecimal.ZERO, atMinimumScale(0))
        assertEqualAndSameHash(BigDecimal.ZERO, BigDecimal(BigInteger.ZERO, Int.MAX_VALUE))
    }

    @Test
    fun `numbers with extreme scales work as keys in a set`() {
        val set = hashSetOf(DataValue.Num(BigDecimal("100e2147483647")), DataValue.Num(BigDecimal("0e2147483647")))

        assertTrue(DataValue.Num(atMinimumScale(10)) in set)
        assertTrue(DataValue.Num(BigDecimal.ZERO) in set)
    }

    /** The string constructor documents no exponent that reaches this scale, so it is built directly. */
    private fun atMinimumScale(unscaled: Long) = BigDecimal(BigInteger.valueOf(unscaled), Int.MIN_VALUE)

    private fun assertEqualAndSameHash(first: BigDecimal, second: BigDecimal) {
        assertEquals(DataValue.Num(first), DataValue.Num(second))
        assertEquals(DataValue.Num(first).hashCode(), DataValue.Num(second).hashCode())
    }

    @Test
    fun `a number and its text form are different values`() {
        assertNotEquals<DataValue>(DataValue.Num(BigDecimal.ONE), DataValue.Text("1"))
    }
}
