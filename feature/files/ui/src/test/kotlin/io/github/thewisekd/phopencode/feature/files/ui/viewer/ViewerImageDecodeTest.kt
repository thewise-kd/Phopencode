package io.github.thewisekd.phopencode.feature.files.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.ImageFormat
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val WAIT_MS = 5_000L

/**
 * The preview takes the success path for a valid image and the failure path for bytes no decoder
 * reads, through Coil's default image loader, without a network or a fake decoder.
 *
 * This runs in Robolectric's default graphics mode, which works on every host, the Pi's arm64 Linux
 * included. That mode decodes the real PNG header, so Coil's result has the image's true size, but
 * it draws nothing, so the pixels are checked in `ViewerImageRenderTest` with native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ViewerImageDecodeTest {
    @get:Rule
    val compose = createComposeRule()

    private val decoded = mutableListOf<Pair<Int, Int>>()

    private fun show(bytes: ByteArray) {
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Box(Modifier.size(360.dp, 480.dp)) {
                    ImagePreview(
                        image = ViewerContent.Image(bytes, ImageFormat.Png),
                        name = "logo.png",
                        onDecoded = { width, height -> decoded += width to height },
                    )
                }
            }
        }
    }

    @Test
    fun `a valid image decodes at its size and is shown`() {
        show(PNG_BYTES)

        compose.waitUntil(WAIT_MS) { decoded.isNotEmpty() }

        assertEquals(listOf(2 to 2), decoded)
        compose.awaitTag(ViewerTags.IMAGE_PICTURE)
        assertFalse(compose.hasTag(ViewerTags.IMAGE_FAILED))
        compose.onNodeWithContentDescription("Preview of logo.png").assertExists()
    }

    @Test
    fun `an image that cannot be decoded says so`() {
        show(PNG_SIGNATURE_ONLY)

        compose.awaitTag(ViewerTags.IMAGE_FAILED)
        compose.awaitText("The image could not be shown.")

        assertFalse(compose.hasTag(ViewerTags.IMAGE_PICTURE))
        assertEquals(emptyList(), decoded)
        compose.onNodeWithContentDescription("Preview of logo.png").assertExists()
    }
}
