package io.github.thewisekd.phopencode.core.git.parse

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * A path exactly as git printed it, relative to the repository root.
 *
 * Git stores file names as bytes, which need not be valid UTF-8, so the bytes are the identity:
 * [equals] and [hashCode] compare bytes, and [toByteArray] gives them back unchanged, for example to
 * pass the path to a later git command. [text] is for display only: invalid UTF-8 sequences become
 * U+FFFD, so two different paths can share one [text].
 *
 * [toString] never shows the path, only its length.
 */
public class GitPath(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    /** The path decoded as UTF-8 for display; invalid sequences become U+FFFD. */
    public val text: String by lazy { decodeUtf8(this.bytes, 0, this.bytes.size) }

    /** Whether the bytes are valid UTF-8, that is whether [text] round-trips to the same bytes. */
    public val isValidUtf8: Boolean by lazy { isValidUtf8(this.bytes) }

    /** The number of bytes in the path. */
    public val size: Int get() = bytes.size

    /** A copy of the raw bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is GitPath && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "GitPath(${bytes.size} bytes)"

    public companion object {
        /** A path from text, encoded as UTF-8. */
        public fun of(text: String): GitPath = GitPath(text.toByteArray(Charsets.UTF_8))
    }
}

/** Decodes UTF-8, replacing invalid sequences with U+FFFD. */
internal fun decodeUtf8(bytes: ByteArray, from: Int, to: Int): String = String(bytes, from, to - from, Charsets.UTF_8)

internal fun isValidUtf8(bytes: ByteArray): Boolean {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }
}
