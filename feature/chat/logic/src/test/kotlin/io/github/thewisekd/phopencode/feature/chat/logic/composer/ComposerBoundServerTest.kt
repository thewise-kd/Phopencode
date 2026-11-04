package io.github.thewisekd.phopencode.feature.chat.logic.composer

import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ActiveChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatConnection
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.OTHER_PROFILE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The composer on the real [ActiveChatSession]: prompts and stops reach the bound server only. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerBoundServerTest {
    private val sessionId = SessionId("ses_bound")
    private val text = "deploy " + "the branch"

    private fun FakeOpencodeClient.asked(name: String) = control.calls.count { it == name }

    private suspend fun TestScope.connected(): ChatConnection {
        val connection = ChatConnection(backgroundScope)
        connection.arrange = { it.sessions.seed(Session(id = sessionId, title = "Bound")) }
        connection.connect()
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
        return connection
    }

    @Test
    fun `a prompt goes to the bound server`() = runTest {
        val connection = connected()
        val chat = ActiveChatSession(sessionId, connection.manager, backgroundScope)
        val presenter = DefaultComposerPresenter(session = chat, scope = backgroundScope)
        runCurrent()
        assertTrue(presenter.state.value.enabled)

        presenter.onIntent(ComposerIntent.DraftChanged(text))
        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        assertEquals(1, connection.lastClient().asked("messages.prompt"))
        assertEquals("", presenter.state.value.draft)
    }

    @Test
    fun `after a switch to another profile nothing is sent or stopped there`() = runTest {
        val connection = connected()
        connection.lastClient().sessions.setActivity(sessionId, SessionActivity.Running)
        val chat = ActiveChatSession(sessionId, connection.manager, backgroundScope)
        val presenter = DefaultComposerPresenter(session = chat, scope = backgroundScope)
        runCurrent()

        connection.switchTo(OTHER_PROFILE)
        runCurrent()
        val other = connection.lastClient()
        other.sessions.setActivity(sessionId, SessionActivity.Running)

        presenter.onIntent(ComposerIntent.DraftChanged(text))
        presenter.onIntent(ComposerIntent.Send)
        presenter.onIntent(ComposerIntent.Stop)
        runCurrent()

        assertFalse(presenter.state.value.enabled)
        assertEquals(text, presenter.state.value.draft)
        assertEquals(0, other.asked("messages.prompt"))
        assertEquals(0, other.asked("sessions.stop"))
    }
}
