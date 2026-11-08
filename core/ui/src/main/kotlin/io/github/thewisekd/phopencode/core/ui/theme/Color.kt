package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Tonal palettes: the number is the tone, 0 is black and 100 is white. Screens read
// roles from `MaterialTheme.colorScheme`, never these. Every role is set below, since
// a role left out silently keeps Material's purple baseline value.
//
// The brand is the logo's teal (Teal20 and Teal90). Coral is the warm accent, the
// complement of teal, for the few things that should catch the eye. Neutrals carry a
// soft teal tint, and the dark theme sits on deep ink rather than black.

// Brand teal, the logo's family.
private val Teal10 = Color(0xFF002020)
private val Teal20 = Color(0xFF003737)
private val Teal30 = Color(0xFF004F4F)
private val Teal40 = Color(0xFF006A69)
private val Teal80 = Color(0xFF7FD6D4)
private val Teal90 = Color(0xFF9CF1F0)

// Muted sea-glass teal, for secondary controls and tonal buttons.
private val Sage10 = Color(0xFF051F1F)
private val Sage20 = Color(0xFF1B3534)
private val Sage30 = Color(0xFF324B4A)
private val Sage40 = Color(0xFF4A6362)
private val Sage80 = Color(0xFFB1CCCA)
private val Sage90 = Color(0xFFCDE8E6)

// Warm coral accent.
private val Coral10 = Color(0xFF3A0B00)
private val Coral20 = Color(0xFF5C1A04)
private val Coral30 = Color(0xFF7D2D0F)
private val Coral40 = Color(0xFF9C4323)
private val Coral80 = Color(0xFFFFB59C)
private val Coral90 = Color(0xFFFFDBCF)

private val Red10 = Color(0xFF410002)
private val Red20 = Color(0xFF690005)
// A deep, muted red for the dark error container, so a dark error card does not glare.
private val Red25 = Color(0xFF6E1A16)
private val Red40 = Color(0xFFBA1A1A)
private val Red80 = Color(0xFFFFB4AB)
private val Red90 = Color(0xFFFFDAD6)

// Teal-tinted light neutrals.
private val Neutral10 = Color(0xFF141D1D)
private val Neutral20 = Color(0xFF293232)
private val Neutral86 = Color(0xFFD3DCDB)
private val Neutral89 = Color(0xFFD7E5E3)
private val Neutral90 = Color(0xFFDCE5E4)
private val Neutral91 = Color(0xFFDFEBE9)
private val Neutral93 = Color(0xFFE6F0EE)
private val Neutral95 = Color(0xFFEAF2F1)
private val Neutral96 = Color(0xFFEDF5F3)
private val Neutral98 = Color(0xFFF4FAF9)
private val Neutral100 = Color(0xFFFFFFFF)

// Deep ink: dark neutrals leaning blue-teal, so the dark theme is never flat black.
private val Ink4 = Color(0xFF061012)
private val Ink6 = Color(0xFF0B1517)
private val Ink11 = Color(0xFF131E20)
private val Ink13 = Color(0xFF172325)
private val Ink16 = Color(0xFF1F2B2D)
private val Ink21 = Color(0xFF2A3638)
private val Ink24 = Color(0xFF303B3D)

private val NeutralVariant30 = Color(0xFF3E4948)
private val NeutralVariant50 = Color(0xFF6E7A79)
private val NeutralVariant60 = Color(0xFF8C9897)
private val NeutralVariant80 = Color(0xFFBDC9C8)
private val NeutralVariant82 = Color(0xFFBEC9C7)
private val NeutralVariant90 = Color(0xFFDAE5E3)

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
    tertiary = Coral40,
    onTertiary = Neutral100,
    tertiaryContainer = Coral90,
    onTertiaryContainer = Coral10,
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
    outlineVariant = NeutralVariant82,
    scrim = Scrim,
    surfaceBright = Neutral98,
    surfaceContainer = Neutral93,
    surfaceContainerHigh = Neutral91,
    surfaceContainerHighest = Neutral89,
    surfaceContainerLow = Neutral96,
    surfaceContainerLowest = Neutral100,
    surfaceDim = Neutral86,
    primaryFixed = Teal90,
    primaryFixedDim = Teal80,
    onPrimaryFixed = Teal10,
    onPrimaryFixedVariant = Teal30,
    secondaryFixed = Sage90,
    secondaryFixedDim = Sage80,
    onSecondaryFixed = Sage10,
    onSecondaryFixedVariant = Sage30,
    tertiaryFixed = Coral90,
    tertiaryFixedDim = Coral80,
    onTertiaryFixed = Coral10,
    onTertiaryFixedVariant = Coral30,
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
    tertiary = Coral80,
    onTertiary = Coral20,
    tertiaryContainer = Coral30,
    onTertiaryContainer = Coral90,
    background = Ink6,
    onBackground = Neutral90,
    surface = Ink6,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceTint = Teal80,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    error = Red80,
    onError = Red20,
    errorContainer = Red25,
    onErrorContainer = Red90,
    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    scrim = Scrim,
    surfaceBright = Ink24,
    surfaceContainer = Ink13,
    surfaceContainerHigh = Ink16,
    surfaceContainerHighest = Ink21,
    surfaceContainerLow = Ink11,
    surfaceContainerLowest = Ink4,
    surfaceDim = Ink6,
    primaryFixed = Teal90,
    primaryFixedDim = Teal80,
    onPrimaryFixed = Teal10,
    onPrimaryFixedVariant = Teal30,
    secondaryFixed = Sage90,
    secondaryFixedDim = Sage80,
    onSecondaryFixed = Sage10,
    onSecondaryFixedVariant = Sage30,
    tertiaryFixed = Coral90,
    tertiaryFixedDim = Coral80,
    onTertiaryFixed = Coral10,
    onTertiaryFixedVariant = Coral30,
)
