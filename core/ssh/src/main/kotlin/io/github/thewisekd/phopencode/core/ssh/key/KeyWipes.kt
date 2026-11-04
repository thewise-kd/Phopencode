package io.github.thewisekd.phopencode.core.ssh.key

/**
 * Wipes buffers that held key material and, for tests only, reports each wiped buffer to
 * [observer], so that a test can see that every exit path wiped what it should.
 */
internal object KeyWipes {
    /** The decoded binary body of an OpenSSH key file. */
    const val BODY = "body"

    /** The UTF-8 bytes of a passphrase, the input of the key derivation. */
    const val KDF_INPUT = "kdf-input"

    /** The private part of an OpenSSH key in the clear, decrypted or about to be encrypted. */
    const val PLAIN = "plain"

    /** The encoder's working buffer while a passphrase becomes UTF-8. */
    const val ENCODER = "encoder"

    /** A newly generated ed25519 seed. */
    const val SEED = "seed"

    /** An ed25519 private key as OpenSSH stores it: seed and public key. */
    const val PRIVATE = "private"

    /** A [WireWriter]'s buffer, on close or when it grows. */
    const val WRITER = "writer"

    /** Base64 text of a key body, copied out of the file before decoding. */
    const val BASE64 = "base64"

    @Volatile
    var observer: ((name: String, buffer: ByteArray) -> Unit)? = null

    fun wipe(name: String, buffer: ByteArray?) {
        if (buffer == null) return
        buffer.fill(0)
        observer?.invoke(name, buffer)
    }
}
