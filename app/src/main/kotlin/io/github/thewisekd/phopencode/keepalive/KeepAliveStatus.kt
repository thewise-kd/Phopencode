package io.github.thewisekd.phopencode.keepalive

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState

/** What the keep-alive notification says. It names no server: none of these carries a detail. */
internal enum class KeepAliveStatus { Connecting, Reconnecting, Connected }

/**
 * The notification's status for [state], or null when no connection is active and nothing needs
 * keeping alive: [ConnectionState.Idle], [ConnectionState.Error] and [ConnectionState.Closed].
 */
internal fun keepAliveStatus(state: ConnectionState): KeepAliveStatus? = when (state) {
    is ConnectionState.Connected -> KeepAliveStatus.Connected
    is ConnectionState.Reconnecting -> KeepAliveStatus.Reconnecting
    is ConnectionState.Ssh,
    is ConnectionState.HostApproval,
    is ConnectionState.Server,
    is ConnectionState.Api,
    -> KeepAliveStatus.Connecting
    ConnectionState.Idle,
    is ConnectionState.Error,
    ConnectionState.Closed,
    -> null
}

/** Whether a connection is active in [state], so the service must keep the process alive. */
internal fun keepsAlive(state: ConnectionState): Boolean = keepAliveStatus(state) != null
