package io.github.thewisekd.phopencode.core.ssh.key

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class GenerateKeyTest {
    @Test
    fun `generates an ed25519 private key in OpenSSH format`() {
        SshKeys.generateEd25519(KeySecrets.COMMENT).use { key ->
            val lines = key.privateKeyBytes().decodeToString().lines()
            assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", lines.first())
            assertEquals("", lines.last(), "The file ends with a newline")
            assertEquals("-----END OPENSSH PRIVATE KEY-----", lines[lines.size - 2])
            val body = lines.subList(1, lines.size - 2)
            assertTrue(body.all { it.length in 1..70 }, "Body lines are wrapped at 70 like ssh-keygen's")
            val blob = Base64.getDecoder().decode(body.joinToString(""))
            assertContentEquals("openssh-key-v1\u0000".encodeToByteArray(), blob.copyOf(15))
        }
    }

    @Test
    fun `exports the public key as an authorized_keys line`() {
        SshKeys.generateEd25519(KeySecrets.COMMENT).use { key ->
            val line = key.publicKey.authorizedKeysLine()
            val (type, base64, comment) = line.split(' ')
            assertEquals("ssh-ed25519", type)
            assertTrue(base64.startsWith("AAAAC3NzaC1lZDI1NTE5"), "Wire format starts with the type name")
            assertEquals(KeySecrets.COMMENT, comment)
            assertEquals("ssh-ed25519", key.publicKey.type)
            assertEquals(KeySecrets.COMMENT, key.publicKey.comment)
            assertEquals(4 + 11 + 4 + 32, key.publicKey.toByteArray().size)
        }
    }

    @Test
    fun `without a comment the line has two fields`() {
        SshKeys.generateEd25519().use { key ->
            assertEquals(2, key.publicKey.authorizedKeysLine().split(' ').size)
        }
    }

    @Test
    fun `a comment that would break the line is refused`() {
        listOf("two\nlines", "tab\there", "nul\u0000").forEach { comment ->
            assertFailsWith<IllegalArgumentException> { SshKeys.generateEd25519(comment) }
        }
    }

    @Test
    fun `every key is new`() {
        val first = SshKeys.generateEd25519().use { it.publicKey }
        val second = SshKeys.generateEd25519().use { it.publicKey }
        assertNotEquals(first, second)
    }

    @Test
    fun `MINA reads the generated key and finds the same public key`() {
        SshKeys.generateEd25519(KeySecrets.COMMENT).use { key ->
            val loaded = MinaKeys.load(key.privateKeyBytes(), null)
            assertEquals(MinaKeys.publicLine(loaded.public), SshKeygen.typeAndKey(key.publicKey.authorizedKeysLine()))
        }
    }

    @Test
    fun `MINA reads a passphrase-protected generated key`() {
        SshKeys.generateEd25519(KeySecrets.COMMENT, KeySecrets.PASSPHRASE.toCharArray()).use { key ->
            val loaded = MinaKeys.load(key.privateKeyBytes(), KeySecrets.PASSPHRASE)
            assertEquals(MinaKeys.publicLine(loaded.public), SshKeygen.typeAndKey(key.publicKey.authorizedKeysLine()))
        }
    }

    @Test
    fun `a generated key reads back with its comment`() {
        SshKeys.generateEd25519(KeySecrets.COMMENT).use { key ->
            val info = SshKeys.read(key.privateKeyBytes())
            assertEquals(key.publicKey, info.publicKey)
            assertEquals(KeySecrets.COMMENT, info.publicKey.comment)
            assertEquals(PrivateKeyFormat.OpenSsh, info.format)
            assertFalse(info.encrypted)
        }
    }

    @Test
    fun `a passphrase-protected key reads back only with its passphrase`() {
        SshKeys.generateEd25519(KeySecrets.COMMENT, KeySecrets.PASSPHRASE.toCharArray()).use { key ->
            val file = key.privateKeyBytes()
            assertFailsWith<SshKeyException.PassphraseRequired> { SshKeys.read(file) }
            assertFailsWith<SshKeyException.PassphraseRequired> { SshKeys.read(file, CharArray(0)) }
            assertFailsWith<SshKeyException.WrongPassphrase> { SshKeys.read(file, KeySecrets.WRONG_PASSPHRASE.toCharArray()) }
            val info = SshKeys.read(file, KeySecrets.PASSPHRASE.toCharArray())
            assertEquals(key.publicKey, info.publicKey)
            assertEquals(KeySecrets.COMMENT, info.publicKey.comment)
            assertTrue(info.encrypted)
        }
    }

    @Test
    fun `an empty passphrase means no encryption, as with ssh-keygen -N ''`() {
        SshKeys.generateEd25519(passphrase = CharArray(0)).use { key ->
            assertFalse(SshKeys.read(key.privateKeyBytes()).encrypted)
        }
    }

    @Test
    fun `the caller's passphrase array is left as it was`() {
        val passphrase = KeySecrets.PASSPHRASE.toCharArray()
        SshKeys.generateEd25519(passphrase = passphrase).use { }
        assertContentEquals(KeySecrets.PASSPHRASE.toCharArray(), passphrase)
    }

    @Test
    fun `chars and bytes of the private key are the same text, and each call gives a fresh copy`() {
        SshKeys.generateEd25519().use { key ->
            val bytes = key.privateKeyBytes()
            assertEquals(bytes.decodeToString(), key.privateKeyChars().concatToString())
            bytes.fill(0)
            assertNotEquals(0, key.privateKeyBytes()[0].toInt())
        }
    }

    @Test
    fun `closing wipes the private key`() {
        val key = SshKeys.generateEd25519()
        key.close()
        key.close()
        assertFailsWith<IllegalStateException> { key.privateKeyBytes() }
        assertFailsWith<IllegalStateException> { key.privateKeyChars() }
        assertEquals("ssh-ed25519", key.publicKey.type, "The public key stays usable")
    }

    // Login

    @Test
    fun `a generated key logs in to an SSH server`() = login(passphrase = null)

    @Test
    fun `a generated passphrase-protected key logs in to an SSH server`() = login(passphrase = KeySecrets.PASSPHRASE)

    @Test
    fun `a generated key the server does not know is refused`() = runBlocking {
        withTimeout(30.seconds) {
            val authorized = SshKeys.generateEd25519().use { MinaKeys.parsePublic(it.publicKey.authorizedKeysLine()) }
            KeyTestServer(listOf(authorized)).use { server ->
                SshKeys.generateEd25519().use { key ->
                    assertFailsWith<SshException.AuthenticationFailed> {
                        SshjConnector().connect(server.target, SshCredential.PrivateKey(key.privateKeyChars()), ACCEPT).close()
                    }
                }
            }
        }
    }

    private fun login(passphrase: String?) = runBlocking {
        withTimeout(30.seconds) {
            SshKeys.generateEd25519(KeySecrets.COMMENT, passphrase?.toCharArray()).use { key ->
                val authorized = MinaKeys.parsePublic(key.publicKey.authorizedKeysLine())
                KeyTestServer(listOf(authorized)).use { server ->
                    val credential = SshCredential.PrivateKey(key.privateKeyChars(), passphrase?.toCharArray())
                    SshjConnector().connect(server.target, credential, ACCEPT).use { session ->
                        assertTrue(session.connected.value)
                    }
                    credential.wipe()
                }
            }
        }
    }

    private companion object {
        val ACCEPT = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }
    }
}
