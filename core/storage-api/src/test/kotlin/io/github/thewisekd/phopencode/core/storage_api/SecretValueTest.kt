package io.github.thewisekd.phopencode.core.storage_api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class SecretValueTest {
    private val sentinel = "sentinel-" + "value"

    private fun assertMasked(text: String) {
        assertFalse(text.contains("sentinel"), text)
        assertFalse(text.contains(sentinel.encodeToByteArray().contentToString()), text)
    }

    private data class Holder(val label: String, val secret: SecretValue)

    @Test
    fun `toString is masked for text and for bytes`() {
        assertMasked(SecretValue.ofText(sentinel).toString())
        assertMasked(SecretValue.ofBytes(sentinel.encodeToByteArray()).toString())
    }

    @Test
    fun `toString does not reveal the length`() {
        assertEquals(SecretValue.ofText("a").toString(), SecretValue.ofText(sentinel).toString())
    }

    @Test
    fun `printing stays masked inside data classes, collections and templates`() {
        val secret = SecretValue.ofText(sentinel)

        assertMasked(Holder("label", secret).toString())
        assertMasked(listOf(secret).toString())
        assertMasked(mapOf("key" to secret).toString())
        assertMasked(mapOf(secret to "value").toString())
        assertMasked(setOf(Holder("label", secret)).toString())
        assertMasked(Pair(secret, secret).toString())
        assertMasked("value: $secret")
    }

    @Test
    fun `equal contents do not make values equal`() {
        val first = SecretValue.ofText(sentinel)
        val second = SecretValue.ofText(sentinel)

        assertNotEquals(first, second)
        assertEquals(first, first)
    }

    @Test
    fun `text and bytes reveal what was stored`() {
        val text = "deneme-şifre-ığü"
        val secret = SecretValue.ofText(text)

        assertEquals(text, secret.revealText())
        assertContentEquals(text.encodeToByteArray(), secret.revealBytes())
    }

    @Test
    fun `binary content survives`() {
        val bytes = ByteArray(256) { it.toByte() }

        assertContentEquals(bytes, SecretValue.ofBytes(bytes).revealBytes())
    }

    @Test
    fun `an empty secret is a value`() {
        assertEquals("", SecretValue.ofText("").revealText())
    }

    @Test
    fun `bytes that are not text fail instead of turning into a different password`() {
        val secret = SecretValue.ofBytes(byteArrayOf(0xC3.toByte(), 0x28))

        assertFailsWith<CharacterCodingException> { secret.revealText() }
    }

    @Test
    fun `the value does not follow later changes to the source array`() {
        val source = byteArrayOf(1, 2, 3)
        val secret = SecretValue.ofBytes(source)

        source.fill(0)

        assertContentEquals(byteArrayOf(1, 2, 3), secret.revealBytes())
    }

    @Test
    fun `changing a revealed array does not change the value`() {
        val secret = SecretValue.ofBytes(byteArrayOf(1, 2, 3))

        secret.revealBytes().fill(0)

        assertContentEquals(byteArrayOf(1, 2, 3), secret.revealBytes())
    }

    @Test
    fun `closing overwrites the stored bytes`() {
        val secret = SecretValue.ofBytes(byteArrayOf(1, 2, 3))
        val field = SecretValue::class.java.declaredFields.single { it.type == ByteArray::class.java }
        field.isAccessible = true
        val stored = field.get(secret) as ByteArray

        secret.close()

        assertContentEquals(byteArrayOf(0, 0, 0), stored)
    }

    @Test
    fun `a closed value cannot be revealed`() {
        val secret = SecretValue.ofText(sentinel)

        secret.close()

        assertMasked(assertFailsWith<IllegalStateException> { secret.revealBytes() }.toString())
        assertMasked(assertFailsWith<IllegalStateException> { secret.revealText() }.toString())
    }

    @Test
    fun `closing twice is allowed and use closes the value`() {
        val secret = SecretValue.ofText(sentinel)

        val length = secret.use { it.revealBytes().size }
        secret.close()

        assertEquals(sentinel.length, length)
        assertFailsWith<IllegalStateException> { secret.revealBytes() }
    }

    @Test
    fun `a closed value still prints masked`() {
        val secret = SecretValue.ofText(sentinel)

        secret.close()

        assertMasked(secret.toString())
    }
}
