package io.github.thewisekd.phopencode.feature.chat.logic.actions

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.ForkBoundary
import io.github.thewisekd.phopencode.core.model.ForkBoundaryType
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionFork
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SessionAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val MAIN = SessionId("ses_main")
private val FIRST = MessageId("msg_first")
private val SECOND = MessageId("msg_second")
private const val FOLDER = "/srv/work/project"

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

    /** What the last call that reached the server returned. */
    @Volatile
    var lastResult: Any? = null

    /** Runs once a call's answer is in, before the caller sees it: lets the sync get there first. */
    var afterCall: (Any?) -> Unit = {}

    override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
        if (!connected) throw ApiException(ApiError.Closed)
        return block(client).also {
            lastResult = it
            afterCall(it)
        }
    }

    fun live(
        messages: List<Message> = emptyList(),
        inbox: List<InboxItem> = emptyList(),
        status: SessionStatus = SessionStatus.Idle,
        directory: String? = null,
    ) {
        mutableState.value = ChatSessionState(
            availability = ChatAvailability.Live,
            session = SessionState(
                Session(id = sessionId, directory = directory),
                status = status,
                messages = messages,
                inbox = inbox,
            ),
            phase = SyncPhase.Live,
        )
    }

    val calls: List<String>
        get() = client.control.calls
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultActionsPresenterTest {
    private val history = listOf(
        Message.User(FIRST, MAIN, text = "first"),
        Message.User(SECOND, MAIN, text = "second"),
    )

    private fun TestScope.setup(
        live: Boolean = true,
        route: ChatRoute = ChatRoute.Chat(MAIN.value),
    ): Triple<FakeChatSession, DefaultActionsPresenter, List<ActionsEvent>> {
        val chat = FakeChatSession(MAIN)
        chat.client.sessions.seed(Session(id = MAIN))
        chat.client.messages.seed(MAIN, history)
        if (live) chat.live(history)
        val presenter = DefaultActionsPresenter(route = route, scope = backgroundScope, session = chat)
        val events = mutableListOf<ActionsEvent>()
        backgroundScope.launch { presenter.events.toList(events) }
        runCurrent()
        return Triple(chat, presenter, events)
    }

    private fun compaction(id: MessageId, status: String?) = Message.Compaction(id, MAIN, status = status)

    // Start and enabled

    @Test
    fun `nothing runs before the scope gets to it`() = runTest {
        val chat = FakeChatSession(MAIN)
        chat.live(history)
        val presenter = DefaultActionsPresenter(ChatRoute.Chat(MAIN.value), backgroundScope, chat)

        assertEquals(ActionsState(), presenter.state.value)
        assertEquals(emptyList(), chat.calls)
    }

    @Test
    fun `enabled follows the loaded session and its live connection`() = runTest {
        val (chat, presenter) = setup(live = false)
        assertFalse(presenter.state.value.enabled)

        chat.live(history)
        runCurrent()
        assertTrue(presenter.state.value.enabled)
        assertTrue(presenter.state.value.canAct)

        chat.mutableState.value = chat.mutableState.value.copy(availability = ChatAvailability.Reconnecting)
        runCurrent()
        assertFalse(presenter.state.value.enabled)

        chat.mutableState.value = ChatSessionState(availability = ChatAvailability.OtherServer)
        runCurrent()
        assertFalse(presenter.state.value.enabled)
        assertFalse(presenter.state.value.canAct)
    }

    @Test
    fun `a route other than the chat never acts`() = runTest {
        val (chat, presenter) = setup(route = ChatRoute.Subagents(MAIN.value))
        assertFalse(presenter.state.value.enabled)

        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        assertEquals(emptyList(), chat.calls)
    }

    // Menu and confirmation

    @Test
    fun `the menu opens and closes, and a request closes it and asks first`() = runTest {
        val (chat, presenter) = setup()

        presenter.onIntent(ActionsIntent.OpenMenu)
        assertTrue(presenter.state.value.menuOpen)
        presenter.onIntent(ActionsIntent.CloseMenu)
        assertFalse(presenter.state.value.menuOpen)

        presenter.onIntent(ActionsIntent.OpenMenu)
        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        runCurrent()
        assertFalse(presenter.state.value.menuOpen)
        assertEquals(SessionAction.Compact, presenter.state.value.confirming)
        assertNull(presenter.state.value.running)
        assertEquals(emptyList(), chat.calls)

        presenter.onIntent(ActionsIntent.Cancel)
        runCurrent()
        assertNull(presenter.state.value.confirming)
        assertEquals(emptyList(), chat.calls)
    }

    @Test
    fun `the menu does not open while disabled`() = runTest {
        val (_, presenter) = setup(live = false)

        presenter.onIntent(ActionsIntent.OpenMenu)
        assertFalse(presenter.state.value.menuOpen)
    }

    @Test
    fun `Confirm with nothing to confirm sends nothing`() = runTest {
        val (chat, presenter) = setup()

        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        assertEquals(emptyList(), chat.calls)
        assertNull(presenter.state.value.running)
    }

    @Test
    fun `Confirm while disabled sends nothing and keeps the question`() = runTest {
        val (chat, presenter) = setup()
        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        chat.mutableState.value = chat.mutableState.value.copy(availability = ChatAvailability.Reconnecting)
        runCurrent()

        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        assertEquals(emptyList(), chat.calls)
        assertEquals(SessionAction.Compact, presenter.state.value.confirming)
    }

    // Compact

    @Test
    fun `a confirmed compaction is sent once, and compacting holds until the sync shows it ended`() = runTest {
        val (chat, presenter, events) = setup()
        chat.client.control.stall()

        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        assertEquals(SessionAction.Compact, presenter.state.value.running)
        assertNull(presenter.state.value.confirming)
        assertFalse(presenter.state.value.canAct)
        assertFalse(presenter.state.value.compacting)
        assertEquals(emptyList(), events)

        // The sync shows the item queued as the answer comes.
        chat.afterCall = { result -> chat.live(history, inbox = listOf(result as InboxItem)) }
        chat.client.control.resume()
        runCurrent()

        assertEquals(listOf("sessions.compact"), chat.calls)
        assertNull(presenter.state.value.running)
        assertTrue(presenter.state.value.compacting)
        assertEquals(listOf<ActionsEvent>(ActionsEvent.CompactionStarted), events)
        val item = chat.lastResult as InboxItem

        // Running in the timeline: still compacting.
        chat.live(history + compaction(item.id, "running"), status = SessionStatus.Running)
        runCurrent()
        assertTrue(presenter.state.value.compacting)

        chat.live(history + compaction(item.id, "completed"), status = SessionStatus.Running)
        runCurrent()
        assertFalse(presenter.state.value.compacting)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a compaction that fails on the server ends compacting`() = runTest {
        val (chat, presenter) = setup()
        chat.live(history, status = SessionStatus.Running)
        runCurrent()

        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        val id = (chat.lastResult as InboxItem).id
        assertTrue(presenter.state.value.compacting)

        chat.live(history + compaction(id, "failed"), status = SessionStatus.Running)
        runCurrent()
        assertFalse(presenter.state.value.compacting)
    }

    @Test
    fun `a compaction the sync showed ended before the answer came is not compacting`() = runTest {
        val (chat, presenter, events) = setup()
        // The sync reports it done while the answer is on its way, with the session still busy.
        chat.afterCall = { result ->
            chat.live(history + compaction((result as InboxItem).id, "completed"), status = SessionStatus.Running)
        }

        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()

        assertFalse(presenter.state.value.compacting)
        assertEquals(listOf<ActionsEvent>(ActionsEvent.CompactionStarted), events)
    }

    @Test
    fun `a compaction the sync added and removed before the answer came is not compacting`() = runTest {
        val (chat, presenter, events) = setup()
        chat.afterCall = { result ->
            chat.live(history, inbox = listOf(result as InboxItem))
            chat.live(history)
        }

        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()

        assertFalse(presenter.state.value.compacting)
        assertEquals(listOf<ActionsEvent>(ActionsEvent.CompactionStarted), events)

        // Nothing holds it back: another can be asked for.
        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        assertEquals(SessionAction.Compact, presenter.state.value.confirming)
    }

    @Test
    fun `a compaction the sync never shows ends once the session goes idle`() = runTest {
        val (chat, presenter) = setup()
        chat.live(history, status = SessionStatus.Running)
        runCurrent()

        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        assertTrue(presenter.state.value.compacting)

        chat.live(history, status = SessionStatus.Running)
        runCurrent()
        assertTrue(presenter.state.value.compacting)

        chat.live(history, status = SessionStatus.Idle)
        runCurrent()
        assertFalse(presenter.state.value.compacting)
    }

    @Test
    fun `a compaction whose end the sync missed ends once the session goes idle`() = runTest {
        val (chat, presenter) = setup()
        chat.live(history, status = SessionStatus.Running)
        runCurrent()
        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        val id = (chat.lastResult as InboxItem).id

        chat.live(history + compaction(id, "running"), status = SessionStatus.Running)
        runCurrent()
        assertTrue(presenter.state.value.compacting)

        // The end never reached the message, but the session is idle again.
        chat.live(history + compaction(id, "running"), status = SessionStatus.Idle)
        runCurrent()
        assertFalse(presenter.state.value.compacting)
    }

    @Test
    fun `a compaction still queued holds even while the session is idle`() = runTest {
        val (chat, presenter) = setup()
        chat.live(history, status = SessionStatus.Running)
        runCurrent()
        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        val item = chat.lastResult as InboxItem

        chat.live(history, inbox = listOf(item), status = SessionStatus.Idle)
        runCurrent()
        assertTrue(presenter.state.value.compacting)

        // Cancelled or reverted away.
        chat.live(history)
        runCurrent()
        assertFalse(presenter.state.value.compacting)
    }

    @Test
    fun `another compaction cannot be asked for while one runs on the server`() = runTest {
        val (chat, presenter) = setup()
        chat.live(history, status = SessionStatus.Running)
        runCurrent()
        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        assertTrue(presenter.state.value.compacting)

        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        assertNull(presenter.state.value.confirming)
        assertEquals(listOf("sessions.compact"), chat.calls)

        // A fork still can.
        presenter.onIntent(ActionsIntent.Request(SessionAction.Fork(SECOND)))
        assertEquals(SessionAction.Fork(SECOND), presenter.state.value.confirming)
    }

    @Test
    fun `a failed compaction sets the error and starts nothing`() = runTest {
        val (chat, presenter, events) = setup()
        chat.client.control.failNext(ApiError.Conflict(null))

        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()

        assertEquals(ActionError(SessionAction.Compact, ChatFailure.Conflict), presenter.state.value.error)
        assertNull(presenter.state.value.running)
        assertFalse(presenter.state.value.compacting)
        assertTrue(presenter.state.value.canAct)
        assertEquals(emptyList(), events)

        presenter.onIntent(ActionsIntent.DismissError)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a call the session refuses reports NotConnected`() = runTest {
        val (chat, presenter) = setup()
        chat.connected = false

        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()

        assertEquals(ActionError(SessionAction.Compact, ChatFailure.NotConnected), presenter.state.value.error)
        assertEquals(emptyList(), chat.calls)
    }

    // Fork

    @Test
    fun `a confirmed fork switches to the copy only once the server made it`() = runTest {
        val (chat, presenter, events) = setup()
        chat.client.control.stall()

        presenter.onIntent(ActionsIntent.Request(SessionAction.Fork(SECOND)))
        assertEquals(SessionAction.Fork(SECOND), presenter.state.value.confirming)
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        assertEquals(SessionAction.Fork(SECOND), presenter.state.value.running)
        assertEquals(emptyList(), events)

        // One action at a time.
        presenter.onIntent(ActionsIntent.Request(SessionAction.Compact))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        assertNull(presenter.state.value.confirming)

        chat.client.control.resume()
        runCurrent()

        assertEquals(listOf("sessions.fork"), chat.calls)
        val copy = chat.lastResult as Session
        assertEquals(SessionFork(MAIN, ForkBoundary(ForkBoundaryType.Before, SECOND)), copy.fork)
        assertEquals(listOf<ActionsEvent>(ActionsEvent.OpenSession(copy.id)), events)
        assertNull(presenter.state.value.running)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a fork of the whole history names no message`() = runTest {
        val (chat, presenter, events) = setup()

        presenter.onIntent(ActionsIntent.Request(SessionAction.Fork(null)))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()

        val copy = chat.lastResult as Session
        assertEquals(SessionFork(MAIN, ForkBoundary(ForkBoundaryType.Through, SECOND)), copy.fork)
        assertEquals(listOf<ActionsEvent>(ActionsEvent.OpenSession(copy.id)), events)
    }

    @Test
    fun `a failed fork shows the error and stays on this session`() = runTest {
        val (chat, presenter, events) = setup()
        chat.client.control.failNext(ApiError.Network)

        presenter.onIntent(ActionsIntent.Request(SessionAction.Fork(FIRST)))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()

        assertEquals(ActionError(SessionAction.Fork(FIRST), ChatFailure.Unreachable), presenter.state.value.error)
        assertNull(presenter.state.value.running)
        assertEquals(emptyList(), events)
        assertNull(chat.lastResult)

        // A new confirmed action clears the old error.
        presenter.onIntent(ActionsIntent.Request(SessionAction.Fork(FIRST)))
        presenter.onIntent(ActionsIntent.Confirm)
        runCurrent()
        assertNull(presenter.state.value.error)
        assertEquals(1, events.size)
    }

    // A turn's changes

    @Test
    fun `ShowTurnChanges opens that turn's diff`() = runTest {
        val (chat, presenter, events) = setup()

        presenter.onIntent(ActionsIntent.ShowTurnChanges(FIRST))
        runCurrent()

        assertEquals(listOf<ActionsEvent>(ActionsEvent.OpenTurnDiff(FIRST)), events)
        assertEquals(emptyList(), chat.calls)
    }

    // Files and git

    @Test
    fun `directory follows the loaded session's folder`() = runTest {
        val (chat, presenter) = setup(live = false)
        assertNull(presenter.state.value.directory)

        chat.live(history, directory = FOLDER)
        runCurrent()
        assertEquals(FOLDER, presenter.state.value.directory)

        // Kept while the session is kept, as through a reconnect.
        chat.mutableState.value = chat.mutableState.value.copy(availability = ChatAvailability.Reconnecting)
        runCurrent()
        assertEquals(FOLDER, presenter.state.value.directory)

        chat.mutableState.value = ChatSessionState(availability = ChatAvailability.OtherServer)
        runCurrent()
        assertNull(presenter.state.value.directory)
    }

    @Test
    fun `a session without a folder has no directory`() = runTest {
        val (chat, presenter) = setup(live = false)

        chat.live(history, directory = null)
        runCurrent()
        assertNull(presenter.state.value.directory)

        chat.live(history, directory = "  ")
        runCurrent()
        assertNull(presenter.state.value.directory)
    }

    @Test
    fun `OpenFiles and OpenGit open the session's folder`() = runTest {
        val (chat, presenter, events) = setup(live = false)
        chat.live(history, directory = FOLDER)
        runCurrent()

        presenter.onIntent(ActionsIntent.OpenFiles)
        presenter.onIntent(ActionsIntent.OpenGit)
        runCurrent()

        assertEquals(listOf(ActionsEvent.OpenFiles(FOLDER), ActionsEvent.OpenGit(FOLDER)), events)
        assertEquals(emptyList(), chat.calls)
    }

    @Test
    fun `OpenFiles and OpenGit do nothing while the folder is not known`() = runTest {
        val (_, presenter, events) = setup(live = false)

        presenter.onIntent(ActionsIntent.OpenFiles)
        presenter.onIntent(ActionsIntent.OpenGit)
        runCurrent()

        assertEquals(emptyList(), events)
    }

    @Test
    fun `a route other than the chat opens neither files nor git`() = runTest {
        val (chat, presenter, events) = setup(live = false, route = ChatRoute.Subagents(MAIN.value))
        chat.live(history, directory = FOLDER)
        runCurrent()
        assertNull(presenter.state.value.directory)

        presenter.onIntent(ActionsIntent.OpenFiles)
        presenter.onIntent(ActionsIntent.OpenGit)
        runCurrent()

        assertEquals(emptyList(), events)
    }

    @Test
    fun `it prints no folder`() = runTest {
        val (chat, presenter) = setup(live = false)
        chat.live(history, directory = FOLDER)
        runCurrent()

        assertFalse(presenter.toString().contains(FOLDER))
        assertFalse(presenter.state.value.toString().contains(FOLDER))
        assertFalse(ActionsEvent.OpenFiles(FOLDER).toString().contains(FOLDER))
        assertFalse(ActionsEvent.OpenGit(FOLDER).toString().contains(FOLDER))
    }

    @Test
    fun `no event is dropped when nobody collects yet`() = runTest {
        val chat = FakeChatSession(MAIN)
        chat.live(history)
        val presenter = DefaultActionsPresenter(ChatRoute.Chat(MAIN.value), backgroundScope, chat)
        runCurrent()

        repeat(100) { presenter.onIntent(ActionsIntent.ShowTurnChanges(FIRST)) }
        val events = mutableListOf<ActionsEvent>()
        backgroundScope.launch { presenter.events.toList(events) }
        runCurrent()

        assertEquals(100, events.size)
    }

    @Test
    fun `it prints no message text`() = runTest {
        val (_, presenter) = setup()
        presenter.onIntent(ActionsIntent.Request(SessionAction.Fork(FIRST)))
        assertFalse(presenter.toString().contains("first"))
        assertFalse(presenter.toString().contains(FIRST.value))
    }
}
