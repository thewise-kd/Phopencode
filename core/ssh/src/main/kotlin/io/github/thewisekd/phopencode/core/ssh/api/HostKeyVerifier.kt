package io.github.thewisekd.phopencode.core.ssh.api

import java.security.MessageDigest
import kotlin.io.encoding.Base64

/**
 * Decides whether to trust the key a server presents. It has no default on purpose: every connect
 * must say how it trusts a host.
 *
 * The expected policy: a known key is accepted, an unknown key asks the user, and a key that differs
 * from the stored one is rejected without offering to continue.
 */
fun interface HostKeyVerifier {
    /**
     * May suspend while the user reads the fingerprint. The server does not wait forever during
     * the handshake (OpenSSH drops it after two minutes by default), so a verifier may instead
     * reject at once, ask with the key from [SshException.HostKeyRejected], store it, and connect
     * again. The key is then checked again on the new connection.
     */
    suspend fun verify(target: SshTarget, key: HostKey): HostKeyDecision
}

enum class HostKeyDecision {
    Accept,
    Reject,
}

/**
 * A server's public key. Immutable, because an approved key must stay the key that was approved.
 * Public data, so it prints in full.
 *
 * @property type The SSH name of the algorithm, for example `ssh-ed25519`.
 * @param encoded The public key in SSH wire format (RFC 4253, section 6.6): the base64-decoded
 *   middle field of a `.pub` line. Two libraries produce the same bytes for the same key.
 */
class HostKey(val type: String, encoded: ByteArray) {
    private val encoded: ByteArray = encoded.copyOf()

    init {
        require(type.isNotBlank()) { "Key type is blank" }
        require(encoded.isNotEmpty()) { "Key is empty" }
    }

    /** As OpenSSH prints it: `SHA256:` and unpadded base64. */
    val fingerprintSha256: String = fingerprint(this.encoded)

    fun toByteArray(): ByteArray = encoded.copyOf()

    override fun equals(other: Any?): Boolean =
        other is HostKey && type == other.type && encoded.contentEquals(other.encoded)

    override fun hashCode(): Int = 31 * type.hashCode() + encoded.contentHashCode()

    override fun toString(): String = "HostKey($type, $fingerprintSha256)"

    private companion object {
        val BASE64 = Base64.withPadding(Base64.PaddingOption.ABSENT)

        fun fingerprint(encoded: ByteArray): String =
            "SHA256:" + BASE64.encode(MessageDigest.getInstance("SHA-256").digest(encoded))
    }
}
