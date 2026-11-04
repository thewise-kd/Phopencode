package io.github.thewisekd.phopencode.core.ssh.tunnel

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class InboxTest {
    private fun Inbox.read(length: Int, timeoutMillis: Int = 1_000): ByteArray {
        val out = ByteArray(length)
        val read = read(out, 0, length) { timeoutMillis }
        return if (read < 0) ByteArray(0) else out.copyOf(read)
    }

    @Test
    fun `reads span chunks in order`() {
        val inbox = Inbox()
        inbox.put(byteArrayOf(1, 2, 3))
        inbox.put(byteArrayOf(4, 5))
        assertContentEquals(byteArrayOf(1, 2), inbox.read(2))
        assertContentEquals(byteArrayOf(3, 4, 5), inbox.read(10))
    }

    @Test
    fun `a full inbox holds the filler back until a read makes room`() {
        val inbox = Inbox(capacity = 4)
        inbox.put(ByteArray(4))
        val second = CompletableFuture<Boolean>()
        thread(name = "test-filler") { second.complete(inbox.put(ByteArray(4))) }
        Thread.sleep(200)
        assertFalse(second.isDone)
        inbox.read(4)
        assertTrue(second.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `closing releases a waiting filler`() {
        val inbox = Inbox(capacity = 1)
        inbox.put(ByteArray(1))
        val second = CompletableFuture<Boolean>()
        thread(name = "test-filler") { second.complete(inbox.put(ByteArray(1))) }
        Thread.sleep(100)
        inbox.close()
        assertFalse(second.get(5, TimeUnit.SECONDS))
        assertFailsWith<SocketException> { inbox.read(1) }
    }

    @Test
    fun `buffered bytes come before the end and before a failure`() {
        val ended = Inbox()
        ended.put(byteArrayOf(1))
        ended.end()
        assertContentEquals(byteArrayOf(1), ended.read(5))
        assertEquals(-1, ended.read(ByteArray(1), 0, 1) { 0 })

        val failed = Inbox()
        val error = IOException("test failure")
        failed.put(byteArrayOf(2))
        failed.fail(error)
        assertContentEquals(byteArrayOf(2), failed.read(5))
        assertSame(error, assertFailsWith<IOException> { failed.read(1) })
    }

    @Test
    fun `an empty inbox times out and stays usable`() {
        val inbox = Inbox()
        assertFailsWith<SocketTimeoutException> { inbox.read(1, timeoutMillis = 50) }
        inbox.put(byteArrayOf(7))
        assertContentEquals(byteArrayOf(7), inbox.read(1, timeoutMillis = 50))
    }

    @Test
    fun `a zero-length read returns 0 in every open state`() {
        val inbox = Inbox()
        assertEquals(0, inbox.read(ByteArray(1), 0, 0) { 0 }, "empty")
        inbox.put(byteArrayOf(1))
        assertEquals(0, inbox.read(ByteArray(1), 0, 0) { 0 }, "with data")
        inbox.end()
        assertEquals(0, inbox.read(ByteArray(1), 0, 0) { 0 }, "ended with data")
        inbox.read(1)
        assertEquals(0, inbox.read(ByteArray(1), 0, 0) { 0 }, "after end of stream")

        val shut = Inbox()
        shut.shutdownInput()
        assertEquals(0, shut.read(ByteArray(1), 0, 0) { 0 }, "after shutdownInput")

        val failed = Inbox()
        failed.fail(IOException("test failure"))
        assertEquals(0, failed.read(ByteArray(1), 0, 0) { 0 }, "after a failure")

        val closed = Inbox()
        closed.close()
        assertFailsWith<SocketException> { closed.read(ByteArray(1), 0, 0) { 0 } }
    }
}
