package io.github.thewisekd.phopencode.feature.preview.logic.transport

import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Socket writes have no timeout, so a client that stops reading would block a write to it, and
 * hold its connection slot and tunnel socket, for good. One daemon thread per bridge looks at
 * every [WatchedOutput] a few times per [stallMillis]; a write that has made no progress for that
 * long gets its stall action, which closes the connection. No thread is started per write.
 */
internal class WriteWatchdog(stallMillis: Long) : AutoCloseable {
    private val stallNanos = stallMillis * NANOS_PER_MILLI
    private val period = (stallMillis / CHECKS_PER_STALL).coerceIn(MIN_PERIOD_MILLIS, MAX_PERIOD_MILLIS)
    private val watched = ConcurrentHashMap.newKeySet<WatchedOutput>()

    @Volatile private var closed = false

    private val checker = thread(isDaemon = true, name = THREAD) {
        while (!closed) {
            try {
                Thread.sleep(period)
            } catch (_: InterruptedException) {
                return@thread
            }
            val now = System.nanoTime()
            watched.filter { it.stalledNanos(now) >= stallNanos }.forEach { stalled ->
                watched.remove(stalled)
                runCatching { stalled.onStall() }
            }
        }
    }

    /** [out], watched until [forget]; [onStall] runs at most once, on the watchdog's thread. */
    fun watch(out: OutputStream, onStall: () -> Unit): WatchedOutput = WatchedOutput(out, onStall).also { watched += it }

    fun forget(output: WatchedOutput) {
        watched.remove(output)
    }

    override fun close() {
        closed = true
        watched.clear()
        checker.interrupt()
    }

    override fun toString(): String = "WriteWatchdog"

    private companion object {
        const val THREAD = "phopencode-bridge-watchdog"
        const val NANOS_PER_MILLI = 1_000_000L
        const val CHECKS_PER_STALL = 4
        const val MIN_PERIOD_MILLIS = 10L
        const val MAX_PERIOD_MILLIS = 1_000L
    }
}

/** An [OutputStream] that tells its [WriteWatchdog] how long its current write has been blocked. */
internal class WatchedOutput(private val out: OutputStream, val onStall: () -> Unit) : OutputStream() {
    @Volatile private var writing = false
    @Volatile private var since = 0L

    /** How long the write in progress has been blocked at [now]; 0 between writes. */
    fun stalledNanos(now: Long): Long = if (writing) now - since else 0L

    override fun write(b: Int) = watched { out.write(b) }

    override fun write(b: ByteArray, off: Int, len: Int) = watched { out.write(b, off, len) }

    override fun flush() = watched { out.flush() }

    override fun close() = out.close()

    private inline fun watched(block: () -> Unit) {
        since = System.nanoTime()
        writing = true
        try {
            block()
        } finally {
            writing = false
        }
    }
}
