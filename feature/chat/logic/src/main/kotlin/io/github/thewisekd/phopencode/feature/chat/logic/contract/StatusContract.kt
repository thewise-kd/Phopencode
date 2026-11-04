package io.github.thewisekd.phopencode.feature.chat.logic.contract

import io.github.thewisekd.phopencode.core.model.StructuredError
import kotlin.time.Instant

/**
 * Whether the session runs, retries, failed, or cannot be shown at all (P6.13, P6.14).
 *
 * Where it comes from: the chat's [ChatSession]: [ChatSessionState.availability] for the
 * connection and the session's presence, `SyncPhase.Offline` for a dropped event stream, and
 * `SessionState.status` and `SessionState.error` for the work. The banner shows while the chat is
 * disconnected and goes once it is back.
 *
 * @property status See [ChatStatus].
 */
data class StatusState(
    val status: ChatStatus = ChatStatus.Loading,
) {
    /**
     * Nothing of the session can be shown or done: the status takes the whole chat, in place of the
     * timeline and the composer.
     */
    val blocksChat: Boolean
        get() = when (status) {
            ChatStatus.Loading,
            ChatStatus.NotConnected,
            ChatStatus.NotFound,
            ChatStatus.OtherServer,
            is ChatStatus.Unavailable,
            -> true
            ChatStatus.Idle,
            ChatStatus.Running,
            is ChatStatus.Retrying,
            is ChatStatus.Failed,
            ChatStatus.Disconnected,
            -> false
        }
}

sealed interface ChatStatus {
    /** Waiting for the connection and the first fetch. */
    data object Loading : ChatStatus

    data object Idle : ChatStatus

    data object Running : ChatStatus

    /**
     * The provider failed and the server tries again (`SessionStatus.Retrying`).
     *
     * @property attempt The retry counter, from 1; null when the server gave none.
     * @property message The provider's text; prints masked.
     */
    data class Retrying(
        val attempt: Int?,
        val nextAttemptAt: Instant?,
        val message: String? = null,
    ) : ChatStatus {
        override fun toString(): String =
            "Retrying(attempt=$attempt, nextAttemptAt=$nextAttemptAt, message=${if (message == null) "null" else "***"})"
    }

    /** The last execution failed; [StructuredError] prints no server text. */
    data class Failed(val error: StructuredError?) : ChatStatus

    /** Loaded, and the connection or its event stream dropped: what shows may be behind. */
    data object Disconnected : ChatStatus

    /** No connection stands and nothing was loaded. */
    data object NotConnected : ChatStatus

    /** The active connection's server does not have the session. */
    data object NotFound : ChatStatus

    /** The connection now serves another server than the one the chat was opened on. */
    data object OtherServer : ChatStatus

    /** The session cannot be loaded for [failure]. */
    data class Unavailable(val failure: ChatFailure) : ChatStatus
}

sealed interface StatusIntent {
    /** Reconnects now: `ConnectionManager.reconnect`. */
    data object Reconnect : StatusIntent

    /** Leaves the chat, as from [ChatStatus.NotFound] or [ChatStatus.OtherServer]. */
    data object Close : StatusIntent
}

sealed interface StatusEvent {
    data object Close : StatusEvent
}

interface StatusPresenter : Presenter<StatusState, StatusIntent, StatusEvent>
