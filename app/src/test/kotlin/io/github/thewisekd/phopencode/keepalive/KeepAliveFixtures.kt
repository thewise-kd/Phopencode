package io.github.thewisekd.phopencode.keepalive

import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerInfo
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionError
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionServer
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.seconds

/**
 * Made-up server details. None is a real address; the notification tests check that none of them
 * ever shows.
 */
internal const val SECRET_HOST = "gizli-sunucu.invalid"
internal const val SECRET_PORT = 40_123
internal const val SECRET_PROFILE = "ozel-profil-kimligi"

internal val SERVER = ConnectionServer(ServerProfileId(SECRET_PROFILE), HostEndpoint(SECRET_HOST, SECRET_PORT))

/** A client the tests never call. */
private object UnusedClient : OpencodeClient {
    override val sessions get() = error("not used")
    override val messages get() = error("not used")
    override val interactions get() = error("not used")
    override val catalog get() = error("not used")
    override val vcs get() = error("not used")
    override val fs get() = error("not used")
    override val events get() = error("not used")

    override suspend fun serverInfo(): ServerInfo = error("not used")

    override fun close() = Unit
}

internal val CONNECTED: ConnectionState =
    ConnectionState.Connected(UnusedClient, ServerInfo(version = "2.3.0"), generation = 1, server = SERVER)
internal val RECONNECTING: ConnectionState =
    ConnectionState.Reconnecting(attempt = 2, retryIn = 4.seconds, cause = ConnectionError.Disconnected)
internal val HOST_APPROVAL: ConnectionState =
    ConnectionState.HostApproval(HostKey("ssh-ed25519", ByteArray(51) { it.toByte() }), server = SERVER)

/** Every kind of [ConnectionState], each with whether a connection is active then. */
internal val ACTIVE_STATES: List<ConnectionState> = listOf(
    ConnectionState.Ssh(),
    HOST_APPROVAL,
    ConnectionState.Server(),
    ConnectionState.Api(reconnectAttempt = 1),
    CONNECTED,
    RECONNECTING,
)
internal val ENDED_STATES: List<ConnectionState> = listOf(
    ConnectionState.Idle,
    ConnectionState.Error(ConnectionError.Unreachable),
    ConnectionState.Closed,
)

/** The connection as the keep-alive sees it: a state the test sets, and the disconnects asked for. */
internal class FakeConnection(initial: ConnectionState = ConnectionState.Idle) : KeepAliveConnection {
    override val state = MutableStateFlow(initial)
    var disconnects = 0
        private set

    override suspend fun disconnect() {
        disconnects++
        state.value = ConnectionState.Idle
    }
}
