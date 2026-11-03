package io.github.thewisekd.phopencode.core.ssh.connect

import java.io.IOException
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RedactedFailureTest {
    @Test
    fun `keeps types and stack traces and drops every message`() {
        val inner = ConnectException("Connection refused to secret-host:2222")
        val outer = IOException("login for test-user failed", inner)

        val redacted = checkNotNull(RedactedFailure.of(outer))

        assertEquals(IOException::class.java.name, redacted.message)
        assertEquals(ConnectException::class.java.name, redacted.cause?.message)
        assertContentEquals(outer.stackTrace, redacted.stackTrace)
        val text = redacted.stackTraceToString()
        listOf("secret-host", "2222", "test-user").forEach { assertFalse(it in text, "<$it> leaked") }
    }

    @Test
    fun `null stays null`() {
        assertNull(RedactedFailure.of(null))
    }
}
