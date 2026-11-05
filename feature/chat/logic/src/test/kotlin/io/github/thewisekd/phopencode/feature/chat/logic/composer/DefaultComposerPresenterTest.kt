package io.github.thewisekd.phopencode.feature.chat.logic.composer

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.MessageApi
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.Prompt
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerAction
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerError
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A [ChatSession] the test drives: its state is set by hand, and [call] runs on [client] only while
 * [connected], as `ActiveChatSession` runs it only on the bound server.
 */
private class FakeChatSession(override val sessionId: SessionId) : ChatSession {
    val client = FakeOpencodeClient()

    val mutableState = MutableStateFlow(ChatSessionState())

    override val state: StateFlow<ChatSessionState> = mutableState

    @Volatile
    var connected = true

    var calls = 0

    /** Every prompt the presenter handed over, as it handed it over. */
    val sent = mutableListOf<Prompt>()

    private val recording = object : OpencodeClient by client {
        override val messages: MessageApi = object : MessageApi by client.messages {
            override suspend fun prompt(sessionId: SessionId, prompt: Prompt): InboxItem {
                sent += prompt
                return client.messages.prompt(sessionId, prompt)
            }
        }
    }

    override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
        calls++
        if (!connected) throw ApiException(ApiError.Closed)
        return block(recording)
    }

    fun live(status: SessionStatus = SessionStatus.Idle) {
        mutableState.value = ChatSessionState(
            availability = ChatAvailability.Live,
            session = SessionState(Session(id = sessionId, title = "Chat"), status = status),
            phase = SyncPhase.Live,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultComposerPresenterTest {
    private val sessionId = SessionId("ses_composer")
    private val text = "fix the " + "build please"

    private fun TestScope.setup(status: SessionStatus = SessionStatus.Idle): Pair<FakeChatSession, DefaultComposerPresenter> {
        val chat = FakeChatSession(sessionId)
        chat.client.sessions.seed(Session(id = sessionId, title = "Chat"))
        chat.live(status)
        val presenter = DefaultComposerPresenter(session = chat, scope = backgroundScope)
        runCurrent()
        return chat to presenter
    }

    private fun TestScope.events(presenter: DefaultComposerPresenter): List<ComposerEvent> {
        val seen = mutableListOf<ComposerEvent>()
        backgroundScope.launch { presenter.events.collect { seen += it } }
        return seen
    }

    private suspend fun FakeChatSession.prompts() = client.messages.inbox(sessionId)

    private fun FakeChatSession.promptCalls() = client.control.calls.count { it == "messages.prompt" }

    private fun FakeChatSession.stopCalls() = client.control.calls.count { it == "sessions.stop" }

    @Test
    fun `nothing runs in the constructor`() = runTest {
        val chat = FakeChatSession(sessionId)
        chat.live()
        val presenter = DefaultComposerPresenter(session = chat, scope = backgroundScope)

        assertEquals(ComposerState(), presenter.state.value)

        runCurrent()

        assertTrue(presenter.state.value.enabled)
    }

    @Test
    fun `the composer is enabled only while the session is live and loaded`() = runTest {
        val (chat, presenter) = setup()
        assertTrue(presenter.state.value.enabled)

        val loaded = chat.mutableState.value
        for (availability in listOf(
            ChatAvailability.Waiting,
            ChatAvailability.Reconnecting,
            ChatAvailability.NotConnected,
            ChatAvailability.OtherServer,
            ChatAvailability.Failed(ChatFailure.Unauthorized),
        )) {
            chat.mutableState.value = loaded.copy(availability = availability)
            runCurrent()
            assertFalse(presenter.state.value.enabled, "$availability")
        }

        chat.mutableState.value = ChatSessionState(availability = ChatAvailability.Live, session = null)
        runCurrent()
        assertFalse(presenter.state.value.enabled)

        chat.mutableState.value = loaded
        runCurrent()
        assertTrue(presenter.state.value.enabled)
    }

    @Test
    fun `running follows the session's status`() = runTest {
        val (chat, presenter) = setup()
        assertFalse(presenter.state.value.running)

        chat.live(SessionStatus.Running)
        runCurrent()
        assertTrue(presenter.state.value.running)
        assertTrue(presenter.state.value.canStop)

        chat.live(SessionStatus.Retrying(attempt = 1))
        runCurrent()
        assertTrue(presenter.state.value.running)

        chat.live(SessionStatus.Idle)
        runCurrent()
        assertFalse(presenter.state.value.running)
        assertFalse(presenter.state.value.canStop)
    }

    @Test
    fun `the draft follows typing`() = runTest {
        val (_, presenter) = setup()

        presenter.onIntent(ComposerIntent.DraftChanged(text))

        assertEquals(text, presenter.state.value.draft)
        assertTrue(presenter.state.value.canSend)
    }

    @Test
    fun `a send while idle goes trimmed with the server's default delivery and clears the draft`() = runTest {
        val (chat, presenter) = setup()
        val seen = events(presenter)
        presenter.onIntent(ComposerIntent.DraftChanged("  $text \n"))

        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        assertEquals(listOf(Prompt(text)), chat.sent)
        assertEquals(InboxContent.User(text), chat.prompts().single().content)
        with(presenter.state.value) {
            assertEquals("", draft)
            assertFalse(sending)
            assertNull(error)
        }
        assertEquals(listOf<ComposerEvent>(ComposerEvent.Sent), seen)
    }

    @Test
    fun `every accepted prompt is announced, even when nobody collects for a while`() = runTest {
        val (chat, presenter) = setup()
        val count = 100

        repeat(count) {
            presenter.onIntent(ComposerIntent.DraftChanged("$text $it"))
            presenter.onIntent(ComposerIntent.Send)
            runCurrent()
        }
        assertEquals(count, chat.sent.size)

        val seen = events(presenter)
        runCurrent()

        assertEquals(List<ComposerEvent>(count) { ComposerEvent.Sent }, seen)
    }

    @Test
    fun `the draft stays until the server accepts the prompt, and repeated taps send once`() = runTest {
        val (chat, presenter) = setup()
        presenter.onIntent(ComposerIntent.DraftChanged(text))
        chat.client.control.stall()

        presenter.onIntent(ComposerIntent.Send)
        presenter.onIntent(ComposerIntent.Send)
        runCurrent()
        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        with(presenter.state.value) {
            assertTrue(sending)
            assertFalse(canSend)
            assertEquals(text, draft)
        }
        assertEquals(1, chat.promptCalls())

        chat.client.control.resume()
        runCurrent()

        assertEquals(1, chat.promptCalls())
        assertEquals(1, chat.prompts().size)
        assertEquals("", presenter.state.value.draft)
        assertFalse(presenter.state.value.sending)
    }

    @Test
    fun `the draft cannot change while a prompt is on its way`() = runTest {
        val (chat, presenter) = setup()
        presenter.onIntent(ComposerIntent.DraftChanged(text))
        chat.client.control.stall()
        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        presenter.onIntent(ComposerIntent.DraftChanged("$text and more"))
        assertEquals(text, presenter.state.value.draft)

        chat.client.control.resume()
        runCurrent()
        assertEquals("", presenter.state.value.draft)
    }

    @Test
    fun `a failed send keeps the text and shows the error`() = runTest {
        val (chat, presenter) = setup()
        val seen = events(presenter)
        presenter.onIntent(ComposerIntent.DraftChanged(text))
        chat.client.control.failNext(ApiError.Network)

        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        with(presenter.state.value) {
            assertEquals(text, draft)
            assertFalse(sending)
            assertEquals(ComposerError(ComposerAction.Send, ChatFailure.Unreachable), error)
            assertTrue(canSend)
        }
        assertTrue(seen.isEmpty())
        assertEquals(1, chat.promptCalls())

        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        assertEquals(2, chat.promptCalls())
        assertEquals("", presenter.state.value.draft)
        assertNull(presenter.state.value.error)
        assertEquals(listOf<ComposerEvent>(ComposerEvent.Sent), seen)
    }

    @Test
    fun `a send the bound server cannot take keeps the text`() = runTest {
        val (chat, presenter) = setup()
        presenter.onIntent(ComposerIntent.DraftChanged(text))
        chat.connected = false

        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        assertEquals(text, presenter.state.value.draft)
        assertEquals(ComposerError(ComposerAction.Send, ChatFailure.NotConnected), presenter.state.value.error)
        assertEquals(0, chat.promptCalls())
    }

    @Test
    fun `the error can be dismissed`() = runTest {
        val (chat, presenter) = setup()
        presenter.onIntent(ComposerIntent.DraftChanged(text))
        chat.client.control.failNext(ApiError.Timeout)
        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        presenter.onIntent(ComposerIntent.DismissError)

        assertNull(presenter.state.value.error)
        assertEquals(text, presenter.state.value.draft)
    }

    @Test
    fun `nothing is sent while disabled or blank`() = runTest {
        val (chat, presenter) = setup()
        presenter.onIntent(ComposerIntent.DraftChanged("   "))
        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        chat.mutableState.value = chat.mutableState.value.copy(availability = ChatAvailability.Reconnecting)
        runCurrent()
        presenter.onIntent(ComposerIntent.DraftChanged(text))
        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        assertEquals(0, chat.calls)
        assertEquals(text, presenter.state.value.draft)
    }

    @Test
    fun `while running a prompt steers by default`() = runTest {
        val (chat, presenter) = setup(SessionStatus.Running)
        presenter.onIntent(ComposerIntent.DraftChanged(text))

        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        assertEquals(listOf(Prompt(text, delivery = PromptDelivery.Steer)), chat.sent)
        assertEquals(PromptDelivery.Steer, chat.prompts().single().delivery)
        assertEquals("", presenter.state.value.draft)
    }

    @Test
    fun `while running a prompt can be queued`() = runTest {
        val (chat, presenter) = setup(SessionStatus.Running)
        presenter.onIntent(ComposerIntent.DeliveryChanged(PromptDelivery.Queue))
        presenter.onIntent(ComposerIntent.DraftChanged(text))

        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        assertEquals(PromptDelivery.Queue, presenter.state.value.delivery)
        assertEquals(listOf(Prompt(text, delivery = PromptDelivery.Queue)), chat.sent)
        assertEquals(PromptDelivery.Queue, chat.prompts().single().delivery)
    }

    @Test
    fun `a queue choice is not sent when the session is idle`() = runTest {
        val (chat, presenter) = setup(SessionStatus.Idle)
        presenter.onIntent(ComposerIntent.DeliveryChanged(PromptDelivery.Queue))
        presenter.onIntent(ComposerIntent.DraftChanged(text))

        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        assertEquals(listOf(Prompt(text, delivery = null)), chat.sent)
    }

    @Test
    fun `stop interrupts the running work once`() = runTest {
        val (chat, presenter) = setup(SessionStatus.Running)
        chat.client.sessions.setActivity(sessionId, SessionActivity.Running)
        chat.client.control.stall()

        presenter.onIntent(ComposerIntent.Stop)
        presenter.onIntent(ComposerIntent.Stop)
        runCurrent()

        assertTrue(presenter.state.value.stopping)
        assertFalse(presenter.state.value.canStop)

        chat.client.control.resume()
        runCurrent()

        assertEquals(1, chat.stopCalls())
        assertFalse(presenter.state.value.stopping)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `nothing is stopped when nothing runs`() = runTest {
        val (chat, presenter) = setup(SessionStatus.Idle)

        presenter.onIntent(ComposerIntent.Stop)
        runCurrent()

        assertEquals(0, chat.calls)
    }

    @Test
    fun `a failed stop shows the error`() = runTest {
        val (chat, presenter) = setup(SessionStatus.Running)
        chat.client.control.failNext(ApiError.Server(500))

        presenter.onIntent(ComposerIntent.Stop)
        runCurrent()

        assertFalse(presenter.state.value.stopping)
        assertEquals(ComposerError(ComposerAction.Stop, ChatFailure.ServerFailed), presenter.state.value.error)
    }

    @Test
    fun `the typed text stays out of the printed state`() = runTest {
        val (_, presenter) = setup()
        presenter.onIntent(ComposerIntent.DraftChanged(text))

        assertFalse(presenter.state.value.toString().contains(text))
        assertFalse(presenter.toString().contains(text))
    }

    private val file = PromptAttachment.File("src/" + "secret-plan.md")
    private val agent = PromptAttachment.Agent("explore")
    private val skill = PromptAttachment.Skill("review")

    @Test
    fun `attachments go along as the prompt's files, agents and skills, and clear once accepted`() = runTest {
        val (chat, presenter) = setup()
        val seen = events(presenter)
        presenter.onIntent(ComposerIntent.DraftChanged(text))
        presenter.onIntent(ComposerIntent.Attach(file))
        presenter.onIntent(ComposerIntent.Attach(agent))
        presenter.onIntent(ComposerIntent.Attach(skill))
        presenter.onIntent(ComposerIntent.Attach(PromptAttachment.File("README.md")))
        assertEquals(listOf(file, agent, skill, PromptAttachment.File("README.md")), presenter.state.value.attachments)

        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        assertEquals(
            listOf(Prompt(text, files = listOf(file.path, "README.md"), agents = listOf("explore"), skills = listOf("review"))),
            chat.sent,
        )
        assertEquals(emptyList(), presenter.state.value.attachments)
        assertEquals(listOf<ComposerEvent>(ComposerEvent.Sent), seen)
    }

    @Test
    fun `the same attachment is held once`() = runTest {
        val (_, presenter) = setup()

        presenter.onIntent(ComposerIntent.Attach(file))
        presenter.onIntent(ComposerIntent.Attach(PromptAttachment.File(file.path)))
        presenter.onIntent(ComposerIntent.Attach(agent))
        presenter.onIntent(ComposerIntent.Attach(agent))

        assertEquals(listOf(file, agent), presenter.state.value.attachments)
    }

    @Test
    fun `an attachment can be taken off`() = runTest {
        val (chat, presenter) = setup()
        presenter.onIntent(ComposerIntent.Attach(file))
        presenter.onIntent(ComposerIntent.Attach(skill))

        presenter.onIntent(ComposerIntent.Detach(file))
        presenter.onIntent(ComposerIntent.Detach(agent))
        assertEquals(listOf<PromptAttachment>(skill), presenter.state.value.attachments)

        presenter.onIntent(ComposerIntent.DraftChanged(text))
        presenter.onIntent(ComposerIntent.Send)
        runCurrent()
        assertEquals(listOf(Prompt(text, skills = listOf("review"))), chat.sent)
    }

    @Test
    fun `a failed send keeps the attachments`() = runTest {
        val (chat, presenter) = setup()
        presenter.onIntent(ComposerIntent.DraftChanged(text))
        presenter.onIntent(ComposerIntent.Attach(file))
        chat.client.control.failNext(ApiError.Network)

        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        assertEquals(listOf<PromptAttachment>(file), presenter.state.value.attachments)
        assertEquals(text, presenter.state.value.draft)

        presenter.onIntent(ComposerIntent.Send)
        runCurrent()
        assertEquals(listOf(file.path), chat.sent.last().files)
        assertEquals(emptyList(), presenter.state.value.attachments)
    }

    @Test
    fun `the attachments cannot change while a prompt is on its way`() = runTest {
        val (chat, presenter) = setup()
        presenter.onIntent(ComposerIntent.DraftChanged(text))
        presenter.onIntent(ComposerIntent.Attach(file))
        chat.client.control.stall()
        presenter.onIntent(ComposerIntent.Send)
        runCurrent()

        presenter.onIntent(ComposerIntent.Attach(agent))
        presenter.onIntent(ComposerIntent.Detach(file))
        assertEquals(listOf<PromptAttachment>(file), presenter.state.value.attachments)

        chat.client.control.resume()
        runCurrent()
        assertEquals(listOf(file.path), chat.sent.single().files)
        assertEquals(emptyList(), presenter.state.value.attachments)
    }

    @Test
    fun `attached paths stay out of the printed state and intents`() = runTest {
        val (_, presenter) = setup()
        presenter.onIntent(ComposerIntent.Attach(file))

        assertFalse(presenter.state.value.toString().contains(file.path))
        assertFalse(ComposerIntent.Attach(file).toString().contains(file.path))
        assertFalse(ComposerIntent.Detach(file).toString().contains(file.path))
    }
}
