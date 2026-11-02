package io.github.thewisekd.phopencode.core.storage_api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class SecretReadResultTest {
    private val sentinel = "sentinel-" + "value"

    private fun describe(result: SecretReadResult): String = when (result) {
        is SecretReadResult.Found -> "found"
        SecretReadResult.NotFound -> "not found"
        SecretReadResult.Unreadable -> "unreadable"
    }

    @Test
    fun `a found value prints masked, alone and in a collection`() {
        val found = SecretReadResult.Found(SecretValue.ofText(sentinel))

        assertFalse(found.toString().contains("sentinel"), found.toString())
        assertFalse(listOf(found).toString().contains("sentinel"), listOf(found).toString())
    }

    @Test
    fun `a missing entry and an unreadable entry are different outcomes`() {
        assertNotEquals<SecretReadResult>(SecretReadResult.NotFound, SecretReadResult.Unreadable)
        assertEquals("not found", describe(SecretReadResult.NotFound))
        assertEquals("unreadable", describe(SecretReadResult.Unreadable))
    }

    @Test
    fun `a found result hands back the value`() {
        val found = SecretReadResult.Found(SecretValue.ofText(sentinel))

        assertEquals("found", describe(found))
        assertEquals(sentinel, found.value.revealText())
    }
}
