package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tells whether a password read again later is the one a server accepted, without keeping that
 * password: an HMAC-SHA256 of it under a random key made for this check alone. A connection keeps
 * one in memory for its life, so that a secret changed in the store since the connect, which the
 * server never accepted, is not handed out as the server's password.
 *
 * Neither the key nor the digest leaves this object, and it prints neither. `equals` stays the
 * identity one.
 */
internal class PasswordCheck private constructor(private val key: ByteArray, private val digest: ByteArray) {
    /** Whether [password] is the accepted one; compares in constant time. */
    fun matches(password: SecretValue): Boolean = MessageDigest.isEqual(digest, hmac(key, password))

    override fun toString(): String = "PasswordCheck(***)"

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private const val KEY_BYTES = 32
        private val random = SecureRandom()

        /** A check for [password], the one the server accepted; [password] stays its caller's. */
        fun of(password: SecretValue): PasswordCheck {
            val key = ByteArray(KEY_BYTES).also(random::nextBytes)
            return PasswordCheck(key, hmac(key, password))
        }

        private fun hmac(key: ByteArray, password: SecretValue): ByteArray {
            val bytes = password.revealBytes()
            try {
                val mac = Mac.getInstance(ALGORITHM)
                mac.init(SecretKeySpec(key, ALGORITHM))
                return mac.doFinal(bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }
}
