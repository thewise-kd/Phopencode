package io.github.thewisekd.phopencode.feature.files.ui.viewer

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.test.assertFalse

private const val WAIT_MS = 5_000L
private const val STRONG = 0.9f
private const val WEAK = 0.1f

/**
 * The viewer draws a decoded image: the 2 x 2 red PNG of [PNG_BYTES] goes through Coil's default
 * loader, with the preview's size bound, and its red is read back from the screen.
 *
 * Native graphics give Robolectric a real `BitmapFactory` and real drawing, which reading pixels
 * needs. Robolectric ships no native runtime for Linux on arm64, so on the Pi this test is skipped
 * (an assumption, not a pass) and only CI, on x86_64, runs it. The success and failure paths
 * themselves run everywhere in `ViewerImageDecodeTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [26, 36], qualifiers = "en")
class ViewerImageRenderTest {
    /** Skips before the compose rule opens its activity, which already needs the native runtime. */
    @get:Rule(order = 0)
    val needsNativeRuntime = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
                val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
                assumeFalse(
                    "Robolectric has no native graphics runtime for Linux on arm64",
                    os.contains("linux") && (arch == "aarch64" || arch == "arm64"),
                )
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    /** Whether the decoded picture is on screen and draws the PNG's red. */
    private fun showsRed(): Boolean {
        if (!compose.hasTag(ViewerTags.IMAGE_PICTURE)) return false
        val pixels = compose.onNodeWithTag(ViewerTags.IMAGE_PICTURE, useUnmergedTree = true).captureToImage().toPixelMap()
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                val color = pixels[x, y]
                if (color.red > STRONG && color.green < WEAK && color.blue < WEAK) return true
            }
        }
        return false
    }

    @Test
    fun `a valid image is drawn`() {
        compose.setViewer(IMAGE)

        compose.waitUntil(WAIT_MS) { showsRed() }

        assertFalse(compose.hasTag(ViewerTags.IMAGE_FAILED))
        compose.onNodeWithContentDescription("Preview of logo.png").assertExists()
    }
}
