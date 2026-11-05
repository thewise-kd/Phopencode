package io.github.thewisekd.phopencode.feature.chat.logic.permission

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InteractionApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
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
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionsState
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

private const val DIR = "/work/project"

private val MAIN = SessionId("ses_main")
private val CHILD = SessionId("ses_child")
private val GRANDCHILD = SessionId("ses_grandchild")
private val STRANGER = SessionId("ses_stranger")

/** A reply as the presenter handed it over, with the session it named. */
private data class SentReply(
    val sessionId: SessionId,
    val requestId: PermissionRequestId,
    val decision: PermissionDecision,
    val message: String?,
)

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

    /** The arguments of every `listPermissions`, oldest first. */
    val listed = mutableListOf<Pair<SessionId?, Location?>>()

    val sent = mutableListOf<SentReply>()

    private val recording = object : OpencodeClient by client {
        override val interactions: InteractionApi = object : InteractionApi by client.interactions {
            override suspend fun listPermissions(sessionId: SessionId?, location: Location?): List<PermissionRequest> {
                listed += sessionId to location
                return client.interactions.listPermissions(sessionId, location)
            }

            override suspend fun replyPermission(
                sessionId: SessionId,
                requestId: PermissionRequestId,
                decision: PermissionDecision,
                message: String?,
            ) {
                sent += SentReply(sessionId, requestId, decision, message)
                client.interactions.replyPermission(sessionId, requestId, decision, message)
            }
        }
    }

    override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
        if (!connected) throw ApiException(ApiError.Closed)
        return block(recording)
    }

    fun live(permissions: List<PermissionRequest> = emptyList()) {
        mutableState.value = ChatSessionState(
            availability = ChatAvailability.Live,
            session = SessionState(Session(id = sessionId, directory = DIR), permissions = permissions),
            phase = SyncPhase.Live,
        )
    }
}

private fun request(id: String, session: SessionId, action: String = "bash") = PermissionRequest(
    id = PermissionRequestId(id),
    sessionId = session,
    action = action,
    resources = listOf("rm -rf build"),
    message = "Run a command",
    save = listOf("*"),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPermissionPresenterTest {
    private val own = request("per_own", MAIN)
    private val child = request("per_child", CHILD, action = "edit")
    private val grandchild = request("per_grandchild", GRANDCHILD)
    private val stranger = request("per_stranger", STRANGER)

    private fun FakeOpencodeClient.seedTree() {
        sessions.seed(Session(id = MAIN, directory = DIR))
        sessions.seed(Session(id = CHILD, parentId = MAIN, directory = DIR))
        sessions.seed(Session(id = GRANDCHILD, parentId = CHILD, directory = DIR))
        sessions.seed(Session(id = STRANGER, directory = DIR))
    }

    private suspend fun TestScope.setup(
        ownRequests: List<PermissionRequest> = emptyList(),
        pending: List<PermissionRequest> = emptyList(),
        arrange: suspend (FakeOpencodeClient) -> Unit = {},
    ): Pair<FakeChatSession, DefaultPermissionPresenter> {
        val chat = FakeChatSession(MAIN)
        chat.client.seedTree()
        (ownRequests + pending).forEach { chat.client.interactions.seedPermission(it) }
        arrange(chat.client)
        chat.live(ownRequests)
        val presenter = DefaultPermissionPresenter(route = ChatRoute.Chat(MAIN.value), scope = backgroundScope, session = chat)
        runCurrent()
        return chat to presenter
    }

    private fun DefaultPermissionPresenter.ids() = state.value.cards.map { it.requestId.value }

    private fun DefaultPermissionPresenter.card(request: PermissionRequest): PermissionCardState =
        state.value.cards.single { it.requestId == request.id }

    // Where the cards come from

    @Test
    fun `nothing is asked in the constructor`() = runTest {
        val chat = FakeChatSession(MAIN)
        chat.client.seedTree()
        chat.live(listOf(own))
        val presenter = DefaultPermissionPresenter(route = ChatRoute.Chat(MAIN.value), scope = backgroundScope, session = chat)

        assertEquals(PermissionsState(), presenter.state.value)
        assertTrue(chat.listed.isEmpty())

        runCurrent()

        assertEquals(listOf("per_own"), presenter.ids())
    }

    @Test
    fun `this session's requests show with what they ask`() = runTest {
        val (_, presenter) = setup(ownRequests = listOf(own))

        assertEquals(
            PermissionCardState(
                requestId = own.id,
                sessionId = MAIN,
                fromSubagent = false,
                action = "bash",
                resources = listOf("rm -rf build"),
                message = "Run a command",
                save = listOf("*"),
            ),
            presenter.card(own),
        )
    }

    @Test
    fun `subagents' requests at any depth are listed by folder, and others are left out`() = runTest {
        val (chat, presenter) = setup(ownRequests = listOf(own), pending = listOf(child, grandchild, stranger))

        assertEquals(listOf<Pair<SessionId?, Location?>>(null to Location(DIR)), chat.listed)
        assertEquals(listOf("per_own", "per_child", "per_grandchild"), presenter.ids())
        assertFalse(presenter.card(own).fromSubagent)
        assertTrue(presenter.card(child).fromSubagent)
        assertEquals(CHILD, presenter.card(child).sessionId)
        assertEquals("edit", presenter.card(child).action)
        assertEquals(GRANDCHILD, presenter.card(grandchild).sessionId)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a chain too deep to walk leaves no wrong answer about the subagents on it`() = runTest {
        // MAIN <- ses_deep_1 <- ... <- ses_deep_40: deeper than the walk goes.
        val depth = 40
        val chain = (1..depth).map { SessionId("ses_deep_$it") }
        val deepest = request("per_deepest", chain.last())
        val shallow = request("per_shallow", chain[9])
        val (chat, presenter) = setup(pending = listOf(deepest)) { client ->
            chain.forEachIndexed { index, id ->
                client.sessions.seed(Session(id = id, parentId = if (index == 0) MAIN else chain[index - 1], directory = DIR))
            }
        }
        assertEquals(1, chat.listed.size)

        chat.client.events.emit(ServerEvent.PermissionAsked(shallow))
        runCurrent()

        assertTrue("per_shallow" in presenter.ids())
        assertEquals(chain[9], presenter.card(shallow).sessionId)
        assertTrue(presenter.card(shallow).fromSubagent)
    }

    /** A chain of [depth] sessions under MAIN, with one request from the deepest. */
    private suspend fun TestScope.deepest(depth: Int): DefaultPermissionPresenter {
        val chain = (1..depth).map { SessionId("ses_deep_$it") }
        val (_, presenter) = setup(pending = listOf(request("per_deepest", chain.last()))) { client ->
            chain.forEachIndexed { index, id ->
                client.sessions.seed(Session(id = id, parentId = if (index == 0) MAIN else chain[index - 1], directory = DIR))
            }
        }
        return presenter
    }

    @Test
    fun `a subagent exactly at the walk's limit is found`() = runTest {
        assertEquals(listOf("per_deepest"), deepest(depth = 32).ids())
    }

    @Test
    fun `a subagent one level past the walk's limit is not`() = runTest {
        assertEquals(emptyList(), deepest(depth = 33).ids())
    }

    @Test
    fun `asked and replied events add and remove subagents' cards`() = runTest {
        val (chat, presenter) = setup()
        assertEquals(1, chat.client.events.activeConnections.value)

        chat.client.events.emit(ServerEvent.PermissionAsked(grandchild))
        chat.client.events.emit(ServerEvent.PermissionAsked(stranger))
        runCurrent()

        assertEquals(listOf("per_grandchild"), presenter.ids())

        chat.client.events.emit(ServerEvent.PermissionReplied(GRANDCHILD, grandchild.id, PermissionDecision.Once))
        runCurrent()

        assertEquals(emptyList(), presenter.ids())
    }

    @Test
    fun `every new connection lists again, since the server replays nothing`() = runTest {
        val (chat, presenter) = setup()
        chat.client.interactions.seedPermission(child)

        chat.client.events.drop()
        chat.client.events.reconnect()
        runCurrent()

        assertEquals(2, chat.listed.size)
        assertEquals(listOf("per_child"), presenter.ids())
    }

    @Test
    fun `a request settled meanwhile leaves on the next list`() = runTest {
        val (chat, presenter) = setup(pending = listOf(child))
        chat.client.interactions.replyPermission(CHILD, child.id, PermissionDecision.Once)
        chat.client.events.drop()
        chat.client.events.reconnect()
        runCurrent()

        assertEquals(emptyList(), presenter.ids())
    }

    @Test
    fun `this session's own request listed again shows once`() = runTest {
        val (_, presenter) = setup(ownRequests = listOf(own))

        assertEquals(listOf("per_own"), presenter.ids())
    }

    @Test
    fun `a failed list shows the error, keeps this session's cards, and retry lists again`() = runTest {
        val (chat, presenter) = setup(ownRequests = listOf(own), pending = listOf(child)) {
            it.control.failNext(ApiError.Server(500))
        }

        assertEquals(ChatFailure.ServerFailed, presenter.state.value.error)
        assertEquals(listOf("per_own"), presenter.ids())

        presenter.onIntent(PermissionIntent.Retry)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(listOf("per_own", "per_child"), presenter.ids())
        assertEquals(2, chat.listed.size)
    }

    @Test
    fun `another server clears every card and asks it nothing`() = runTest {
        val (chat, presenter) = setup(pending = listOf(child))
        assertEquals(listOf("per_child"), presenter.ids())

        chat.connected = false
        chat.mutableState.value = ChatSessionState(availability = ChatAvailability.OtherServer)
        runCurrent()

        assertEquals(PermissionsState(), presenter.state.value)
        assertEquals(1, chat.listed.size)
    }

    @Test
    fun `nothing is listed before the session is live`() = runTest {
        val chat = FakeChatSession(MAIN)
        val presenter = DefaultPermissionPresenter(route = ChatRoute.Chat(MAIN.value), scope = backgroundScope, session = chat)
        runCurrent()

        assertTrue(chat.listed.isEmpty())
        assertEquals(PermissionsState(), presenter.state.value)
    }

    // Replying

    @Test
    fun `allow once goes at once, to the session the request names`() = runTest {
        val (chat, presenter) = setup(pending = listOf(grandchild))

        presenter.onIntent(PermissionIntent.AllowOnce(grandchild.id))
        runCurrent()

        assertEquals(listOf(SentReply(GRANDCHILD, grandchild.id, PermissionDecision.Once, null)), chat.sent)
        assertEquals(1, chat.client.interactions.permissionReplies.size)
        assertEquals(emptyList(), presenter.ids())
    }

    @Test
    fun `allow always waits for its separate confirmation`() = runTest {
        val (chat, presenter) = setup(ownRequests = listOf(own))

        presenter.onIntent(PermissionIntent.AllowAlways(own.id))
        runCurrent()

        assertTrue(presenter.card(own).confirmingAlways)
        assertTrue(chat.sent.isEmpty())

        presenter.onIntent(PermissionIntent.ConfirmAlways(own.id))
        runCurrent()

        assertEquals(listOf(SentReply(MAIN, own.id, PermissionDecision.Always, null)), chat.sent)
        assertEquals(1, chat.client.interactions.savedRules.size)
        assertEquals(emptyList(), presenter.ids())
    }

    @Test
    fun `confirming always without asking for it sends nothing`() = runTest {
        val (chat, presenter) = setup(ownRequests = listOf(own))

        presenter.onIntent(PermissionIntent.ConfirmAlways(own.id))
        runCurrent()
        presenter.onIntent(PermissionIntent.AllowAlways(own.id))
        presenter.onIntent(PermissionIntent.CancelAlways(own.id))
        presenter.onIntent(PermissionIntent.ConfirmAlways(own.id))
        runCurrent()

        assertTrue(chat.sent.isEmpty())
        assertFalse(presenter.card(own).confirmingAlways)
    }

    @Test
    fun `reject goes with the trimmed message`() = runTest {
        val (chat, presenter) = setup(pending = listOf(child))

        presenter.onIntent(PermissionIntent.StartReject(child.id))
        presenter.onIntent(PermissionIntent.RejectMessageChanged(child.id, "  use the tests instead \n"))

        assertTrue(presenter.card(child).rejecting)
        assertEquals("  use the tests instead \n", presenter.card(child).rejectMessage)
        assertTrue(chat.sent.isEmpty())

        presenter.onIntent(PermissionIntent.Reject(child.id))
        runCurrent()

        assertEquals(listOf(SentReply(CHILD, child.id, PermissionDecision.Reject, "use the tests instead")), chat.sent)
        assertEquals(emptyList(), presenter.ids())
    }

    @Test
    fun `reject with a blank message, or without opening it, goes with none`() = runTest {
        val (chat, presenter) = setup(ownRequests = listOf(own), pending = listOf(child))

        presenter.onIntent(PermissionIntent.StartReject(own.id))
        presenter.onIntent(PermissionIntent.RejectMessageChanged(own.id, "   "))
        presenter.onIntent(PermissionIntent.Reject(own.id))
        presenter.onIntent(PermissionIntent.Reject(child.id))
        runCurrent()

        assertEquals(
            listOf(
                SentReply(MAIN, own.id, PermissionDecision.Reject, null),
                SentReply(CHILD, child.id, PermissionDecision.Reject, null),
            ),
            chat.sent,
        )
    }

    @Test
    fun `cancelling the rejection closes and clears the message`() = runTest {
        val (chat, presenter) = setup(ownRequests = listOf(own))

        presenter.onIntent(PermissionIntent.StartReject(own.id))
        presenter.onIntent(PermissionIntent.RejectMessageChanged(own.id, "no"))
        presenter.onIntent(PermissionIntent.CancelReject(own.id))

        assertFalse(presenter.card(own).rejecting)
        assertEquals("", presenter.card(own).rejectMessage)
        assertTrue(chat.sent.isEmpty())
    }

    @Test
    fun `a failed reply keeps the card, its message and the error`() = runTest {
        val (chat, presenter) = setup(ownRequests = listOf(own))
        chat.client.control.failNext(ApiError.Forbidden)

        presenter.onIntent(PermissionIntent.StartReject(own.id))
        presenter.onIntent(PermissionIntent.RejectMessageChanged(own.id, "not now"))
        presenter.onIntent(PermissionIntent.Reject(own.id))
        runCurrent()

        val card = presenter.card(own)
        assertEquals(PermissionError(PermissionDecision.Reject, ChatFailure.Rejected), card.error)
        assertNull(card.replying)
        assertTrue(card.rejecting)
        assertEquals("not now", card.rejectMessage)

        presenter.onIntent(PermissionIntent.DismissError(own.id))

        assertNull(presenter.card(own).error)
    }

    @Test
    fun `a request the server already settled leaves on NotFound`() = runTest {
        // Only in the session's state: the server no longer has it.
        val (chat, presenter) = setup(ownRequests = listOf(own)) {
            it.interactions.replyPermission(MAIN, own.id, PermissionDecision.Once)
        }

        presenter.onIntent(PermissionIntent.AllowOnce(own.id))
        runCurrent()

        assertEquals(1, chat.sent.size)
        assertEquals(emptyList(), presenter.ids())
    }

    @Test
    fun `one reply at a time per card`() = runTest {
        val (chat, presenter) = setup(ownRequests = listOf(own))
        chat.client.control.stall()

        presenter.onIntent(PermissionIntent.AllowOnce(own.id))
        runCurrent()

        assertEquals(PermissionDecision.Once, presenter.card(own).replying)
        assertFalse(presenter.card(own).canReply)

        presenter.onIntent(PermissionIntent.AllowOnce(own.id))
        presenter.onIntent(PermissionIntent.Reject(own.id))
        presenter.onIntent(PermissionIntent.AllowAlways(own.id))
        presenter.onIntent(PermissionIntent.ConfirmAlways(own.id))
        runCurrent()
        chat.client.control.resume()
        runCurrent()

        assertEquals(listOf(SentReply(MAIN, own.id, PermissionDecision.Once, null)), chat.sent)
        assertEquals(emptyList(), presenter.ids())
    }

    @Test
    fun `no reply goes without the bound server`() = runTest {
        val (chat, presenter) = setup(ownRequests = listOf(own))
        chat.connected = false

        presenter.onIntent(PermissionIntent.AllowOnce(own.id))
        runCurrent()

        assertTrue(chat.sent.isEmpty())
        assertEquals(PermissionError(PermissionDecision.Once, ChatFailure.NotConnected), presenter.card(own).error)
    }

    @Test
    fun `intents for a request that is not shown do nothing`() = runTest {
        val (chat, presenter) = setup(pending = listOf(stranger))

        presenter.onIntent(PermissionIntent.AllowOnce(stranger.id))
        presenter.onIntent(PermissionIntent.AllowAlways(stranger.id))
        presenter.onIntent(PermissionIntent.ConfirmAlways(stranger.id))
        presenter.onIntent(PermissionIntent.Reject(stranger.id))
        runCurrent()

        assertTrue(chat.sent.isEmpty())
        assertEquals(PermissionsState(), presenter.state.value)
    }
}
