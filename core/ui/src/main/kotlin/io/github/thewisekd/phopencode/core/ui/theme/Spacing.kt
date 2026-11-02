package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The spacing scale, on a 4 dp grid after the [xxs] hairline step. */
@Immutable
data class PhopencodeSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    /** The smallest size of anything tappable. */
    val touchTarget: Dp = 48.dp,
)

internal val LocalPhopencodeSpacing = staticCompositionLocalOf { PhopencodeSpacing() }
