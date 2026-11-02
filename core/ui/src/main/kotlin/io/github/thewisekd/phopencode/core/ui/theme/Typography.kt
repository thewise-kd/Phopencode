package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * The Material 3 type scale on the system font: a bundled font would add APK size
 * and would have to be checked for Turkish coverage. All sizes are in `sp`, so they
 * follow the system font size.
 */
internal val PhopencodeTypography = Typography()

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
