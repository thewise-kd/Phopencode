package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The app's Material 3 theme. It follows the system setting unless [darkTheme] says
 * otherwise, so the theme setting only has to pass a value.
 *
 * Dynamic color stays off on purpose: a wallpaper-derived scheme would replace the
 * tokens whose contrast is tested, next to status colors it knows nothing about.
 */
@Composable
fun PhopencodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPhopencodeStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = PhopencodeTypography,
            content = content,
        )
    }
}

/** The tokens Material has no place for. Colors and text styles live in `MaterialTheme`. */
object PhopencodeTheme {
    val spacing: PhopencodeSpacing
        @Composable @ReadOnlyComposable
        get() = LocalPhopencodeSpacing.current

    val status: PhopencodeStatusColors
        @Composable @ReadOnlyComposable
        get() = LocalPhopencodeStatusColors.current

    val codeTypography: CodeTypography
        @Composable @ReadOnlyComposable
        get() = LocalPhopencodeCodeTypography.current
}
