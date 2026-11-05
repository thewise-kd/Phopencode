package io.github.thewisekd.phopencode.feature.chat.logic.status

import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ActiveChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatConnection
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.OTHER_PROFILE
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The status presenter on the real [ActiveChatSession]: Reconnect reaches the bound server's connection only. */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBoundServerTest {
    private val sessionId = SessionId("ses_status_bound")

    private suspend fun TestScope.connected(): ChatConnection {
        val connection = ChatConnection(backgroundScope)
        connection.arrange = { it.sessions.seed(Session(id = sessionId, title = "Bound")) }
        connection.connect()
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
        return connection
    }

    private fun TestScope.chat(connection: ChatConnection): DefaultStatusPresenter {
        val chat = ActiveChatSession(sessionId, connection.manager, backgroundScope)
        val presenter = DefaultStatusPresenter(chat, connection.manager, backgroundScope)
        runCurrent()
        return presenter
    }

    private fun ChatConnection.generation(): Long = (manager.state.value as ConnectionState.Connected).generation

    @Test
    fun `reconnect drops and remakes the bound server's connection, and the banner comes and goes`() = runTest {
        val connection = connected()
        val presenter = chat(connection)
        assertEquals(ChatStatus.Idle, presenter.state.value.status)
        val before = connection.generation()
        val clientsBefore = connection.clients.size

        presenter.onIntent(StatusIntent.Reconnect)
        runCurrent()

        assertIs<ConnectionState.Reconnecting>(connection.manager.state.value)
        assertEquals(ChatStatus.Disconnected, presenter.state.value.status)

        advanceTimeBy(1_100.milliseconds)
        runCurrent()
        assertTrue(connection.generation() > before)
        assertEquals(clientsBefore + 1, connection.clients.size)
        assertEquals(ChatStatus.Idle, presenter.state.value.status)
    }

    @Test
    fun `reconnect leaves a wait alone, since no state proves whose wait it is`() = runTest {
        val connection = connected()
        val presenter = chat(connection)

        connection.manager.reconnect()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(connection.manager.state.value)
        val clients = connection.clients.size

        presenter.onIntent(StatusIntent.Reconnect)
        runCurrent()

        assertIs<ConnectionState.Reconnecting>(connection.manager.state.value)
        assertEquals(clients, connection.clients.size)
        assertEquals(ChatStatus.Disconnected, presenter.state.value.status)
    }

    @Test
    fun `reconnect never cuts short another profile's wait, even when the presenter skipped its states`() = runTest {
        val connection = connected()
        val chat = ActiveChatSession(sessionId, connection.manager, backgroundScope)
        val held = HeldDispatcher()
        val presenter = DefaultStatusPresenter(chat, connection.manager, CoroutineScope(backgroundScope.coroutineContext + held))
        runCurrent()
        held.release()
        runCurrent()
        held.release()
        assertEquals(ChatStatus.Idle, presenter.state.value.status)

        // The presenter runs nothing while the connection switches profile and that one drops.
        connection.switchTo(OTHER_PROFILE)
        runCurrent()
        connection.manager.reconnect()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(connection.manager.state.value)
        val clients = connection.clients.size

        presenter.onIntent(StatusIntent.Reconnect)
        held.release()
        runCurrent()
        held.release()

        assertIs<ConnectionState.Reconnecting>(connection.manager.state.value)
        assertEquals(clients, connection.clients.size)
    }

    /** Runs what is dispatched to it only on [release], so a collector misses the states in between. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun release() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    @Test
    fun `reconnect never touches a connection to another profile`() = runTest {
        val connection = connected()
        val presenter = chat(connection)

        connection.switchTo(OTHER_PROFILE)
        runCurrent()
        assertEquals(ChatStatus.OtherServer, presenter.state.value.status)
        val other = connection.generation()
        val clients = connection.clients.size

        presenter.onIntent(StatusIntent.Reconnect)
        runCurrent()
        advanceTimeBy(1_100.milliseconds)
        runCurrent()

        assertEquals(other, connection.generation())
        assertEquals(clients, connection.clients.size)
    }

    @Test
    fun `reconnect never cuts short another profile's wait`() = runTest {
        val connection = connected()
        val presenter = chat(connection)

        connection.switchTo(OTHER_PROFILE)
        runCurrent()
        connection.manager.reconnect()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(connection.manager.state.value)
        val clients = connection.clients.size

        presenter.onIntent(StatusIntent.Reconnect)
        runCurrent()

        assertIs<ConnectionState.Reconnecting>(connection.manager.state.value)
        assertEquals(clients, connection.clients.size)
    }

    @Test
    fun `reconnect asks the server to stop nothing`() = runTest {
        val connection = connected()
        val presenter = chat(connection)
        val first = connection.lastClient()

        presenter.onIntent(StatusIntent.Reconnect)
        runCurrent()
        advanceTimeBy(1_100.milliseconds)
        runCurrent()

        val calls = connection.clients.flatMap { it.control.calls }
        val harmful = listOf("stop", "abort", "dispose", "delete", "interrupt", "kill", "restart")
        assertTrue(calls.none { call -> harmful.any { it in call.lowercase() } }, "calls: $calls")
        assertTrue(first.control.calls.isNotEmpty())
    }
}
