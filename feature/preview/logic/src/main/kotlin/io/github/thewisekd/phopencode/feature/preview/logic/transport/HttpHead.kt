package io.github.thewisekd.phopencode.feature.preview.logic.transport

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Something on a connection that is not HTTP/1.1 the bridge relays. Its message names the [kind]
 * only, never a byte of the request or response.
 */
internal class HttpViolation(val kind: Kind) : IOException("HTTP violation: $kind") {
    enum class Kind {
        Malformed,
        HeadTooLarge,
        TooManyHeaders,
        BodyTooLarge,
        NotImplemented,
        VersionNotSupported,
    }
}

internal class Header(val name: String, val value: String)

internal class RequestHead(val method: String, val target: String, val version: String, val headers: List<Header>)

internal class ResponseHead(val version: String, val status: Int, val reason: String, val headers: List<Header>)

/** The values of every [name] field, case-insensitively, in order. */
internal fun List<Header>.values(name: String): List<String> = filter { it.name.equals(name, ignoreCase = true) }.map { it.value }

/** The comma-separated tokens of every [name] field, trimmed and lower-cased. */
internal fun List<Header>.tokens(name: String): List<String> =
    values(name).flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

/**
 * A buffered reader of one side of a connection: heads are read line by line within limits, and
 * whatever the buffer holds beyond a head is read first by [read], so nothing is lost when a body
 * follows or the connection becomes a raw relay.
 */
internal class HttpInput(private val source: InputStream) {
    private val buffer = ByteArray(BUFFER)
    private var position = 0
    private var limit = 0

    /**
     * How many bytes have come from the stream so far. Compared before and after a request, it
     * tells a kept-alive connection that was dropped unanswered from one that began an answer.
     */
    var received = 0L
        private set

    /** Whether bytes are buffered that no read has taken yet. */
    val hasBuffered: Boolean
        get() = position < limit

    /**
     * Reads one head, up to its empty line, as lines without their line ends; empty lines before it
     * are skipped. Null when the stream ends before any byte of it. [beforeRead] runs before each
     * read from the stream and may throw to end the wait, as a deadline does.
     *
     * @throws HttpViolation for a head longer than [maxBytes], one with more than [maxHeaders] fields,
     *   a stray CR or NUL, or a stream that ends inside it.
     */
    fun readHead(maxBytes: Int, maxHeaders: Int, beforeRead: () -> Unit = {}): List<String>? {
        val lines = ArrayList<String>()
        val line = ByteArrayOutputStream()
        var total = 0
        var started = false
        while (true) {
            if (position == limit && !fill(beforeRead)) {
                if (!started) return null
                throw HttpViolation(HttpViolation.Kind.Malformed)
            }
            val byte = buffer[position++].toInt() and BYTE
            started = true
            if (++total > maxBytes) throw HttpViolation(HttpViolation.Kind.HeadTooLarge)
            if (byte != LF) {
                line.write(byte)
                continue
            }
            val text = lineText(line)
            line.reset()
            if (text.isEmpty()) {
                if (lines.isEmpty()) continue
                return lines
            }
            lines += text
            if (lines.size > maxHeaders + 1) throw HttpViolation(HttpViolation.Kind.TooManyHeaders)
        }
    }

    /** One line of at most [maxBytes], without its line end. */
    fun readLine(maxBytes: Int): String {
        val line = ByteArrayOutputStream()
        while (true) {
            if (position == limit && !fill {}) throw HttpViolation(HttpViolation.Kind.Malformed)
            val byte = buffer[position++].toInt() and BYTE
            if (byte == LF) return lineText(line)
            if (line.size() >= maxBytes) throw HttpViolation(HttpViolation.Kind.Malformed)
            line.write(byte)
        }
    }

    /** Reads what is buffered first, then from the stream; -1 at its end. */
    fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position == limit) {
            val read = source.read(target, offset, length)
            if (read > 0) received += read
            return read
        }
        val count = minOf(length, limit - position)
        System.arraycopy(buffer, position, target, offset, count)
        position += count
        return count
    }

    private fun fill(beforeRead: () -> Unit): Boolean {
        while (true) {
            beforeRead()
            val read = source.read(buffer, 0, buffer.size)
            if (read < 0) return false
            if (read > 0) {
                received += read
                position = 0
                limit = read
                return true
            }
        }
    }

    /** The line as ISO-8859-1, its CR end dropped; a CR or NUL anywhere else is refused. */
    private fun lineText(line: ByteArrayOutputStream): String {
        val bytes = line.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes.last().toInt() == CR) bytes.size - 1 else bytes.size
        for (i in 0 until end) {
            val byte = bytes[i].toInt()
            if (byte == CR || byte == 0) throw HttpViolation(HttpViolation.Kind.Malformed)
        }
        return String(bytes, 0, end, Charsets.ISO_8859_1)
    }

    private companion object {
        const val BUFFER = 16 * 1024
        const val BYTE = 0xff
        const val LF = '\n'.code
        const val CR = '\r'.code
    }
}

/** @throws HttpViolation when [lines] are not an HTTP request head. */
internal fun parseRequest(lines: List<String>): RequestHead {
    val parts = lines.first().split(' ')
    if (parts.size != 3) throw HttpViolation(HttpViolation.Kind.Malformed)
    val (method, target, version) = parts
    if (!isToken(method) || target.isEmpty() || !target.all { it.code in VISIBLE } || !VERSION.matches(version)) {
        throw HttpViolation(HttpViolation.Kind.Malformed)
    }
    return RequestHead(method, target, version, parseHeaders(lines))
}

/** @throws HttpViolation when [lines] are not an HTTP/1.x response head. */
internal fun parseResponse(lines: List<String>): ResponseHead {
    val start = lines.first()
    val match = STATUS_LINE.matchEntire(start) ?: throw HttpViolation(HttpViolation.Kind.Malformed)
    val status = match.groupValues[2].toInt()
    val reason = match.groupValues[3]
    if (status !in STATUSES || reason.any { it.code < SPACE && it != '\t' || it.code == DEL }) {
        throw HttpViolation(HttpViolation.Kind.Malformed)
    }
    return ResponseHead(match.groupValues[1], status, reason, parseHeaders(lines))
}

/** A trailer or header field line; the name must be a token right before the colon. */
internal fun parseHeader(line: String): Header {
    val colon = line.indexOf(':')
    if (colon <= 0) throw HttpViolation(HttpViolation.Kind.Malformed)
    val name = line.substring(0, colon)
    val value = line.substring(colon + 1).trim(' ', '\t')
    if (!isToken(name) || value.any { it.code < SPACE && it != '\t' || it.code == DEL }) {
        throw HttpViolation(HttpViolation.Kind.Malformed)
    }
    return Header(name, value)
}

private fun parseHeaders(lines: List<String>): List<Header> = lines.drop(1).map(::parseHeader)

/** The head's bytes, as ISO-8859-1, ended by its empty line. */
internal fun headBytes(startLine: String, headers: List<Header>): ByteArray {
    val text = StringBuilder(startLine).append("\r\n")
    headers.forEach { text.append(it.name).append(": ").append(it.value).append("\r\n") }
    return text.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1)
}

private fun isToken(text: String): Boolean = text.isNotEmpty() && text.all { it in TOKEN_SYMBOLS || it.isAsciiLetterOrDigit() }

private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"
private const val SPACE = 0x20
private const val DEL = 0x7f
private val VISIBLE = 0x21..0x7e
private val STATUSES = 100..599
private val VERSION = Regex("HTTP/[0-9]\\.[0-9]")
private val STATUS_LINE = Regex("(HTTP/1\\.[01]) ([0-9]{3})(?: (.*))?")
