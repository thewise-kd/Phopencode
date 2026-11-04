package io.github.thewisekd.phopencode.core.ssh.tunnel

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoopbackTargetTest {
    private fun resolved(vararg bytes: Int, port: Int = 4096) =
        InetSocketAddress(InetAddress.getByAddress(ByteArray(bytes.size) { bytes[it].toByte() }), port)

    @Test
    fun `resolved IPv4 loopback addresses are allowed`() {
        assertEquals("127.0.0.1", LoopbackTarget.hostFor(resolved(127, 0, 0, 1)))
        assertEquals("127.1.2.3", LoopbackTarget.hostFor(resolved(127, 1, 2, 3)))
    }

    @Test
    fun `the resolved IPv6 loopback address is allowed`() {
        val ipv6 = ByteArray(16).also { it[15] = 1 }.map { it.toInt() }.toIntArray()
        assertEquals("::1", LoopbackTarget.hostFor(resolved(*ipv6)))
    }

    @Test
    fun `unresolved localhost and loopback literals are allowed`() {
        assertEquals("localhost", LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("localhost", 80)))
        assertEquals("localhost", LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("LocalHost", 80)))
        assertEquals("127.0.0.1", LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("127.0.0.1", 80)))
        assertEquals("::1", LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("::1", 80)))
        assertEquals("::1", LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("[::1]", 80)))
    }

    @Test
    fun `other addresses are refused`() {
        assertNull(LoopbackTarget.hostFor(resolved(192, 0, 2, 1)))
        assertNull(LoopbackTarget.hostFor(resolved(10, 0, 0, 1)))
        assertNull(LoopbackTarget.hostFor(resolved(0, 0, 0, 0)))
        assertNull(LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("example.invalid", 80)))
        assertNull(LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("localhost.example.invalid", 80)))
        assertNull(LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("127.0.0.1.example.invalid", 80)))
        assertNull(LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("127.0.0.256", 80)))
        assertNull(LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("127.0.0", 80)))
        assertNull(LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("0177.0.0.1", 80)))
        assertNull(LoopbackTarget.hostFor(InetSocketAddress.createUnresolved("", 80)))
    }

    @Test
    fun `port zero is refused`() {
        assertNull(LoopbackTarget.hostFor(resolved(127, 0, 0, 1, port = 0)))
    }
}
