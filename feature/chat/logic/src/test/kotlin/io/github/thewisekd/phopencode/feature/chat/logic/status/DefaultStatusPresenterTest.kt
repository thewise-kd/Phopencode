package io.github.thewisekd.phopencode.feature.chat.logic.status

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatConnection
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The status presenter on a session a test steers: how availability and the session's status map. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultStatusPresenterTest {
    private val sessionId = SessionId("ses_status")
    private val loaded = SessionState(session = Session(id = sessionId, title = "Status"))
    private val failure = StructuredError(type = "provider.transport", message = "provider " + "said no")
    private val nextAt = Instant.fromEpochSeconds(1_700_000_000)

    /** A session whose state the test sets; it makes no server call. */
    private class SteeredSession(override val sessionId: SessionId) : ChatSession {
        val mutable = MutableStateFlow(ChatSessionState())
        override val state: StateFlow<ChatSessionState> = mutable

        override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T = throw ApiException(ApiError.Closed)
    }

    private fun TestScope.presenter(): Pair<SteeredSession, DefaultStatusPresenter> {
        val session = SteeredSession(sessionId)
        val presenter = DefaultStatusPresenter(session, ChatConnection(backgroundScope).manager, backgroundScope)
        return session to presenter
    }

    private fun live(status: SessionStatus = SessionStatus.Idle, error: StructuredError? = null) =
        ChatSessionState(ChatAvailability.Live, loaded.copy(status = status, error = error), SyncPhase.Live)

    private fun TestScope.show(session: SteeredSession, presenter: DefaultStatusPresenter, state: ChatSessionState): ChatStatus {
        session.mutable.value = state
        runCurrent()
        return presenter.state.value.status
    }

    @Test
    fun `starts loading and does nothing in the constructor`() = runTest {
        val (session, presenter) = presenter()
        session.mutable.value = live(SessionStatus.Running)
        assertEquals(ChatStatus.Loading, presenter.state.value.status)
        assertTrue(presenter.state.value.blocksChat)
        runCurrent()
        assertEquals(ChatStatus.Running, presenter.state.value.status)
    }

    @Test
    fun `waiting and a first fetch show loading`() = runTest {
        val (session, presenter) = presenter()
        assertEquals(ChatStatus.Loading, show(session, presenter, ChatSessionState(ChatAvailability.Waiting)))
        assertEquals(
            ChatStatus.Loading,
            show(session, presenter, ChatSessionState(ChatAvailability.Live, null, SyncPhase.Syncing)),
        )
        assertEquals(
            ChatStatus.Loading,
            show(session, presenter, ChatSessionState(ChatAvailability.Live, null, SyncPhase.Retrying(ApiError.Timeout, 1, 1.seconds))),
        )
        assertTrue(presenter.state.value.blocksChat)
    }

    @Test
    fun `idle and running follow the session`() = runTest {
        val (session, presenter) = presenter()
        assertEquals(ChatStatus.Idle, show(session, presenter, live()))
        assertFalse(presenter.state.value.blocksChat)
        assertEquals(ChatStatus.Running, show(session, presenter, live(SessionStatus.Running)))
        assertEquals(ChatStatus.Idle, show(session, presenter, live(SessionStatus.Unknown("paused"))))
    }

    @Test
    fun `retrying carries the attempt, the next time and the message`() = runTest {
        val (session, presenter) = presenter()
        val shown = show(session, presenter, live(SessionStatus.Retrying(attempt = 2, message = "busy", nextAttemptAt = nextAt)))
        assertEquals(ChatStatus.Retrying(attempt = 2, nextAttemptAt = nextAt, message = "busy"), shown)
        assertFalse(presenter.state.value.blocksChat)
    }

    @Test
    fun `the retry counter follows the server's attempts`() = runTest {
        val (session, presenter) = presenter()
        assertEquals(1, (show(session, presenter, live(SessionStatus.Retrying(attempt = 1))) as ChatStatus.Retrying).attempt)
        assertEquals(ChatStatus.Running, show(session, presenter, live(SessionStatus.Running)))
        assertEquals(2, (show(session, presenter, live(SessionStatus.Retrying(attempt = 2))) as ChatStatus.Retrying).attempt)
        // A retry that names no attempt, within the same turn, keeps the last one the server gave.
        assertEquals(2, (show(session, presenter, live(SessionStatus.Retrying(attempt = null))) as ChatStatus.Retrying).attempt)
        assertEquals(3, (show(session, presenter, live(SessionStatus.Retrying(attempt = 3))) as ChatStatus.Retrying).attempt)
    }

    @Test
    fun `the retry counter resets when the turn ends`() = runTest {
        val (session, presenter) = presenter()
        show(session, presenter, live(SessionStatus.Retrying(attempt = 3)))
        assertEquals(ChatStatus.Idle, show(session, presenter, live()))
        assertEquals(
            ChatStatus.Retrying(attempt = null, nextAttemptAt = null),
            show(session, presenter, live(SessionStatus.Retrying(attempt = null))),
        )
        assertEquals(1, (show(session, presenter, live(SessionStatus.Retrying(attempt = 1))) as ChatStatus.Retrying).attempt)

        show(session, presenter, live(SessionStatus.Retrying(attempt = 4)))
        show(session, presenter, live(SessionStatus.Idle, failure))
        assertEquals(
            ChatStatus.Retrying(attempt = null, nextAttemptAt = null),
            show(session, presenter, live(SessionStatus.Retrying(attempt = null))),
        )
    }

    @Test
    fun `the retry counter resets when the turn ends while the chat is disconnected`() = runTest {
        val (session, presenter) = presenter()
        show(session, presenter, live(SessionStatus.Retrying(attempt = 3)))
        assertEquals(ChatStatus.Disconnected, show(session, presenter, live().copy(phase = SyncPhase.Offline(ApiError.Network))))
        assertEquals(
            ChatStatus.Retrying(attempt = null, nextAttemptAt = null),
            show(session, presenter, live(SessionStatus.Retrying(attempt = null))),
        )

        show(session, presenter, live(SessionStatus.Retrying(attempt = 5)))
        val failedWhileAway = live(SessionStatus.Failed("gone")).copy(availability = ChatAvailability.Reconnecting)
        assertEquals(ChatStatus.Disconnected, show(session, presenter, failedWhileAway))
        assertEquals(
            ChatStatus.Retrying(attempt = null, nextAttemptAt = null),
            show(session, presenter, live(SessionStatus.Retrying(attempt = null))),
        )

        show(session, presenter, live(SessionStatus.Retrying(attempt = 6)))
        val unknownWhileAway = live(SessionStatus.Unknown("paused")).copy(availability = ChatAvailability.NotConnected)
        assertEquals(ChatStatus.Disconnected, show(session, presenter, unknownWhileAway))
        assertEquals(
            ChatStatus.Retrying(attempt = null, nextAttemptAt = null),
            show(session, presenter, live(SessionStatus.Retrying(attempt = null))),
        )
    }

    @Test
    fun `an attempt the server gave while the chat was disconnected is kept`() = runTest {
        val (session, presenter) = presenter()
        show(session, presenter, live(SessionStatus.Running))
        val offline = live(SessionStatus.Retrying(attempt = 2)).copy(phase = SyncPhase.Offline(ApiError.Network))
        assertEquals(ChatStatus.Disconnected, show(session, presenter, offline))
        val reconnecting = live(SessionStatus.Retrying(attempt = 3)).copy(availability = ChatAvailability.Reconnecting)
        assertEquals(ChatStatus.Disconnected, show(session, presenter, reconnecting))
        assertEquals(3, (show(session, presenter, live(SessionStatus.Retrying(attempt = null))) as ChatStatus.Retrying).attempt)
    }

    @Test
    fun `a failed execution shows its error until a new one runs`() = runTest {
        val (session, presenter) = presenter()
        assertEquals(ChatStatus.Failed(failure), show(session, presenter, live(SessionStatus.Idle, failure)))
        assertFalse(presenter.state.value.blocksChat)
        assertEquals(ChatStatus.Running, show(session, presenter, live(SessionStatus.Running, failure)))
        assertEquals(ChatStatus.Failed(null), show(session, presenter, live(SessionStatus.Failed("gone"))))
        assertEquals(ChatStatus.Failed(failure), show(session, presenter, live(SessionStatus.Failed("gone"), failure)))
        assertEquals(ChatStatus.Idle, show(session, presenter, live()))
    }

    @Test
    fun `a drop of the bound server shows disconnected and a reconnect clears it`() = runTest {
        val (session, presenter) = presenter()
        show(session, presenter, live(SessionStatus.Running))

        val reconnecting = live(SessionStatus.Running).copy(availability = ChatAvailability.Reconnecting)
        assertEquals(ChatStatus.Disconnected, show(session, presenter, reconnecting))
        assertFalse(presenter.state.value.blocksChat)

        val notConnected = live(SessionStatus.Running).copy(availability = ChatAvailability.NotConnected)
        assertEquals(ChatStatus.Disconnected, show(session, presenter, notConnected))

        // Connected again: the session stays while it resyncs, and the banner goes.
        val resyncing = live(SessionStatus.Running).copy(phase = SyncPhase.Syncing)
        assertEquals(ChatStatus.Running, show(session, presenter, resyncing))
    }

    @Test
    fun `a dropped event stream or a failing resync shows disconnected`() = runTest {
        val (session, presenter) = presenter()
        show(session, presenter, live())
        assertEquals(ChatStatus.Disconnected, show(session, presenter, live().copy(phase = SyncPhase.Offline(ApiError.Network))))
        assertEquals(
            ChatStatus.Disconnected,
            show(session, presenter, live().copy(phase = SyncPhase.Retrying(ApiError.Timeout, 2, 2.seconds))),
        )
        assertEquals(ChatStatus.Idle, show(session, presenter, live()))
    }

    @Test
    fun `nothing loaded and no connection blocks the chat`() = runTest {
        val (session, presenter) = presenter()
        assertEquals(ChatStatus.NotConnected, show(session, presenter, ChatSessionState(ChatAvailability.NotConnected)))
        assertTrue(presenter.state.value.blocksChat)
        assertEquals(ChatStatus.Loading, show(session, presenter, ChatSessionState(ChatAvailability.Reconnecting)))
        assertTrue(presenter.state.value.blocksChat)
    }

    @Test
    fun `a session not found or on another server blocks the chat`() = runTest {
        val (session, presenter) = presenter()
        show(session, presenter, live(SessionStatus.Retrying(attempt = 2)))
        assertEquals(ChatStatus.NotFound, show(session, presenter, ChatSessionState(ChatAvailability.NotFound)))
        assertTrue(presenter.state.value.blocksChat)
        assertEquals(ChatStatus.OtherServer, show(session, presenter, ChatSessionState(ChatAvailability.OtherServer)))
        assertTrue(presenter.state.value.blocksChat)
        // The session's turn was lost with it: the counter starts over.
        assertEquals(
            ChatStatus.Retrying(attempt = null, nextAttemptAt = null),
            show(session, presenter, live(SessionStatus.Retrying(attempt = null))),
        )
    }

    @Test
    fun `a sync that cannot be retried shows why`() = runTest {
        val (session, presenter) = presenter()
        val failed = ChatSessionState(ChatAvailability.Failed(ChatFailure.Unauthorized), null, SyncPhase.Failed(ApiError.Unauthorized))
        assertEquals(ChatStatus.Unavailable(ChatFailure.Unauthorized), show(session, presenter, failed))
        assertTrue(presenter.state.value.blocksChat)

        val keptSession = failed.copy(session = loaded)
        assertEquals(ChatStatus.Disconnected, show(session, presenter, keptSession))
        assertFalse(presenter.state.value.blocksChat)
    }

    @Test
    fun `close is sent once`() = runTest {
        val (_, presenter) = presenter()
        presenter.onIntent(StatusIntent.Close)
        runCurrent()
        assertEquals(StatusEvent.Close, presenter.events.first())
    }

    @Test
    fun `reconnect with no connection does nothing`() = runTest {
        val connection = ChatConnection(backgroundScope)
        val session = SteeredSession(sessionId)
        val presenter = DefaultStatusPresenter(session, connection.manager, backgroundScope)
        runCurrent()
        presenter.onIntent(StatusIntent.Reconnect)
        runCurrent()
        assertTrue(connection.clients.isEmpty())
    }
}
