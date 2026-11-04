package io.github.thewisekd.phopencode.core.ssh.key

import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/**
 * Makes and reads SSH key pairs. Private keys travel as the text of a key file, in byte or
 * character arrays, never as `String`: a `String` cannot be wiped. Arrays passed in stay the
 * caller's and are left unchanged; every copy made here is wiped before returning, except those
 * the crypto libraries keep inside their own key objects, which offer no way to wipe them.
 *
 * Nothing here logs, and no failure carries a key, a passphrase or a parser's message.
 */
object SshKeys {
    /**
     * Generates an ed25519 key pair. The private key is written in OpenSSH format, as
     * `ssh-keygen -t ed25519` does; with a non-empty [passphrase] it is encrypted the way ssh-keygen
     * encrypts it (bcrypt key derivation, 16 rounds, aes256-ctr). An empty passphrase means none, as
     * with `ssh-keygen -N ''`.
     *
     * @param comment Ends up in the public key line, for example `phone@phopencode`.
     * @throws IllegalArgumentException for a [comment] with control characters, or a [passphrase]
     *   with no UTF-8 form, such as a lone surrogate.
     */
    fun generateEd25519(comment: String = "", passphrase: CharArray? = null): GeneratedKey {
        SshPublicKey.requireValidComment(comment)
        val random = SecureRandom()
        val secret = Ed25519PrivateKeyParameters(random)
        val publicKey = secret.generatePublicKey().encoded
        val public = SshPublicKey(PublicBlob.ED25519, PublicBlob.ed25519(publicKey), comment)
        val seed = secret.encoded
        val blob = try {
            val passphraseBytes = passphrase?.takeIf { it.isNotEmpty() }?.let {
                requireNotNull(Passphrase.utf8(it)) { "Passphrase is not valid text" }
            }
            try {
                OpenSshFormat.writeEd25519(seed, publicKey, comment, passphraseBytes, random, OpenSshFormat.DEFAULT_ROUNDS)
            } finally {
                KeyWipes.wipe(KeyWipes.KDF_INPUT, passphraseBytes)
            }
        } finally {
            KeyWipes.wipe(KeyWipes.SEED, seed)
        }
        return try {
            GeneratedKey(Armor.encodeOpenSsh(blob), public)
        } finally {
            blob.fill(0)
        }
    }

    /**
     * Reads a private key file: OpenSSH format (ed25519, RSA, ECDSA on the NIST curves; unencrypted
     * or with a passphrase) and the older PEM formats PKCS#1, SEC1 and PKCS#8. It checks that the
     * private key matches its public key, so a key that reads here can log in.
     *
     * @param privateKey The file's content.
     * @param passphrase Null or empty for none. Ignored for a key that is not encrypted.
     * @throws SshKeyException
     */
    fun read(privateKey: ByteArray, passphrase: CharArray? = null): PrivateKeyInfo {
        val text = Armor.ascii(privateKey)
        return try {
            read(text, passphrase)
        } finally {
            text.fill(ZERO)
        }
    }

    /** As [read] for bytes; this suits [io.github.thewisekd.phopencode.core.ssh.api.SshCredential.PrivateKey]. */
    fun read(privateKey: CharArray, passphrase: CharArray? = null): PrivateKeyInfo {
        val usable = passphrase?.takeIf { it.isNotEmpty() }
        return when (val armor = Armor.classify(privateKey)) {
            is Armor.Kind.OpenSsh -> try {
                val (publicKey, encrypted) = OpenSshFormat.read(armor.body, usable)
                PrivateKeyInfo(publicKey, PrivateKeyFormat.OpenSsh, encrypted)
            } finally {
                KeyWipes.wipe(KeyWipes.BODY, armor.body)
            }

            Armor.Kind.Pem -> {
                val (publicKey, encrypted) = PemFormat.read(privateKey, usable)
                PrivateKeyInfo(publicKey, PrivateKeyFormat.Pem, encrypted)
            }
        }
    }

    private const val ZERO = '\u0000'
}

/** What reading a private key found out. Public data only. */
data class PrivateKeyInfo(val publicKey: SshPublicKey, val format: PrivateKeyFormat, val encrypted: Boolean)

enum class PrivateKeyFormat {
    /** `-----BEGIN OPENSSH PRIVATE KEY-----`, ssh-keygen's default. */
    OpenSsh,

    /** PKCS#1, SEC1 or PKCS#8 in PEM armour. */
    Pem,
}

/**
 * A new key pair. The private key file is held here until [close], which wipes it; store it, for
 * example in `SecretStore`, and close this. Thread-safe. Prints only the public key.
 */
class GeneratedKey internal constructor(private val file: ByteArray, val publicKey: SshPublicKey) : AutoCloseable {
    private var closed = false

    /** A copy of the private key file in OpenSSH format, owned by the caller. Fails once closed. */
    @Synchronized
    fun privateKeyBytes(): ByteArray {
        check(!closed) { CLOSED }
        return file.copyOf()
    }

    /** The same file as characters, for [io.github.thewisekd.phopencode.core.ssh.api.SshCredential.PrivateKey]. */
    @Synchronized
    fun privateKeyChars(): CharArray {
        check(!closed) { CLOSED }
        return CharArray(file.size) { file[it].toInt().toChar() }
    }

    @Synchronized
    override fun close() {
        file.fill(0)
        closed = true
    }

    override fun toString(): String = "GeneratedKey($publicKey)"

    private companion object {
        const val CLOSED = "GeneratedKey is closed"
    }
}
