package io.github.thewisekd.phopencode.feature.chat.logic.subagent

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.ToolStatus
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentNode
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentStatus
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentTools
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSubagentPresenterTest {
    private val root = SessionId("ses_root")
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)

    /**
     * A chat session on one [FakeOpencodeClient]: [call] runs on it only while [state] is
     * [ChatAvailability.Live], as the real one does for the bound server, and counts what it refused.
     */
    private class TestChatSession(override val sessionId: SessionId, var client: FakeOpencodeClient) : ChatSession {
        val mutable = MutableStateFlow(ChatSessionState(availability = ChatAvailability.Live))
        override val state: StateFlow<ChatSessionState> = mutable.asStateFlow()

        @Volatile
        var accepted = 0

        @Volatile
        var refused = 0

        override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
            if (mutable.value.availability != ChatAvailability.Live) {
                refused++
                throw ApiException(ApiError.Closed)
            }
            accepted++
            return block(client)
        }
    }

    /** Follows the test scheduler's virtual time. */
    private fun TestScope.virtualClock(start: Instant = t0): Clock = object : Clock {
        override fun now(): Instant = start + testScheduler.currentTime.milliseconds
    }

    private fun session(
        id: String,
        parent: SessionId? = root,
        created: Int = 0,
        updated: Int? = null,
        outcome: SessionOutcome? = null,
        title: String? = "Task $id",
        agent: String? = "general",
        usage: TokenUsage? = null,
    ) = Session(
        id = SessionId(id),
        parentId = parent,
        title = title,
        agent = agent,
        createdAt = t0 + created.seconds,
        updatedAt = updated?.let { t0 + it.seconds },
        outcome = outcome,
        usage = usage,
    )

    private fun client(vararg sessions: Session): FakeOpencodeClient {
        val client = FakeOpencodeClient()
        client.sessions.seed(Session(id = root, title = "Root", agent = "build", createdAt = t0))
        sessions.forEach { client.sessions.seed(it) }
        return client
    }

    private fun TestScope.start(
        client: FakeOpencodeClient,
        route: ChatRoute = ChatRoute.Subagents(root.value),
        sessionId: SessionId = root,
        clock: Clock = virtualClock(),
    ): Pair<TestChatSession, DefaultSubagentPresenter> {
        val chat = TestChatSession(sessionId, client)
        val presenter = DefaultSubagentPresenter(route, backgroundScope, chat, clock)
        runCurrent()
        return chat to presenter
    }

    private fun SubagentState.ids(): List<String> = subagents.map { it.sessionId.value }

    private fun SubagentState.node(id: String): SubagentNode =
        flatten(subagents).first { it.sessionId.value == id }

    private fun flatten(nodes: List<SubagentNode>): List<SubagentNode> = nodes.flatMap { listOf(it) + flatten(it.children) }

    private fun tool(id: String, name: String, status: ToolStatus) = AssistantBlock.Tool(id = id, name = name, status = status)

    private fun assistant(session: SessionId, id: String, vararg blocks: AssistantBlock) =
        Message.Assistant(id = MessageId(id), sessionId = session, blocks = blocks.toList())

    // Start

    @Test
    fun `nothing runs in the constructor and the first state is loading`() = runTest {
        val client = client(session("ses_a"))
        val chat = TestChatSession(root, client)

        val presenter = DefaultSubagentPresenter(ChatRoute.Chat(root.value), backgroundScope, chat, virtualClock())

        assertEquals(SubagentState(), presenter.state.value)
        assertEquals(0, chat.accepted)
        assertTrue(client.control.calls.isEmpty())

        runCurrent()
        assertFalse(presenter.state.value.loading)
        assertEquals(listOf("ses_a"), presenter.state.value.ids())
    }

    @Test
    fun `the public constructor is the contract's`() = runTest {
        val client = client(session("ses_a"))
        val chat = TestChatSession(root, client)

        val presenter = DefaultSubagentPresenter(route = ChatRoute.Chat(root.value), scope = backgroundScope, session = chat)
        runCurrent()

        assertEquals(listOf("ses_a"), presenter.state.value.ids())
    }

    // The tree

    @Test
    fun `the tree holds direct children oldest first, each with its own below it`() = runTest {
        val a = SessionId("ses_a")
        val b = SessionId("ses_b")
        val client = client(
            session("ses_b", created = 20),
            session("ses_a", created = 10),
            session("ses_b1", parent = b, created = 30),
            session("ses_a2", parent = a, created = 50),
            session("ses_a1", parent = a, created = 40),
            session("ses_a1x", parent = SessionId("ses_a1"), created = 60),
            session("ses_other", parent = null, created = 5),
        )
        val (_, presenter) = start(client)

        val state = presenter.state.value
        assertEquals(listOf("ses_a", "ses_b"), state.ids())
        assertEquals(listOf("ses_a1", "ses_a2"), state.node("ses_a").children.map { it.sessionId.value })
        assertEquals(listOf("ses_a1x"), state.node("ses_a1").children.map { it.sessionId.value })
        assertEquals(listOf("ses_b1"), state.node("ses_b").children.map { it.sessionId.value })
        assertEquals(6, state.totalCount)
        assertNull(state.error)
    }

    @Test
    fun `a node carries the child's title, agent and start`() = runTest {
        val client = client(session("ses_a", created = 10, title = "Find the bug", agent = "explore"))
        val (_, presenter) = start(client)

        val node = presenter.state.value.node("ses_a")
        assertEquals("Find the bug", node.title)
        assertEquals("explore", node.agent)
        assertEquals(t0 + 10.seconds, node.startedAt)
    }

    @Test
    fun `every page of children is read`() = runTest {
        val children = (1..120).map { session("ses_c%03d".format(it), created = it) }
        val client = client(*children.toTypedArray())
        val (_, presenter) = start(client)

        assertEquals(children.map { it.id.value }, presenter.state.value.ids())
    }

    @Test
    fun `a cycle in the parent links is shown once and ends`() = runTest {
        // The route's session is the child of its own child: asking for children never ends otherwise.
        val client = FakeOpencodeClient()
        client.sessions.seed(Session(id = root, parentId = SessionId("ses_a"), createdAt = t0))
        client.sessions.seed(session("ses_a", parent = root, created = 1))
        val (_, presenter) = start(client)

        val state = presenter.state.value
        assertEquals(listOf("ses_a"), state.ids())
        assertTrue(state.node("ses_a").children.isEmpty())
        assertEquals(1, state.totalCount)
    }

    @Test
    fun `a session that is its own parent is not its own subagent`() = runTest {
        val client = FakeOpencodeClient()
        client.sessions.seed(Session(id = root, parentId = root, createdAt = t0))
        client.sessions.seed(session("ses_a", parent = root, created = 1))
        val (_, presenter) = start(client)

        assertEquals(listOf("ses_a"), presenter.state.value.ids())
        assertEquals(1, presenter.state.value.totalCount)
    }

    @Test
    fun `a runaway chain is cut at a fixed depth`() = runTest {
        val client = client()
        var parent = root
        repeat(200) { level ->
            val child = session("ses_l$level", parent = parent, created = level)
            client.sessions.seed(child)
            parent = child.id
        }
        val (_, presenter) = start(client)

        val total = presenter.state.value.totalCount
        assertTrue(total in 1 until 200, "total $total")
        assertFalse(presenter.state.value.loading)
        val listCalls = client.control.calls.count { it == "sessions.list" }
        assertTrue(listCalls < 200, "list calls $listCalls")
    }

    // Status, duration, tokens

    @Test
    fun `status comes from the active list, else from the outcome`() = runTest {
        val client = client(
            session("ses_run", created = 1),
            session("ses_ok", created = 2, updated = 5, outcome = SessionOutcome.Succeeded),
            session("ses_fail", created = 3, updated = 5, outcome = SessionOutcome.Failed),
            session("ses_stop", created = 4, updated = 5, outcome = SessionOutcome.Interrupted),
            session("ses_new", created = 5),
            session("ses_odd", created = 6, updated = 7, outcome = SessionOutcome.Unknown("weird")),
            session("ses_retry", created = 7, outcome = SessionOutcome.Failed),
        )
        client.sessions.setActivity(SessionId("ses_run"), SessionActivity.Running)
        client.sessions.setActivity(SessionId("ses_retry"), SessionActivity.Unknown("retrying"))
        val (_, presenter) = start(client)

        val state = presenter.state.value
        assertEquals(SubagentStatus.Running, state.node("ses_run").status)
        assertEquals(SubagentStatus.Succeeded, state.node("ses_ok").status)
        assertEquals(SubagentStatus.Failed, state.node("ses_fail").status)
        assertEquals(SubagentStatus.Interrupted, state.node("ses_stop").status)
        assertEquals(SubagentStatus.Idle, state.node("ses_new").status)
        assertEquals(SubagentStatus.Idle, state.node("ses_odd").status)
        assertEquals(SubagentStatus.Running, state.node("ses_retry").status)
        assertEquals(2, state.runningCount)
    }

    @Test
    fun `an ended subagent's duration runs from creation to its last update, never negative`() = runTest {
        val client = client(
            session("ses_ok", created = 10, updated = 75, outcome = SessionOutcome.Succeeded),
            session("ses_skew", created = 10, updated = 5, outcome = SessionOutcome.Succeeded),
            session("ses_noend", created = 10, outcome = SessionOutcome.Succeeded),
        )
        val (_, presenter) = start(client)

        val state = presenter.state.value
        assertEquals(65.seconds, state.node("ses_ok").duration)
        assertEquals(0.seconds, state.node("ses_skew").duration)
        assertNull(state.node("ses_noend").duration)
    }

    @Test
    fun `a running subagent's duration counts up once a second, and the ticker stops when nothing runs`() = runTest {
        val client = client(session("ses_run", created = 0, updated = 1))
        client.sessions.setActivity(SessionId("ses_run"), SessionActivity.Running)
        var reads = 0
        val clock = object : Clock {
            override fun now(): Instant {
                reads++
                return t0 + 30.seconds + testScheduler.currentTime.milliseconds
            }
        }
        val (_, presenter) = start(client, clock = clock)
        client.events.awaitConnections(1)

        assertEquals(30.seconds, presenter.state.value.node("ses_run").duration)
        advanceTimeBy(1_001.milliseconds)
        assertEquals(31.seconds, presenter.state.value.node("ses_run").duration)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(32.seconds, presenter.state.value.node("ses_run").duration)

        // It ends: the server's own times give the final duration, and the clock is read no more.
        client.sessions.seed(session("ses_run", created = 0, updated = 40, outcome = SessionOutcome.Succeeded))
        client.sessions.setActivity(SessionId("ses_run"), null)
        client.events.emit(ServerEvent.ExecutionSucceeded(SessionId("ses_run")))
        runCurrent()

        val ended = presenter.state.value.node("ses_run")
        assertEquals(SubagentStatus.Succeeded, ended.status)
        assertEquals(40.seconds, ended.duration)
        val readsAtEnd = reads
        advanceTimeBy(10_000.milliseconds)
        assertEquals(readsAtEnd, reads)
        assertEquals(40.seconds, presenter.state.value.node("ses_run").duration)
    }

    @Test
    fun `no ticker runs while nothing runs`() = runTest {
        val client = client(session("ses_ok", created = 0, updated = 3, outcome = SessionOutcome.Succeeded))
        var reads = 0
        val clock = object : Clock {
            override fun now(): Instant {
                reads++
                return t0
            }
        }
        val (_, presenter) = start(client, clock = clock)
        val before = reads

        advanceTimeBy(5_000.milliseconds)

        assertEquals(before, reads)
        assertEquals(3.seconds, presenter.state.value.node("ses_ok").duration)
    }

    @Test
    fun `tokens come from the session's usage`() = runTest {
        val usage = TokenUsage(input = BigInteger.valueOf(1200), output = BigInteger.valueOf(300))
        val client = client(session("ses_a", usage = usage), session("ses_b", created = 1))
        val (_, presenter) = start(client)

        assertEquals(usage, presenter.state.value.node("ses_a").usage)
        assertNull(presenter.state.value.node("ses_b").usage)
    }

    // Tools

    @Test
    fun `tool progress is mapped from the child's tool blocks`() = runTest {
        val a = SessionId("ses_a")
        val client = client(session("ses_a"))
        client.messages.seed(
            a,
            listOf(
                Message.User(id = MessageId("msg_u"), sessionId = a, text = "go"),
                assistant(a, "msg_1", tool("t1", "read", ToolStatus.Completed), tool("t2", "grep", ToolStatus.Failed)),
                assistant(a, "msg_2", AssistantBlock.Text("thinking"), tool("t3", "edit", ToolStatus.Completed)),
                assistant(
                    a,
                    "msg_3",
                    tool("t4", "bash", ToolStatus.Running),
                    tool("t5", "write", ToolStatus.Streaming),
                    tool("t6", "mystery", ToolStatus.Unknown("paused")),
                ),
            ),
        )
        val (_, presenter) = start(client)

        val tools = presenter.state.value.node("ses_a").tools
        assertEquals(SubagentTools(completed = 2, failed = 1, running = 2, current = "write"), tools)
        assertEquals(5, tools.total)
    }

    @Test
    fun `a tool seen twice counts once, as its latest status`() = runTest {
        val a = SessionId("ses_a")
        val client = client(session("ses_a"))
        client.messages.seed(
            a,
            listOf(
                assistant(a, "msg_1", tool("t1", "read", ToolStatus.Running)),
                assistant(a, "msg_2", tool("t1", "read", ToolStatus.Completed)),
            ),
        )
        val (_, presenter) = start(client)

        assertEquals(SubagentTools(completed = 1), presenter.state.value.node("ses_a").tools)
    }

    @Test
    fun `every page of a child's messages is read for its tools`() = runTest {
        val a = SessionId("ses_a")
        val client = client(session("ses_a"))
        client.messages.seed(a, (1..130).map { assistant(a, "msg_$it", tool("t$it", "read", ToolStatus.Completed)) })
        val (_, presenter) = start(client)

        assertEquals(130, presenter.state.value.node("ses_a").tools.completed)
    }

    @Test
    fun `a tool event of a subagent refreshes its tools`() = runTest {
        val a = SessionId("ses_a")
        val client = client(session("ses_a"))
        client.sessions.setActivity(a, SessionActivity.Running)
        val (_, presenter) = start(client)
        client.events.awaitConnections(1)
        assertEquals(SubagentTools(), presenter.state.value.node("ses_a").tools)

        client.messages.seed(a, listOf(assistant(a, "msg_1", tool("t1", "bash", ToolStatus.Running))))
        client.events.emit(ServerEvent.ToolCalled(sessionId = a, id = "t1"))
        runCurrent()

        assertEquals(SubagentTools(running = 1, current = "bash"), presenter.state.value.node("ses_a").tools)
    }

    // Live

    @Test
    fun `events of the subagents, the execution and the creation, are asked for`() = runTest {
        val client = client(session("ses_a"))
        start(client)
        client.events.awaitConnections(1)

        val types = client.events.requestedTypes.single()
        assertTrue(
            types.containsAll(
                setOf(
                    "session.created",
                    "session.execution.started",
                    "session.execution.succeeded",
                    "session.execution.failed",
                    "session.execution.interrupted",
                ),
            ),
            "types $types",
        )
    }

    @Test
    fun `an execution start and end, at any depth, update the status`() = runTest {
        val a1 = SessionId("ses_a1")
        val client = client(session("ses_a"), session("ses_a1", parent = SessionId("ses_a"), created = 1))
        val (_, presenter) = start(client)
        client.events.awaitConnections(1)

        client.events.emit(ServerEvent.ExecutionStarted(a1))
        runCurrent()
        assertEquals(SubagentStatus.Running, presenter.state.value.node("ses_a1").status)
        assertEquals(1, presenter.state.value.runningCount)

        client.events.emit(ServerEvent.ExecutionFailed(a1))
        runCurrent()
        assertEquals(SubagentStatus.Failed, presenter.state.value.node("ses_a1").status)

        client.events.emit(ServerEvent.ExecutionStarted(a1))
        runCurrent()
        client.events.emit(ServerEvent.ExecutionInterrupted(a1))
        runCurrent()
        assertEquals(SubagentStatus.Interrupted, presenter.state.value.node("ses_a1").status)
        assertEquals(0, presenter.state.value.runningCount)
    }

    @Test
    fun `an end refreshes the node's tokens and tools`() = runTest {
        val a = SessionId("ses_a")
        val client = client(session("ses_a"))
        client.sessions.setActivity(a, SessionActivity.Running)
        val (_, presenter) = start(client)
        client.events.awaitConnections(1)

        val usage = TokenUsage(input = BigInteger.TEN)
        client.sessions.seed(session("ses_a", updated = 9, outcome = SessionOutcome.Succeeded, usage = usage))
        client.messages.seed(a, listOf(assistant(a, "msg_1", tool("t1", "read", ToolStatus.Completed))))
        client.sessions.setActivity(a, null)
        client.events.emit(ServerEvent.ExecutionSucceeded(a))
        runCurrent()

        val node = presenter.state.value.node("ses_a")
        assertEquals(usage, node.usage)
        assertEquals(SubagentTools(completed = 1), node.tools)
        assertEquals(9.seconds, node.duration)
    }

    @Test
    fun `events of sessions outside the tree change nothing`() = runTest {
        val client = client(session("ses_a"), session("ses_x", parent = null, created = 1))
        val (_, presenter) = start(client)
        client.events.awaitConnections(1)
        val before = presenter.state.value
        val calls = client.control.calls.size

        client.events.emit(ServerEvent.ExecutionStarted(SessionId("ses_x")))
        client.events.emit(ServerEvent.ExecutionStarted(root))
        client.events.emit(ServerEvent.SessionCreated(SessionId("ses_y"), parentId = SessionId("ses_x")))
        runCurrent()

        assertEquals(before, presenter.state.value)
        assertEquals(calls, client.control.calls.size)
    }

    @Test
    fun `a new subagent, at any depth, joins the tree`() = runTest {
        val client = client(session("ses_a"))
        val (_, presenter) = start(client)
        client.events.awaitConnections(1)

        client.sessions.seed(session("ses_b", created = 5))
        client.events.emit(ServerEvent.SessionCreated(SessionId("ses_b"), parentId = root))
        runCurrent()
        assertEquals(listOf("ses_a", "ses_b"), presenter.state.value.ids())

        client.sessions.seed(session("ses_b1", parent = SessionId("ses_b"), created = 6))
        client.events.emit(ServerEvent.SessionCreated(SessionId("ses_b1"), parentId = SessionId("ses_b")))
        runCurrent()
        assertEquals(listOf("ses_b1"), presenter.state.value.node("ses_b").children.map { it.sessionId.value })
    }

    @Test
    fun `each reconnect of the event stream asks for the tree and the active list again`() = runTest {
        val client = client(session("ses_a"))
        val (_, presenter) = start(client)
        client.events.awaitConnections(1)
        assertEquals(1, client.control.calls.count { it == "sessions.activeStatuses" })

        client.sessions.seed(session("ses_b", created = 5))
        client.sessions.setActivity(SessionId("ses_b"), SessionActivity.Running)
        client.events.drop()
        client.events.reconnect()
        runCurrent()

        assertEquals(2, client.control.calls.count { it == "sessions.activeStatuses" })
        assertEquals(listOf("ses_a", "ses_b"), presenter.state.value.ids())
        assertEquals(SubagentStatus.Running, presenter.state.value.node("ses_b").status)
    }

    // The header

    @Test
    fun `the header of a root session has no parent`() = runTest {
        val (_, presenter) = start(client(), route = ChatRoute.Chat(root.value))

        val state = presenter.state.value
        assertEquals("Root", state.title)
        assertEquals("build", state.agent)
        assertNull(state.parentId)
    }

    @Test
    fun `the header of a child names its parent and whether that parent is a root`() = runTest {
        val a = SessionId("ses_a")
        val a1 = SessionId("ses_a1")
        val client = client(session("ses_a", title = "Plan", agent = "plan"), session("ses_a1", parent = a, created = 1, title = "Dig", agent = "explore"))

        val (_, child) = start(client, route = ChatRoute.ChildSession(a.value), sessionId = a)
        assertEquals("Plan", child.state.value.title)
        assertEquals("plan", child.state.value.agent)
        assertEquals(root, child.state.value.parentId)
        assertTrue(child.state.value.parentIsRoot)
        assertEquals(listOf("ses_a1"), child.state.value.ids())

        val (_, grandchild) = start(client, route = ChatRoute.ChildSession(a1.value), sessionId = a1)
        assertEquals("Dig", grandchild.state.value.title)
        assertEquals(a, grandchild.state.value.parentId)
        assertFalse(grandchild.state.value.parentIsRoot)
        assertTrue(grandchild.state.value.subagents.isEmpty())
    }

    // Intents

    @Test
    fun `intents become navigation events`() = runTest {
        val a = SessionId("ses_a")
        val a1 = SessionId("ses_a1")
        val client = client(session("ses_a"), session("ses_a1", parent = a, created = 1))
        val (_, presenter) = start(client, route = ChatRoute.ChildSession(a1.value), sessionId = a1)
        val events = mutableListOf<SubagentEvent>()
        backgroundScope.launch { presenter.events.collect { events += it } }
        runCurrent()

        presenter.onIntent(SubagentIntent.OpenList)
        presenter.onIntent(SubagentIntent.Open(SessionId("ses_z")))
        presenter.onIntent(SubagentIntent.OpenParent)
        presenter.onIntent(SubagentIntent.Close)
        runCurrent()

        assertEquals(
            listOf(
                SubagentEvent.OpenList(a1),
                SubagentEvent.OpenChild(SessionId("ses_z")),
                SubagentEvent.OpenParent(a, root = false),
                SubagentEvent.Close,
            ),
            events,
        )
    }

    @Test
    fun `open parent goes to a root parent as a chat, and does nothing on a root`() = runTest {
        val a = SessionId("ses_a")
        val client = client(session("ses_a"))
        val (_, child) = start(client, route = ChatRoute.ChildSession(a.value), sessionId = a)
        val (_, rootPresenter) = start(client, route = ChatRoute.Chat(root.value))
        val events = mutableListOf<SubagentEvent>()
        backgroundScope.launch { child.events.collect { events += it } }
        backgroundScope.launch { rootPresenter.events.collect { events += it } }
        runCurrent()

        rootPresenter.onIntent(SubagentIntent.OpenParent)
        child.onIntent(SubagentIntent.OpenParent)
        runCurrent()

        assertEquals(listOf<SubagentEvent>(SubagentEvent.OpenParent(root, root = true)), events)
    }

    // Failures and servers

    @Test
    fun `a failed load shows its error, keeps what was loaded, and retry loads again`() = runTest {
        val client = client(session("ses_a"))
        val (_, presenter) = start(client)
        client.events.awaitConnections(1)
        assertEquals(listOf("ses_a"), presenter.state.value.ids())

        client.sessions.seed(session("ses_b", created = 5))
        client.control.failNext(ApiError.Network)
        client.events.drop()
        client.events.reconnect()
        runCurrent()

        assertEquals(ChatFailure.Unreachable, presenter.state.value.error)
        assertEquals(listOf("ses_a"), presenter.state.value.ids())
        assertFalse(presenter.state.value.loading)

        presenter.onIntent(SubagentIntent.Retry)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(listOf("ses_a", "ses_b"), presenter.state.value.ids())
    }

    @Test
    fun `a first load that fails stops loading and shows the error`() = runTest {
        val client = client(session("ses_a"))
        client.control.failNext(ApiError.Server(500))
        val (_, presenter) = start(client)

        assertFalse(presenter.state.value.loading)
        assertEquals(ChatFailure.ServerFailed, presenter.state.value.error)
    }

    @Test
    fun `a stream that fails for good shows the error and retry follows again`() = runTest {
        val client = client(session("ses_a"))
        val (_, presenter) = start(client)
        client.events.awaitConnections(1)

        client.events.fail(ApiError.Unauthorized)
        runCurrent()
        assertEquals(ChatFailure.Unauthorized, presenter.state.value.error)
        assertEquals(listOf("ses_a"), presenter.state.value.ids())

        presenter.onIntent(SubagentIntent.Retry)
        runCurrent()
        assertNull(presenter.state.value.error)
        assertEquals(2, client.events.connectionsOpened)
    }

    @Test
    fun `nothing is asked while the chat is not live`() = runTest {
        val client = client(session("ses_a"))
        val chat = TestChatSession(root, client)
        chat.mutable.value = ChatSessionState(availability = ChatAvailability.Waiting)
        val presenter = DefaultSubagentPresenter(ChatRoute.Chat(root.value), backgroundScope, chat, virtualClock())
        runCurrent()
        advanceTimeBy(10_000.milliseconds)

        assertTrue(presenter.state.value.loading)
        assertTrue(client.control.calls.isEmpty())
        assertEquals(0, chat.accepted)

        chat.mutable.value = ChatSessionState(availability = ChatAvailability.Live)
        runCurrent()
        assertEquals(listOf("ses_a"), presenter.state.value.ids())
    }

    @Test
    fun `a reconnect to the bound server follows its new client and keeps the tree meanwhile`() = runTest {
        val first = client(session("ses_a"))
        val (chat, presenter) = start(first)
        first.events.awaitConnections(1)

        chat.mutable.value = ChatSessionState(availability = ChatAvailability.Reconnecting)
        first.close()
        runCurrent()
        assertEquals(listOf("ses_a"), presenter.state.value.ids())

        val second = client(session("ses_a"), session("ses_b", created = 3))
        chat.client = second
        chat.mutable.value = ChatSessionState(availability = ChatAvailability.Live)
        runCurrent()

        assertEquals(listOf("ses_a", "ses_b"), presenter.state.value.ids())
        assertEquals(1, second.events.activeConnections.value)
    }

    @Test
    fun `a client closed while the chat still shows live is followed again once the chat changes`() = runTest {
        val first = client(session("ses_a"))
        val (chat, presenter) = start(first)
        first.events.awaitConnections(1)

        // The client closes before the chat has seen its connection go: the presenter waits, it does not spin.
        first.close()
        runCurrent()
        val accepted = chat.accepted
        advanceTimeBy(10_000.milliseconds)
        assertEquals(accepted, chat.accepted)

        val second = client(session("ses_a"), session("ses_b", created = 3))
        chat.client = second
        chat.mutable.value = ChatSessionState(availability = ChatAvailability.Live, phase = SyncPhase.Live)
        runCurrent()

        assertEquals(listOf("ses_a", "ses_b"), presenter.state.value.ids())
    }

    @Test
    fun `another server or a lost session clears the tree`() = runTest {
        val client = client(session("ses_a"))
        val (chat, presenter) = start(client)
        assertEquals(1, presenter.state.value.totalCount)

        chat.mutable.value = ChatSessionState(availability = ChatAvailability.OtherServer)
        runCurrent()
        assertEquals(0, presenter.state.value.totalCount)
        assertFalse(presenter.state.value.loading)
        assertNull(presenter.state.value.title)

        chat.mutable.value = ChatSessionState(availability = ChatAvailability.Live)
        runCurrent()
        assertEquals(1, presenter.state.value.totalCount)

        chat.mutable.value = ChatSessionState(availability = ChatAvailability.NotFound)
        runCurrent()
        assertEquals(0, presenter.state.value.totalCount)
    }

    // Printing

    @Test
    fun `printing the state masks titles and tool names`() = runTest {
        val a = SessionId("ses_a")
        val client = client(session("ses_a", title = "secret task"))
        client.sessions.seed(Session(id = root, title = "private root title", createdAt = t0))
        client.messages.seed(a, listOf(assistant(a, "msg_1", tool("t1", "mcp_private_server", ToolStatus.Running))))
        val (_, presenter) = start(client)

        val printed = presenter.state.value.toString()
        assertEquals("private root title", presenter.state.value.title)
        assertFalse("secret task" in printed)
        assertFalse("private root title" in printed)
        assertFalse("mcp_private_server" in printed)
        assertTrue("ses_a" in printed)
    }
}
