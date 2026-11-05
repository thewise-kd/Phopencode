package io.github.thewisekd.phopencode.feature.chat.logic.form

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.FormState
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldAnswer
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A [ChatSession] the test drives; [call] runs on [client] only while [connected]. */
private class SubagentChatSession(override val sessionId: SessionId) : ChatSession {
    @Volatile
    var client = FakeOpencodeClient()

    val mutableState = MutableStateFlow(ChatSessionState())

    override val state: StateFlow<ChatSessionState> = mutableState

    @Volatile
    var connected = true

    override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
        if (!connected) throw ApiException(ApiError.Closed)
        return block(client)
    }

    fun asked(name: String): Int = client.control.calls.count { it == name }
}

/** The forms of this session's subagents, at any depth, listed for the session's folder and kept live. */
@OptIn(ExperimentalCoroutinesApi::class)
class FormSubagentTest {
    private val folder = "/srv/project"
    private val root = SessionId("ses_root")
    private val child = SessionId("ses_child")
    private val grandchild = SessionId("ses_grand")
    private val stranger = SessionId("ses_stranger")
    private val elsewhere = SessionId("ses_elsewhere")

    private fun form(id: String, session: SessionId) = Form(
        id = FormId(id),
        sessionId = session,
        kind = FormKind.Question,
        fields = listOf(FormField.YesNo(key = "q0", required = true)),
    )

    private fun SubagentChatSession.live(vararg own: Form, availability: ChatAvailability = ChatAvailability.Live) {
        mutableState.value = ChatSessionState(
            availability = availability,
            session = SessionState(session = Session(id = root, directory = folder), forms = own.toList()),
            phase = SyncPhase.Live,
        )
    }

    private fun TestScope.setup(): Pair<SubagentChatSession, DefaultFormPresenter> {
        val chat = SubagentChatSession(root)
        chat.client.seedSessions()
        val presenter = DefaultFormPresenter(route = ChatRoute.Chat(root.value), scope = backgroundScope, session = chat)
        return chat to presenter
    }

    private fun FakeOpencodeClient.seedSessions() {
        with(sessions) {
            seed(Session(id = root, directory = folder))
            seed(Session(id = child, parentId = root, directory = folder))
            seed(Session(id = grandchild, parentId = child, directory = folder))
            seed(Session(id = stranger, directory = folder))
            seed(Session(id = elsewhere, parentId = root, directory = "/srv/other"))
        }
    }

    private fun DefaultFormPresenter.ids() = state.value.cards.map { it.formId.value }

    @Test
    fun `subagent forms at any depth are listed for the folder, others are not`() = runTest {
        val (chat, presenter) = setup()
        val own = form("frm_own", root)
        with(chat.client.interactions) {
            seedForm(own)
            seedForm(form("frm_child", child))
            seedForm(form("frm_grand", grandchild))
            seedForm(form("frm_stranger", stranger))
            seedForm(form("frm_elsewhere", elsewhere))
        }
        chat.live(own)
        runCurrent()

        assertEquals(listOf("frm_own", "frm_child", "frm_grand"), presenter.ids())
        val cards = presenter.state.value.cards
        assertFalse(cards[0].fromSubagent)
        assertTrue(cards[1].fromSubagent)
        assertEquals(child, cards[1].sessionId)
        assertEquals(grandchild, cards[2].sessionId)
        assertEquals(1, chat.asked("interactions.listForms"))
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `nothing is listed before the session is loaded and live`() = runTest {
        val (chat, presenter) = setup()
        chat.client.interactions.seedForm(form("frm_child", child))
        runCurrent()
        chat.mutableState.value = ChatSessionState(availability = ChatAvailability.Live)
        runCurrent()

        assertEquals(0, chat.asked("interactions.listForms"))
        assertTrue(presenter.state.value.cards.isEmpty())
    }

    @Test
    fun `a reply goes to the subagent's session`() = runTest {
        val (chat, presenter) = setup()
        chat.client.interactions.seedForm(form("frm_grand", grandchild))
        chat.live()
        runCurrent()

        presenter.onIntent(FormIntent.Answer(FormId("frm_grand"), "q0", FieldAnswer.YesNo(true)))
        presenter.onIntent(FormIntent.Submit(FormId("frm_grand")))
        runCurrent()

        assertEquals(
            FormState.Answered(mapOf("q0" to DataValue.Bool(true))),
            chat.client.interactions.stateOf(FormId("frm_grand")),
        )
        assertTrue(presenter.state.value.cards.isEmpty())
    }

    @Test
    fun `events add and remove subagent forms`() = runTest {
        val (chat, presenter) = setup()
        chat.live()
        runCurrent()
        chat.client.events.awaitConnections(1)
        assertEquals(setOf("form.created", "form.replied", "form.cancelled"), chat.client.events.requestedTypes.single())

        val created = form("frm_new", grandchild)
        chat.client.interactions.seedForm(created)
        chat.client.events.emit(ServerEvent.FormCreated(created))
        chat.client.events.emit(ServerEvent.FormCreated(form("frm_stranger", stranger)))
        chat.client.events.emit(ServerEvent.FormCreated(form("frm_own", root)))
        runCurrent()

        assertEquals(listOf("frm_new"), presenter.ids(), "Own forms come from the session's sync, not from here")

        chat.client.events.emit(ServerEvent.FormCancelled(grandchild, FormId("frm_new")))
        runCurrent()

        assertTrue(presenter.state.value.cards.isEmpty())
    }

    @Test
    fun `every new connection lists again`() = runTest {
        val (chat, presenter) = setup()
        chat.live()
        runCurrent()
        chat.client.events.awaitConnections(1)
        assertEquals(1, chat.asked("interactions.listForms"))

        chat.client.events.drop()
        chat.client.interactions.seedForm(form("frm_late", child))
        chat.client.events.reconnect()
        runCurrent()

        assertEquals(2, chat.asked("interactions.listForms"))
        assertEquals(listOf("frm_late"), presenter.ids())
    }

    @Test
    fun `a failed list keeps this session's forms, and retry lists again`() = runTest {
        val (chat, presenter) = setup()
        val own = form("frm_own", root)
        chat.client.interactions.seedForm(own)
        chat.client.interactions.seedForm(form("frm_child", child))
        chat.client.control.failNext(ApiError.Server(500, "boom"))
        chat.live(own)
        runCurrent()

        assertEquals(ChatFailure.ServerFailed, presenter.state.value.error)
        assertEquals(listOf("frm_own"), presenter.ids())

        presenter.onIntent(FormIntent.Retry)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(listOf("frm_own", "frm_child"), presenter.ids())
    }

    @Test
    fun `the stream starts again when the chat is live again`() = runTest {
        val (chat, presenter) = setup()
        chat.live()
        runCurrent()
        chat.client.events.awaitConnections(1)

        chat.connected = false
        chat.client.events.end()
        chat.live(availability = ChatAvailability.Reconnecting)
        runCurrent()
        chat.client.interactions.seedForm(form("frm_child", child))
        chat.connected = true
        chat.live()
        runCurrent()

        assertEquals(2, chat.client.events.connectionsOpened)
        assertEquals(listOf("frm_child"), presenter.ids())
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `another server or a lost session clears the subagent forms`() = runTest {
        val (chat, presenter) = setup()
        chat.client.interactions.seedForm(form("frm_child", child))
        chat.live()
        runCurrent()
        assertEquals(listOf("frm_child"), presenter.ids())

        chat.mutableState.value = ChatSessionState(availability = ChatAvailability.OtherServer)
        runCurrent()

        assertTrue(presenter.state.value.cards.isEmpty())
    }

    @Test
    fun `a parent loop does not hang the lookup`() = runTest {
        val (chat, presenter) = setup()
        val a = SessionId("ses_loop_a")
        val b = SessionId("ses_loop_b")
        chat.client.sessions.seed(Session(id = a, parentId = b, directory = folder))
        chat.client.sessions.seed(Session(id = b, parentId = a, directory = folder))
        chat.client.interactions.seedForm(form("frm_loop", a))
        chat.client.interactions.seedForm(form("frm_child", child))
        chat.live()
        runCurrent()

        assertEquals(listOf("frm_child"), presenter.ids())
    }

    @Test
    fun `an event admits only what a list would, so a subagent in another folder stays out`() = runTest {
        val (chat, presenter) = setup()
        chat.live()
        runCurrent()
        chat.client.events.awaitConnections(1)

        val far = form("frm_elsewhere", elsewhere)
        chat.client.interactions.seedForm(far)
        chat.client.events.emit(ServerEvent.FormCreated(far))
        runCurrent()

        assertTrue(presenter.state.value.cards.isEmpty())

        val near = form("frm_child", child)
        chat.client.interactions.seedForm(near)
        chat.client.events.emit(ServerEvent.FormCreated(near))
        chat.client.events.drop()
        chat.client.events.reconnect()
        runCurrent()

        assertEquals(listOf("frm_child"), presenter.ids(), "The relist keeps what the event admitted")
    }

    @Test
    fun `a stream that ends only after the chat is live again is replaced, once`() = runTest {
        val (chat, presenter) = setup()
        chat.live()
        runCurrent()
        val old = chat.client
        old.events.awaitConnections(1)

        // The chat drops and comes back on a new connection while the old stream is still running.
        chat.live(availability = ChatAvailability.Reconnecting)
        runCurrent()
        val fresh = FakeOpencodeClient().apply { seedSessions() }
        chat.client = fresh
        chat.live()
        runCurrent()
        // Only now does the old stream end.
        old.events.end()
        runCurrent()

        assertEquals(0, old.events.activeConnections.value)
        assertEquals(1, fresh.events.activeConnections.value)
        assertEquals(1, fresh.events.connectionsOpened)

        val created = form("frm_new", child)
        fresh.interactions.seedForm(created)
        fresh.events.emit(ServerEvent.FormCreated(created))
        runCurrent()

        assertEquals(listOf("frm_new"), presenter.ids())
    }

    @Test
    fun `a slow retry list that ends after the session went away brings no card back`() = runTest {
        val (chat, presenter) = setup()
        chat.client.interactions.seedForm(form("frm_child", child))
        chat.live()
        runCurrent()
        assertEquals(listOf("frm_child"), presenter.ids())

        chat.client.control.stall()
        presenter.onIntent(FormIntent.Retry)
        runCurrent()
        chat.client.control.awaitRunningCalls(1)

        chat.mutableState.value = ChatSessionState(availability = ChatAvailability.OtherServer)
        runCurrent()
        assertTrue(presenter.state.value.cards.isEmpty())

        chat.client.control.resume()
        runCurrent()

        assertTrue(presenter.state.value.cards.isEmpty())
        assertNull(presenter.state.value.error)
    }

    /** Seeds a chain of [depth] sessions below the root and returns the deepest. */
    private fun SubagentChatSession.chain(depth: Int): SessionId {
        var parent = root
        repeat(depth) { index ->
            val next = SessionId("ses_deep_$index")
            client.sessions.seed(Session(id = next, parentId = parent, directory = folder))
            parent = next
        }
        return parent
    }

    @Test
    fun `a subagent exactly at the depth limit is found, one below it is not`() = runTest {
        val (chat, presenter) = setup()
        val atLimit = chat.chain(64)
        chat.client.interactions.seedForm(form("frm_limit", atLimit))
        val below = SessionId("ses_too_deep")
        chat.client.sessions.seed(Session(id = below, parentId = atLimit, directory = folder))
        chat.client.interactions.seedForm(form("frm_too_deep", below))
        chat.live()
        runCurrent()

        assertEquals(listOf("frm_limit"), presenter.ids())
    }
}
