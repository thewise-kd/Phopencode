package io.github.thewisekd.phopencode.feature.chat.logic.status

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The chat's status, from [session]'s state:
 *
 * - [ChatAvailability.Waiting], or a first fetch not done yet: [ChatStatus.Loading].
 * - Live with the session loaded: the session's own status. Running and retrying win over the error
 *   of an earlier execution, which shows as [ChatStatus.Failed] once the session is idle again.
 * - Loaded, and the bound server's connection dropped ([ChatAvailability.Reconnecting] or
 *   [ChatAvailability.NotConnected]), its event stream dropped ([SyncPhase.Offline]), a resync keeps
 *   failing ([SyncPhase.Retrying]) or the sync ended ([ChatAvailability.Failed]): [ChatStatus.Disconnected],
 *   which does not block the chat. Connected to the bound server again, the session's status shows again.
 * - Nothing loaded: [ChatStatus.NotConnected], [ChatStatus.Unavailable] or [ChatStatus.Loading].
 * - [ChatAvailability.NotFound] and [ChatAvailability.OtherServer] block the chat.
 *
 * Retry counter: [ChatStatus.Retrying.attempt] is the server's attempt. A retry that names none keeps
 * the last attempt the server gave in the same turn; the counter starts over when the turn ends (the
 * session's own status goes idle, failed or unknown, whatever the sync phase or the connection) or
 * the session is lost.
 *
 * [StatusIntent.Reconnect] calls [ConnectionManager.reconnect], which drops and remakes only the
 * app's own connection; it never stops, restarts or kills anything on the server. It does so only
 * while a connection stands that [ChatSession.call] accepts, that is one to the server [session] is
 * bound to, and from inside that call. In any other state it does nothing: a connection that is
 * being made or waiting to reconnect names no server, and neither its states nor
 * [ChatAvailability.Reconnecting] (set for any connect once a server is bound) prove it is the bound
 * server's, so Reconnect cannot shorten a wait it cannot prove is the bound server's.
 *
 * Nothing runs in the constructor: work starts in [scope] after a `yield()`. Nothing here logs.
 *
 * @param session The chat's session, whose availability, sync phase and status the banner shows.
 * @param connection Only for [StatusIntent.Reconnect]; the server stays the one [session] is bound
 *   to.
 */
class DefaultStatusPresenter(
    private val session: ChatSession,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
) : StatusPresenter {
    private val mutableState = MutableStateFlow(StatusState())

    override val state: StateFlow<StatusState> = mutableState.asStateFlow()

    private val eventChannel = Channel<StatusEvent>(Channel.BUFFERED)

    override val events: Flow<StatusEvent> = eventChannel.receiveAsFlow()

    /** The last attempt the server gave in the current turn; reset when the turn ends. */
    private var turnAttempt: Int? = null

    init {
        scope.launch {
            yield()
            session.state.collect { current -> mutableState.value = StatusState(statusOf(current)) }
        }
    }

    override fun onIntent(intent: StatusIntent) {
        when (intent) {
            StatusIntent.Reconnect -> scope.launch { reconnect() }
            StatusIntent.Close -> eventChannel.trySend(StatusEvent.Close)
        }
    }

    private suspend fun reconnect() {
        if (connection.state.value !is ConnectionState.Connected) return
        try {
            // Runs only while connected to the bound server, and reconnects the very connection the
            // call checked, never one that replaced it meanwhile.
            session.call { client ->
                val now = connection.state.value
                if (now is ConnectionState.Connected && now.client === client) connection.reconnect()
            }
        } catch (_: ApiException) {
            // Another server's connection, or none: left alone.
        }
    }

    private fun statusOf(current: ChatSessionState): ChatStatus {
        val loaded = current.session
        // The counter follows the session's own status whatever the connection or the sync shows
        // meanwhile: an attempt the server gave is recorded, and the turn's end resets it.
        when (val status = loaded?.status) {
            null, SessionStatus.Idle, is SessionStatus.Failed, is SessionStatus.Unknown -> turnAttempt = null
            is SessionStatus.Retrying -> if (status.attempt != null) turnAttempt = status.attempt
            SessionStatus.Running -> Unit
        }
        return when (val availability = current.availability) {
            ChatAvailability.Waiting -> ChatStatus.Loading
            ChatAvailability.NotFound -> ChatStatus.NotFound
            ChatAvailability.OtherServer -> ChatStatus.OtherServer
            ChatAvailability.Reconnecting -> if (loaded == null) ChatStatus.Loading else ChatStatus.Disconnected
            ChatAvailability.NotConnected -> if (loaded == null) ChatStatus.NotConnected else ChatStatus.Disconnected
            is ChatAvailability.Failed ->
                if (loaded == null) ChatStatus.Unavailable(availability.failure) else ChatStatus.Disconnected
            ChatAvailability.Live -> when {
                loaded == null -> ChatStatus.Loading
                current.phase is SyncPhase.Offline || current.phase is SyncPhase.Retrying -> ChatStatus.Disconnected
                current.phase is SyncPhase.Failed -> ChatStatus.Disconnected
                else -> workOf(loaded)
            }
        }
    }

    private fun workOf(loaded: SessionState): ChatStatus = when (val status = loaded.status) {
        SessionStatus.Running -> ChatStatus.Running
        is SessionStatus.Retrying ->
            ChatStatus.Retrying(attempt = turnAttempt, nextAttemptAt = status.nextAttemptAt, message = status.message)
        is SessionStatus.Failed -> ChatStatus.Failed(loaded.error)
        SessionStatus.Idle, is SessionStatus.Unknown ->
            if (loaded.error != null) ChatStatus.Failed(loaded.error) else ChatStatus.Idle
    }
}
