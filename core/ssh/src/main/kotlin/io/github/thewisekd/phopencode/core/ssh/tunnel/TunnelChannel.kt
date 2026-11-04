package io.github.thewisekd.phopencode.core.ssh.tunnel

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.TransportException

/**
 * One `direct-tcpip` channel. It differs from sshj's [DirectConnection] in two ways:
 *
 * - [openWithin] waits as long as the caller allows rather than sshj's session-wide timeout, and
 *   [cancel] ends that wait. A channel given up on before the server confirmed it is closed as soon
 *   as the confirmation arrives, so it never lingers on either side.
 * - [requestClose] never blocks: the close request is written on a thread of its own, and nothing
 *   waits for the server's answer. sshj's `close()` waits, which would block whoever closes a
 *   socket over a stalled connection.
 *
 * [openWithin] writes the open request itself instead of calling sshj's `open()`. That is safe
 * because sshj registers the channel with the connection in the `AbstractDirectChannel`
 * constructor (`conn.attach(this)`), not in `open()`, so the server's answer reaches this channel.
 *
 * [afterTimeout] is for tests: it runs after a timed-out wait, before the channel is given up.
 *
 * The local input stream is never closed here: sshj fails the whole connection when data reaches a
 * stream closed early. It ends when the server's close arrives, or when the connection fails.
 */
internal class TunnelChannel(
    connection: Connection,
    host: String,
    port: Int,
    private val afterTimeout: () -> Unit = {},
) : DirectConnection(connection, host, port) {
    private val lock = ReentrantLock()
    private val answer = lock.newCondition()
    private var abandoned = false
    private var sendFailure: TransportException? = null
    private val closeRequested = AtomicBoolean()

    enum class Opening { Opened, TimedOut, Cancelled }

    /** The server confirmed the channel. */
    val confirmed: Boolean get() = openEvent.isSet

    /**
     * Sends the open request and waits for the server's answer, for at most [timeoutMillis] in
     * total; 0 waits until the answer comes or the connection ends. Unless the result is
     * [Opening.Opened], the channel is abandoned.
     *
     * The request is written on a short-lived thread of its own, because a write blocks while the
     * transport cannot send, for example during a key exchange the server does not answer, and
     * the timeout must cover that too. A request still unsent when the caller gives up is dropped;
     * one sent anyway is closed when its confirmation arrives. The thread ends when the write
     * completes or fails, at the latest when the connection closes.
     *
     * @throws ConnectionException when the server refuses or the connection ends.
     * @throws TransportException when the request cannot be sent.
     * @throws InterruptedException when the waiting thread is interrupted.
     */
    fun openWithin(timeoutMillis: Long): Opening {
        require(timeoutMillis >= 0) { "Negative timeout" }
        thread(isDaemon = true, name = SENDER_THREAD) { send() }
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val outcome = try {
            lock.withLock {
                while (!answered() && !abandoned && sendFailure == null) {
                    if (timeoutMillis == 0L) {
                        answer.await()
                    } else {
                        if (remaining <= 0) break
                        remaining = answer.awaitNanos(remaining)
                    }
                }
                when {
                    answered() -> null
                    abandoned -> Opening.Cancelled
                    sendFailure != null -> null
                    else -> Opening.TimedOut
                }
            }
        } catch (interrupted: InterruptedException) {
            cancel()
            requestClose()
            throw interrupted
        }
        lock.withLock { sendFailure }?.takeIf { !answered() }?.let { failure ->
            cancel()
            throw failure
        }
        if (outcome != null) {
            if (outcome == Opening.TimedOut) afterTimeout()
            cancel()
            // A confirmation that arrived after the wait ended but before cancel() is not closed by
            // gotUnknown, so it is closed here.
            requestClose()
            return outcome
        }
        // Throws the refusal or the connection's failure.
        if (openEvent.inError()) openEvent.await()
        return Opening.Opened
    }

    /**
     * Gives the channel up, from any thread, without sending anything: a waiting [openWithin]
     * returns, and a confirmation arriving later gets a close request at once. A channel that is
     * already open needs [requestClose] as well.
     */
    fun cancel() {
        lock.withLock {
            abandoned = true
            answer.signalAll()
        }
    }

    /**
     * Sends the close request once, on a short-lived thread of its own, and returns at once. Does
     * nothing before the server confirmed the channel, since the request needs the server's
     * channel number.
     *
     * The write leaves the calling thread because it blocks while the transport cannot send, for
     * example during a key exchange, and the callers must not block: a connecting caller past its
     * deadline, a socket close, or sshj's reader thread, which is the one that finishes a key
     * exchange, so a write there would deadlock. The thread ends when the write completes or
     * fails, at the latest when the connection closes.
     */
    fun requestClose() {
        if (!openEvent.isSet || !closeRequested.compareAndSet(false, true)) return
        thread(isDaemon = true, name = CLOSER_THREAD) {
            try {
                sendClose()
            } catch (_: TransportException) {
                // The connection is gone, and the channel with it.
            }
        }
    }

    private fun send() {
        if (lock.withLock { abandoned }) return
        try {
            trans.write(buildOpenReq())
        } catch (failure: TransportException) {
            lock.withLock {
                sendFailure = failure
                answer.signalAll()
            }
        }
    }

    private fun answered(): Boolean = openEvent.isSet || openEvent.inError()

    override fun gotUnknown(msg: Message, buf: SSHPacket) {
        super.gotUnknown(msg, buf)
        // On sshj's reader thread, after the answer took effect: either this sees the abandonment
        // or cancel() sees the confirmation.
        val giveUp = lock.withLock {
            answer.signalAll()
            abandoned
        }
        if (msg == Message.CHANNEL_OPEN_CONFIRMATION && giveUp) requestClose()
    }

    override fun notifyError(error: SSHException) {
        super.notifyError(error)
        lock.withLock { answer.signalAll() }
    }

    private companion object {
        const val SENDER_THREAD = "phopencode-tunnel-open"
        const val CLOSER_THREAD = "phopencode-tunnel-close"
    }
}
