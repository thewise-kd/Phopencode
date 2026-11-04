package io.github.thewisekd.phopencode.feature.chat.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import kotlinx.coroutines.flow.StateFlow

/**
 * One chat screen's session, on one server, shared by all the presenters of that screen: one
 * event stream and one resync for the timeline, the composer, the cards, the usage and the status.
 *
 * Which server: the chat is bound to the server of the first `ConnectionState.Connected` it sees,
 * named by its `ConnectionServer` (profile and endpoint). A route carries no server, so a chat
 * opened right after a profile switch binds the new server, and shows [ChatAvailability.NotFound]
 * when the session is not there. Once bound, it never follows another server: a connection to any
 * other profile or endpoint shows [ChatAvailability.OtherServer], is asked nothing about the
 * session, and [call] refuses it.
 *
 * [ActiveChatSession] is the one implementation; tests may use their own.
 */
interface ChatSession {
    val sessionId: SessionId

    val state: StateFlow<ChatSessionState>

    /**
     * Runs [block] on the client of the connection standing right now, only if it is `Connected`
     * to the bound server. Presenters make every server call through here, never through the
     * connection manager directly.
     *
     * @throws ApiException With [ApiError.Closed] when no such connection stands, without running
     *   [block]; or whatever [block] throws.
     */
    suspend fun <T> call(block: suspend (OpencodeClient) -> T): T
}

/**
 * What the chat knows of its session.
 *
 * @property session The session, its messages, inbox, forms and permissions, as `SessionSync`
 *   keeps them: the fetched truth plus the events since, never a partly fetched one. Null until
 *   the first fetch completes, and whenever the session is [ChatAvailability.NotFound] or the
 *   connection serves [ChatAvailability.OtherServer]. Kept through a drop and a reconnect to the
 *   bound server, so the timeline stays while offline.
 * @property phase Where the sync is on the bound server's current connection; meaningful while
 *   [availability] is [ChatAvailability.Live].
 */
data class ChatSessionState(
    val availability: ChatAvailability = ChatAvailability.Waiting,
    val session: SessionState? = null,
    val phase: SyncPhase = SyncPhase.Connecting,
) {
    /** [SessionState] prints counts only, and [SyncPhase] carries no server text. */
    override fun toString(): String = "ChatSessionState(availability=$availability, session=$session, phase=$phase)"
}

/** Whether the chat can show and act on its session, and if not, why. */
sealed interface ChatAvailability {
    /** No server is bound yet, and a connection is being made. */
    data object Waiting : ChatAvailability

    /** Connected to the bound server; [ChatSessionState.phase] tells how far the sync is. */
    data object Live : ChatAvailability

    /** Bound, and the connection is being made again. What was loaded stays. */
    data object Reconnecting : ChatAvailability

    /** No connection stands, and none is being made. */
    data object NotConnected : ChatAvailability

    /** The bound server does not have the session: it never had it, or it was deleted. */
    data object NotFound : ChatAvailability

    /** The connection now serves another server. Nothing is asked of it about this session. */
    data object OtherServer : ChatAvailability

    /**
     * The sync ended with [failure], which retrying cannot help, such as refused credentials. The
     * next connection to the bound server tries again.
     */
    data class Failed(val failure: ChatFailure) : ChatAvailability
}
