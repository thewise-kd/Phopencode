package io.github.thewisekd.phopencode.core.remote.connection

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class ConnectionErrorTest {
    @Test
    fun `Unexpected keeps its cause`() {
        val cause = IOException("broken")

        assertSame(cause, ConnectionError.Unexpected(cause).cause)
        assertNull(ConnectionError.Unexpected().cause)
    }

    @Test
    fun `Unexpected equals by type alone, whatever the cause`() {
        val one = ConnectionError.Unexpected(IOException("one"))
        val other = ConnectionError.Unexpected(IllegalStateException("other"))

        assertEquals<ConnectionError>(ConnectionError.Unexpected(), one)
        assertEquals<ConnectionError>(one, other)
        assertEquals(one.hashCode(), other.hashCode())
        assertEquals(ConnectionError.Unexpected().hashCode(), one.hashCode())
        assertEquals<ConnectionState>(ConnectionState.Error(one), ConnectionState.Error(ConnectionError.Unexpected()))
        assertFalse(one.equals(ConnectionError.Disconnected))
    }

    @Test
    fun `Unexpected prints no cause`() {
        val text = "printed-" + "detail"
        val error = ConnectionError.Unexpected(IOException(text, IllegalStateException(text)))

        assertEquals("Unexpected", error.toString())
        assertFalse(text in ConnectionState.Error(error).toString())
        assertFalse("IOException" in error.toString())
    }

    @Test
    fun `Unexpected stays not retryable`() {
        assertFalse(ConnectionError.Unexpected(IOException()).retryable)
    }
}
