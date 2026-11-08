package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThemeTokensTest {
    private val spacing = PhopencodeSpacing()

    private val textStyles: Map<String, TextStyle> = with(PhopencodeTypography) {
        mapOf(
            "displayLarge" to displayLarge,
            "displayMedium" to displayMedium,
            "displaySmall" to displaySmall,
            "headlineLarge" to headlineLarge,
            "headlineMedium" to headlineMedium,
            "headlineSmall" to headlineSmall,
            "titleLarge" to titleLarge,
            "titleMedium" to titleMedium,
            "titleSmall" to titleSmall,
            "bodyLarge" to bodyLarge,
            "bodyMedium" to bodyMedium,
            "bodySmall" to bodySmall,
            "labelLarge" to labelLarge,
            "labelMedium" to labelMedium,
            "labelSmall" to labelSmall,
            "code" to PhopencodeCodeTypography.body,
            "codeSmall" to PhopencodeCodeTypography.small,
        )
    }

    @Test
    fun `the spacing scale grows strictly, in steps of the 4 dp grid after the hairline step`() {
        val scale = listOf(spacing.xxs, spacing.xs, spacing.sm, spacing.md, spacing.lg, spacing.xl, spacing.xxl)
        assertEquals(scale.sorted().distinct(), scale)
        assertEquals(2.dp, spacing.xxs)
        scale.drop(1).forEach { assertEquals(0f, it.value % 4f, "$it is off the grid") }
    }

    @Test
    fun `the spacing tokens keep the values the screens are laid out with`() {
        assertEquals(
            listOf(2.dp, 4.dp, 8.dp, 12.dp, 16.dp, 24.dp, 32.dp),
            listOf(spacing.xxs, spacing.xs, spacing.sm, spacing.md, spacing.lg, spacing.xl, spacing.xxl),
        )
    }

    @Test
    fun `the touch target token is the 48 dp minimum`() {
        assertEquals(48.dp, spacing.touchTarget)
    }

    @Test
    fun `every text style scales with the system font size`() {
        textStyles.forEach { (name, style) ->
            assertTrue(style.fontSize.isSp, "$name font size")
            assertTrue(style.lineHeight.isSp, "$name line height")
        }
    }

    @Test
    fun `every text style is legible and its lines do not collide`() {
        textStyles.forEach { (name, style) ->
            assertTrue(style.fontSize.value >= 11f, "$name is too small")
            assertTrue(style.lineHeight.value >= style.fontSize.value, "$name lines overlap")
        }
    }

    @Test
    fun `body text is at least 14 sp`() {
        assertTrue(PhopencodeTypography.bodyMedium.fontSize >= 14.sp)
        assertTrue(PhopencodeCodeTypography.body.fontSize >= 14.sp)
    }

    @Test
    fun `code styles are monospaced`() {
        assertEquals(FontFamily.Monospace, PhopencodeCodeTypography.body.fontFamily)
        assertEquals(FontFamily.Monospace, PhopencodeCodeTypography.small.fontFamily)
    }

    @Test
    fun `text uses the system font, so nothing is bundled`() {
        textStyles.filterKeys { !it.startsWith("code") }.forEach { (name, style) ->
            assertTrue(style.fontFamily in setOf(null, FontFamily.Default, FontFamily.SansSerif), "$name font family")
        }
    }

    @Test
    fun `titles and headlines are semi-bold, so the hierarchy reads at a glance`() {
        with(PhopencodeTypography) {
            listOf(headlineLarge, headlineMedium, headlineSmall, titleLarge, titleMedium, titleSmall).forEach {
                assertEquals(FontWeight.SemiBold, it.fontWeight)
            }
        }
    }

    @Test
    fun `labels are medium weight and body text stays regular`() {
        with(PhopencodeTypography) {
            listOf(labelLarge, labelMedium, labelSmall).forEach { assertEquals(FontWeight.Medium, it.fontWeight) }
            listOf(bodyLarge, bodyMedium, bodySmall).forEach { assertEquals(FontWeight.Normal, it.fontWeight) }
        }
    }

    @Test
    fun `shapes round generously and grow with the component size`() {
        val density = Density(1f)
        val size = Size(400f, 400f)
        fun radius(shape: androidx.compose.ui.graphics.Shape): Float {
            val rounded = assertIs<RoundedCornerShape>(shape)
            return rounded.topStart.toPx(size, density)
        }
        with(PhopencodeShapes) {
            assertEquals(
                listOf(6f, 10f, 16f, 24f, 28f),
                listOf(extraSmall, small, medium, large, extraLarge).map(::radius),
            )
        }
    }
}
