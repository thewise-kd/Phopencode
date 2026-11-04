package io.github.thewisekd.phopencode.core.ssh.tunnel

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bytes received on a channel and not read yet. A reader thread fills it from sshj's stream, whose
 * reads have no per-read timeout; reads from here honour the socket's `soTimeout`, which OkHttp's
 * read timeouts depend on.
 *
 * It holds at most [capacity] bytes; beyond that the filling thread waits, sshj's window stops
 * growing and the server stops sending, so a slow reader slows the sender instead of filling
 * memory.
 */
internal class Inbox(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var buffered = 0
    private var ended = false
    private var failure: IOException? = null
    private var closed = false
    private var inputShutdown = false

    /**
     * Adds a chunk the caller no longer touches, waiting while the inbox is full. Returns false
     * once the inbox is closed, which tells the filling thread to stop. After [shutdownInput],
     * chunks are dropped.
     */
    fun put(chunk: ByteArray): Boolean = lock.withLock {
        while (!closed && !inputShutdown && buffered >= capacity) changed.await()
        when {
            closed -> false
            inputShutdown -> true
            else -> {
                chunks.addLast(chunk)
                buffered += chunk.size
                changed.signalAll()
                true
            }
        }
    }

    /** The peer finished sending; readers get end of stream after the buffered bytes. */
    fun end() = lock.withLock {
        ended = true
        changed.signalAll()
    }

    /** The channel failed; readers get [error] after the buffered bytes. */
    fun fail(error: IOException) = lock.withLock {
        if (failure == null) failure = error
        changed.signalAll()
    }

    /**
     * Makes waiting readers read their timeout again, for a timeout that changed while they wait.
     * The new timeout counts from now for a reader that was waiting without limit.
     */
    fun timeoutChanged() = lock.withLock { changed.signalAll() }

    fun shutdownInput() = lock.withLock {
        inputShutdown = true
        chunks.clear()
        buffered = 0
        headOffset = 0
        changed.signalAll()
    }

    /** Wakes every waiter; reads then fail with "Socket closed". */
    fun close() = lock.withLock {
        closed = true
        chunks.clear()
        buffered = 0
        changed.signalAll()
    }

    fun available(): Int = lock.withLock {
        if (closed) throw SocketException(SOCKET_CLOSED)
        if (inputShutdown) 0 else buffered
    }

    /**
     * Reads up to [length] bytes, waiting at most [timeoutMillis] for the first one; 0 waits without
     * limit. Returns -1 at end of stream.
     *
     * @throws SocketTimeoutException when nothing arrived in time; the socket stays usable.
     * @throws InterruptedIOException when the waiting thread is interrupted.
     * @throws SocketException once the socket is closed.
     */
    fun read(target: ByteArray, offset: Int, length: Int, timeoutMillis: () -> Int): Int {
        try {
            lock.lockInterruptibly()
        } catch (interrupted: InterruptedException) {
            throw interruptedIo(interrupted)
        }
        try {
            return awaitAndTake(target, offset, length, timeoutMillis)
        } finally {
            lock.unlock()
        }
    }

    private fun awaitAndTake(target: ByteArray, offset: Int, length: Int, timeoutMillis: () -> Int): Int {
        var waitedNanos = 0L
        while (true) {
            if (closed) throw SocketException(SOCKET_CLOSED)
            // InputStream requires 0 for a zero-length read, whatever the state of the stream.
            if (length == 0) return 0
            if (inputShutdown) return -1
            if (buffered > 0) return take(target, offset, length)
            if (ended) return -1
            failure?.let { throw it }
            // Read on each pass, so a timeout set while waiting applies to the rest of the wait.
            val limitNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis().toLong())
            try {
                if (limitNanos == 0L) {
                    changed.await()
                } else {
                    val remaining = limitNanos - waitedNanos
                    if (remaining <= 0) throw SocketTimeoutException(READ_TIMED_OUT)
                    val started = System.nanoTime()
                    changed.awaitNanos(remaining)
                    waitedNanos += System.nanoTime() - started
                }
            } catch (interrupted: InterruptedException) {
                throw interruptedIo(interrupted)
            }
        }
    }

    private fun take(target: ByteArray, offset: Int, length: Int): Int {
        var copied = 0
        while (copied < length && chunks.isNotEmpty()) {
            val head = chunks.first()
            val count = minOf(length - copied, head.size - headOffset)
            System.arraycopy(head, headOffset, target, offset + copied, count)
            copied += count
            headOffset += count
            if (headOffset == head.size) {
                chunks.removeFirst()
                headOffset = 0
            }
        }
        buffered -= copied
        changed.signalAll()
        return copied
    }

    private fun interruptedIo(cause: InterruptedException): InterruptedIOException {
        Thread.currentThread().interrupt()
        return InterruptedIOException(INTERRUPTED).apply { initCause(cause) }
    }

    companion object {
        const val DEFAULT_CAPACITY = 256 * 1024
        const val SOCKET_CLOSED = "Socket closed"
        const val READ_TIMED_OUT = "Read timed out"
        const val INTERRUPTED = "Interrupted"
    }
}
