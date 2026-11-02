package io.github.thewisekd.phopencode.core.model

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CostTest {
    private fun cost(text: String): Cost = assertNotNull(Cost.parse(text), "should parse: $text")

    @Test
    fun `addition does not drift the way binary floating point does`() {
        check(0.1 + 0.2 != 0.3) { "the premise of this test" }
        assertEquals(cost("0.3"), cost("0.1") + cost("0.2"))
    }

    @Test
    fun `a million tiny costs add up to an exact amount`() {
        val tiny = cost("0.000001")
        val total = generateSequence { tiny }.take(1_000_000).fold(Cost.ZERO, Cost::plus)
        assertEquals(cost("1"), total)
    }

    @Test
    fun `adding far apart magnitudes keeps every digit`() {
        assertEquals("100000000000000000000.00000000000000000001", (cost("1e20") + cost("1e-20")).toString())
    }

    @Test
    fun `equality and hash ignore the written scale`() {
        assertEquals(cost("1.5"), cost("1.50"))
        assertEquals(cost("1.5").hashCode(), cost("1.50").hashCode())
        assertEquals(cost("100"), cost("1e2"))
        assertEquals(Cost.ZERO, cost("0.000"))
        assertEquals(Cost.ZERO.hashCode(), cost("0.000").hashCode())
    }

    @Test
    fun `a sum that cancels out still equals zero`() {
        assertEquals(Cost.ZERO, cost("0.10") + cost("-0.1"))
    }

    @Test
    fun `toString is plain decimal, never scientific`() {
        assertEquals("100", cost("1e2").toString())
        assertEquals("0.00000012", cost("1.2e-7").toString())
        assertEquals("0", Cost.ZERO.toString())
    }

    @Test
    fun `costs are ordered by amount`() {
        assertTrue(cost("0.10") < cost("0.2"))
        assertEquals(0, cost("1.0").compareTo(cost("1.00")))
    }

    @Test
    fun `of normalizes a BigDecimal`() {
        assertEquals(cost("2.5"), Cost.of(BigDecimal("2.500")))
    }

    @Test
    fun `text that is not a finite number is rejected, not thrown`() {
        listOf("", " ", "abc", "NaN", "Infinity", "1,5", "1e").forEach { assertNull(Cost.parse(it), it) }
    }

    @Test
    fun `amounts that would make arithmetic explode are rejected`() {
        assertNull(Cost.parse("1e999999999"))
        assertNull(Cost.parse("1e-999999999"))
        // Past the exponent BigDecimal documents; null whether it refuses the text or accepts it.
        assertNull(Cost.parse("1e99999999999"))
        assertNull(Cost.parse("1".repeat(100_000)))
        assertNull(Cost.of(BigDecimal.ONE.scaleByPowerOfTen(1_000_000)))
    }

    @Test
    fun `text at the limits of the BigDecimal scale is rejected, not thrown`() {
        listOf(
            "100e2147483647",
            "-100e2147483647",
            "10e2147483647",
            "1e2147483647",
            "1.0e2147483647",
            "100e-2147483647",
            "1e-2147483647",
            "-1e-2147483647",
            // Past the exponent BigDecimal documents; null whether it refuses the text or accepts it.
            "100e2147483648",
            "1e2147483648",
            "1e2147483649",
            "1e-2147483648",
            "1e-2147483649",
        ).forEach { assertNull(Cost.parse(it), it) }
    }

    @Test
    fun `a BigDecimal at the limits of the scale is rejected, not thrown`() {
        listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, Int.MAX_VALUE, Int.MAX_VALUE - 1).forEach { scale ->
            listOf("1", "-1", "100", "-100").forEach { unscaled ->
                assertNull(Cost.of(BigDecimal(BigInteger(unscaled), scale)), "$unscaled at scale $scale")
            }
        }
    }

    @Test
    fun `zero is a valid amount at any scale`() {
        listOf("0e2147483647", "0e-2147483647", "-0e2147483647", "0.00e-400").forEach {
            assertEquals(Cost.ZERO, Cost.parse(it), it)
        }
        listOf(Int.MIN_VALUE, Int.MAX_VALUE).forEach { scale ->
            assertEquals(Cost.ZERO, Cost.of(BigDecimal(BigInteger.ZERO, scale)))
        }
    }

    @Test
    fun `the range applies to the normalized value, not to how it was written`() {
        assertEquals(cost("1e400"), cost("100e398"))
        assertEquals(cost("1e-400"), cost("1000e-403"))
        assertEquals(cost("-1e400"), cost("-100e398"))
        assertNull(Cost.parse("100e399"))
        assertNull(Cost.parse("1e401"))
        assertNull(Cost.parse("1e-401"))
        assertNull(Cost.parse("1000e-404"))
    }

    @Test
    fun `adding the largest supported amounts neither throws nor loses digits`() {
        val large = cost("1e400")
        val small = cost("1e-400")

        assertEquals(cost("2e400"), large + large)
        assertEquals(Cost.ZERO, large + cost("-1e400"))
        assertEquals((large + small).amount.compareTo(large.amount + small.amount), 0)
    }

    @Test
    fun `the smallest and largest double a server can print are representable`() {
        assertNotNull(Cost.parse("5e-324"))
        assertNotNull(Cost.parse("1.7976931348623157e308"))
    }
}
