package io.github.thewisekd.phopencode.core.remote.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanReportTest {
    @Test
    fun `reads processes and ss listeners`() {
        val report = ScanReport.parse(
            """
            H proc
            P 1234
            P 99
            H ss
            S State  Recv-Q Send-Q Local Address:Port Peer Address:Port Process
            S LISTEN 0      512        127.0.0.1:4096      0.0.0.0:*    users:(("opencode",pid=1234,fd=20))
            S LISTEN 0      512            [::1]:4097         [::]:*    users:(("opencode",pid=1234,fd=21),("opencode",pid=99,fd=3))
            S LISTEN 0      128          0.0.0.0:22        0.0.0.0:*
            """.trimIndent(),
        )
        assertTrue(report.processToolSeen)
        assertTrue(report.listenerToolSeen)
        assertEquals(setOf(1234L, 99L), report.processes)
        assertEquals(
            setOf(
                Listener(1234, "127.0.0.1", 4096),
                Listener(1234, "::1", 4097),
                Listener(99, "::1", 4097),
                Listener(null, "0.0.0.0", 22),
            ),
            report.listeners.toSet(),
        )
    }

    @Test
    fun `reads proc net tables through socket inodes`() {
        val report = ScanReport.parse(
            """
            H proc
            P 1234
            H procnet
            T 1234 5551
            T 1234 5552
            T 1234 5553
            N4 0100007F:1000 5551
            N4 00000000:1001 5552
            N6 00000000000000000000000001000000:1002 5553
            N6 0000000000000000FFFF00000100007F:1003 5554
            N4 0101A8C0:1004 7777
            """.trimIndent(),
        )
        assertEquals(
            setOf(
                Listener(1234, "127.0.0.1", 0x1000),
                Listener(1234, "0.0.0.0", 0x1001),
                Listener(1234, "::1", 0x1002),
                Listener(null, "127.0.0.1", 0x1003),
                Listener(null, "192.168.1.1", 0x1004),
            ),
            report.listeners.toSet(),
        )
    }

    @Test
    fun `reads lsof field output`() {
        val report = ScanReport.parse(
            """
            H ps
            P 321
            H lsof
            L p321
            L f9
            L n127.0.0.1:4096
            L f10
            L n*:4097
            L n[::1]:4098
            """.trimIndent(),
        )
        assertTrue(report.processToolSeen)
        assertEquals(
            setOf(Listener(321, "127.0.0.1", 4096), Listener(321, "*", 4097), Listener(321, "::1", 4098)),
            report.listeners.toSet(),
        )
    }

    @Test
    fun `skips lines it cannot read`() {
        val report = ScanReport.parse(
            """
            P abc
            P -4
            P 0
            P 99999999999999999999
            Q 4
            S LISTEN 0 512 127.0.0.1:notaport 0.0.0.0:* users:(("x",pid=1,fd=1))
            S LISTEN 0 512 127.0.0.1:70000 0.0.0.0:* users:(("x",pid=1,fd=1))
            N4 zz:1000 1
            N6 00:1000 1
            L nonsense
            T 1 x
            """.trimIndent(),
        )
        assertFalse(report.processToolSeen)
        assertFalse(report.listenerToolSeen)
        assertEquals(emptySet(), report.processes)
        assertEquals(emptyList(), report.listeners)
    }

    @Test
    fun `names only loopback addresses as loopback`() {
        assertEquals("127.0.0.1", ListenerAddress.loopbackHost("127.0.0.1"))
        assertEquals("127.0.0.53", ListenerAddress.loopbackHost("127.0.0.53"))
        assertEquals("127.0.0.1", ListenerAddress.loopbackHost("127.0.0.1%lo"))
        assertEquals("::1", ListenerAddress.loopbackHost("[::1]"))
        assertEquals("::1", ListenerAddress.loopbackHost("::1"))
        assertEquals("127.0.0.1", ListenerAddress.loopbackHost("[::ffff:127.0.0.1]"))
        listOf("0.0.0.0", "*", "::", "[::]", "192.168.1.1", "1.0.0.127", "127.0.0.256", "0127.0.0.1", "localhost", "")
            .forEach { assertNull(ListenerAddress.loopbackHost(it), it) }
    }
}
