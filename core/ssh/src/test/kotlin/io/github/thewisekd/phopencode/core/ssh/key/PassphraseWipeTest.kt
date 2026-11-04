package io.github.thewisekd.phopencode.core.ssh.key

import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.parallel.Isolated

/**
 * A passphrase is encoded only when the key is encrypted, and every buffer holding the decoded key
 * or the encoded passphrase is wiped on every way out, failures included.
 */
@Isolated
class PassphraseWipeTest {
    private val wiped = mutableListOf<Pair<String, ByteArray>>()

    @BeforeTest
    fun watch() {
        KeyWipes.observer = { name, buffer -> synchronized(wiped) { wiped += name to buffer } }
    }

    @AfterTest
    fun stop() {
        KeyWipes.observer = null
    }

    @Test
    fun `a passphrase that is not valid text is ignored for an unencrypted key`() {
        SshKeys.generateEd25519().use { key ->
            val info = SshKeys.read(key.privateKeyBytes(), LONE_SURROGATE.copyOf())
            assertEquals(key.publicKey, info.publicKey)
        }
        assertFalse(names().contains(KeyWipes.KDF_INPUT), "No passphrase was encoded")
        assertAllWiped(KeyWipes.BODY)
    }

    @Test
    fun `a passphrase that is not valid text fails with WrongPassphrase for an encrypted key`() {
        SshKeys.generateEd25519(passphrase = KeySecrets.PASSPHRASE.toCharArray()).use { key ->
            assertFailsWith<SshKeyException.WrongPassphrase> { SshKeys.read(key.privateKeyBytes(), LONE_SURROGATE.copyOf()) }
        }
        assertAllWiped(KeyWipes.BODY)
    }

    @Test
    fun `a wrong passphrase leaves every buffer wiped`() {
        SshKeys.generateEd25519(passphrase = KeySecrets.PASSPHRASE.toCharArray()).use { key ->
            assertFailsWith<SshKeyException.WrongPassphrase> {
                SshKeys.read(key.privateKeyBytes(), KeySecrets.WRONG_PASSPHRASE.toCharArray())
            }
        }
        assertAllWiped(KeyWipes.BODY, KeyWipes.KDF_INPUT, KeyWipes.PLAIN)
    }

    @Test
    fun `a successful read leaves every buffer wiped`() {
        SshKeys.generateEd25519(passphrase = KeySecrets.PASSPHRASE.toCharArray()).use { key ->
            SshKeys.read(key.privateKeyBytes(), KeySecrets.PASSPHRASE.toCharArray())
        }
        assertAllWiped(KeyWipes.BODY, KeyWipes.KDF_INPUT, KeyWipes.PLAIN)
    }

    @Test
    fun `a damaged key leaves the decoded body wiped`() {
        val damaged = OpenSshRsa.parts().let { OpenSshRsa.Parts(it.n, it.e, it.d + it.e, it.iqmp, it.p, it.q) }
        assertFailsWith<SshKeyException.Malformed> { SshKeys.read(OpenSshRsa.file(damaged)) }
        assertAllWiped(KeyWipes.BODY, KeyWipes.PLAIN)
    }

    // Encoder

    @Test
    fun `encoding a long multi-byte passphrase gives UTF-8 and wipes the encoder's buffer`() {
        val text = "ş".repeat(300) + "🔑" + "x".repeat(100)
        val bytes = checkNotNull(Passphrase.utf8(text.toCharArray()))
        assertContentEquals(text.encodeToByteArray(), bytes)
        assertAllWiped(KeyWipes.ENCODER)
    }

    @Test
    fun `a key protected by a long multi-byte passphrase opens elsewhere with the same text`() {
        val text = "şifre-".repeat(40)
        SshKeys.generateEd25519(passphrase = text.toCharArray()).use { key ->
            assertEquals(MinaKeys.publicLine(MinaKeys.load(key.privateKeyBytes(), text).public), SshKeygen.typeAndKey(key.publicKey.authorizedKeysLine()))
        }
        assertAllWiped(KeyWipes.ENCODER, KeyWipes.KDF_INPUT, KeyWipes.SEED, KeyWipes.PLAIN, KeyWipes.PRIVATE, KeyWipes.WRITER)
    }

    @Test
    fun `an encoding failure wipes what was already encoded`() {
        assertNull(Passphrase.utf8(charArrayOf('a', 'b', 'c', '\uD800')))
        assertAllWiped(KeyWipes.ENCODER)
    }

    // Generating

    @Test
    fun `generating with a passphrase that is not valid text wipes the seed`() {
        assertFailsWith<IllegalArgumentException> { SshKeys.generateEd25519(passphrase = LONE_SURROGATE.copyOf()) }
        assertAllWiped(KeyWipes.SEED, KeyWipes.ENCODER)
    }

    @Test
    fun `a failure while encrypting wipes the private key buffers`() {
        val seed = ByteArray(32) { (it + 1).toByte() }
        val publicKey = ByteArray(32) { 7 }
        assertFailsWith<IllegalArgumentException> {
            OpenSshFormat.writeEd25519(seed, publicKey, "c", "x".encodeToByteArray(), SecureRandom(), rounds = 0)
        }
        assertAllWiped(KeyWipes.PLAIN, KeyWipes.PRIVATE, KeyWipes.WRITER)
    }

    // Armour

    @Test
    fun `a bad character inside the body wipes the base64 copied so far`() {
        val body = SshKeys.generateEd25519().use { it.privateKeyBytes() }.decodeToString().lines()
            .filter { it.isNotBlank() && !it.startsWith("-") }
        listOf(" ", "!").forEach { bad ->
            synchronized(wiped) { wiped.clear() }
            val lines = body.toMutableList().also { it[1] = it[1].substring(0, 30) + bad + it[1].substring(30) }
            assertFailsWith<SshKeyException.Malformed> { SshKeys.read(armored("OPENSSH", lines.joinToString("\n")).encodeToByteArray()) }
            assertAllWiped(KeyWipes.BASE64)
        }
    }

    private fun names(): Set<String> = synchronized(wiped) { wiped.map { it.first }.toSet() }

    private fun assertAllWiped(vararg expected: String) {
        val seen = synchronized(wiped) { wiped.toList() }
        expected.forEach { name -> assertTrue(seen.any { it.first == name }, "$name was not wiped") }
        seen.forEach { (name, buffer) -> assertTrue(buffer.all { it == 0.toByte() }, "$name still holds data") }
    }

    private companion object {
        val LONE_SURROGATE = charArrayOf('a', '\uD800', 'b')
    }
}
