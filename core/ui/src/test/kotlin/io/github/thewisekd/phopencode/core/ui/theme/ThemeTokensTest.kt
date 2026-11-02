package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Test
import kotlin.test.assertEquals
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
}
