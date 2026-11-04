package io.github.thewisekd.phopencode.core.ssh.key

import java.math.BigInteger

/**
 * Reads SSH wire format (RFC 4251, section 5). Running out of data or a length that does not fit
 * fails with [SshKeyException.Malformed]. Byte arrays it returns are copies the caller wipes.
 */
internal class WireReader(private val data: ByteArray) {
    private var position = 0

    val remaining: Int get() = data.size - position

    fun uint32(): Long {
        val bytes = take(UINT32)
        var value = 0L
        for (byte in bytes) value = (value shl BYTE_BITS) or (byte.toLong() and BYTE_MASK)
        return value
    }

    fun uint8(): Int = take(1)[0].toInt() and BYTE_MASK.toInt()

    fun skip(count: Int) {
        take(count).fill(0)
    }

    fun string(): ByteArray {
        val length = uint32()
        if (length > remaining) throw SshKeyException.Malformed()
        return take(length.toInt())
    }

    /** A string that names something, such as a cipher. Never use this for secret content. */
    fun name(): String {
        val bytes = string()
        if (bytes.any { it < PRINTABLE_FIRST || it > PRINTABLE_LAST }) throw SshKeyException.Malformed()
        return bytes.decodeToString()
    }

    fun mpint(): BigInteger {
        val bytes = string()
        return try {
            if (bytes.isEmpty()) BigInteger.ZERO else BigInteger(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun requireEnd() {
        if (remaining != 0) throw SshKeyException.Malformed()
    }

    private fun take(count: Int): ByteArray {
        if (count > remaining) throw SshKeyException.Malformed()
        return data.copyOfRange(position, position + count).also { position += count }
    }

    private companion object {
        const val UINT32 = 4
        const val BYTE_BITS = 8
        const val BYTE_MASK = 0xFFL
        const val PRINTABLE_FIRST: Byte = 0x21
        const val PRINTABLE_LAST: Byte = 0x7E
    }
}

/**
 * Writes SSH wire format into a buffer it wipes when it grows and when it is closed, so key
 * material leaves no stray copies behind.
 */
internal class WireWriter : AutoCloseable {
    private var buffer = ByteArray(INITIAL)
    var size = 0
        private set

    fun uint32(value: Long): WireWriter {
        ensure(UINT32)
        for (shift in (UINT32 - 1) downTo 0) buffer[size++] = (value shr (shift * BYTE_BITS)).toByte()
        return this
    }

    fun raw(bytes: ByteArray): WireWriter {
        ensure(bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
        return this
    }

    fun string(bytes: ByteArray): WireWriter = uint32(bytes.size.toLong()).raw(bytes)

    fun string(text: String): WireWriter = string(text.encodeToByteArray())

    fun mpint(value: BigInteger): WireWriter {
        val bytes = if (value.signum() == 0) ByteArray(0) else value.toByteArray()
        return try {
            string(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    override fun close() {
        KeyWipes.wipe(KeyWipes.WRITER, buffer)
        size = 0
    }

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        val grown = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
        KeyWipes.wipe(KeyWipes.WRITER, buffer)
        buffer = grown
    }

    private companion object {
        const val INITIAL = 256
        const val UINT32 = 4
        const val BYTE_BITS = 8
    }
}

/** Builds wire-format bytes in a writer that is wiped afterwards. */
internal inline fun wire(build: WireWriter.() -> Unit): ByteArray = WireWriter().use { it.build(); it.toByteArray() }
