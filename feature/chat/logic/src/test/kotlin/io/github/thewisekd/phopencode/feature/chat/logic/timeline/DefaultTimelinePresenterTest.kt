package io.github.thewisekd.phopencode.feature.chat.logic.timeline

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.ToolStatus
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineNotice
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TurnOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTimelinePresenterTest {
    private val sessionId = SessionId("ses_timeline")
    private val done = Instant.fromEpochMilliseconds(1_000)

    /** A chat session the test drives by hand; the timeline must never call the server. */
    private class FakeChat(override val sessionId: SessionId) : ChatSession {
        val flow = MutableStateFlow(ChatSessionState())
        var calls = 0

        override val state: StateFlow<ChatSessionState> = flow

        override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
            calls++
            throw ApiException(ApiError.Closed)
        }
    }

    private fun live(messages: List<Message>, inbox: List<InboxItem> = emptyList()) = ChatSessionState(
        availability = ChatAvailability.Live,
        session = SessionState(session = Session(id = sessionId), messages = messages, inbox = inbox),
        phase = SyncPhase.Live,
    )

    private fun user(id: String, text: String = "secret prompt $id") =
        Message.User(MessageId(id), sessionId, text = text)

    private fun assistant(id: String, vararg blocks: AssistantBlock, completed: Boolean = true, error: StructuredError? = null) =
        Message.Assistant(
            MessageId(id),
            sessionId,
            completedAt = if (completed) done else null,
            blocks = blocks.toList(),
            error = error,
        )

    private fun inbox(id: String, text: String = "queued prompt", delivery: PromptDelivery? = PromptDelivery.Queue) =
        InboxItem(MessageId(id), sessionId, content = InboxContent.User(text), delivery = delivery)

    private fun users(count: Int, from: Int = 0) = (from until from + count).map { user("m%04d".format(it)) }

    private fun TestScope.open(chat: FakeChat, pageSize: Int = DefaultTimelinePresenter.DEFAULT_PAGE_SIZE) =
        DefaultTimelinePresenter(session = chat, scope = backgroundScope, pageSize = pageSize)

    private fun TimelineState.keys() = items.map { it.key }

    @Test
    fun `nothing is read before the scope runs`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(users(3))

        val presenter = open(chat)

        assertEquals(TimelineState(), presenter.state.value)
        runCurrent()
        assertEquals(3, presenter.state.value.items.size)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `it loads until the session is there, then shows the empty state`() = runTest {
        val chat = FakeChat(sessionId)
        val presenter = open(chat)
        runCurrent()
        assertTrue(presenter.state.value.loading)

        chat.flow.value = live(emptyList())
        runCurrent()

        assertTrue(presenter.state.value.isEmpty)
    }

    @Test
    fun `messages turn into items in order`() = runTest {
        val chat = FakeChat(sessionId)
        val error = StructuredError(type = "ProviderError")
        val model = ModelRef("prov", "mod")
        chat.flow.value = live(
            listOf(
                Message.User(MessageId("u1"), sessionId, text = "hello", files = listOf("a.kt"), agents = listOf("plan")),
                assistant(
                    "a1",
                    AssistantBlock.Reasoning("thinking"),
                    AssistantBlock.Text("answer", completedAt = done),
                    AssistantBlock.Unknown("future"),
                    AssistantBlock.Tool(id = "call1", name = "bash", status = ToolStatus.Completed),
                    error = error,
                ),
                Message.Shell(MessageId("s1"), sessionId, command = "ls", output = "out"),
                Message.Compaction(MessageId("c1"), sessionId, status = "completed", summary = "sum"),
                Message.AgentSwitched(MessageId("n1"), sessionId, agent = "build", previous = "plan"),
                Message.ModelSwitched(MessageId("n2"), sessionId, model = model),
                Message.LocationSwitched(MessageId("n3"), sessionId, directory = "/srv"),
                Message.SkillInvoked(MessageId("n4"), sessionId, name = "skill"),
                Message.Synthetic(MessageId("n5"), sessionId, text = "syn"),
                Message.System(MessageId("n6"), sessionId, text = "sys"),
                Message.Idle(MessageId("i1"), sessionId, outcome = SessionOutcome.Interrupted),
                Message.Idle(MessageId("i2"), sessionId, outcome = SessionOutcome.Unknown("new"), error = error),
                Message.Unknown(MessageId("x1"), sessionId, rawType = "move"),
            ),
        )
        val presenter = open(chat)
        runCurrent()

        val a1 = MessageId("a1")
        assertEquals(
            listOf(
                TimelineItem.User(TimelineKey.message(MessageId("u1")), MessageId("u1"), "hello", files = listOf("a.kt"), agents = listOf("plan")),
                TimelineItem.Reasoning(TimelineKey.reasoning(a1, 0), a1),
                TimelineItem.AssistantText(TimelineKey.text(a1, 1), a1, "answer", streaming = false),
                TimelineItem.Tool(TimelineKey.tool(a1, "call1"), a1, callId = "call1", name = "bash"),
                TimelineItem.AssistantError(TimelineKey.message(a1), a1, error),
                TimelineItem.Shell(TimelineKey.message(MessageId("s1")), MessageId("s1"), "ls", "out"),
                TimelineItem.Compaction(TimelineKey.message(MessageId("c1")), MessageId("c1"), "completed", "sum"),
                TimelineItem.Notice(TimelineKey.message(MessageId("n1")), MessageId("n1"), TimelineNotice.AgentSwitched("build", "plan")),
                TimelineItem.Notice(TimelineKey.message(MessageId("n2")), MessageId("n2"), TimelineNotice.ModelSwitched(model)),
                TimelineItem.Notice(TimelineKey.message(MessageId("n3")), MessageId("n3"), TimelineNotice.LocationSwitched("/srv")),
                TimelineItem.Notice(TimelineKey.message(MessageId("n4")), MessageId("n4"), TimelineNotice.SkillInvoked("skill")),
                TimelineItem.Notice(TimelineKey.message(MessageId("n5")), MessageId("n5"), TimelineNotice.Synthetic("syn")),
                TimelineItem.Notice(TimelineKey.message(MessageId("n6")), MessageId("n6"), TimelineNotice.System("sys")),
                TimelineItem.TurnEnded(TimelineKey.message(MessageId("i1")), MessageId("i1"), TurnOutcome.Interrupted),
                TimelineItem.TurnEnded(TimelineKey.message(MessageId("i2")), MessageId("i2"), TurnOutcome.Unknown, error),
                TimelineItem.Unknown(TimelineKey.message(MessageId("x1")), MessageId("x1"), "move"),
            ),
            presenter.state.value.items,
        )
    }

    @Test
    fun `text streams until its block or its message completes`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(listOf(assistant("a1", AssistantBlock.Text("part"), completed = false)))
        val presenter = open(chat)
        runCurrent()
        assertEquals(true, (presenter.state.value.items.single() as TimelineItem.AssistantText).streaming)

        chat.flow.value = live(listOf(assistant("a1", AssistantBlock.Text("part and more"), completed = true)))
        runCurrent()

        val text = presenter.state.value.items.single() as TimelineItem.AssistantText
        assertFalse(text.streaming)
        assertEquals("part and more", text.text)
    }

    @Test
    fun `a waiting prompt keeps its key once delivered`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(listOf(user("u1")), inbox = listOf(inbox("u2", delivery = PromptDelivery.Steer)))
        val presenter = open(chat)
        runCurrent()
        val pending = presenter.state.value.items.last() as TimelineItem.User
        assertEquals(PromptDelivery.Steer, pending.pending)
        assertEquals(TimelineKey.message(MessageId("u2")), pending.key)

        chat.flow.value = live(listOf(user("u1"), user("u2", "queued prompt")))
        runCurrent()

        val delivered = presenter.state.value.items.last() as TimelineItem.User
        assertEquals(pending.key, delivered.key)
        assertEquals(null, delivered.pending)
    }

    @Test
    fun `an item never appears twice under one key`() = runTest {
        val chat = FakeChat(sessionId)
        // Delivered and still in the inbox for a moment; and a tool call repeated by the server.
        val tool = AssistantBlock.Tool(id = "call1", name = "bash", status = ToolStatus.Running)
        chat.flow.value = live(listOf(user("u1"), assistant("a1", tool, tool)), inbox = listOf(inbox("u1")))
        val presenter = open(chat)
        runCurrent()

        val keys = presenter.state.value.keys()
        assertEquals(keys.distinct(), keys)
        assertEquals(2, keys.size)
        assertEquals(null, (presenter.state.value.items.first() as TimelineItem.User).pending)
    }

    @Test
    fun `only the newest page shows at first, and older ones load on request`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(users(25))
        val presenter = open(chat, pageSize = 10)
        runCurrent()

        with(presenter.state.value) {
            assertEquals(users(10, from = 15).map { TimelineKey.message(it.id) }, keys())
            assertTrue(hasOlder)
            assertFalse(loadingOlder)
            assertEquals(null, olderError)
        }

        presenter.onIntent(TimelineIntent.LoadOlder)
        assertEquals(20, presenter.state.value.items.size)
        assertTrue(presenter.state.value.hasOlder)

        presenter.onIntent(TimelineIntent.LoadOlder)
        assertEquals(users(25).map { TimelineKey.message(it.id) }, presenter.state.value.keys())
        assertFalse(presenter.state.value.hasOlder)

        presenter.onIntent(TimelineIntent.LoadOlder)
        assertEquals(25, presenter.state.value.items.size)
    }

    @Test
    fun `new messages widen the window instead of pushing old ones out`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(users(15))
        val presenter = open(chat, pageSize = 10)
        runCurrent()
        val first = presenter.state.value.items.first().key

        chat.flow.value = live(users(18))
        runCurrent()

        assertEquals(first, presenter.state.value.items.first().key)
        assertEquals(13, presenter.state.value.items.size)
        assertTrue(presenter.state.value.hasOlder)
    }

    @Test
    fun `the window survives the removal of its first message`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(users(15))
        val presenter = open(chat, pageSize = 10)
        runCurrent()

        chat.flow.value = live(users(15).filterNot { it.id == MessageId("m0005") })
        runCurrent()

        assertEquals(10, presenter.state.value.items.size)
        assertTrue(presenter.state.value.hasOlder)
    }

    @Test
    fun `waiting prompts always show below the window`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(users(15), inbox = listOf(inbox("q1"), inbox("q2")))
        val presenter = open(chat, pageSize = 10)
        runCurrent()

        val keys = presenter.state.value.keys()
        assertEquals(12, keys.size)
        assertEquals(listOf(TimelineKey.message(MessageId("q1")), TimelineKey.message(MessageId("q2"))), keys.takeLast(2))
    }

    @Test
    fun `it follows the latest item until the user scrolls away`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(users(2))
        val presenter = open(chat)
        runCurrent()
        assertTrue(presenter.state.value.followLatest)

        chat.flow.value = live(users(3))
        runCurrent()
        assertTrue(presenter.state.value.followLatest)
        assertEquals(0, presenter.state.value.unseenCount)

        presenter.onIntent(TimelineIntent.Scrolled(atLatest = false))
        assertFalse(presenter.state.value.followLatest)

        chat.flow.value = live(users(5))
        runCurrent()
        assertFalse(presenter.state.value.followLatest)
        assertEquals(2, presenter.state.value.unseenCount)
    }

    @Test
    fun `growth and delivery of items already there are not unseen`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(
            listOf(user("u1"), assistant("a1", AssistantBlock.Text("a"), completed = false)),
            inbox = listOf(inbox("u2")),
        )
        val presenter = open(chat)
        runCurrent()
        presenter.onIntent(TimelineIntent.Scrolled(atLatest = false))

        chat.flow.value = live(
            listOf(user("u1"), assistant("a1", AssistantBlock.Text("a longer text"), completed = false)),
            inbox = listOf(inbox("u2")),
        )
        runCurrent()
        chat.flow.value = live(
            listOf(user("u1"), assistant("a1", AssistantBlock.Text("a longer text")), user("u2", "queued prompt")),
        )
        runCurrent()

        assertEquals(0, presenter.state.value.unseenCount)
    }

    @Test
    fun `a prompt that leaves the inbox before its message arrives is not unseen`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(listOf(user("u1")), inbox = listOf(inbox("u2")))
        val presenter = open(chat)
        runCurrent()
        presenter.onIntent(TimelineIntent.Scrolled(atLatest = false))

        // Delivered: gone from the inbox one update before its message comes.
        chat.flow.value = live(listOf(user("u1")))
        runCurrent()
        chat.flow.value = live(listOf(user("u1"), user("u2", "queued prompt")))
        runCurrent()
        assertEquals(0, presenter.state.value.unseenCount)

        chat.flow.value = live(listOf(user("u1"), user("u2", "queued prompt"), assistant("a1", AssistantBlock.Text("reply"))))
        runCurrent()
        assertEquals(1, presenter.state.value.unseenCount)
    }

    @Test
    fun `a new prompt is unseen even when nothing shown before is left`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(emptyList(), inbox = listOf(inbox("q1")))
        val presenter = open(chat)
        runCurrent()
        presenter.onIntent(TimelineIntent.Scrolled(atLatest = false))

        chat.flow.value = live(emptyList())
        runCurrent()
        chat.flow.value = live(emptyList(), inbox = listOf(inbox("q2")))
        runCurrent()

        assertEquals(1, presenter.state.value.unseenCount)
    }

    @Test
    fun `older items loaded at the top are not unseen`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(users(15))
        val presenter = open(chat, pageSize = 10)
        runCurrent()
        presenter.onIntent(TimelineIntent.Scrolled(atLatest = false))

        presenter.onIntent(TimelineIntent.LoadOlder)
        chat.flow.value = live(users(15) + user("new"))
        runCurrent()

        assertEquals(1, presenter.state.value.unseenCount)
    }

    @Test
    fun `scrolling back down or jumping resumes following`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(users(1))
        val presenter = open(chat)
        runCurrent()

        presenter.onIntent(TimelineIntent.Scrolled(atLatest = false))
        chat.flow.value = live(users(3))
        runCurrent()
        presenter.onIntent(TimelineIntent.Scrolled(atLatest = true))
        assertTrue(presenter.state.value.followLatest)
        assertEquals(0, presenter.state.value.unseenCount)

        presenter.onIntent(TimelineIntent.Scrolled(atLatest = false))
        chat.flow.value = live(users(4))
        runCurrent()
        assertEquals(1, presenter.state.value.unseenCount)
        presenter.onIntent(TimelineIntent.JumpToLatest)
        assertTrue(presenter.state.value.followLatest)
        assertEquals(0, presenter.state.value.unseenCount)
    }

    @Test
    fun `another server's session is never shown, and the timeline starts over`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(users(15))
        val presenter = open(chat, pageSize = 10)
        runCurrent()
        presenter.onIntent(TimelineIntent.LoadOlder)
        presenter.onIntent(TimelineIntent.Scrolled(atLatest = false))

        chat.flow.value = ChatSessionState(availability = ChatAvailability.OtherServer)
        runCurrent()
        assertEquals(TimelineState(), presenter.state.value)

        chat.flow.value = live(users(15))
        runCurrent()
        assertEquals(10, presenter.state.value.items.size)
        assertTrue(presenter.state.value.followLatest)
        assertEquals(0, chat.calls, "the timeline asks the server nothing")
    }

    @Test
    fun `a drop keeps what was loaded`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(users(3))
        val presenter = open(chat)
        runCurrent()

        chat.flow.update { it.copy(availability = ChatAvailability.Reconnecting, phase = SyncPhase.Connecting) }
        runCurrent()

        assertEquals(3, presenter.state.value.items.size)
    }

    @Test
    fun `printing the timeline shows no message text`() = runTest {
        val chat = FakeChat(sessionId)
        chat.flow.value = live(
            listOf(user("u1", "typed-by-user"), assistant("a1", AssistantBlock.Text("written-by-model"))),
            inbox = listOf(inbox("q1", "waiting-text")),
        )
        val presenter = open(chat)
        runCurrent()

        val printed = presenter.state.value.toString()
        listOf("typed-by-user", "written-by-model", "waiting-text").forEach {
            assertFalse(it in printed, "printed $it")
        }
    }
}
