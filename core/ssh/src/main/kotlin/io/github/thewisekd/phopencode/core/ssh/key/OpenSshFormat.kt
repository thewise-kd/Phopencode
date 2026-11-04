package io.github.thewisekd.phopencode.core.ssh.key

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/**
 * The binary body of an OpenSSH private key file (`PROTOCOL.key` in OpenSSH's sources): the
 * `openssh-key-v1` format ssh-keygen has written by default since OpenSSH 7.8.
 *
 * Every intermediate array that holds key material or a passphrase is wiped before returning.
 */
internal object OpenSshFormat {
    private val MAGIC = "openssh-key-v1\u0000".encodeToByteArray()
    private const val NONE = "none"
    private const val BCRYPT = "bcrypt"
    private const val WRITE_CIPHER = "aes256-ctr"
    private const val SALT_SIZE = 16
    private const val UNENCRYPTED_BLOCK = 8
    private const val ED25519_PRIVATE_SIZE = 64

    /** ssh-keygen's default for `-a`. */
    const val DEFAULT_ROUNDS = 16

    private class CipherSpec(val transformation: String, val keySize: Int, val ivSize: Int, val blockSize: Int)

    private val CIPHERS = mapOf(
        NONE to CipherSpec("", 0, 0, UNENCRYPTED_BLOCK),
        "aes128-ctr" to CipherSpec("AES/CTR/NoPadding", 16, 16, 16),
        "aes192-ctr" to CipherSpec("AES/CTR/NoPadding", 24, 16, 16),
        "aes256-ctr" to CipherSpec("AES/CTR/NoPadding", 32, 16, 16),
        "aes128-cbc" to CipherSpec("AES/CBC/NoPadding", 16, 16, 16),
        "aes192-cbc" to CipherSpec("AES/CBC/NoPadding", 24, 16, 16),
        "aes256-cbc" to CipherSpec("AES/CBC/NoPadding", 32, 16, 16),
    )

    // Writing

    /**
     * An ed25519 key as an `openssh-key-v1` body, encrypted with bcrypt and aes256-ctr as ssh-keygen
     * does when [passphrase] is given.
     */
    fun writeEd25519(
        seed: ByteArray,
        publicKey: ByteArray,
        comment: String,
        passphrase: ByteArray?,
        random: SecureRandom,
        rounds: Int,
    ): ByteArray {
        val cipherName = if (passphrase == null) NONE else WRITE_CIPHER
        val spec = CIPHERS.getValue(cipherName)
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val plain = WireWriter().use { section ->
            val privateBytes = seed + publicKey
            try {
                val check = random.nextInt().toLong() and UINT32_MASK
                section.uint32(check).uint32(check)
                    .string(PublicBlob.ED25519).string(publicKey).string(privateBytes).string(comment)
                var pad = 1
                while (section.size % spec.blockSize != 0) section.raw(byteArrayOf((pad++).toByte()))
                section.toByteArray()
            } finally {
                KeyWipes.wipe(KeyWipes.PRIVATE, privateBytes)
            }
        }
        try {
            val encrypted = if (passphrase == null) plain else crypt(Cipher.ENCRYPT_MODE, spec, passphrase, salt, rounds, plain)
            try {
                return wire {
                    raw(MAGIC)
                    string(cipherName)
                    string(if (passphrase == null) NONE else BCRYPT)
                    string(if (passphrase == null) ByteArray(0) else wire { string(salt).uint32(rounds.toLong()) })
                    uint32(1)
                    string(PublicBlob.ed25519(publicKey))
                    string(encrypted)
                }
            } finally {
                encrypted.fill(0)
            }
        } finally {
            KeyWipes.wipe(KeyWipes.PLAIN, plain)
        }
    }

    // Reading

    /**
     * @param passphrase Null or empty for none. Encoded only if the key is encrypted, so text with no
     *   UTF-8 form fails only there, with [SshKeyException.WrongPassphrase].
     * @return The public key, with the comment stored in the private part, and whether it was encrypted.
     */
    fun read(blob: ByteArray, passphrase: CharArray?): Pair<SshPublicKey, Boolean> {
        if (blob.size < MAGIC.size || !MessageDigest.isEqual(blob.copyOf(MAGIC.size), MAGIC)) {
            throw SshKeyException.Malformed()
        }
        val reader = WireReader(blob)
        reader.skip(MAGIC.size)
        val cipherName = reader.name()
        val kdfName = reader.name()
        val kdfOptions = reader.string()
        val count = reader.uint32()
        if (count == 0L) throw SshKeyException.Malformed()
        if (count != 1L) throw SshKeyException.Unsupported()
        val publicBlob = reader.string()
        val section = reader.string()
        try {
            reader.requireEnd()
            val publicKey = PublicBlob.parse(publicBlob)
            val spec = CIPHERS[cipherName] ?: throw SshKeyException.Unsupported()
            val encrypted = cipherName != NONE
            if (!encrypted && (kdfName != NONE || kdfOptions.isNotEmpty())) throw SshKeyException.Malformed()
            if (encrypted && kdfName != BCRYPT) throw SshKeyException.Unsupported()
            if (section.size % spec.blockSize != 0) throw SshKeyException.Malformed()
            val plain = if (encrypted) decrypt(spec, kdfOptions, passphrase, section) else section.copyOf()
            try {
                val comment = readPrivate(WireReader(plain), publicKey, encrypted)
                return SshPublicKey(publicKey.type, publicBlob, comment) to encrypted
            } finally {
                KeyWipes.wipe(KeyWipes.PLAIN, plain)
            }
        } finally {
            section.fill(0)
        }
    }

    private fun decrypt(spec: CipherSpec, kdfOptions: ByteArray, passphrase: CharArray?, section: ByteArray): ByteArray {
        if (passphrase == null || passphrase.isEmpty()) throw SshKeyException.PassphraseRequired()
        val options = WireReader(kdfOptions)
        val salt = options.string()
        val rounds = options.uint32()
        options.requireEnd()
        if (rounds !in 1..MAX_ROUNDS) throw SshKeyException.Malformed()
        // Text that has no UTF-8 form cannot be the passphrase of any key.
        val bytes = Passphrase.utf8(passphrase) ?: throw SshKeyException.WrongPassphrase()
        try {
            return crypt(Cipher.DECRYPT_MODE, spec, bytes, salt, rounds.toInt(), section)
        } finally {
            KeyWipes.wipe(KeyWipes.KDF_INPUT, bytes)
        }
    }

    /** Checks the private part against [publicKey] and returns the comment. */
    private fun readPrivate(reader: WireReader, publicKey: PublicKeyData, encrypted: Boolean): String {
        if (reader.uint32() != reader.uint32()) {
            throw if (encrypted) SshKeyException.WrongPassphrase() else SshKeyException.Malformed()
        }
        if (reader.name() != publicKey.type) throw SshKeyException.Malformed()
        when (publicKey) {
            is PublicKeyData.Ed25519 -> checkEd25519(reader, publicKey)
            is PublicKeyData.Rsa -> checkRsa(reader, publicKey)
            is PublicKeyData.Ecdsa -> checkEcdsa(reader, publicKey)
        }
        val commentBytes = reader.string()
        var pad = 1
        while (reader.remaining > 0) {
            if (reader.uint8() != pad++) throw SshKeyException.Malformed()
        }
        val comment = commentBytes.decodeToString()
        return if (comment.any { it.isISOControl() } || comment.contains('�')) "" else comment
    }

    private fun checkEd25519(reader: WireReader, publicKey: PublicKeyData.Ed25519) {
        val pub = reader.string()
        val private = reader.string()
        try {
            if (private.size != ED25519_PRIVATE_SIZE) throw SshKeyException.Malformed()
            val seed = private.copyOf(PublicBlob.ED25519_KEY_SIZE)
            val derived = try {
                Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
            } finally {
                seed.fill(0)
            }
            val embedded = private.copyOfRange(PublicBlob.ED25519_KEY_SIZE, ED25519_PRIVATE_SIZE)
            if (!pub.contentEquals(publicKey.key) || !derived.contentEquals(pub) || !embedded.contentEquals(pub)) {
                throw SshKeyException.Malformed()
            }
        } finally {
            private.fill(0)
        }
    }

    private fun checkRsa(reader: WireReader, publicKey: PublicKeyData.Rsa) {
        val n = reader.mpint()
        val e = reader.mpint()
        val d = reader.mpint()
        val iqmp = reader.mpint()
        val p = reader.mpint()
        val q = reader.mpint()
        if (n != publicKey.n || e != publicKey.e) throw SshKeyException.Malformed()
        RsaConsistency.require(n, e, d, iqmp, p, q)
    }

    private fun checkEcdsa(reader: WireReader, publicKey: PublicKeyData.Ecdsa) {
        if (reader.name() != publicKey.curve) throw SshKeyException.Malformed()
        val q = reader.string()
        val d = reader.mpint()
        val order = publicKey.parameters.n
        if (!q.contentEquals(publicKey.q) || d.signum() <= 0 || d >= order) throw SshKeyException.Malformed()
        if (publicKey.parameters.g.multiply(d).normalize() != publicKey.point.normalize()) throw SshKeyException.Malformed()
    }

    private fun crypt(mode: Int, spec: CipherSpec, passphrase: ByteArray, salt: ByteArray, rounds: Int, input: ByteArray): ByteArray {
        require(rounds in 1..MAX_ROUNDS) { "Key derivation rounds out of range" }
        val derived = ByteArray(spec.keySize + spec.ivSize)
        try {
            BCrypt().pbkdf(passphrase, salt, rounds, derived)
            val cipher = Cipher.getInstance(spec.transformation)
            cipher.init(mode, SecretKeySpec(derived, 0, spec.keySize, "AES"), IvParameterSpec(derived, spec.keySize, spec.ivSize))
            return cipher.doFinal(input)
        } catch (failure: GeneralSecurityException) {
            throw SshKeyException.Unsupported()
        } finally {
            derived.fill(0)
        }
    }

    private const val UINT32_MASK = 0xFFFFFFFFL
    private const val MAX_ROUNDS = 1L shl 16
}
