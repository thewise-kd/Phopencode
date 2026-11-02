package io.github.thewisekd.phopencode.core.model

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The prices and usage come from the `reasoning` recording; see "Tokens and pricing" in `docs/api/README.md`. */
class ModelPricingTest {
    private fun cost(text: String): Cost = assertNotNull(Cost.parse(text), "should parse: $text")

    private val recordedPrice = ModelPriceTier(
        input = cost("1.5"),
        output = cost("6"),
        cacheRead = cost("0.15"),
        cacheWrite = cost("1.875"),
    )

    /** The server stored this for 200 prompt tokens (50 cached) and 40 completion tokens (25 reasoning). */
    private val recordedUsage = TokenUsage.of(input = 150, output = 15, reasoning = 25, cacheRead = 50)

    @Test
    fun `the recorded usage costs what the server charged, with reasoning at the output price`() {
        assertEquals(cost("0.0004725"), recordedPrice.costOf(recordedUsage))
    }

    @Test
    fun `reasoning tokens and output tokens cost the same`() {
        val asReasoning = TokenUsage.of(reasoning = 1_000)
        val asOutput = TokenUsage.of(output = 1_000)

        assertEquals(cost("0.006"), recordedPrice.costOf(asReasoning))
        assertEquals(recordedPrice.costOf(asOutput), recordedPrice.costOf(asReasoning))
    }

    @Test
    fun `prices are per million tokens`() {
        assertEquals(cost("1.5"), recordedPrice.costOf(TokenUsage.of(input = 1_000_000)))
        assertEquals(cost("0.0000015"), recordedPrice.costOf(TokenUsage.of(input = 1)))
    }

    @Test
    fun `cache writes use the cache write price`() {
        assertEquals(cost("1.875"), recordedPrice.costOf(TokenUsage.of(cacheWrite = 1_000_000)))
    }

    @Test
    fun `summed usage costs the same as the sum of its parts`() {
        val first = TokenUsage.of(input = 150, output = 15, reasoning = 25, cacheRead = 50)
        val second = TokenUsage.of(input = 1, output = 2, reasoning = 3, cacheRead = 4, cacheWrite = 5)
        val parts = listOf(first, second)

        assertEquals(
            parts.map { assertNotNull(recordedPrice.costOf(it)) }.reduce(Cost::plus),
            recordedPrice.costOf(parts.sumUsage()),
        )
    }

    @Test
    fun `a free model costs zero, which is a real price`() {
        val free = ModelPriceTier(input = Cost.ZERO, output = Cost.ZERO, cacheRead = Cost.ZERO, cacheWrite = Cost.ZERO)

        assertEquals(Cost.ZERO, free.costOf(recordedUsage))
    }

    @Test
    fun `a tier with no cache price can bill usage that has no cache tokens`() {
        val noCache = ModelPriceTier(input = cost("1"), output = cost("2"))

        assertEquals(cost("0.000005"), noCache.costOf(TokenUsage.of(input = 1, output = 1, reasoning = 1)))
    }

    @Test
    fun `cache tokens with no cache price are unknown, not free`() {
        val noCache = ModelPriceTier(input = cost("1"), output = cost("2"))

        assertNull(noCache.costOf(TokenUsage.of(cacheRead = 1)))
        assertNull(noCache.costOf(TokenUsage.of(cacheWrite = 1)))
        assertNull(noCache.costOf(TokenUsage(cacheRead = BigInteger.ONE.shiftLeft(10_000_000))))
    }

    @Test
    fun `a huge counter gives no cost instead of an unbounded calculation`() {
        val huge = TokenUsage(input = BigInteger.ONE.shiftLeft(10_000_000))

        assertNull(recordedPrice.costOf(huge))
    }

    @Test
    fun `a price at the digit limit bills a million tokens at that price`() {
        val price = cost("9".repeat(1000))
        val tier = ModelPriceTier(input = price, output = Cost.ZERO)

        assertEquals(price, tier.costOf(TokenUsage.of(input = 1_000_000)))
    }

    private fun inputAt(price: Cost) = ModelPriceTier(input = price, output = Cost.ZERO)

    private fun tokens(count: BigInteger) = TokenUsage(input = count)

    @Test
    fun `a charge is judged after its trailing zeros are gone`() {
        val price = cost("9".repeat(1000) + "e400")

        assertEquals(price, inputAt(price).costOf(TokenUsage.of(input = 1_000_000)))
    }

    @Test
    fun `the largest total in range comes from a count wider than any price`() {
        val count = BigInteger.TEN.pow(1806) - BigInteger.TEN.pow(806)

        assertEquals(cost("9".repeat(1000) + "e400"), inputAt(cost("1e-400")).costOf(tokens(count)))
    }

    @Test
    fun `a total with too many digits gives no cost`() {
        assertNull(inputAt(cost("9".repeat(1000))).costOf(TokenUsage.of(input = 7)))
    }

    @Test
    fun `a total past the largest exponent gives no cost`() {
        assertNull(inputAt(cost("1e400")).costOf(tokens(BigInteger.TEN.pow(7))))
        assertNull(inputAt(cost("1e-400")).costOf(tokens(BigInteger.TEN.pow(1806))))
    }

    @Test
    fun `a count too wide for any total in range gives no cost without multiplying`() {
        assertNull(inputAt(cost("1e-400")).costOf(tokens(BigInteger.ONE.shiftLeft(10_000_000))))
    }

    @Test
    fun `a zero price costs zero for any count, even one too wide for other prices`() {
        assertEquals(Cost.ZERO, inputAt(Cost.ZERO).costOf(tokens(BigInteger.TEN.pow(1000))))
        assertEquals(Cost.ZERO, inputAt(Cost.ZERO).costOf(tokens(BigInteger.ONE.shiftLeft(10_000_000))))
    }

    @Test
    fun `zero tokens cost zero at any price`() {
        assertEquals(Cost.ZERO, inputAt(cost("9".repeat(1000) + "e400")).costOf(TokenUsage.EMPTY))
    }

    @Test
    fun `only the total has to be in range, not each charge`() {
        val tier = ModelPriceTier(input = cost("5e-395"), output = cost("5e-395"))

        assertEquals(cost("1e-400"), tier.costOf(TokenUsage.of(input = 1, output = 1)))
    }

    @Test
    fun `charges that are valid alone but too long together give no cost`() {
        val tier = ModelPriceTier(input = cost("1".repeat(1000) + "e100"), output = cost("1e-394"))

        assertNotNull(tier.costOf(TokenUsage.of(input = 1_000_000)))
        assertNotNull(tier.costOf(TokenUsage.of(output = 1)))
        assertNull(tier.costOf(TokenUsage.of(input = 1_000_000, output = 1)))
    }

    @Test
    fun `charges that carry past the largest exponent give no cost`() {
        val tier = ModelPriceTier(input = cost("5e400"), output = cost("5e400"))

        assertEquals(cost("5e400"), tier.costOf(TokenUsage.of(input = 1_000_000)))
        assertNull(tier.costOf(TokenUsage.of(input = 1_000_000, output = 1_000_000)))
    }

    @Test
    fun `pricing is a list of tiers, and the base tier is the one with no condition`() {
        val over = recordedPrice.copy(threshold = PriceThreshold(type = "context", size = 200_000), input = cost("3"))
        val pricing = ModelPricing(listOf(over, recordedPrice))

        assertEquals(listOf(over, recordedPrice), pricing.tiers)
        assertEquals(recordedPrice, pricing.base)
    }

    @Test
    fun `pricing with only conditional tiers has no base`() {
        val over = recordedPrice.copy(threshold = PriceThreshold(type = "context", size = 200_000))

        assertNull(ModelPricing(listOf(over)).base)
        assertNull(ModelPricing(emptyList()).base)
    }

    @Test
    fun `a price tier has no field for a reasoning price`() {
        val names = ModelPriceTier::class.java.declaredFields.map { it.name.lowercase() }

        assertEquals(emptyList(), names.filter { "reasoning" in it })
        assertEquals(setOf("tiers"), ModelPricing::class.java.declaredFields.map { it.name }.toSet())
    }
}
