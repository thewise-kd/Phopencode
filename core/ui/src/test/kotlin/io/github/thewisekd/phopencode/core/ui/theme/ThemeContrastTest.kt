package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val AA_TEXT = 4.5
private const val AA_NON_TEXT = 3.0

/** How far a card's surface must stand from the background behind it. */
private const val SURFACE_STEP = 1.15

/** Every color role of the Material 3 scheme this build compiles against. */
private const val COLOR_ROLES = 48

/** WCAG 2.2 relative luminance, written out so the test does not trust a library for it. */
private fun relativeLuminance(color: Color): Double {
    fun linear(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
}

private fun contrast(first: Color, second: Color): Double {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

private data class ContrastPair(val name: String, val foreground: Color, val background: Color, val minimum: Double)

private class Scheme(val name: String, val colors: ColorScheme, val status: PhopencodeStatusColors)

private val schemes = listOf(
    Scheme("light", LightColorScheme, LightStatusColors),
    Scheme("dark", DarkColorScheme, DarkStatusColors),
)

private fun ColorScheme.surfaces() = mapOf(
    "background" to background,
    "surface" to surface,
    "surfaceBright" to surfaceBright,
    "surfaceDim" to surfaceDim,
    "surfaceVariant" to surfaceVariant,
    "surfaceContainerLowest" to surfaceContainerLowest,
    "surfaceContainerLow" to surfaceContainerLow,
    "surfaceContainer" to surfaceContainer,
    "surfaceContainerHigh" to surfaceContainerHigh,
    "surfaceContainerHighest" to surfaceContainerHighest,
)

private fun PhopencodeStatusColors.all() = mapOf(
    "success" to success,
    "warning" to warning,
    "danger" to danger,
    "info" to info,
)

/** Roles drawn directly on any surface, as body text or as colored text such as a link. */
private fun textOnSurfaces(scheme: Scheme): Map<String, Color> = with(scheme.colors) {
    mapOf(
        "onBackground" to onBackground,
        "onSurface" to onSurface,
        "onSurfaceVariant" to onSurfaceVariant,
        "primary" to primary,
        "secondary" to secondary,
        "tertiary" to tertiary,
        "error" to error,
    ) + scheme.status.all().map { (name, status) -> "$name.color" to status.color }
}

private fun pairedRoles(scheme: Scheme): List<ContrastPair> = with(scheme.colors) {
    fun text(name: String, foreground: Color, background: Color) = ContrastPair(name, foreground, background, AA_TEXT)
    listOf(
        text("onPrimary on primary", onPrimary, primary),
        text("onPrimaryContainer on primaryContainer", onPrimaryContainer, primaryContainer),
        text("onSecondary on secondary", onSecondary, secondary),
        text("onSecondaryContainer on secondaryContainer", onSecondaryContainer, secondaryContainer),
        text("onTertiary on tertiary", onTertiary, tertiary),
        text("onTertiaryContainer on tertiaryContainer", onTertiaryContainer, tertiaryContainer),
        text("onError on error", onError, error),
        text("onErrorContainer on errorContainer", onErrorContainer, errorContainer),
        text("inverseOnSurface on inverseSurface", inverseOnSurface, inverseSurface),
        text("inversePrimary on inverseSurface", inversePrimary, inverseSurface),
        text("onPrimaryFixed on primaryFixed", onPrimaryFixed, primaryFixed),
        text("onPrimaryFixed on primaryFixedDim", onPrimaryFixed, primaryFixedDim),
        text("onPrimaryFixedVariant on primaryFixed", onPrimaryFixedVariant, primaryFixed),
        text("onPrimaryFixedVariant on primaryFixedDim", onPrimaryFixedVariant, primaryFixedDim),
        text("onSecondaryFixed on secondaryFixed", onSecondaryFixed, secondaryFixed),
        text("onSecondaryFixed on secondaryFixedDim", onSecondaryFixed, secondaryFixedDim),
        text("onSecondaryFixedVariant on secondaryFixed", onSecondaryFixedVariant, secondaryFixed),
        text("onSecondaryFixedVariant on secondaryFixedDim", onSecondaryFixedVariant, secondaryFixedDim),
        text("onTertiaryFixed on tertiaryFixed", onTertiaryFixed, tertiaryFixed),
        text("onTertiaryFixed on tertiaryFixedDim", onTertiaryFixed, tertiaryFixedDim),
        text("onTertiaryFixedVariant on tertiaryFixed", onTertiaryFixedVariant, tertiaryFixed),
        text("onTertiaryFixedVariant on tertiaryFixedDim", onTertiaryFixedVariant, tertiaryFixedDim),
        // An icon in the brand or accent color inside a soft tinted circle or chip of the same family.
        ContrastPair("primary on primaryContainer", primary, primaryContainer, AA_NON_TEXT),
        ContrastPair("tertiary on tertiaryContainer", tertiary, tertiaryContainer, AA_NON_TEXT),
        // A filled round icon button, such as send, on the field it sits in.
        ContrastPair("primary on surfaceContainerHigh", primary, surfaceContainerHigh, AA_NON_TEXT),
    )
}

private fun statusRoles(scheme: Scheme): List<ContrastPair> = scheme.status.all().flatMap { (name, status) ->
    listOf(
        ContrastPair("$name.onColor on color", status.onColor, status.color, AA_TEXT),
        ContrastPair("$name.onContainer on container", status.onContainer, status.container, AA_TEXT),
        // An icon or border in the accent color, inside a chip of the same status.
        ContrastPair("$name.color on container", status.color, status.container, AA_NON_TEXT),
    )
}

private fun onEverySurface(scheme: Scheme): List<ContrastPair> = scheme.colors.surfaces().flatMap { (surface, background) ->
    textOnSurfaces(scheme).map { (role, foreground) ->
        ContrastPair("$role on $surface", foreground, background, AA_TEXT)
    } + ContrastPair("outline on $surface", scheme.colors.outline, background, AA_NON_TEXT)
}

private fun allPairs(scheme: Scheme) = pairedRoles(scheme) + statusRoles(scheme) + onEverySurface(scheme)

/** The raw value of every color role, read by reflection so that a role added by an upgrade is seen. */
private fun ColorScheme.rolesByName(): Map<String, Long> = ColorScheme::class.java.methods
    .filter { it.name.startsWith("get") && it.returnType == Long::class.java && it.parameterCount == 0 }
    .associate { it.name.removePrefix("get").substringBefore('-') to it.invoke(this) as Long }

class ThemeContrastTest {
    @Test
    fun `the contrast formula matches published WCAG values`() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.001)
        assertEquals(4.48, contrast(Color(0xFF777777), Color.White), 0.01)
        assertEquals(1.0, contrast(Color(0xFF123456), Color(0xFF123456)), 0.001)
    }

    @Test
    fun `every text and color pair meets WCAG AA in both schemes`() {
        val failures = schemes.flatMap { scheme ->
            allPairs(scheme).mapNotNull { pair ->
                val ratio = contrast(pair.foreground, pair.background)
                if (ratio >= pair.minimum) null else "${scheme.name}: ${pair.name} is $ratio, needs ${pair.minimum}"
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `tokens are opaque, so the measured contrast is the one on screen`() {
        val translucent = schemes.flatMap { scheme ->
            allPairs(scheme).filter { it.foreground.alpha != 1f || it.background.alpha != 1f }
                .map { "${scheme.name}: ${it.name}" }
        }
        assertEquals(emptyList(), translucent)
    }

    @Test
    fun `no color role is left at the Material baseline value`() {
        val light = LightColorScheme.rolesByName()
        val dark = DarkColorScheme.rolesByName()
        assertEquals(COLOR_ROLES, light.size, "the reflection must see every role")
        val baselineLight = lightColorScheme().rolesByName()
        val baselineDark = darkColorScheme().rolesByName()

        val leftover = light.filter { (role, value) -> baselineLight[role] == value }.keys.map { "light $it" } +
            dark.filter { (role, value) -> baselineDark[role] == value }.keys.map { "dark $it" }

        assertEquals(emptySet(), leftover.toSet() - SHARED_WITH_BASELINE)
    }

    @Test
    fun `the danger status is the scheme's error color`() {
        schemes.forEach { scheme ->
            assertEquals(scheme.colors.error, scheme.status.danger.color, scheme.name)
            assertEquals(scheme.colors.onError, scheme.status.danger.onColor, scheme.name)
            assertEquals(scheme.colors.errorContainer, scheme.status.danger.container, scheme.name)
            assertEquals(scheme.colors.onErrorContainer, scheme.status.danger.onContainer, scheme.name)
        }
    }

    @Test
    fun `surface containers are layered, so a card stands out from the background`() {
        schemes.forEach { scheme ->
            with(scheme.colors) {
                val ladder = listOf(
                    surfaceContainerLowest,
                    surfaceContainerLow,
                    surfaceContainer,
                    surfaceContainerHigh,
                    surfaceContainerHighest,
                ).map(::relativeLuminance)
                val expected = if (scheme.name == "dark") ladder.sorted() else ladder.sortedDescending()
                assertEquals(expected, ladder, "${scheme.name}: the containers must step away from the background")
                assertEquals(ladder.size, ladder.distinct().size, "${scheme.name}: two containers are equal")
                val card = contrast(surfaceContainerHighest, background)
                assertTrue(card >= SURFACE_STEP, "${scheme.name}: a card is only $card from the background")
            }
        }
    }

    @Test
    fun `neutrals carry a soft teal tint and the dark background is deep ink, not black`() {
        schemes.forEach { scheme ->
            with(scheme.colors.background) {
                assertTrue(green > red && blue > red, "${scheme.name}: background $this is not teal-tinted")
            }
        }
        assertTrue(DarkColorScheme.background != Color.Black)
        assertTrue(relativeLuminance(DarkColorScheme.background) > relativeLuminance(Color(0xFF050505)))
    }

    @Test
    fun `the accent is warm, next to a teal brand`() {
        schemes.forEach { scheme ->
            with(scheme.colors) {
                assertTrue(tertiary.red > tertiary.blue, "${scheme.name}: tertiary $tertiary is not warm")
                assertTrue(primary.green > primary.red && primary.blue > primary.red, "${scheme.name}: primary is not teal")
            }
        }
    }

    @Test
    fun `dark surfaces are darker than light ones`() {
        assertTrue(relativeLuminance(DarkColorScheme.background) < relativeLuminance(LightColorScheme.background))
    }

    private companion object {
        /** Values that match Material's on purpose: pure white and a black scrim. */
        val SHARED_WITH_BASELINE = setOf(
            "light OnPrimary",
            "light OnSecondary",
            "light OnTertiary",
            "light OnError",
            "light SurfaceContainerLowest",
            "light Scrim",
            "dark Scrim",
        )
    }
}
