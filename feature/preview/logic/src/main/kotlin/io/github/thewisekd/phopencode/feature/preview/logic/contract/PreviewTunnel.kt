package io.github.thewisekd.phopencode.feature.preview.logic.contract

import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import kotlinx.coroutines.flow.StateFlow

/** Why a [PreviewTunnel] ended. */
enum class PreviewTunnelEnd {
    /** [PreviewTunnel.close] was called. */
    Closed,

    /** The SSH connection dropped or was closed; a reload after the reconnect opens a new tunnel. */
    ConnectionLost,
}

/**
 * A tunnel to one [port] on the server's loopback, on the session of the connection standing when it
 * was opened (P13.3's pinned tunnel from P13.6's connection manager). Opens no port on the phone.
 */
interface PreviewTunnel : AutoCloseable {
    /** The only port the tunnel reaches. */
    val port: Int

    /**
     * The pinned tunnel itself. Only the preview transport (P13.10) uses its socket factory; nothing
     * else in the feature opens a connection.
     */
    val loopback: SshSession.LoopbackTunnel

    /** Null while the tunnel is open; set once, when it ends, and never cleared. */
    val end: StateFlow<PreviewTunnelEnd?>

    /** Idempotent. Closes [loopback] and its sockets; [end] becomes [PreviewTunnelEnd.Closed] unless it ended before. */
    override fun close()
}

/** Opens [PreviewTunnel]s on the active connection (P13.6, wired by P13.7). */
interface PreviewTunnelSource {
    /**
     * @throws IllegalArgumentException when [port] is outside 1..65535.
     * @throws PreviewException with [PreviewFailure.NotConnected] without a connection,
     *   [PreviewFailure.OpencodePort] for the opencode server's port, or
     *   [PreviewFailure.ConnectionLost] when the connection ended while opening.
     */
    suspend fun open(port: Int): PreviewTunnel
}
