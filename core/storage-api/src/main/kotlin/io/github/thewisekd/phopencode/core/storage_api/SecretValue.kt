package io.github.thewisekd.phopencode.core.storage_api

/**
 * A secret held in memory: an SSH password, a private key, a passphrase, the opencode credential or
 * the ntfy topic.
 *
 * Bytes, not `String`: private keys are binary, and a `String` can never be overwritten, while
 * [close] wipes this value's array. That limits how long a secret stays in memory; it does not
 * prove it is gone, because [revealText] and text from an input field are `String`s the runtime
 * keeps until it collects them. Prefer [revealBytes] where the receiving library accepts bytes, and
 * wipe that copy too.
 *
 * Printing never shows the content or its length. `equals` and `hashCode` stay the identity ones
 * inherited from [Any], so that no hash derived from the secret can reach a log and no comparison
 * can stop at the first differing byte; compare revealed bytes where comparing is really needed.
 *
 * Whoever holds a value owns it and closes it, normally with `use`. Thread-safe.
 */
class SecretValue private constructor(private val bytes: ByteArray) : AutoCloseable {
    private var closed = false

    /** A copy of the content, owned by the caller. Fails once this value is closed. */
    @Synchronized
    fun revealBytes(): ByteArray {
        check(!closed) { CLOSED_MESSAGE }
        return bytes.copyOf()
    }

    /**
     * The content as UTF-8 text. The result cannot be wiped, so use it for values that must become
     * a `String` anyway. Content that is not valid UTF-8 throws [CharacterCodingException] rather
     * than quietly giving a different password. Fails once this value is closed.
     */
    @Synchronized
    fun revealText(): String {
        check(!closed) { CLOSED_MESSAGE }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    /** Overwrites the content with zeros. Closing again does nothing. */
    @Synchronized
    override fun close() {
        bytes.fill(0)
        closed = true
    }

    override fun toString(): String = "SecretValue(***)"

    companion object {
        private const val CLOSED_MESSAGE = "SecretValue is closed"

        /** Copies [bytes], so the caller still owns the array and should wipe it. */
        fun ofBytes(bytes: ByteArray): SecretValue = SecretValue(bytes.copyOf())

        /** Stores [text] as UTF-8. The `String` itself stays in memory until it is collected. */
        fun ofText(text: String): SecretValue = SecretValue(text.encodeToByteArray())
    }
}
