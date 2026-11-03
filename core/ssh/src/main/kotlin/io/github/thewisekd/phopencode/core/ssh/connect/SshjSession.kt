package io.github.thewisekd.phopencode.core.ssh.connect

import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.schmizz.concurrent.Promise
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.DisconnectListener

/**
 * A logged-in sshj connection. It runs its own keep-alive rather than sshj's, whose interval has
 * whole-second steps: every interval it sends `keepalive@openssh.com` with a reply requested, and
 * after [SshConnectOptions.keepAliveMaxMissed] ticks in a row with a request unanswered or unsent,
 * the connection counts as lost, within interval × (keepAliveMaxMissed + 1) of the last reply.
 * A lost connection releases itself; [close] after that is a no-op.
 */
internal class SshjSession(
    internal val client: SSHClient,
    private val sockets: TrackingSocketFactory,
    options: SshConnectOptions,
    private val features: SessionFeatures,
) : SshSession {
    private val state = MutableStateFlow(true)
    private val closed = AtomicBoolean()
    private val resources = LinkedHashSet<AutoCloseable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ssh-session"))

    override val connected: StateFlow<Boolean> = state.asStateFlow()

    override val exec: RemoteExec = features.exec(this)

    private val keepAlive: Job?

    init {
        client.transport.disconnectListener = DisconnectListener { _, _ -> lost() }
        keepAlive = options.keepAliveInterval?.let { interval ->
            scope.launch { keepAlive(interval, options.keepAliveMaxMissed) }
        }
        // The connection may have ended before the listener was in place.
        if (!client.transport.isRunning) lost()
    }

    internal val keepAliveRunning: Boolean get() = keepAlive?.isActive == true

    override suspend fun openSftp(root: String): SftpClient = features.openSftp(this, root)

    override fun socketFactory(): SocketFactory = features.socketFactory(this)

    /** @throws SshException.Disconnected once the session is closed or lost. */
    internal fun requireConnected() {
        if (closed.get() || !state.value) throw SshException.Disconnected()
    }

    /**
     * Closes [resource] with the session. Returns false, after closing it, when the session is
     * already closed.
     */
    internal fun track(resource: AutoCloseable): Boolean {
        val added = synchronized(resources) { !closed.get() && resources.add(resource) }
        if (!added) closeQuietly(resource)
        return added
    }

    internal fun untrack(resource: AutoCloseable) {
        synchronized(resources) { resources.remove(resource) }
    }

    override fun close() {
        state.value = false
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        val open = synchronized(resources) { resources.toList().also { resources.clear() } }
        open.asReversed().forEach(::closeQuietly)
        // The polite goodbye writes to the socket, and a write blocks while the peer reads
        // nothing. So it gets its own thread and a short grace period; closing the socket then
        // breaks any blocked write, which also ends that thread.
        val goodbye = thread(isDaemon = true, name = "phopencode-ssh-disconnect") { runCatching { client.disconnect() } }
        try {
            goodbye.join(DISCONNECT_GRACE_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            sockets.closeAll()
        }
    }

    override fun toString(): String = "SshjSession(connected=${state.value})"

    /** Called on sshj's threads, so the release runs elsewhere. */
    private fun lost() {
        state.value = false
        if (!closed.get()) Dispatchers.IO.asExecutor().execute(::close)
    }

    /**
     * Each tick at which a request is still unanswered, or still not even sent, counts as missed;
     * a tick with everything answered resets the count. The send runs off this loop, because a
     * write blocks on a peer that reads nothing, and a blocked loop would never count. At most one
     * send is in flight; close() breaks it by closing the socket.
     */
    private suspend fun keepAlive(interval: Duration, maxMissed: Int) {
        val unanswered = ArrayDeque<Promise<SSHPacket, ConnectionException>>()
        var sending: Deferred<Result<Promise<SSHPacket, ConnectionException>>>? = null
        var missed = 0
        while (scope.isActive) {
            delay(interval)
            val done = sending?.takeIf { it.isCompleted }
            if (done != null) {
                // Completed, so await returns at once. A failed send means the transport is gone.
                unanswered.addLast(done.await().getOrNull() ?: break)
                sending = null
            }
            while (unanswered.firstOrNull()?.isFulfilled == true) unanswered.removeFirst()
            missed = if (unanswered.isEmpty() && sending == null) 0 else missed + 1
            if (missed >= maxMissed) break
            if (sending == null) {
                sending = scope.async {
                    runCatching { client.connection.sendGlobalRequest(KEEP_ALIVE_REQUEST, true, ByteArray(0)) }
                }
            }
        }
        lost()
    }

    private fun closeQuietly(resource: AutoCloseable) {
        runCatching { resource.close() }
    }

    private companion object {
        const val KEEP_ALIVE_REQUEST = "keepalive@openssh.com"
        const val DISCONNECT_GRACE_MILLIS = 500L
    }
}
