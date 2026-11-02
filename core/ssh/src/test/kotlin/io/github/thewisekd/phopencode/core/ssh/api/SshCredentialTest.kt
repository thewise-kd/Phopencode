package io.github.thewisekd.phopencode.core.ssh.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SshCredentialTest {
    private val sentinel = listOf("sentinel", "value").joinToString("-")

    private fun password() = SshCredential.Password(sentinel.toCharArray())

    private fun key() = SshCredential.PrivateKey(sentinel.toCharArray(), passphrase = sentinel.toCharArray())

    @Test
    fun `toString reveals no secret`() {
        listOf(password(), key(), SshCredential.PrivateKey(sentinel.toCharArray())).forEach { credential ->
            val text = credential.toString()
            assertFalse(sentinel in text, text)
            assertTrue("***" in text, text)
        }
    }

    @Test
    fun `string templates and collections stay masked`() {
        assertFalse(sentinel in "${listOf(password(), key())}")
    }

    @Test
    fun `wipe overwrites a key and its passphrase in place`() {
        val secret = sentinel.toCharArray()
        val passphrase = sentinel.toCharArray()

        SshCredential.PrivateKey(secret, passphrase).wipe()

        assertContentEquals(CharArray(sentinel.length), secret)
        assertContentEquals(CharArray(sentinel.length), passphrase)
    }

    @Test
    fun `wipe overwrites a password in place`() {
        val secret = sentinel.toCharArray()

        SshCredential.Password(secret).wipe()

        assertContentEquals(CharArray(sentinel.length), secret)
    }

    @Test
    fun `equal secrets do not make two credentials equal`() {
        assertNotEquals<SshCredential>(password(), password())
    }
}
