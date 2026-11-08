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

// Tuned to the teal and coral scheme: green leans cool and amber leans golden, so
// neither reads as the brand teal or the coral accent, and every container is soft.
internal val LightStatusColors = PhopencodeStatusColors(
    success = StatusColor(
        color = Color(0xFF1B6B3A),
        onColor = Color(0xFFFFFFFF),
        container = Color(0xFFB9F0C6),
        onContainer = Color(0xFF00210C),
    ),
    warning = StatusColor(
        color = Color(0xFF7A5900),
        onColor = Color(0xFFFFFFFF),
        container = Color(0xFFFFDF9E),
        onContainer = Color(0xFF261900),
    ),
    danger = LightColorScheme.run { StatusColor(error, onError, errorContainer, onErrorContainer) },
    info = StatusColor(
        color = Color(0xFF00629D),
        onColor = Color(0xFFFFFFFF),
        container = Color(0xFFD0E4FF),
        onContainer = Color(0xFF001D34),
    ),
)

internal val DarkStatusColors = PhopencodeStatusColors(
    success = StatusColor(
        color = Color(0xFF80D99B),
        onColor = Color(0xFF00391A),
        container = Color(0xFF00522A),
        onContainer = Color(0xFF9DF6B5),
    ),
    warning = StatusColor(
        color = Color(0xFFF2C14C),
        onColor = Color(0xFF3F2E00),
        container = Color(0xFF5A4300),
        onContainer = Color(0xFFFFDF9E),
    ),
    danger = DarkColorScheme.run { StatusColor(error, onError, errorContainer, onErrorContainer) },
    info = StatusColor(
        color = Color(0xFF9ACBFF),
        onColor = Color(0xFF003355),
        container = Color(0xFF004A77),
        onContainer = Color(0xFFD0E4FF),
    ),
)

/** No default: outside the theme there is no way to know which scheme a color must fit. */
internal val LocalPhopencodeStatusColors = staticCompositionLocalOf<PhopencodeStatusColors> {
    error("PhopencodeTheme.status was read outside PhopencodeTheme")
}
