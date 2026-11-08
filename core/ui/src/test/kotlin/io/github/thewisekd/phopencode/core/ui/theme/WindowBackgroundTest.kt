package io.github.thewisekd.phopencode.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's window, drawn before Compose, must have the theme's background, or a
 * screen opens with a flash of another color. Unit tests run in the module's folder,
 * so the app's theme files are two levels up.
 */
class WindowBackgroundTest {
    private fun windowColors(folder: String): Map<String, Color> {
        val file = File("../../app/src/main/res/$folder/themes.xml")
        assertTrue(file.isFile, "${file.absolutePath} is missing")
        val items = Regex("""<item name="android:(windowBackground|colorBackground)">#([0-9A-Fa-f]{8})</item>""")
            .findAll(file.readText())
            .associate { it.groupValues[1] to Color(it.groupValues[2].toLong(16)) }
        assertEquals(setOf("windowBackground", "colorBackground"), items.keys, "$folder sets both backgrounds")
        return items
    }

    @Test
    fun `the light window matches the light background`() {
        windowColors("values").forEach { (name, color) -> assertEquals(LightColorScheme.background, color, name) }
    }

    @Test
    fun `the dark window matches the dark background`() {
        windowColors("values-night").forEach { (name, color) -> assertEquals(DarkColorScheme.background, color, name) }
    }
}
