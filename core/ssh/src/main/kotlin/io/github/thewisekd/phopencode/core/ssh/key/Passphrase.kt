package io.github.thewisekd.phopencode.core.ssh.key

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal object Passphrase {
    /**
     * UTF-8, as ssh-keygen reads a passphrase, without making a `String`; null for text with no
     * UTF-8 form, such as a lone surrogate. The caller wipes the result.
     *
     * The working buffer is allocated once at the largest size UTF-8 can need, so it never grows
     * and leaves no discarded copy behind; it is wiped on every way out, failures included.
     */
    fun utf8(chars: CharArray): ByteArray? {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val work = ByteArray(chars.size * MAX_BYTES_PER_CHAR)
        try {
            val out = ByteBuffer.wrap(work)
            val input = CharBuffer.wrap(chars)
            if (encoder.encode(input, out, true).isError || input.hasRemaining()) return null
            if (encoder.flush(out).isError) return null
            return work.copyOf(out.position())
        } finally {
            KeyWipes.wipe(KeyWipes.ENCODER, work)
        }
    }

    /** UTF-8 needs at most three bytes per UTF-16 unit; a surrogate pair takes four for two units. */
    private const val MAX_BYTES_PER_CHAR = 3
}
