package io.github.thewisekd.phopencode.feature.chat.logic.subagent

import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ActiveChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatConnection
import io.github.thewisekd.phopencode.feature.chat.logic.contract.OTHER_PROFILE
import io.github.thewisekd.phopencode.feature.chat.logic.contract.PROFILE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The subagent presenter on the real [ActiveChatSession]: only the bound server is asked. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentBoundServerTest {
    private val root = SessionId("ses_sub_root")
    private val child = SessionId("ses_sub_child")

    private val treeCalls = setOf("sessions.list", "sessions.get", "sessions.activeStatuses", "messages.list")

    @Test
    fun `the tree comes from the bound server, and another server is asked nothing`() = runTest {
        val connection = ChatConnection(backgroundScope)
        connection.arrange = {
            it.sessions.seed(Session(id = root, title = "Root"))
            it.sessions.seed(Session(id = child, parentId = root, title = "Child"))
        }
        connection.connect()
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
        val chat = ActiveChatSession(root, connection.manager, backgroundScope)
        val presenter = DefaultSubagentPresenter(ChatRoute.Chat(root.value), backgroundScope, chat)
        runCurrent()

        assertEquals(listOf(child), presenter.state.value.subagents.map { it.sessionId })

        connection.switchTo(OTHER_PROFILE)
        runCurrent()
        val other = connection.lastClient()

        assertEquals(0, presenter.state.value.totalCount)
        assertTrue(other.control.calls.none { it in treeCalls }, "calls ${other.control.calls}")
        // The connection keeps a stream of its own; none asks for what keeps the tree live.
        assertTrue(other.events.requestedTypes.none { "session.execution.started" in it }, "types ${other.events.requestedTypes}")

        // Back on the bound server, the tree loads again from its new client.
        connection.switchTo(PROFILE)
        runCurrent()
        assertEquals(listOf(child), presenter.state.value.subagents.map { it.sessionId })
        assertTrue(connection.lastClient().control.calls.any { it == "sessions.activeStatuses" })
    }
}
