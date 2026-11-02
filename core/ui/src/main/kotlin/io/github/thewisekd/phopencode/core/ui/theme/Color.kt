package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Tonal palettes: the number is the tone, 0 is black and 100 is white. Screens read
// roles from `MaterialTheme.colorScheme`, never these. Every role is set below, since
// a role left out silently keeps Material's purple baseline value.
private val Teal10 = Color(0xFF002020)
private val Teal20 = Color(0xFF003737)
private val Teal30 = Color(0xFF004F4F)
private val Teal40 = Color(0xFF006A6A)
private val Teal80 = Color(0xFF80D5D4)
private val Teal90 = Color(0xFF9CF1F0)

private val Sage10 = Color(0xFF051F1F)
private val Sage20 = Color(0xFF1B3534)
private val Sage30 = Color(0xFF324B4B)
private val Sage40 = Color(0xFF4A6363)
private val Sage80 = Color(0xFFB0CCCB)
private val Sage90 = Color(0xFFCCE8E7)

private val Blue10 = Color(0xFF041C35)
private val Blue20 = Color(0xFF1C3148)
private val Blue30 = Color(0xFF334860)
private val Blue40 = Color(0xFF4B607C)
private val Blue80 = Color(0xFFB3C8E8)
private val Blue90 = Color(0xFFD3E4FF)

private val Red10 = Color(0xFF410002)
private val Red20 = Color(0xFF690005)
private val Red30 = Color(0xFF93000A)
private val Red40 = Color(0xFFBA1A1A)
private val Red80 = Color(0xFFFFB4AB)
private val Red90 = Color(0xFFFFDAD6)

private val Neutral4 = Color(0xFF090F0F)
private val Neutral6 = Color(0xFF0E1514)
private val Neutral10 = Color(0xFF161D1D)
private val Neutral12 = Color(0xFF1A2121)
private val Neutral17 = Color(0xFF252B2B)
private val Neutral20 = Color(0xFF2B3231)
private val Neutral22 = Color(0xFF303636)
private val Neutral24 = Color(0xFF343A3A)
private val Neutral87 = Color(0xFFD5DBDA)
private val Neutral90 = Color(0xFFDDE4E3)
private val Neutral92 = Color(0xFFE3E9E9)
private val Neutral94 = Color(0xFFE9EFEE)
private val Neutral95 = Color(0xFFECF2F1)
private val Neutral96 = Color(0xFFEFF5F4)
private val Neutral98 = Color(0xFFF4FBFA)
private val Neutral100 = Color(0xFFFFFFFF)

private val NeutralVariant30 = Color(0xFF3F4948)
private val NeutralVariant50 = Color(0xFF6F7979)
private val NeutralVariant60 = Color(0xFF8F9A99)
private val NeutralVariant80 = Color(0xFFBEC9C8)
private val NeutralVariant90 = Color(0xFFDAE5E4)

private val Scrim = Color(0xFF000000)

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = Neutral100,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    inversePrimary = Teal80,
    secondary = Sage40,
    onSecondary = Neutral100,
    secondaryContainer = Sage90,
    onSecondaryContainer = Sage10,
    tertiary = Blue40,
    onTertiary = Neutral100,
    tertiaryContainer = Blue90,
    onTertiaryContainer = Blue10,
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    surfaceTint = Teal40,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    error = Red40,
    onError = Neutral100,
    errorContainer = Red90,
    onErrorContainer = Red10,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
    scrim = Scrim,
    surfaceBright = Neutral98,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = Neutral90,
    surfaceContainerLow = Neutral96,
    surfaceContainerLowest = Neutral100,
    surfaceDim = Neutral87,
    primaryFixed = Teal90,
    primaryFixedDim = Teal80,
    onPrimaryFixed = Teal10,
    onPrimaryFixedVariant = Teal30,
    secondaryFixed = Sage90,
    secondaryFixedDim = Sage80,
    onSecondaryFixed = Sage10,
    onSecondaryFixedVariant = Sage30,
    tertiaryFixed = Blue90,
    tertiaryFixedDim = Blue80,
    onTertiaryFixed = Blue10,
    onTertiaryFixedVariant = Blue30,
)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    inversePrimary = Teal40,
    secondary = Sage80,
    onSecondary = Sage20,
    secondaryContainer = Sage30,
    onSecondaryContainer = Sage90,
    tertiary = Blue80,
    onTertiary = Blue20,
    tertiaryContainer = Blue30,
    onTertiaryContainer = Blue90,
    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceTint = Teal80,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red90,
    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    scrim = Scrim,
    surfaceBright = Neutral24,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,
    surfaceContainerLow = Neutral10,
    surfaceContainerLowest = Neutral4,
    surfaceDim = Neutral6,
    primaryFixed = Teal90,
    primaryFixedDim = Teal80,
    onPrimaryFixed = Teal10,
    onPrimaryFixedVariant = Teal30,
    secondaryFixed = Sage90,
    secondaryFixedDim = Sage80,
    onSecondaryFixed = Sage10,
    onSecondaryFixedVariant = Sage30,
    tertiaryFixed = Blue90,
    tertiaryFixedDim = Blue80,
    onTertiaryFixed = Blue10,
    onTertiaryFixedVariant = Blue30,
)
