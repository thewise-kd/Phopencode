package io.github.thewisekd.phopencode.feature.files.logic.viewer

import io.github.thewisekd.phopencode.feature.files.logic.contract.ImageFormat
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * What a file's bytes are, for the viewer (P8.6). The bytes decide; the extension only tells
 * Markdown from other text.
 *
 * 1. An image is known by its signature (PNG, JPEG, GIF, WebP, BMP), whatever the extension.
 * 2. Binary: a zero byte, or many control characters, in the first [SAMPLE_BYTES]; or much of the
 *    file is not valid UTF-8.
 * 3. Anything else is text, decoded as UTF-8 with every invalid sequence replaced by U+FFFD and
 *    marked [ViewerContent.Text.malformed]. A UTF-8 byte order mark is dropped. Decoding never
 *    throws.
 * 4. Text is Markdown when the file name ends in `.md` or `.markdown`, in any case.
 */
internal object ViewerDetection {
    /** How much of the start of a file the control character check looks at. */
    const val SAMPLE_BYTES = 8 * 1024

    /** Above this share of control characters in the sample, the file is binary. */
    private const val CONTROL_SHARE = 0.10

    /**
     * Above this share of bytes in invalid UTF-8 sequences, and at least [MALFORMED_MIN_BYTES] of
     * them, the file is binary. The floor keeps a short text with one bad byte text.
     */
    private const val MALFORMED_SHARE = 0.30

    private const val MALFORMED_MIN_BYTES = 16

    private const val REPLACEMENT = '�'

    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown")

    /** The sizes of the bitmap information headers Windows and OS/2 write after the file header. */
    private val BMP_INFO_HEADER_SIZES = setOf(12, 16, 40, 52, 56, 64, 108, 124)

    fun detect(path: String, bytes: ByteArray): ViewerContent {
        imageFormat(bytes)?.let { return ViewerContent.Image(bytes, it) }
        if (looksBinary(bytes)) return ViewerContent.Binary(bytes.size.toLong())
        val start = if (hasUtf8Bom(bytes)) 3 else 0
        val decoded = decode(bytes, start)
        val malformed = decoded.malformedBytes
        if (malformed >= MALFORMED_MIN_BYTES && malformed > (bytes.size - start) * MALFORMED_SHARE) {
            return ViewerContent.Binary(bytes.size.toLong())
        }
        return ViewerContent.Text(decoded.text, markdown = isMarkdown(path), malformed = decoded.malformedBytes > 0)
    }

    fun isMarkdown(path: String): Boolean {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return dot > 0 && name.substring(dot + 1).lowercase() in MARKDOWN_EXTENSIONS
    }

    // Images

    private fun imageFormat(bytes: ByteArray): ImageFormat? = when {
        bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageFormat.Png
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> ImageFormat.Jpeg
        bytes.startsWith("GIF87a") || bytes.startsWith("GIF89a") -> ImageFormat.Gif
        bytes.size >= 12 && bytes.startsWith("RIFF") && bytes.hasAt(8, "WEBP") -> ImageFormat.Webp
        isBmp(bytes) -> ImageFormat.Bmp
        else -> null
    }

    /** `BM`, then a 12-byte file header, then an information header of a known size. */
    private fun isBmp(bytes: ByteArray): Boolean {
        if (bytes.size < 18 || !bytes.startsWith("BM")) return false
        val infoSize = (bytes[14].toInt() and 0xFF) or
            ((bytes[15].toInt() and 0xFF) shl 8) or
            ((bytes[16].toInt() and 0xFF) shl 16) or
            ((bytes[17].toInt() and 0xFF) shl 24)
        return infoSize in BMP_INFO_HEADER_SIZES
    }

    // Binary

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, SAMPLE_BYTES)
        if (sample == 0) return false
        var control = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (isControl(b)) control++
        }
        return control > sample * CONTROL_SHARE
    }

    /** Control characters text does not use; tab, line ends, form feed and escape are text. */
    private fun isControl(b: Int): Boolean =
        (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0C && b != 0x0D && b != 0x1B) || b == 0x7F

    // Text

    private class Decoded(val text: String, val malformedBytes: Int)

    private fun hasUtf8Bom(bytes: ByteArray): Boolean = bytes.startsWith(0xEF, 0xBB, 0xBF)

    /**
     * UTF-8 from [start], every invalid sequence replaced by one U+FFFD. The decoder reports each
     * error instead of replacing it, so that the bytes in errors are counted.
     */
    private fun decode(bytes: ByteArray, start: Int): Decoded {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, start, bytes.size - start)
        // UTF-8 never gives more chars than bytes, and a replacement takes at least one byte.
        var output = CharBuffer.allocate(bytes.size - start + 1)
        var malformed = 0
        while (true) {
            val result = decoder.decode(input, output, true)
            when {
                result.isError -> {
                    if (!output.hasRemaining()) output = grow(output)
                    output.put(REPLACEMENT)
                    malformed += result.length()
                    input.base().position(input.position() + result.length())
                }
                result.isOverflow -> output = grow(output)
                else -> break
            }
        }
        while (decoder.flush(output).isOverflow) output = grow(output)
        output.base().flip()
        return Decoded(output.toString(), malformed)
    }

    // Old Android lacks Java 9's covariant overrides (CharBuffer.flip(): CharBuffer and so on): call Buffer's own.
    private fun Buffer.base(): Buffer = this

    private fun grow(buffer: CharBuffer): CharBuffer {
        val bigger = CharBuffer.allocate(buffer.capacity() * 2 + 16)
        buffer.base().flip()
        bigger.put(buffer)
        return bigger
    }

    // Bytes

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { this[it] == values[it].toByte() }

    private fun ByteArray.startsWith(ascii: String): Boolean = hasAt(0, ascii)

    private fun ByteArray.hasAt(offset: Int, ascii: String): Boolean =
        size >= offset + ascii.length && ascii.indices.all { this[offset + it] == ascii[it].code.toByte() }
}
