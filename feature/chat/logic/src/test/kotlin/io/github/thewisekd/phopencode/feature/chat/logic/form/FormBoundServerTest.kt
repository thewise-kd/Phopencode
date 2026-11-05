package io.github.thewisekd.phopencode.feature.chat.logic.form

import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ActiveChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatConnection
import io.github.thewisekd.phopencode.feature.chat.logic.contract.OTHER_PROFILE
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The form logic on the real [ActiveChatSession]: lists and replies reach the bound server only. */
@OptIn(ExperimentalCoroutinesApi::class)
class FormBoundServerTest {
    private val root = SessionId("ses_form_bound")
    private val child = SessionId("ses_form_bound_child")
    private val folder = "/srv/bound"

    private fun FakeOpencodeClient.asked(name: String) = control.calls.count { it == name }

    private fun FakeOpencodeClient.arrange() {
        sessions.seed(Session(id = root, directory = folder))
        sessions.seed(Session(id = child, parentId = root, directory = folder))
        interactions.seedForm(
            Form(
                id = FormId("frm_child"),
                sessionId = child,
                kind = FormKind.Other(null),
                fields = listOf(FormField.YesNo(key = "ok", defaultValue = true)),
            ),
        )
    }

    private suspend fun TestScope.connected(): ChatConnection {
        val connection = ChatConnection(backgroundScope)
        connection.arrange = { it.arrange() }
        connection.connect()
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
        return connection
    }

    @Test
    fun `subagent forms come from the bound server and replies go there`() = runTest {
        val connection = connected()
        val chat = ActiveChatSession(root, connection.manager, backgroundScope)
        val presenter = DefaultFormPresenter(ChatRoute.Chat(root.value), backgroundScope, chat)
        runCurrent()

        assertEquals(listOf("frm_child"), presenter.state.value.cards.map { it.formId.value })

        presenter.onIntent(FormIntent.Submit(FormId("frm_child")))
        runCurrent()

        assertEquals(1, connection.lastClient().asked("interactions.replyForm"))
        assertTrue(presenter.state.value.cards.isEmpty())
    }

    @Test
    fun `after a switch to another profile nothing is listed or answered there`() = runTest {
        val connection = connected()
        val chat = ActiveChatSession(root, connection.manager, backgroundScope)
        val presenter = DefaultFormPresenter(ChatRoute.Chat(root.value), backgroundScope, chat)
        runCurrent()

        connection.switchTo(OTHER_PROFILE)
        runCurrent()
        val other = connection.lastClient()

        presenter.onIntent(FormIntent.Retry)
        presenter.onIntent(FormIntent.Submit(FormId("frm_child")))
        presenter.onIntent(FormIntent.Decline(FormId("frm_child")))
        runCurrent()

        assertTrue(presenter.state.value.cards.isEmpty())
        assertEquals(0, other.asked("interactions.listForms"))
        assertEquals(0, other.asked("interactions.replyForm"))
        assertEquals(0, other.asked("interactions.cancelForm"))
        assertEquals(0, other.asked("sessions.get"))
    }
}
