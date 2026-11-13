package io.github.thewisekd.phopencode.feature.preview.logic.transport

import java.io.EOFException
import java.io.OutputStream

/** How a message body is delimited (RFC 9112, section 6). */
internal sealed interface Framing {
    data object None : Framing

    class Length(val bytes: Long) : Framing

    data object Chunked : Framing

    /** Only a response: its body ends when the dev server closes the connection. */
    data object UntilClose : Framing
}

/**
 * A request's body framing. Both `Transfer-Encoding` and `Content-Length`, differing lengths, or a
 * transfer coding other than `chunked` alone are refused, so a request cannot be read one way
 * here and another way by the dev server.
 */
internal fun requestFraming(headers: List<Header>): Framing {
    val codings = headers.tokens("Transfer-Encoding")
    val length = contentLength(headers)
    if (headers.values("Transfer-Encoding").isNotEmpty()) {
        if (length != null) throw HttpViolation(HttpViolation.Kind.Malformed)
        if (codings != listOf(CHUNKED)) throw HttpViolation(HttpViolation.Kind.NotImplemented)
        return Framing.Chunked
    }
    return when (length) {
        null, 0L -> Framing.None
        else -> Framing.Length(length)
    }
}

/** A final response's body framing; [method] is the request's. */
internal fun responseFraming(method: String, response: ResponseHead): Framing {
    val status = response.status
    if (method == "HEAD" || status == NO_CONTENT || status == NOT_MODIFIED || status in INFORMATIONAL) return Framing.None
    val length = contentLength(response.headers)
    if (response.headers.values("Transfer-Encoding").isNotEmpty()) {
        if (length != null) throw HttpViolation(HttpViolation.Kind.Malformed)
        return if (response.headers.tokens("Transfer-Encoding").lastOrNull() == CHUNKED) Framing.Chunked else Framing.UntilClose
    }
    return when (length) {
        null -> Framing.UntilClose
        0L -> Framing.None
        else -> Framing.Length(length)
    }
}

/** The one length every `Content-Length` value names, or null without one. */
private fun contentLength(headers: List<Header>): Long? {
    val values = headers.values("Content-Length").flatMap { it.split(',') }.map { it.trim() }
    if (values.isEmpty()) return null
    if (values.any { it.isEmpty() || it.length > MAX_LENGTH_DIGITS || !it.all { c -> c in '0'..'9' } } || values.toSet().size != 1) {
        throw HttpViolation(HttpViolation.Kind.Malformed)
    }
    return values.first().toLong()
}

/** Counts the body bytes of one message against [limit]; past it, the relay stops before writing them. */
internal class Budget(private val limit: Long) {
    private var spent = 0L

    fun spend(bytes: Long) {
        spent += bytes
        if (spent > limit) throw HttpViolation(HttpViolation.Kind.BodyTooLarge)
    }
}

/**
 * Relays one body from [input] to [output] as [framing] delimits it, flushing as bytes arrive, so a
 * stream of server-sent events is not held back. A chunked body is passed on chunked, without its
 * chunk extensions, and with at most [maxTrailers] trailer fields.
 *
 * @throws HttpViolation for a malformed chunked body or one past [budget].
 * @throws EOFException when the stream ends before a delimited body does.
 */
internal fun relayBody(framing: Framing, input: HttpInput, output: OutputStream, budget: Budget, maxTrailers: Int) {
    val buffer = ByteArray(CHUNK)
    when (framing) {
        Framing.None -> Unit
        is Framing.Length -> {
            budget.spend(framing.bytes)
            copyExactly(framing.bytes, input, output, buffer)
        }
        Framing.UntilClose -> while (true) {
            val read = input.read(buffer, 0, buffer.size)
            if (read < 0) break
            budget.spend(read.toLong())
            output.write(buffer, 0, read)
            output.flush()
        }
        Framing.Chunked -> relayChunked(input, output, budget, maxTrailers, buffer)
    }
    output.flush()
}

private fun relayChunked(input: HttpInput, output: OutputStream, budget: Budget, maxTrailers: Int, buffer: ByteArray) {
    while (true) {
        val size = chunkSize(input.readLine(MAX_CHUNK_LINE))
        if (size == 0L) break
        budget.spend(size)
        output.write("${size.toString(HEX)}\r\n".toByteArray(Charsets.ISO_8859_1))
        copyExactly(size, input, output, buffer)
        if (input.readLine(MAX_CHUNK_LINE).isNotEmpty()) throw HttpViolation(HttpViolation.Kind.Malformed)
        output.write(CRLF)
        output.flush()
    }
    output.write("0\r\n".toByteArray(Charsets.ISO_8859_1))
    var trailers = 0
    while (true) {
        val line = input.readLine(MAX_CHUNK_LINE)
        if (line.isEmpty()) break
        if (++trailers > maxTrailers) throw HttpViolation(HttpViolation.Kind.TooManyHeaders)
        val trailer = parseHeader(line)
        output.write("${trailer.name}: ${trailer.value}\r\n".toByteArray(Charsets.ISO_8859_1))
    }
    output.write(CRLF)
}

/** Hex digits, then optional extensions after `;`, which are dropped. */
private fun chunkSize(line: String): Long {
    val digits = line.substringBefore(';').trimEnd(' ', '\t')
    if (digits.isEmpty() || digits.length > MAX_HEX_DIGITS || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        throw HttpViolation(HttpViolation.Kind.Malformed)
    }
    return digits.toLong(HEX)
}

private fun copyExactly(count: Long, input: HttpInput, output: OutputStream, buffer: ByteArray) {
    var left = count
    while (left > 0) {
        val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
        if (read < 0) throw EOFException("Body ended early")
        output.write(buffer, 0, read)
        output.flush()
        left -= read
    }
}

private const val CHUNKED = "chunked"
private const val CHUNK = 16 * 1024
private const val HEX = 16
private const val MAX_CHUNK_LINE = 4 * 1024
private const val MAX_HEX_DIGITS = 15
private const val MAX_LENGTH_DIGITS = 18
private const val NO_CONTENT = 204
private const val NOT_MODIFIED = 304
private val INFORMATIONAL = 100..199
private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
