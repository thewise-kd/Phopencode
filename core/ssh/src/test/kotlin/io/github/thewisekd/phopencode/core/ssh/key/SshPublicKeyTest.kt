package io.github.thewisekd.phopencode.core.ssh.key

import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.apache.sshd.common.config.keys.KeyUtils

class SshPublicKeyTest {
    @Test
    fun `the fingerprint is OpenSSH's SHA256 form`() {
        listOf(MinaKeys.ed25519(), MinaKeys.rsa(), MinaKeys.ecdsa()).forEach { keyPair ->
            val key = SshPublicKey.parse(MinaKeys.publicLine(keyPair.public))
            assertEquals(KeyUtils.getFingerPrint(keyPair.public), key.fingerprintSha256)
            assertEquals(HostKey(key.type, key.toByteArray()).fingerprintSha256, key.fingerprintSha256)
        }
    }

    @Test
    fun `parses an authorized_keys line and prints it back`() {
        val line = MinaKeys.publicLine(MinaKeys.rsa().public) + " " + KeySecrets.COMMENT
        val key = SshPublicKey.parse(line)
        assertEquals("ssh-rsa", key.type)
        assertEquals(KeySecrets.COMMENT, key.comment)
        assertEquals(line, key.authorizedKeysLine())
        assertEquals(line, SshPublicKey.parse("  $line  \n").authorizedKeysLine())
    }

    @Test
    fun `a comment may contain spaces`() {
        val line = MinaKeys.publicLine(MinaKeys.ed25519().public) + " my phone key"
        assertEquals("my phone key", SshPublicKey.parse(line).comment)
    }

    @Test
    fun `lines that are not a public key are refused`() {
        val ed = MinaKeys.publicLine(MinaKeys.ed25519().public)
        val base64 = ed.split(' ')[1]
        listOf("", "ssh-ed25519", "ssh-ed25519 !!!!", "ssh-rsa $base64", "ssh-ed25519 AAAA", "ssh-ed25519 ${base64}x").forEach {
            assertFailsWith<IllegalArgumentException>(it) { SshPublicKey.parse(it) }
        }
    }

    @Test
    fun `equality ignores the comment, which is only a label`() {
        val line = MinaKeys.publicLine(MinaKeys.ed25519().public)
        assertEquals(SshPublicKey.parse("$line a"), SshPublicKey.parse("$line b"))
        assertEquals(SshPublicKey.parse("$line a").hashCode(), SshPublicKey.parse("$line b").hashCode())
        assertNotEquals(SshPublicKey.parse(line), SshPublicKey.parse(MinaKeys.publicLine(MinaKeys.ed25519().public)))
    }

    @Test
    fun `the encoded key cannot be changed from outside`() {
        val key = SshPublicKey.parse(MinaKeys.publicLine(MinaKeys.ed25519().public))
        val bytes = key.toByteArray()
        val copy = bytes.copyOf()
        bytes.fill(0)
        assertContentEquals(copy, key.toByteArray())
    }

    @Test
    fun `a comment that would break the line is refused`() {
        val key = SshPublicKey.parse(MinaKeys.publicLine(MinaKeys.ed25519().public))
        assertFailsWith<IllegalArgumentException> { SshPublicKey(key.type, key.toByteArray(), "a\nb") }
    }

    @Test
    fun `prints type and fingerprint`() {
        val key = SshPublicKey.parse(MinaKeys.publicLine(MinaKeys.ed25519().public) + " c")
        assertEquals("SshPublicKey(ssh-ed25519, ${key.fingerprintSha256})", key.toString())
    }
}
