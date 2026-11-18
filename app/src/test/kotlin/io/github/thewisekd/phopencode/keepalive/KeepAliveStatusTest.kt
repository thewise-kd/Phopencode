package io.github.thewisekd.phopencode.keepalive

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plain JVM: which states keep the app alive, and what the notification says for each. */
class KeepAliveStatusTest {
    @Test
    fun `every state of an active connection keeps the app alive`() {
        ACTIVE_STATES.forEach { assertTrue(keepsAlive(it), it.toString()) }
    }

    @Test
    fun `idle, error and closed let the app go`() {
        ENDED_STATES.forEach {
            assertFalse(keepsAlive(it), it.toString())
            assertNull(keepAliveStatus(it), it.toString())
        }
    }

    @Test
    fun `the status names connected, reconnecting, and connecting for every other active state`() {
        assertEquals(KeepAliveStatus.Connected, keepAliveStatus(CONNECTED))
        assertEquals(KeepAliveStatus.Reconnecting, keepAliveStatus(RECONNECTING))
        listOf(ConnectionState.Ssh(), HOST_APPROVAL, ConnectionState.Server(), ConnectionState.Api())
            .forEach { assertEquals(KeepAliveStatus.Connecting, keepAliveStatus(it), it.toString()) }
    }
}
