package io.github.thewisekd.phopencode.feature.chat.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveChatSessionTest {
    private val sessionId = SessionId("ses_chat")
    private val session = Session(id = sessionId, title = "Chat", directory = "/srv/app")

    /** What a client was asked, apart from the version check every connection makes. */
    private fun FakeOpencodeClient.askedFor(): List<String> = control.calls.filter { it != "serverInfo" }

    private suspend fun TestScope.connected(
        arrange: (FakeOpencodeClient) -> Unit = { it.sessions.seed(session) },
    ): ChatConnection {
        val connection = ChatConnection(backgroundScope)
        connection.arrange = arrange
        connection.connect()
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
        return connection
    }

    private fun TestScope.open(connection: ChatConnection): ActiveChatSession =
        ActiveChatSession(sessionId, connection.manager, backgroundScope)

    @Test
    fun `nothing is asked in the constructor`() = runTest {
        val connection = connected()
        val chat = open(connection)

        assertEquals(ChatSessionState(), chat.state.value)
        assertEquals(sessionId, chat.sessionId)
        assertTrue(connection.lastClient().askedFor().isEmpty())
    }

    @Test
    fun `the session loads from the active connection's server`() = runTest {
        val connection = connected()
        val chat = open(connection)

        runCurrent()

        with(chat.state.value) {
            assertEquals(ChatAvailability.Live, availability)
            assertEquals(SyncPhase.Live, phase)
            assertEquals(sessionId, assertNotNull(session).session.id)
        }
    }

    @Test
    fun `a session the active server does not have is not found`() = runTest {
        val connection = connected(arrange = {})
        val chat = open(connection)

        runCurrent()

        assertEquals(ChatAvailability.NotFound, chat.state.value.availability)
        assertNull(chat.state.value.session)
    }

    @Test
    fun `opened right after a profile switch, only the new server is asked`() = runTest {
        // The old server has the session; the new one does not.
        val connection = connected()
        val old = connection.lastClient()
        connection.arrange = {}
        connection.switchTo(OTHER_PROFILE)
        runCurrent()
        val oldCalls = old.askedFor()

        val chat = open(connection)
        runCurrent()

        assertEquals(ChatAvailability.NotFound, chat.state.value.availability)
        assertEquals(oldCalls, old.askedFor(), "the old server is never asked")
        assertFailsWith<ApiException> { chat.call { it.sessions.get(sessionId) } }
    }

    @Test
    fun `without a connection the chat says so`() = runTest {
        val connection = ChatConnection(backgroundScope)
        val chat = open(connection)

        runCurrent()

        assertEquals(ChatAvailability.NotConnected, chat.state.value.availability)
        val failure = assertFailsWith<ApiException> { chat.call { error("must not run") } }
        assertEquals(ApiError.Closed, failure.error)
    }

    @Test
    fun `a call goes to the bound server's client`() = runTest {
        val connection = connected()
        val chat = open(connection)
        runCurrent()

        val client = chat.call { it }

        assertSame(connection.lastClient(), client)
    }

    @Test
    fun `a switch to another server stops the chat and asks that server nothing`() = runTest {
        // Both servers have a session with this ID: only the profile tells them apart.
        val connection = connected()
        val chat = open(connection)
        runCurrent()
        assertEquals(ChatAvailability.Live, chat.state.value.availability)

        connection.switchTo(OTHER_PROFILE)
        runCurrent()
        val other = connection.lastClient()

        assertEquals(ChatAvailability.OtherServer, chat.state.value.availability)
        assertNull(chat.state.value.session, "the old server's session is not shown")
        var ran = false
        val failure = assertFailsWith<ApiException> { chat.call { ran = true } }
        assertEquals(ApiError.Closed, failure.error)
        assertFalse(ran)
        assertTrue(other.askedFor().isEmpty(), "asked ${other.askedFor()}")
    }

    @Test
    fun `back on the bound server the chat loads again`() = runTest {
        val connection = connected()
        val chat = open(connection)
        runCurrent()
        connection.switchTo(OTHER_PROFILE)
        runCurrent()

        connection.switchTo(PROFILE)
        runCurrent()

        assertEquals(ChatAvailability.Live, chat.state.value.availability)
        assertNotNull(chat.state.value.session)
    }

    @Test
    fun `a session deleted meanwhile is not found`() = runTest {
        val connection = connected()
        val chat = open(connection)
        runCurrent()

        connection.lastClient().sessions.delete(sessionId)
        runCurrent()

        assertEquals(ChatAvailability.NotFound, chat.state.value.availability)
        assertNull(chat.state.value.session)
    }

    @Test
    fun `a drop keeps what was loaded while reconnecting`() = runTest {
        val connection = connected()
        val chat = open(connection)
        runCurrent()

        connection.manager.reconnect()
        runCurrent()

        assertNotNull(chat.state.value.session)
    }

    @Test
    fun `a refusal retrying cannot help ends in a failure`() = runTest {
        val connection = connected()
        connection.lastClient().control.failNext(ApiError.Forbidden)
        val chat = open(connection)

        runCurrent()

        assertEquals(ChatAvailability.Failed(ChatFailure.Rejected), chat.state.value.availability)
    }
}
