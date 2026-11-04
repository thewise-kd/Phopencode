package io.github.thewisekd.phopencode.core.ssh.key

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Checks against the system's OpenSSH. Skipped where `ssh-keygen` is not installed. */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SshKeygenInteropTest {
    @TempDir
    lateinit var dir: Path

    @BeforeTest
    fun needsSshKeygen() = SshKeygen.assume()

    // Keys written here, read by ssh-keygen

    @Test
    fun `ssh-keygen reads a generated key`() {
        SshKeys.generateEd25519(KeySecrets.COMMENT).use { key ->
            val file = SshKeygen.writePrivate(dir, "id", key.privateKeyBytes())
            val derived = SshKeygen.run("-y", "-P", "", "-f", file.toString())
            assertEquals(SshKeygen.typeAndKey(key.publicKey.authorizedKeysLine()), SshKeygen.typeAndKey(derived))
        }
    }

    @Test
    fun `ssh-keygen reads a generated passphrase-protected key`() {
        SshKeys.generateEd25519(KeySecrets.COMMENT, KeySecrets.PASSPHRASE.toCharArray()).use { key ->
            val file = SshKeygen.writePrivate(dir, "id", key.privateKeyBytes())
            val derived = SshKeygen.run("-y", "-P", KeySecrets.PASSPHRASE, "-f", file.toString())
            assertEquals(SshKeygen.typeAndKey(key.publicKey.authorizedKeysLine()), SshKeygen.typeAndKey(derived))
        }
    }

    @Test
    fun `ssh-keygen prints the same fingerprint`() {
        SshKeys.generateEd25519(KeySecrets.COMMENT).use { key ->
            val pub = dir.resolve("id.pub")
            Files.writeString(pub, key.publicKey.authorizedKeysLine() + "\n")
            val printed = SshKeygen.run("-l", "-E", "sha256", "-f", pub.toString()).trim().split(' ')
            assertEquals(key.publicKey.fingerprintSha256, printed[1])
            assertEquals(KeySecrets.COMMENT, printed[2])
        }
    }

    // Keys written by ssh-keygen, read here

    @Test
    fun `reads an ed25519 key from ssh-keygen`() = readsKeygenKey("ed25519", passphrase = null)

    @Test
    fun `reads a passphrase-protected ed25519 key from ssh-keygen`() = readsKeygenKey("ed25519", KeySecrets.PASSPHRASE)

    @Test
    fun `reads an RSA key from ssh-keygen`() = readsKeygenKey("rsa", passphrase = null)

    @Test
    fun `reads a passphrase-protected RSA key from ssh-keygen`() = readsKeygenKey("rsa", KeySecrets.PASSPHRASE)

    @Test
    fun `reads a passphrase-protected ECDSA key from ssh-keygen`() = readsKeygenKey("ecdsa", KeySecrets.PASSPHRASE)

    @Test
    fun `reads a PEM RSA key from ssh-keygen`() = readsKeygenKey("rsa", passphrase = null, format = "PEM")

    @Test
    fun `reads a passphrase-protected PEM RSA key from ssh-keygen`() =
        readsKeygenKey("rsa", KeySecrets.PASSPHRASE, format = "PEM")

    @Test
    fun `reads a passphrase-protected PKCS#8 RSA key from ssh-keygen`() =
        readsKeygenKey("rsa", KeySecrets.PASSPHRASE, format = "PKCS8")

    @Test
    fun `a wrong passphrase for an ssh-keygen key fails with WrongPassphrase`() {
        val file = keygen("ed25519", KeySecrets.PASSPHRASE, format = null)
        assertFailsWith<SshKeyException.WrongPassphrase> {
            SshKeys.read(Files.readAllBytes(file), KeySecrets.WRONG_PASSPHRASE.toCharArray())
        }
        assertFailsWith<SshKeyException.PassphraseRequired> { SshKeys.read(Files.readAllBytes(file)) }
    }

    private fun readsKeygenKey(type: String, passphrase: String?, format: String? = null) {
        val file = keygen(type, passphrase, format)
        val info = SshKeys.read(Files.readAllBytes(file), passphrase?.toCharArray())
        val pub = Files.readString(Path.of("$file.pub"))
        assertEquals(SshKeygen.typeAndKey(pub), SshKeygen.typeAndKey(info.publicKey.authorizedKeysLine()))
        assertEquals(passphrase != null, info.encrypted)
        if (format == null) {
            assertEquals(PrivateKeyFormat.OpenSsh, info.format)
            assertEquals(KeySecrets.COMMENT, info.publicKey.comment)
        } else {
            assertEquals(PrivateKeyFormat.Pem, info.format)
        }
        val fingerprint = SshKeygen.run("-l", "-E", "sha256", "-f", "$file.pub").trim().split(' ')[1]
        assertEquals(fingerprint, info.publicKey.fingerprintSha256)
    }

    private fun keygen(type: String, passphrase: String?, format: String?): Path {
        val file = dir.resolve("id_$type")
        val args = mutableListOf("-q", "-t", type, "-N", passphrase ?: "", "-C", KeySecrets.COMMENT, "-f", file.toString())
        if (type == "rsa") args += listOf("-b", "2048")
        if (format != null) args += listOf("-m", format)
        SshKeygen.run(*args.toTypedArray())
        return file
    }
}
