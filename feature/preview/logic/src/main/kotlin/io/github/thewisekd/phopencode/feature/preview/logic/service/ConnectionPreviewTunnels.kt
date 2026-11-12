package io.github.thewisekd.phopencode.feature.preview.logic.service

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.remote.connection.OpencodePortRefusedException
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelEnd
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [PreviewTunnelSource] on P13.6's [ConnectionManager.openPreviewTunnel]: each tunnel is pinned to
 * its port on the loopback of the server of the connection standing when it opened, and belongs to
 * that connection.
 *
 * - A port outside 1..65535 throws [IllegalArgumentException] before the manager is asked.
 * - Cancellation is never wrapped: a caller cancelled before or during the open gets a
 *   [CancellationException] and no tunnel stays open.
 * - No connection is [PreviewFailure.NotConnected]; one that stood when the open began and is gone
 *   when the manager is asked is [PreviewFailure.ConnectionLost]; the opencode server's port is
 *   [PreviewFailure.OpencodePort]; a connection that changed while the tunnel opened is
 *   [PreviewFailure.ConnectionLost], and the tunnel is closed again. See [toTunnelFailure].
 * - A tunnel's [PreviewTunnel.end] becomes [PreviewTunnelEnd.ConnectionLost] as soon as its
 *   connection is no longer the [ConnectionState.Connected] of the generation it opened on: the
 *   manager closes the tunnel then, and it never moves to a later connection. A reload after the
 *   reconnect opens a new tunnel.
 *
 * [scope] runs one small watch per open tunnel, which ends with the tunnel. It is the service's own
 * by default, so the binding needs no scope; tests pass theirs.
 */
class ConnectionPreviewTunnels(
    private val connection: ConnectionManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PreviewTunnelSource {

    override suspend fun open(port: Int): PreviewTunnel {
        require(port in 1..MAX_TCP_PORT) { "Port is out of range" }
        // The manager publishes a connection's state and makes it current in one step, and every
        // new connection gets a higher generation: equal generations before and after the open
        // mean the tunnel is on that connection.
        // A caller cancelled before the open gets no tunnel at all.
        currentCoroutineContext().ensureActive()
        val before = connection.state.value
        val loopback = try {
            connection.openPreviewTunnel(port)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NotConnectedException) {
            // A connection stood when the open began and is gone now: it was lost under the open.
            throw PreviewException(
                if (before is ConnectionState.Connected) PreviewFailure.ConnectionLost else PreviewFailure.NotConnected,
            )
        } catch (failure: Exception) {
            throw PreviewException(failure.toTunnelFailure())
        }
        val after = connection.state.value
        if (before !is ConnectionState.Connected || after !is ConnectionState.Connected ||
            before.generation != after.generation
        ) {
            closeQuietly(loopback)
            throw PreviewException(PreviewFailure.ConnectionLost)
        }
        // Cancelled during the open: nobody would close the tunnel, so it is closed here.
        if (!currentCoroutineContext().isActive) {
            closeQuietly(loopback)
            currentCoroutineContext().ensureActive()
        }
        return ConnectionTunnel(loopback).also { it.watch(after.generation) }
    }

    override fun toString(): String = "ConnectionPreviewTunnels"

    private inner class ConnectionTunnel(override val loopback: SshSession.LoopbackTunnel) : PreviewTunnel {
        override val port: Int = loopback.port

        private val ended = MutableStateFlow<PreviewTunnelEnd?>(null)
        override val end: StateFlow<PreviewTunnelEnd?> = ended.asStateFlow()

        @Volatile
        private var watcher: Job? = null

        /** Ends this tunnel as lost once [generation] is no longer the connected one. */
        fun watch(generation: Long) {
            watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                connection.state.first { !(it is ConnectionState.Connected && it.generation == generation) }
                finish(PreviewTunnelEnd.ConnectionLost)
            }
            // Closed before the watch was set: it must not outlive the tunnel.
            if (ended.value != null) watcher?.cancel()
        }

        override fun close() = finish(PreviewTunnelEnd.Closed)

        private fun finish(reason: PreviewTunnelEnd) {
            if (!ended.compareAndSet(null, reason)) return
            closeQuietly(loopback)
            watcher?.cancel()
        }

        override fun toString(): String = "PreviewTunnel(***)"
    }

    private companion object {
        const val MAX_TCP_PORT = 65535

        fun closeQuietly(tunnel: SshSession.LoopbackTunnel) {
            try {
                tunnel.close()
            } catch (_: Exception) {
                // Closing is cleanup; the tunnel's end stands.
            }
        }
    }
}

/**
 * How a failure of [ConnectionManager.openPreviewTunnel] reaches the contract. The manager throws
 * [NotConnectedException] without a connection and [OpencodePortRefusedException] for the opencode
 * port; an SSH failure means the session ended under the open. Anything else is
 * [PreviewFailure.Unknown].
 */
internal fun Exception.toTunnelFailure(): PreviewFailure = when (this) {
    is NotConnectedException -> PreviewFailure.NotConnected
    is OpencodePortRefusedException -> PreviewFailure.OpencodePort
    is SshException -> PreviewFailure.ConnectionLost
    else -> PreviewFailure.Unknown
}
