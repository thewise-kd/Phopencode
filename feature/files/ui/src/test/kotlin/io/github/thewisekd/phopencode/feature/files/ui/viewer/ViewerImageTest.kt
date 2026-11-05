package io.github.thewisekd.phopencode.feature.files.ui.viewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Size
import io.github.thewisekd.phopencode.feature.files.logic.contract.ImageFormat
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A 4096 x 4096 ARGB bitmap already takes 64 MiB, more than many phones give an app's heap. */
private const val SAFE_SIDE_PX = 4_096

/**
 * The image preview decodes the file's bytes no larger than a fixed bound, so that a huge image,
 * or one whose header claims huge dimensions, cannot exhaust the heap. The decoding itself is
 * Coil's and is checked on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ViewerImageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the preview's decoded size is bounded`() {
        val request = previewRequest(context, ViewerContent.Image(PNG_BYTES, ImageFormat.Png))

        assertEquals(Size(MAX_IMAGE_SIDE_PX, MAX_IMAGE_SIDE_PX), request.maxBitmapSize)
        assertTrue(MAX_IMAGE_SIDE_PX in 1..SAFE_SIDE_PX / 2)
        val width = request.maxBitmapSize.width
        assertTrue(width is Dimension.Pixels && width.px == MAX_IMAGE_SIDE_PX)
    }

    @Test
    fun `the preview reads the bytes the viewer loaded`() {
        val image = ViewerContent.Image(PNG_BYTES, ImageFormat.Png)

        assertSame(image.bytes, previewRequest(context, image).data)
    }
}
