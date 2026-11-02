package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** One status, in the four roles Material gives a color: draw `onX` on top of `x`. */
@Immutable
data class StatusColor(
    val color: Color,
    val onColor: Color,
    val container: Color,
    val onContainer: Color,
)

/**
 * The colors that carry a meaning, for chips, banners and warnings. [danger] is the
 * scheme's `error` color, so a red warning and a Material error look the same.
 */
@Immutable
data class PhopencodeStatusColors(
    val success: StatusColor,
    val warning: StatusColor,
    val danger: StatusColor,
    val info: StatusColor,
)

internal val LightStatusColors = PhopencodeStatusColors(
    success = StatusColor(
        color = Color(0xFF1B6B3A),
        onColor = Color(0xFFFFFFFF),
        container = Color(0xFFB7F1C2),
        onContainer = Color(0xFF00210C),
    ),
    warning = StatusColor(
        color = Color(0xFF7A5900),
        onColor = Color(0xFFFFFFFF),
        container = Color(0xFFFFDEA1),
        onContainer = Color(0xFF261900),
    ),
    danger = LightColorScheme.run { StatusColor(error, onError, errorContainer, onErrorContainer) },
    info = StatusColor(
        color = Color(0xFF00639B),
        onColor = Color(0xFFFFFFFF),
        container = Color(0xFFCEE5FF),
        onContainer = Color(0xFF001D33),
    ),
)

internal val DarkStatusColors = PhopencodeStatusColors(
    success = StatusColor(
        color = Color(0xFF7DDA9A),
        onColor = Color(0xFF00391A),
        container = Color(0xFF00522A),
        onContainer = Color(0xFF99F7B4),
    ),
    warning = StatusColor(
        color = Color(0xFFF5BF48),
        onColor = Color(0xFF402D00),
        container = Color(0xFF5C4200),
        onContainer = Color(0xFFFFDEA1),
    ),
    danger = DarkColorScheme.run { StatusColor(error, onError, errorContainer, onErrorContainer) },
    info = StatusColor(
        color = Color(0xFF96CCFF),
        onColor = Color(0xFF003353),
        container = Color(0xFF004A76),
        onContainer = Color(0xFFCEE5FF),
    ),
)

/** No default: outside the theme there is no way to know which scheme a color must fit. */
internal val LocalPhopencodeStatusColors = staticCompositionLocalOf<PhopencodeStatusColors> {
    error("PhopencodeTheme.status was read outside PhopencodeTheme")
}
