package io.github.thewisekd.phopencode.feature.chat.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionServer
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.session.sync.RetryDelays
import io.github.thewisekd.phopencode.core.session.sync.SessionSync
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.core.session.sync.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The [ChatSession] of one chat screen: follows `ConnectionManager.state`, binds the first
 * connected server, and runs a [SessionSync] on each connection to that server.
 *
 * - `Connected` to the bound server: a new [SessionSync] on its client, which resyncs; the session
 *   shown so far stays until the new fetch completes. A new connection to the same server cancels
 *   the old sync and starts again.
 * - `Connected` to another server: [ChatAvailability.OtherServer], the session is cleared, and no
 *   sync runs; nothing of that server is asked.
 * - Connecting or reconnecting: [ChatAvailability.Waiting] before a server is bound, else
 *   [ChatAvailability.Reconnecting]; the session stays.
 * - `Idle`, `Error` or `Closed`: [ChatAvailability.NotConnected]; the session stays.
 * - The sync fails with [ApiError.NotFound], or the session is deleted while shown:
 *   [ChatAvailability.NotFound], and the session is cleared. Any other error retrying cannot help:
 *   [ChatAvailability.Failed]. Either waits for the next connection to the bound server.
 *
 * Nothing runs in the constructor: the screen's scope may run a launch at once, so following
 * starts after a `yield()`. Nothing here logs.
 *
 * @param sessionId The session, from the route.
 * @param connection The app's connection; the chat follows its state.
 * @param scope The screen's scope; the sync stops with it.
 */
class ActiveChatSession(
    override val sessionId: SessionId,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
    private val retryDelays: RetryDelays = RetryDelays(),
) : ChatSession {
    private val mutableState = MutableStateFlow(ChatSessionState())

    override val state: StateFlow<ChatSessionState> = mutableState.asStateFlow()

    /** The server the chat is bound to; set once, by the follower only. */
    @Volatile
    private var server: ConnectionServer? = null

    init {
        scope.launch {
            yield()
            connection.state.collectLatest(::follow)
        }
    }

    override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
        val bound = server
        val current = connection.state.value
        if (bound == null || current !is ConnectionState.Connected || current.server != bound) {
            throw ApiException(ApiError.Closed)
        }
        return block(current.client)
    }

    private suspend fun follow(current: ConnectionState) {
        when (current) {
            is ConnectionState.Connected -> {
                val bound = server ?: current.server.also { server = it }
                if (current.server == bound) {
                    sync(current.client)
                } else {
                    mutableState.value = ChatSessionState(availability = ChatAvailability.OtherServer)
                }
            }
            ConnectionState.Idle, is ConnectionState.Error, ConnectionState.Closed ->
                mutableState.update { it.copy(availability = ChatAvailability.NotConnected) }
            is ConnectionState.Ssh,
            is ConnectionState.HostApproval,
            is ConnectionState.Server,
            is ConnectionState.Api,
            is ConnectionState.Reconnecting,
            -> mutableState.update {
                it.copy(availability = if (server == null) ChatAvailability.Waiting else ChatAvailability.Reconnecting)
            }
        }
    }

    /** Follows the session on [client] until the connection changes or the sync ends. */
    private suspend fun sync(client: OpencodeClient) {
        val sync = SessionSync(client, sessionId, retryDelays)
        val error = coroutineScope {
            val watcher = launch { sync.state.collect(::publish) }
            val failed = try {
                sync.run()
                null
            } catch (e: ApiException) {
                e.error
            }
            // Stopped before the failure is shown, so that no older state of the sync overwrites it.
            watcher.cancelAndJoin()
            failed
        } ?: return
        mutableState.update { current ->
            when (error) {
                ApiError.NotFound -> ChatSessionState(ChatAvailability.NotFound, session = null, phase = SyncPhase.Failed(error))
                // The client was closed with its connection; the connection's next state follows.
                ApiError.Closed -> current.copy(availability = ChatAvailability.Reconnecting)
                else -> current.copy(availability = ChatAvailability.Failed(ChatFailure.of(error)), phase = SyncPhase.Failed(error))
            }
        }
    }

    private fun publish(synced: SyncState) {
        // A failure is shown once the sync has ended; see sync.
        if (synced.phase is SyncPhase.Failed) return
        mutableState.update { current ->
            val loaded = synced.session
            if (loaded?.deleted == true) {
                ChatSessionState(ChatAvailability.NotFound, session = null, phase = synced.phase)
            } else {
                ChatSessionState(ChatAvailability.Live, session = loaded ?: current.session, phase = synced.phase)
            }
        }
    }
}
