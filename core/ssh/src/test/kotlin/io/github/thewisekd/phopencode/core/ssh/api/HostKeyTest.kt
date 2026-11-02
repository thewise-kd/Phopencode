package io.github.thewisekd.phopencode.core.ssh.api

import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class HostKeyTest {
    @TempDir
    lateinit var dir: Path

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), output)
        return output
    }

    @Test
    fun `the fingerprint is the one ssh-keygen shows`() {
        val key = dir.resolve("key").toString()
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key)
        val (type, blob) = Path.of("$key.pub").readText().trim().split(" ")
        val expected = run("ssh-keygen", "-l", "-E", "sha256", "-f", "$key.pub").split(" ")[1]

        assertEquals(expected, HostKey(type, Base64.getDecoder().decode(blob)).fingerprintSha256)
    }

    @Test
    fun `the fingerprint has the prefix and no padding`() {
        // The SHA-256 test vector for "abc" from FIPS 180-2.
        val fingerprint = HostKey("ssh-ed25519", "abc".toByteArray()).fingerprintSha256
        assertEquals("SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0", fingerprint)
    }

    @Test
    fun `keys compare by content, because a changed key must stop the connection`() {
        val key = HostKey("ssh-ed25519", byteArrayOf(1, 2, 3))

        assertEquals(key, HostKey("ssh-ed25519", byteArrayOf(1, 2, 3)))
        assertEquals(key.hashCode(), HostKey("ssh-ed25519", byteArrayOf(1, 2, 3)).hashCode())
        assertNotEquals(key, HostKey("ssh-ed25519", byteArrayOf(1, 2, 4)))
        assertNotEquals(key, HostKey("ssh-rsa", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `a key cannot change after it was approved`() {
        val input = byteArrayOf(1, 2, 3)
        val key = HostKey("ssh-ed25519", input)
        val fingerprint = key.fingerprintSha256

        input[0] = 9
        key.toByteArray()[1] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), key.toByteArray())
        assertEquals(fingerprint, key.fingerprintSha256)
    }

    @Test
    fun `toString shows what a user compares`() {
        val key = HostKey("ssh-ed25519", byteArrayOf(1, 2, 3))
        assertTrue(key.fingerprintSha256 in key.toString())
        assertTrue("ssh-ed25519" in key.toString())
    }

    @Test
    fun `an empty key is rejected`() {
        assertFailsWith<IllegalArgumentException> { HostKey("ssh-ed25519", ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { HostKey(" ", byteArrayOf(1)) }
    }
}
