package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.model.Cost
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals

/**
 * Token counts and prices follow the screen's language, read from the composition's configuration
 * rather than the process default, which is set to neither language here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class RegistryNumbersTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var saved: Locale

    @Before
    fun otherDefault() {
        saved = Locale.getDefault()
        // Groups with a narrow space and a decimal comma: a read of the default would show.
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun restoreDefault() {
        Locale.setDefault(saved)
    }

    private fun price(text: String): Cost = Cost.of(BigDecimal(text))!!

    @Test
    fun `token counts group by the language`() {
        assertEquals("1,048,576", formatTokens(1_048_576, Locale.ENGLISH))
        assertEquals("1.048.576", formatTokens(1_048_576, Locale.forLanguageTag("tr")))
        assertEquals("0", formatTokens(0, Locale.ENGLISH))
    }

    @Test
    fun `prices keep two decimals at least and show small ones in full`() {
        assertEquals("3.00", formatPrice(price("3"), Locale.ENGLISH))
        assertEquals("0.075", formatPrice(price("0.075"), Locale.ENGLISH))
        assertEquals("0,075", formatPrice(price("0.075"), Locale.forLanguageTag("tr")))
        assertEquals("1,234.50", formatPrice(price("1234.5"), Locale.ENGLISH))
        assertEquals("1.234,50", formatPrice(price("1234.5"), Locale.forLanguageTag("tr")))
        assertEquals("0.000001", formatPrice(price("0.000001"), Locale.ENGLISH))
    }

    @Test
    fun `a price too small to show is not shown as free`() {
        assertEquals("<0.000001", formatPrice(price("0.0000001"), Locale.ENGLISH))
        assertEquals("0.00", formatPrice(price("0"), Locale.ENGLISH))
    }

    @Test
    fun `the screen uses its own language, not the process default`() {
        compose.setModel(modelShown)

        compose.onNodeWithText("200,000 tokens", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("$22.50", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
}
