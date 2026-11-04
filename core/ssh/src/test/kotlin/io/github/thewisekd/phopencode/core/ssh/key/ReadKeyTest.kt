package io.github.thewisekd.phopencode.core.ssh.key

import java.io.StringWriter
import java.security.KeyPair
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PKCS8Generator
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder

class ReadKeyTest {
    // OpenSSH format, written by MINA

    @Test
    fun `reads an ed25519 key`() = readsMinaKey(MinaKeys.ed25519(), "ssh-ed25519", null)

    @Test
    fun `reads an RSA key`() = readsMinaKey(MinaKeys.rsa(), "ssh-rsa", null)

    @Test
    fun `reads an ECDSA key`() = readsMinaKey(MinaKeys.ecdsa(), "ecdsa-sha2-nistp256", null)

    @Test
    fun `reads a passphrase-protected ed25519 key`() = readsMinaKey(MinaKeys.ed25519(), "ssh-ed25519", KeySecrets.PASSPHRASE)

    @Test
    fun `reads a passphrase-protected RSA key`() = readsMinaKey(MinaKeys.rsa(), "ssh-rsa", KeySecrets.PASSPHRASE)

    @Test
    fun `reads a passphrase-protected ECDSA key`() =
        readsMinaKey(MinaKeys.ecdsa(), "ecdsa-sha2-nistp256", KeySecrets.PASSPHRASE)

    private fun readsMinaKey(keyPair: KeyPair, type: String, passphrase: String?) {
        val file = MinaKeys.openSsh(keyPair, KeySecrets.COMMENT, passphrase)
        val info = SshKeys.read(file, passphrase?.toCharArray())
        assertEquals(type, info.publicKey.type)
        assertEquals(MinaKeys.publicLine(keyPair.public), SshKeygen.typeAndKey(info.publicKey.authorizedKeysLine()))
        assertEquals(KeySecrets.COMMENT, info.publicKey.comment)
        assertEquals(PrivateKeyFormat.OpenSsh, info.format)
        assertEquals(passphrase != null, info.encrypted)
    }

    @Test
    fun `text and bytes read the same`() {
        val file = MinaKeys.openSsh(MinaKeys.rsa(), KeySecrets.COMMENT, KeySecrets.PASSPHRASE)
        val fromBytes = SshKeys.read(file, KeySecrets.PASSPHRASE.toCharArray())
        val fromChars = SshKeys.read(file.decodeToString().toCharArray(), KeySecrets.PASSPHRASE.toCharArray())
        assertEquals(fromBytes.publicKey, fromChars.publicKey)
    }

    @Test
    fun `windows line endings and surrounding blank lines are accepted`() {
        val keyPair = MinaKeys.ed25519()
        val text = "\n" + MinaKeys.openSsh(keyPair, "c").decodeToString().replace("\n", "\r\n") + "\n\n"
        assertEquals(MinaKeys.publicLine(keyPair.public), SshKeygen.typeAndKey(SshKeys.read(text.encodeToByteArray()).publicKey.authorizedKeysLine()))
    }

    @Test
    fun `a passphrase given for an unencrypted key is ignored`() {
        val file = MinaKeys.openSsh(MinaKeys.ed25519(), "c")
        assertFalse(SshKeys.read(file, KeySecrets.PASSPHRASE.toCharArray()).encrypted)
    }

    @Test
    fun `a missing passphrase fails with PassphraseRequired`() {
        val file = MinaKeys.openSsh(MinaKeys.rsa(), "c", KeySecrets.PASSPHRASE)
        assertFailsWith<SshKeyException.PassphraseRequired> { SshKeys.read(file) }
    }

    @Test
    fun `a wrong passphrase fails with WrongPassphrase`() {
        listOf(MinaKeys.ed25519(), MinaKeys.rsa()).forEach { keyPair ->
            val file = MinaKeys.openSsh(keyPair, "c", KeySecrets.PASSPHRASE)
            assertFailsWith<SshKeyException.WrongPassphrase> { SshKeys.read(file, KeySecrets.WRONG_PASSPHRASE.toCharArray()) }
        }
    }

    @Test
    fun `reading leaves the caller's arrays as they were`() {
        val file = MinaKeys.openSsh(MinaKeys.ed25519(), "c", KeySecrets.PASSPHRASE)
        val bytes = file.copyOf()
        val chars = file.decodeToString().toCharArray()
        val passphrase = KeySecrets.PASSPHRASE.toCharArray()
        SshKeys.read(bytes, passphrase)
        SshKeys.read(chars, passphrase)
        assertContentEquals(file, bytes)
        assertContentEquals(file.decodeToString().toCharArray(), chars)
        assertContentEquals(KeySecrets.PASSPHRASE.toCharArray(), passphrase)
    }

    // PEM, written by BouncyCastle

    @Test
    fun `reads a PKCS#1 RSA key`() = readsPem(MinaKeys.rsa(), ::pem, null)

    @Test
    fun `reads a passphrase-protected PKCS#1 RSA key`() = readsPem(MinaKeys.rsa(), ::pem, KeySecrets.PASSPHRASE)

    @Test
    fun `reads a PKCS#8 RSA key`() = readsPem(MinaKeys.rsa(), ::pkcs8, null)

    @Test
    fun `reads a passphrase-protected PKCS#8 RSA key`() = readsPem(MinaKeys.rsa(), ::pkcs8, KeySecrets.PASSPHRASE)

    @Test
    fun `reads a PKCS#8 ed25519 key`() = readsPem(MinaKeys.ed25519(), ::pkcs8, null)

    @Test
    fun `an encrypted PEM key needs the right passphrase`() {
        listOf(pem(MinaKeys.rsa(), KeySecrets.PASSPHRASE), pkcs8(MinaKeys.rsa(), KeySecrets.PASSPHRASE)).forEach { file ->
            assertFailsWith<SshKeyException.PassphraseRequired> { SshKeys.read(file) }
            assertFailsWith<SshKeyException.WrongPassphrase> { SshKeys.read(file, KeySecrets.WRONG_PASSPHRASE.toCharArray()) }
        }
    }

    private fun readsPem(keyPair: KeyPair, write: (KeyPair, String?) -> ByteArray, passphrase: String?) {
        val file = write(keyPair, passphrase)
        val info = SshKeys.read(file, passphrase?.toCharArray())
        assertEquals(MinaKeys.publicLine(keyPair.public), SshKeygen.typeAndKey(info.publicKey.authorizedKeysLine()))
        assertEquals("", info.publicKey.comment, "PEM files carry no comment")
        assertEquals(PrivateKeyFormat.Pem, info.format)
        assertEquals(passphrase != null, info.encrypted)
    }

    // Malformed and unsupported input

    @Test
    fun `input that is not a key fails with Malformed`() {
        val inputs = listOf(
            "",
            "   \n",
            "not a key at all",
            armored("OPENSSH", null),
            armored("OPENSSH", ""),
            armored("OPENSSH", "!!!!"),
            armored("OPENSSH", "AAAA"),
            armored("OPENSSH", "AAAA") + "trailing text\n",
            armored("RSA", "AAAA"),
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVl c",
        )
        inputs.forEach { input ->
            assertFailsWith<SshKeyException.Malformed>(input) { SshKeys.read(input.encodeToByteArray()) }
        }
    }

    @Test
    fun `non-ASCII text fails with Malformed`() {
        assertFailsWith<SshKeyException.Malformed> { SshKeys.read("anahtar ş".toCharArray()) }
        assertFailsWith<SshKeyException.Malformed> { SshKeys.read(byteArrayOf(0xC3.toByte(), 0x28)) }
    }

    @Test
    fun `a truncated key fails with Malformed`() {
        val blob = OpenSshBody.decode(MinaKeys.openSsh(MinaKeys.ed25519(), "c"))
        listOf(10, 40, 100, blob.size - 20).forEach { size ->
            assertFailsWith<SshKeyException.Malformed>("size $size") { SshKeys.read(OpenSshBody.encode(blob.copyOf(size))) }
        }
    }

    @Test
    fun `trailing bytes after the key fail with Malformed`() {
        val blob = OpenSshBody.decode(MinaKeys.openSsh(MinaKeys.ed25519(), "c"))
        assertFailsWith<SshKeyException.Malformed> { SshKeys.read(OpenSshBody.encode(blob + ByteArray(4))) }
    }

    @Test
    fun `a wrong magic fails with Malformed`() {
        val blob = OpenSshBody.decode(MinaKeys.openSsh(MinaKeys.ed25519(), "c"))
        blob[0] = 'O'.code.toByte()
        assertFailsWith<SshKeyException.Malformed> { SshKeys.read(OpenSshBody.encode(blob)) }
    }

    @Test
    fun `an unencrypted key whose check numbers differ fails with Malformed`() {
        val blob = OpenSshBody.decode(MinaKeys.openSsh(MinaKeys.ed25519(), "c"))
        // Header: magic 15, "none" 8, "none" 8, "" 4, count 4, public key 4 + 51, private length 4.
        val checkint = 15 + 8 + 8 + 4 + 4 + 4 + 51 + 4
        blob[checkint] = (blob[checkint] + 1).toByte()
        assertFailsWith<SshKeyException.Malformed> { SshKeys.read(OpenSshBody.encode(blob)) }
    }

    @Test
    fun `a private key that does not match its public key fails with Malformed`() {
        val blob = OpenSshBody.decode(MinaKeys.openSsh(MinaKeys.ed25519(), "c"))
        val other = OpenSshBody.decode(MinaKeys.openSsh(MinaKeys.ed25519(), "c"))
        val publicKey = 15 + 8 + 8 + 4 + 4 + 4
        other.copyInto(blob, publicKey, publicKey, publicKey + 51)
        assertFailsWith<SshKeyException.Malformed> { SshKeys.read(OpenSshBody.encode(blob)) }
    }

    @Test
    fun `an unknown cipher fails with Unsupported`() {
        val blob = OpenSshBody.decode(MinaKeys.openSsh(MinaKeys.ed25519(), "c"))
        "nope".encodeToByteArray().copyInto(blob, 15 + 4)
        assertFailsWith<SshKeyException.Unsupported> { SshKeys.read(OpenSshBody.encode(blob)) }
    }

    @Test
    fun `an unknown key type fails with Unsupported`() {
        val blob = OpenSshBody.decode(MinaKeys.openSsh(MinaKeys.ed25519(), "c"))
        val publicType = 15 + 8 + 8 + 4 + 4 + 4 + 4
        "ssh-ed25518".encodeToByteArray().copyInto(blob, publicType)
        assertFailsWith<SshKeyException.Unsupported> { SshKeys.read(OpenSshBody.encode(blob)) }
    }

    @Test
    fun `failures name no detail and carry no cause`() {
        val failures = listOf(
            runCatching { SshKeys.read("garbage".encodeToByteArray()) },
            runCatching { SshKeys.read(MinaKeys.openSsh(MinaKeys.rsa(), "c", KeySecrets.PASSPHRASE), KeySecrets.WRONG_PASSPHRASE.toCharArray()) },
            runCatching { SshKeys.read(pkcs8(MinaKeys.rsa(), KeySecrets.PASSPHRASE), KeySecrets.WRONG_PASSPHRASE.toCharArray()) },
        ).map { assertNotNullFailure(it.exceptionOrNull()) }
        failures.forEach { assertNull(it.cause) }
    }

    private fun assertNotNullFailure(failure: Throwable?): SshKeyException {
        assertTrue(failure is SshKeyException, "Expected a typed failure, got $failure")
        return failure
    }

    private companion object {
        val BC = BouncyCastleProvider()

        fun pem(keyPair: KeyPair, passphrase: String?): ByteArray {
            val out = StringWriter()
            JcaPEMWriter(out).use { writer ->
                if (passphrase == null) {
                    writer.writeObject(keyPair.private)
                } else {
                    writer.writeObject(keyPair.private, JcePEMEncryptorBuilder("AES-128-CBC").setProvider(BC).build(passphrase.toCharArray()))
                }
            }
            return out.toString().encodeToByteArray()
        }

        fun pkcs8(keyPair: KeyPair, passphrase: String?): ByteArray {
            val encryptor = passphrase?.let {
                JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC).setProvider(BC).setPassword(it.toCharArray()).build()
            }
            val out = StringWriter()
            JcaPEMWriter(out).use { it.writeObject(JcaPKCS8Generator(keyPair.private, encryptor)) }
            return out.toString().encodeToByteArray()
        }
    }
}

/** The binary body of an OpenSSH private key file, for tests that damage it on purpose. */
internal object OpenSshBody {
    private const val BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val END = "-----END OPENSSH PRIVATE KEY-----"

    fun decode(file: ByteArray): ByteArray =
        Base64.getDecoder().decode(file.decodeToString().lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString(""))

    fun encode(blob: ByteArray): ByteArray =
        (listOf(BEGIN) + Base64.getEncoder().encodeToString(blob).chunked(70) + END).joinToString("\n", postfix = "\n").encodeToByteArray()
}
