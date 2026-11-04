package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerInfo
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import kotlin.time.Duration

/**
 * Where [ConnectionManager] is. A connect walks [Ssh], [HostApproval] when the host key is new,
 * [Server], [Api] and ends in [Connected] or [Error]. After a drop it goes to [Reconnecting] and
 * walks the steps again, each with its `reconnectAttempt`; a first connect has attempt 0.
 *
 * [Connected], [HostApproval] and [ConnectionError.HostKeyChanged] name the connect they belong to
 * with a [ConnectionServer], so that a screen never has to guess it from the profile it shows.
 *
 * Nothing here holds a secret, and the only address, [ConnectionServer.endpoint], prints masked, so
 * a state may be printed.
 */
sealed interface ConnectionState {
    /** No connection, and none is being made. */
    data object Idle : ConnectionState

    /** Reading the login secret, opening the SSH connection and logging in. */
    data class Ssh(val reconnectAttempt: Int = 0) : ConnectionState

    /**
     * The server presented a host key that was never approved. Nothing waits on the network side:
     * no credential has been sent. Answer with [ConnectionManager.approveHostKey] or
     * [ConnectionManager.rejectHostKey], passing this very state.
     *
     * @property server The profile being connected, and the endpoint whose known key was checked;
     *   approving stores [key] for that endpoint.
     */
    data class HostApproval(
        val key: HostKey,
        val reconnectAttempt: Int = 0,
        val server: ConnectionServer,
    ) : ConnectionState

    /** Finding the running `opencode serve`, or starting one. */
    data class Server(val reconnectAttempt: Int = 0) : ConnectionState

    /** Checking the server's API and version through the tunnel. */
    data class Api(val reconnectAttempt: Int = 0) : ConnectionState

    /**
     * Ready. [client] talks to the server through the SSH tunnel and is closed by the manager when
     * the connection ends, after which its calls fail with `ApiError.Closed`. Each connection has a
     * new client and a larger [generation]: whoever follows the server, such as `SessionSync`, starts
     * over on the new client, whose event stream opens with `ServerEvent.Connected` and so resyncs.
     *
     * @property info [ServerInfo.isNewerMajor] is a warning worth showing.
     * @property server The profile this connection was made for, and the endpoint it reached.
     */
    data class Connected(
        val client: OpencodeClient,
        val info: ServerInfo,
        val generation: Long,
        val server: ConnectionServer,
    ) : ConnectionState

    /**
     * Waiting [retryIn] before reconnect attempt [attempt] (from 1), after [cause]: the drop of a
     * connection that stood, or the failure of the previous attempt.
     */
    data class Reconnecting(val attempt: Int, val retryIn: Duration, val cause: ConnectionError) : ConnectionState

    /** Stopped; nothing is open. A new [ConnectionManager.connect] starts over. */
    data class Error(val error: ConnectionError) : ConnectionState

    /** [ConnectionManager.close] was called; nothing is open, and nothing can start again. */
    data object Closed : ConnectionState
}

/**
 * Which server a state belongs to: the profile [profileId] that [ConnectionManager.connect] was given,
 * and the [endpoint] (host and port) its SSH connection actually used, which is also the endpoint
 * whose known host key was checked. Both are taken from the profile when the connect began, so an
 * edit of the profile afterwards does not change them; a screen compares [profileId] with the
 * profile it serves instead of assuming the connection is the one it expects.
 *
 * Prints no address: [HostEndpoint] masks it.
 */
data class ConnectionServer(val profileId: ServerProfileId, val endpoint: HostEndpoint)
