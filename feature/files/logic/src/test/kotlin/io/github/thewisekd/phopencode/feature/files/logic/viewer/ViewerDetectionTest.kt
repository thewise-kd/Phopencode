package io.github.thewisekd.phopencode.feature.files.logic.viewer

import io.github.thewisekd.phopencode.feature.files.logic.contract.ImageFormat
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private val PNG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52)
private val JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16, 0x4A, 0x46, 0x49, 0x46, 0)
private val GIF = "GIF89a".toByteArray() + bytes(1, 0, 1, 0, 0x80, 0, 0)
private val WEBP = "RIFF".toByteArray() + bytes(26, 0, 0, 0) + "WEBPVP8 ".toByteArray()

/** A file header of 14 bytes, then a 40-byte `BITMAPINFOHEADER`. */
private val BMP = "BM".toByteArray() + ByteArray(12) + bytes(40, 0, 0, 0) + ByteArray(36)

class ViewerDetectionTest {
    @Test
    fun `images are known by their bytes`() {
        val cases = mapOf(
            PNG to ImageFormat.Png,
            JPEG to ImageFormat.Jpeg,
            GIF to ImageFormat.Gif,
            WEBP to ImageFormat.Webp,
            BMP to ImageFormat.Bmp,
        )
        for ((content, format) in cases) {
            val detected = ViewerDetection.detect("picture.bin", content)
            assertEquals(ViewerContent.Image(content, format), detected, "$format")
        }
    }

    @Test
    fun `an image is an image whatever its extension says`() {
        assertEquals(ViewerContent.Image(PNG, ImageFormat.Png), ViewerDetection.detect("notes.md", PNG))
        assertEquals(ViewerContent.Image(JPEG, ImageFormat.Jpeg), ViewerDetection.detect("main.kt", JPEG))
    }

    @Test
    fun `an image extension on other bytes is not trusted`() {
        val text = ViewerDetection.detect("photo.png", "just some text\n".toByteArray())
        assertEquals(ViewerContent.Text("just some text\n"), text)

        val binary = bytes(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0, 0, 0, 0, 0)
        assertEquals(ViewerContent.Binary(binary.size.toLong()), ViewerDetection.detect("photo.jpg", binary))
    }

    @Test
    fun `a text extension on binary bytes is binary`() {
        val content = ByteArray(64) { (it * 7).toByte() }.also { it[3] = 0 }
        assertEquals(ViewerContent.Binary(64), ViewerDetection.detect("README.md", content))
        assertEquals(ViewerContent.Binary(64), ViewerDetection.detect("main.kt", content))
    }

    @Test
    fun `bytes full of control characters are binary even without a zero byte`() {
        val content = ByteArray(200) { (1 + it % 8).toByte() }
        assertEquals(ViewerContent.Binary(200), ViewerDetection.detect("data.txt", content))
    }

    @Test
    fun `a truncated image header is not an image`() {
        val content = PNG.copyOf(4)
        assertFalse(ViewerDetection.detect("a.png", content) is ViewerContent.Image)
        assertEquals(ViewerContent.Text("BM"), ViewerDetection.detect("a.bmp", "BM".toByteArray()))
    }

    @Test
    fun `text that starts with BM is not a bitmap`() {
        val content = "BMW is a car maker, and this line is long enough for a header.\n".toByteArray()
        assertEquals(ViewerContent.Text(content.decodeToString()), ViewerDetection.detect("cars.txt", content))
    }

    @Test
    fun `utf-8 text decodes cleanly`() {
        val text = "Merhaba dünya: çğıöşü ÇĞİÖŞÜ 😀\n\ttabs and\r\nline ends\u001b[0m\n"
        assertEquals(ViewerContent.Text(text), ViewerDetection.detect("hello.kt", text.toByteArray()))
    }

    @Test
    fun `an empty file is empty text`() {
        assertEquals(ViewerContent.Text(""), ViewerDetection.detect("empty.kt", ByteArray(0)))
        assertEquals(ViewerContent.Text("", markdown = true), ViewerDetection.detect("empty.md", ByteArray(0)))
    }

    @Test
    fun `the utf-8 byte order mark is dropped`() {
        val content = bytes(0xEF, 0xBB, 0xBF) + "text".toByteArray()
        assertEquals(ViewerContent.Text("text"), ViewerDetection.detect("bom.txt", content))
    }

    @Test
    fun `markdown is known by its extension when the bytes are text`() {
        val content = "# Title\n\nBody\n".toByteArray()
        for (name in listOf("README.md", "notes.markdown", "UPPER.MD", "dir.md/file.Markdown")) {
            assertEquals(ViewerContent.Text("# Title\n\nBody\n", markdown = true), ViewerDetection.detect(name, content))
        }
        for (name in listOf("README", "notes.mdx", "md", "x.md.txt", "folder.md/file.txt")) {
            assertEquals(ViewerContent.Text("# Title\n\nBody\n"), ViewerDetection.detect(name, content), name)
        }
    }

    @Test
    fun `invalid utf-8 is replaced and marked, never thrown`() {
        val content = "café ".toByteArray(Charsets.ISO_8859_1) + "and more plain text after it\n".toByteArray()
        val detected = ViewerDetection.detect("latin1.txt", content)
        assertEquals(
            ViewerContent.Text("caf� and more plain text after it\n", malformed = true),
            detected,
        )
    }

    @Test
    fun `a sequence cut at the end of the file is replaced`() {
        val content = "ok ".toByteArray() + bytes(0xE2, 0x82)
        val detected = assertIs<ViewerContent.Text>(ViewerDetection.detect("cut.txt", content))
        assertTrue(detected.malformed)
        assertTrue(detected.text.startsWith("ok "))
        assertTrue(detected.text.endsWith("�"))
    }

    @Test
    fun `overlong and surrogate encodings are malformed`() {
        val overlong = "a".toByteArray() + bytes(0xC0, 0xAF) + "b".toByteArray()
        assertTrue(assertIs<ViewerContent.Text>(ViewerDetection.detect("a.txt", overlong)).malformed)
        val surrogate = "a".toByteArray() + bytes(0xED, 0xA0, 0x80) + "b".toByteArray()
        assertTrue(assertIs<ViewerContent.Text>(ViewerDetection.detect("a.txt", surrogate)).malformed)
    }

    @Test
    fun `mostly invalid utf-8 is binary`() {
        val content = ByteArray(300) { (0x80 + it % 0x40).toByte() }
        assertEquals(ViewerContent.Binary(300), ViewerDetection.detect("noise.txt", content))
    }

    @Test
    fun `every byte pattern is handled without an exception`() {
        val random = java.util.Random(8_6)
        repeat(500) { round ->
            val content = ByteArray(random.nextInt(600)).also(random::nextBytes)
            val detected = ViewerDetection.detect("f$round.md", content)
            if (detected is ViewerContent.Binary) assertEquals(content.size.toLong(), detected.sizeBytes)
        }
    }
}
