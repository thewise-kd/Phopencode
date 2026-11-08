package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private val Base = Typography()

/**
 * The Material 3 type scale on the system font: a bundled font would add APK size
 * and would have to be checked for Turkish coverage. All sizes are in `sp`, so they
 * follow the system font size.
 *
 * Weight carries the hierarchy: headlines and titles are semi-bold, labels medium and
 * body text regular, so a screen's structure reads at a glance.
 */
internal val PhopencodeTypography = Base.copy(
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontWeight = FontWeight.Normal),
    bodyMedium = Base.bodyMedium.copy(fontWeight = FontWeight.Normal),
    bodySmall = Base.bodySmall.copy(fontWeight = FontWeight.Normal),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.Medium),
)

/** Text that must keep its columns, such as code, diffs and tool output. */
@Immutable
data class CodeTypography(
    val body: TextStyle,
    val small: TextStyle,
)

internal val PhopencodeCodeTypography = CodeTypography(
    body = PhopencodeTypography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    small = PhopencodeTypography.bodySmall.copy(fontFamily = FontFamily.Monospace),
)

internal val LocalPhopencodeCodeTypography = staticCompositionLocalOf { PhopencodeCodeTypography }
