package io.github.thewisekd.phopencode.core.ssh.key

import java.util.Base64

/**
 * The text wrapping of key files: `-----BEGIN …-----`, base64 lines, `-----END …-----`. Body lines
 * never become a `String`; only marker lines, which hold no secret, do.
 */
internal object Armor {
    private const val OPENSSH_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val OPENSSH_END = "-----END OPENSSH PRIVATE KEY-----"
    private const val PEM_BEGIN = "-----BEGIN "
    private const val PEM_BEGIN_TAIL = "PRIVATE KEY-----"
    private const val MARKER_START = '-'
    private const val LINE_LENGTH = 70
    private const val ASCII_LAST = 0x7F

    sealed interface Kind {
        /** @property body The decoded binary body; the receiver wipes it. */
        class OpenSsh(val body: ByteArray) : Kind

        /** Something BouncyCastle's PEM parser should read. */
        data object Pem : Kind
    }

    /** The bytes as characters, if they are all ASCII, as every key file is. */
    fun ascii(bytes: ByteArray): CharArray {
        if (bytes.any { it < 0 }) throw SshKeyException.Malformed()
        return CharArray(bytes.size) { bytes[it].toInt().toChar() }
    }

    /** @throws SshKeyException.Malformed for text that is not a private key file. */
    fun classify(text: CharArray): Kind {
        if (text.any { it.code > ASCII_LAST }) throw SshKeyException.Malformed()
        val lines = lines(text).filter { !it.isEmpty() }
        val first = lines.firstOrNull() ?: throw SshKeyException.Malformed()
        val begin = first.marker(text) ?: throw SshKeyException.Malformed()
        if (begin != OPENSSH_BEGIN) {
            if (begin.startsWith(PEM_BEGIN) && begin.endsWith(PEM_BEGIN_TAIL)) return Kind.Pem
            throw SshKeyException.Malformed()
        }
        val end = lines.indexOfFirst { it.marker(text) == OPENSSH_END }
        if (end != lines.lastIndex || end < 2) throw SshKeyException.Malformed()
        return Kind.OpenSsh(decode(text, lines.subList(1, end)))
    }

    /** The OpenSSH file for a binary body, as ssh-keygen writes it. */
    fun encodeOpenSsh(blob: ByteArray): ByteArray {
        val base64 = Base64.getEncoder().encode(blob)
        try {
            val lineCount = (base64.size + LINE_LENGTH - 1) / LINE_LENGTH
            val begin = (OPENSSH_BEGIN + "\n").encodeToByteArray()
            val end = (OPENSSH_END + "\n").encodeToByteArray()
            val out = ByteArray(begin.size + base64.size + lineCount + end.size)
            var done = false
            try {
                begin.copyInto(out)
                var position = begin.size
                for (line in 0 until lineCount) {
                    val from = line * LINE_LENGTH
                    val to = minOf(from + LINE_LENGTH, base64.size)
                    base64.copyInto(out, position, from, to)
                    position += to - from
                    out[position++] = '\n'.code.toByte()
                }
                end.copyInto(out, position)
                done = true
                return out
            } finally {
                // The caller owns the result once it is returned; until then it is ours to wipe.
                if (!done) out.fill(0)
            }
        } finally {
            base64.fill(0)
        }
    }

    private class Line(val start: Int, val end: Int) {
        fun isEmpty() = start == end

        /** The line as text if it is a marker line, else null. */
        fun marker(text: CharArray): String? =
            if (text[start] == MARKER_START) String(text, start, end - start) else null
    }

    /** Line ranges without surrounding whitespace, for `\n` and `\r\n` endings. */
    private fun lines(text: CharArray): List<Line> {
        val lines = mutableListOf<Line>()
        var start = 0
        while (start <= text.size) {
            var stop = start
            while (stop < text.size && text[stop] != '\n') stop++
            var from = start
            var to = stop
            while (from < to && text[from].isWhitespace()) from++
            while (to > from && text[to - 1].isWhitespace()) to--
            lines += Line(from, to)
            start = stop + 1
        }
        return lines
    }

    private fun decode(text: CharArray, body: List<Line>): ByteArray {
        val size = body.sumOf { it.end - it.start }
        val base64 = ByteArray(size)
        try {
            var position = 0
            for (line in body) {
                for (index in line.start until line.end) {
                    val char = text[index]
                    if (char.isWhitespace() || char == MARKER_START) throw SshKeyException.Malformed()
                    base64[position++] = char.code.toByte()
                }
            }
            return Base64.getDecoder().decode(base64)
        } catch (failure: IllegalArgumentException) {
            throw SshKeyException.Malformed()
        } finally {
            KeyWipes.wipe(KeyWipes.BASE64, base64)
        }
    }
}
