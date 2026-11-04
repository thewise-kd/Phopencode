package io.github.thewisekd.phopencode.core.ssh.key

import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import java.util.Base64

/**
 * The public half of a key pair: what goes into a server's `authorized_keys`. Public data, so it
 * prints its type and fingerprint. Immutable.
 *
 * @property type The SSH name of the algorithm, for example `ssh-ed25519`.
 * @property comment A label, often `user@host`; empty when there is none. Equality ignores it.
 * @param encoded The key in SSH wire format: the base64-decoded middle field of a `.pub` line.
 * @throws IllegalArgumentException for a key that is not valid for [type], an unsupported type, or a
 *   comment with control characters, which would break the line.
 */
class SshPublicKey(val type: String, encoded: ByteArray, val comment: String = "") {
    private val encoded: ByteArray = encoded.copyOf()

    init {
        requireValidComment(comment)
        val parsed = try {
            PublicBlob.parse(this.encoded)
        } catch (failure: SshKeyException) {
            throw IllegalArgumentException("Not a supported public key")
        }
        require(parsed.type == type) { "Key type does not match the key" }
    }

    /** As `ssh-keygen -l` prints it: `SHA256:` and unpadded base64. */
    val fingerprintSha256: String = HostKey(type, this.encoded).fingerprintSha256

    fun toByteArray(): ByteArray = encoded.copyOf()

    /** `type base64 comment`, one line without a newline, as in `authorized_keys` and `.pub` files. */
    fun authorizedKeysLine(): String {
        val line = "$type ${Base64.getEncoder().encodeToString(encoded)}"
        return if (comment.isEmpty()) line else "$line $comment"
    }

    override fun equals(other: Any?): Boolean =
        other is SshPublicKey && type == other.type && encoded.contentEquals(other.encoded)

    override fun hashCode(): Int = 31 * type.hashCode() + encoded.contentHashCode()

    override fun toString(): String = "SshPublicKey($type, $fingerprintSha256)"

    companion object {
        private val WHITESPACE = Regex("[ \\t]+")
        private const val FIELDS = 3

        /**
         * Reads a `type base64 [comment]` line, as in `.pub` files. Options in front of the type, as
         * `authorized_keys` allows, are not supported.
         *
         * @throws IllegalArgumentException for anything else.
         */
        fun parse(line: String): SshPublicKey {
            val fields = line.trim().split(WHITESPACE, limit = FIELDS)
            require(fields.size >= 2) { "Not a public key line" }
            val encoded = try {
                Base64.getDecoder().decode(fields[1])
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("Public key is not base64")
            }
            return SshPublicKey(fields[0], encoded, fields.getOrElse(2) { "" })
        }

        internal fun requireValidComment(comment: String) {
            require(comment.none { it.isISOControl() }) { "Comment contains control characters" }
        }
    }
}
