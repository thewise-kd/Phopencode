package io.github.thewisekd.phopencode.core.remote.start

import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/** Makes passwords for `opencode serve`. */
object ServerPassword {
    private const val RANDOM_BYTES = 32
    private val random = SecureRandom()

    /**
     * 256 random bits as URL-safe Base64 without padding: 43 characters, one line, nothing a shell
     * or HTTP Basic would treat specially. The caller owns and closes the result.
     */
    fun generate(): SecretValue {
        val raw = ByteArray(RANDOM_BYTES).also(random::nextBytes)
        val encoded = try {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encodeToByteArray(raw)
        } finally {
            raw.fill(0)
        }
        try {
            return SecretValue.ofBytes(encoded)
        } finally {
            encoded.fill(0)
        }
    }
}
