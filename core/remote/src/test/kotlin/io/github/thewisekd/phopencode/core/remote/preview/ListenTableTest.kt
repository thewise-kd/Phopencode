package io.github.thewisekd.phopencode.core.remote.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenTableTest {
    @Test
    fun `ss lines keep loopback and wildcard binds of every address form`() {
        val output = """
            LISTEN 0      4096           127.0.0.1:8080        0.0.0.0:*
            LISTEN 0      511              0.0.0.0:3000        0.0.0.0:*
            LISTEN 0      128                    *:5173              *:*
            LISTEN 0      4096                [::]:9000           [::]:*
            LISTEN 0      511   [::ffff:127.0.0.1]:4200              *:*
            LISTEN 0      4096        127.0.0.1%lo:53          0.0.0.0:*
            LISTEN 0      128                [::1]:631            [::]:*
            LISTEN 0      128              [::1]%lo:6006          [::]:*
            LISTEN 0      128                 :::7070             :::*
        """.trimIndent()
        assertEquals(setOf(8080, 3000, 5173, 9000, 4200, 53, 631, 6006, 7070), ListenTable.fromSs(output))
    }

    @Test
    fun `ss lines bound only to another address are dropped`() {
        val output = """
            LISTEN 0 128 10.0.0.5:22 0.0.0.0:*
            LISTEN 0 128 192.168.1.10:8000 0.0.0.0:*
            LISTEN 0 128 [fe80::1]%eth0:7000 [::]:*
            LISTEN 0 128 [2001:db8::5]:7001 [::]:*
            LISTEN 0 128 0.0.0.0%eth0:7002 0.0.0.0:*
            LISTEN 0 128 [::]%eth0:7005 [::]:*
            LISTEN 0 128 *%eth0:7006 *:*
            LISTEN 0 128 [::%wlan0]:7007 [::]:*
            LISTEN 0 128 127.0.0.1%eth0:7003 0.0.0.0:*
            LISTEN 0 128 [::ffff:10.0.0.5]:7004 *:*
        """.trimIndent()
        assertEquals(emptySet(), ListenTable.fromSs(output))
    }

    @Test
    fun `a port bound to another address and to loopback is kept`() {
        val output = "LISTEN 0 128 10.0.0.5:8000 0.0.0.0:*\nLISTEN 0 128 127.0.0.1:8000 0.0.0.0:*\n"
        assertEquals(setOf(8000), ListenTable.fromSs(output))
    }

    @Test
    fun `ss without the state column is read`() {
        assertEquals(setOf(8080), ListenTable.fromSs("0 4096 127.0.0.1:8080 0.0.0.0:*\n"))
    }

    @Test
    fun `malformed ss lines are skipped`() {
        val output = """
            State Recv-Q Send-Q Local Address:Port Peer Address:Port
            LISTEN 0 128 127.0.0.1:0 0.0.0.0:*
            LISTEN 0 128 127.0.0.1:65536 0.0.0.0:*
            LISTEN 0 128 127.0.0.1:http 0.0.0.0:*
            LISTEN 0 128 127.0.0.1 0.0.0.0:*
            LISTEN 0 128 [::1:80 [::]:*
            LISTEN 0 128 localhost:8080 0.0.0.0:*
            LISTEN 0 128 127.0.0.01:8081 0.0.0.0:*
            ESTAB 0 0 127.0.0.1:8082 127.0.0.1:5000
            garbage

            LISTEN 0 128 127.0.0.1:8083 0.0.0.0:*
        """.trimIndent()
        assertEquals(setOf(8083), ListenTable.fromSs(output))
    }

    @Test
    fun `addresses reachable on loopback`() {
        listOf("127.0.0.1", "0.0.0.0", "*", "::", "[::]", "::1", "[::1]", "[::ffff:127.0.0.1]", "127.0.0.1%lo", "[::1]%lo", "*%lo")
            .forEach { assertTrue(ListenTable.reachableOnLoopback(it), it) }
        listOf("10.0.0.5", "[fe80::1]%eth0", "0.0.0.0%eth0", "[::]%eth0", "*%eth0", "[::%eth0]", ":::%eth0","localhost", "", "[]", "[::1", "::1]", "[::ffff:10.0.0.1]", "127.0.0.1%", "1.2.3.4.5",
            "127.1.2.3", "127.0.0.53", "127.0.0.53%lo", "[::ffff:127.0.0.53]", "127.255.255.255")
            .forEach { assertFalse(ListenTable.reachableOnLoopback(it), it) }
    }

    @Test
    fun `proc net rows keep loopback and wildcard binds`() {
        val output = """
            E 0001
            4 0100007F:1F90
            4 00000000:0BB8
            4 0500000A:0016
            6 00000000000000000000000000000000:2328
            6 00000000000000000000000001000000:0277
            6 0000000000000000FFFF00000100007F:1068
            6 0000000000000000FFFF00000500000A:1069
            6 B80D0120000000000000000005000000:1B59
        """.trimIndent()
        assertEquals(setOf(8080, 3000, 9000, 631, 4200), ListenTable.fromProcNet(output))
    }

    @Test
    fun `malformed proc net rows are skipped`() {
        val output = """
            E 0001
            4 0100007F:0000
            4 0100007F
            4 0100007:1F90
            4 0100007G:1F90
            6 0100007F:1F90
            4 00000000000000000000000001000000:1F90
            5 0100007F:1F90
            4 0100007F:1F9
            4 0100007F:1F90 extra
            4 0100007F:1F91
        """.trimIndent()
        assertEquals(setOf(8081), ListenTable.fromProcNet(output))
    }

    @Test
    fun `big-endian proc net rows are read in native order`() {
        val output = """
            E 0100
            4 7F000001:1F90
            4 00000000:0BB8
            4 0A000005:0016
            6 00000000000000000000000000000000:2328
            6 00000000000000000000000000000001:0277
            6 00000000000000000000FFFF7F000001:1068
            6 00000000000000000000FFFF0A000005:1069
            6 20010DB8000000000000000000000005:1B59
        """.trimIndent()
        assertEquals(setOf(8080, 3000, 9000, 631, 4200), ListenTable.fromProcNet(output))
    }

    @Test
    fun `little-endian rows read as big-endian are not loopback, and the other way round`() {
        // 0100007F is 1.0.0.127 on a big-endian server; 7F000001 is 1.0.0.127 on a little-endian one.
        assertEquals(emptySet(), ListenTable.fromProcNet("E 0100\n4 0100007F:1F90\n6 00000000000000000000000001000000:0277\n"))
        assertEquals(emptySet(), ListenTable.fromProcNet("E 0001\n4 7F000001:1F90\n6 00000000000000000000000000000001:0277\n"))
    }

    @Test
    fun `an unknown byte order reads nothing`() {
        val rows = "4 0100007F:1F90\n4 7F000001:1F91\n"
        listOf("", "E \n", "E\n", "E 0010\n", "E 0001 0100\n", "E 0001\nE 0100\n", "E 00010000\n")
            .forEach { assertEquals(null, ListenTable.fromProcNet(it + rows), it) }
        assertEquals(setOf(8080), ListenTable.fromProcNet("E 0001\nE 0001\n$rows"))
    }

    @Test
    fun `ss lines bound only to another 127 address are dropped`() {
        val output = """
            LISTEN 0 4096 127.0.0.53%lo:53 0.0.0.0:*
            LISTEN 0 4096 127.0.0.54:53 0.0.0.0:*
            LISTEN 0 128 127.0.1.1:8000 0.0.0.0:*
            LISTEN 0 128 [::ffff:127.0.0.2]:8001 *:*
            0 128 127.0.0.2:8002 0.0.0.0:*
            LISTEN 0 128 127.0.0.1:8080 0.0.0.0:*
        """.trimIndent()
        assertEquals(setOf(8080), ListenTable.fromSs(output))
    }

    @Test
    fun `proc net rows bound only to another 127 address are dropped`() {
        // 127.0.0.53:53, 127.0.1.1:8000 and ::ffff:127.0.0.2:8001, then 127.0.0.1:8080.
        val little = """
            E 0001
            4 3500007F:0035
            4 0101007F:1F40
            6 0000000000000000FFFF00000200007F:1F41
            4 0100007F:1F90
        """.trimIndent()
        assertEquals(setOf(8080), ListenTable.fromProcNet(little))
        val big = """
            E 0100
            4 7F000035:0035
            4 7F000101:1F40
            6 00000000000000000000FFFF7F000002:1F41
            4 7F000001:1F90
        """.trimIndent()
        assertEquals(setOf(8080), ListenTable.fromProcNet(big))
    }
}
