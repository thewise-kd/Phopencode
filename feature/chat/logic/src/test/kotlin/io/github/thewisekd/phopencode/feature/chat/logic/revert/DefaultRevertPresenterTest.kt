package io.github.thewisekd.phopencode.feature.chat.logic.revert

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionRevert
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.StagedRevert
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
import kotlinx.coroutines.yield

private val MAIN = SessionId("ses_main")
private val TURN = MessageId("msg_turn")
private val EARLIER = MessageId("msg_earlier")

private fun diff(path: String, patch: String? = "@@ -1 +1 @@\n-a\n+b\n") =
    FileDiff(VcsFile(path, VcsFileStatus.Modified, additions = 1, deletions = 1), patch)

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

    /** Runs as a call starts, before its block: lets a test change the sync at the last moment. */
    var onCall: suspend () -> Unit = {}

    override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
        onCall()
        if (!connected) throw ApiException(ApiError.Closed)
        return block(client)
    }

    fun live(revert: SessionRevert? = null) {
        mutableState.value = ChatSessionState(
            availability = ChatAvailability.Live,
            session = SessionState(Session(id = sessionId, revert = revert)),
            phase = SyncPhase.Live,
        )
    }

    /** The calls that reached the server, oldest first. */
    val calls: List<String>
        get() = client.control.calls
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRevertPresenterTest {
    private val files = listOf(diff("src/b.kt"), diff("src/a.kt"))

    private fun TestScope.setup(
        live: Boolean = true,
        revert: SessionRevert? = null,
    ): Pair<FakeChatSession, DefaultRevertPresenter> {
        val chat = FakeChatSession(MAIN)
        chat.client.sessions.seed(Session(id = MAIN))
        chat.client.messages.seed(
            MAIN,
            listOf(
                Message.User(EARLIER, MAIN, text = "first"),
                Message.User(TURN, MAIN, text = "second"),
            ),
        )
        chat.client.messages.setTurnDiff(MAIN, TURN, files)
        if (live) chat.live(revert)
        val presenter = DefaultRevertPresenter(
            route = ChatRoute.TurnDiff(MAIN.value, TURN.value),
            scope = backgroundScope,
            session = chat,
        )
        runCurrent()
        return chat to presenter
    }

    // Loading

    @Test
    fun `the diff loads once the chat is live, in the server's order`() = runTest {
        val (chat, presenter) = setup(live = false)

        assertTrue(presenter.state.value.loading)
        assertFalse(presenter.state.value.enabled)
        assertEquals(emptyList(), chat.calls)

        chat.live()
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertTrue(state.enabled)
        assertEquals(files, state.files)
        assertNull(state.loadError)
        assertEquals(listOf("messages.turnDiff"), chat.calls)

        // Later session changes do not load it again.
        chat.live()
        runCurrent()
        assertEquals(listOf("messages.turnDiff"), chat.calls)
    }

    @Test
    fun `nothing runs before the scope gets to it`() = runTest {
        val chat = FakeChatSession(MAIN)
        chat.live()
        val presenter = DefaultRevertPresenter(ChatRoute.TurnDiff(MAIN.value, TURN.value), backgroundScope, chat)

        assertTrue(presenter.state.value.loading)
        assertEquals(emptyList(), chat.calls)
    }

    @Test
    fun `a failed load sets loadError and Retry loads again`() = runTest {
        val chat = FakeChatSession(MAIN)
        chat.client.sessions.seed(Session(id = MAIN))
        chat.client.messages.seed(MAIN, listOf(Message.User(TURN, MAIN, text = "t")))
        chat.client.messages.setTurnDiff(MAIN, TURN, files)
        chat.client.control.failNext(ApiError.Network)
        chat.live()
        val presenter = DefaultRevertPresenter(ChatRoute.TurnDiff(MAIN.value, TURN.value), backgroundScope, chat)
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertEquals(ChatFailure.Unreachable, presenter.state.value.loadError)
        assertEquals(emptyList(), presenter.state.value.files)

        presenter.onIntent(RevertIntent.Retry)
        runCurrent()

        assertNull(presenter.state.value.loadError)
        assertEquals(files, presenter.state.value.files)
    }

    @Test
    fun `a load the session refuses reports NotConnected`() = runTest {
        val chat = FakeChatSession(MAIN)
        chat.connected = false
        chat.live()
        val presenter = DefaultRevertPresenter(ChatRoute.TurnDiff(MAIN.value, TURN.value), backgroundScope, chat)
        runCurrent()

        assertEquals(ChatFailure.NotConnected, presenter.state.value.loadError)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `enabled follows the session and its connection`() = runTest {
        val (chat, presenter) = setup()
        assertTrue(presenter.state.value.enabled)

        chat.mutableState.value = ChatSessionState(availability = ChatAvailability.OtherServer)
        runCurrent()
        assertFalse(presenter.state.value.enabled)
        assertFalse(presenter.state.value.canRevert)

        presenter.onIntent(RevertIntent.Revert)
        runCurrent()
        assertEquals(listOf("messages.turnDiff"), chat.calls)
    }

    // Staged revert from the sync

    @Test
    fun `the staged revert comes from the session, for this turn or another`() = runTest {
        val (chat, presenter) = setup(revert = SessionRevert(TURN, files = files))

        assertEquals(StagedRevert(TURN, files, thisTurn = true), presenter.state.value.staged)
        assertTrue(presenter.state.value.canConfirm)

        chat.live(SessionRevert(EARLIER))
        runCurrent()
        assertEquals(StagedRevert(EARLIER, emptyList(), thisTurn = false), presenter.state.value.staged)
        assertFalse(presenter.state.value.canConfirm)
        assertTrue(presenter.state.value.canCancel)

        chat.live(null)
        runCurrent()
        assertNull(presenter.state.value.staged)
    }

    // Stage

    @Test
    fun `Revert stages this turn and blocks the actions until the sync shows it`() = runTest {
        val (chat, presenter) = setup()
        chat.client.messages.revertPreview = files
        chat.client.control.stall()

        presenter.onIntent(RevertIntent.Revert)
        presenter.onIntent(RevertIntent.Revert)
        runCurrent()
        assertEquals(RevertAction.Stage, presenter.state.value.pending)
        assertFalse(presenter.state.value.canRevert)

        chat.client.control.resume()
        runCurrent()

        assertEquals(listOf("messages.turnDiff", "messages.stageRevert"), chat.calls)
        // Staged on the server, not yet in the sync: the data says nothing staged, the actions wait.
        assertNull(presenter.state.value.staged)
        assertEquals(RevertAction.Stage, presenter.state.value.pending)
        assertFalse(presenter.state.value.canRevert)
        assertFalse(presenter.state.value.canCancel)
        presenter.onIntent(RevertIntent.Revert)
        runCurrent()
        assertEquals(listOf("messages.turnDiff", "messages.stageRevert"), chat.calls)

        // An update that leaves the revert as it was frees nothing.
        chat.live(null)
        runCurrent()
        assertEquals(RevertAction.Stage, presenter.state.value.pending)

        chat.live(SessionRevert(TURN, files = files))
        runCurrent()
        assertNull(presenter.state.value.pending)
        assertEquals(StagedRevert(TURN, files, thisTurn = true), presenter.state.value.staged)
        assertTrue(presenter.state.value.canConfirm)
    }

    @Test
    fun `a sync that caught up before the answer blocks nothing`() = runTest {
        val (chat, presenter) = setup()
        chat.client.control.stall()

        presenter.onIntent(RevertIntent.Revert)
        runCurrent()
        // The event comes before the call's answer.
        chat.live(SessionRevert(TURN))
        runCurrent()
        assertEquals(TURN, presenter.state.value.staged?.messageId)
        chat.client.control.resume()
        runCurrent()

        assertNull(presenter.state.value.pending)
        assertTrue(presenter.state.value.canConfirm)
    }

    @Test
    fun `a settling step is freed when the chat stops being live`() = runTest {
        val (chat, presenter) = setup()

        presenter.onIntent(RevertIntent.Revert)
        runCurrent()
        assertEquals(RevertAction.Stage, presenter.state.value.pending)

        chat.mutableState.value = chat.mutableState.value.copy(availability = ChatAvailability.Reconnecting)
        runCurrent()
        assertNull(presenter.state.value.pending)

        chat.live(null)
        runCurrent()
        assertNull(presenter.state.value.pending)
        assertTrue(presenter.state.value.canRevert)
    }

    @Test
    fun `a failed stage sets the error and stages nothing`() = runTest {
        val (chat, presenter) = setup()
        chat.client.control.failNext(ApiError.Conflict("busy"))

        presenter.onIntent(RevertIntent.Revert)
        runCurrent()

        assertEquals(RevertError(RevertAction.Stage, ChatFailure.Conflict), presenter.state.value.error)
        assertNull(presenter.state.value.staged)
        assertNull(presenter.state.value.pending)
        assertTrue(presenter.state.value.canRevert)

        presenter.onIntent(RevertIntent.DismissError)
        runCurrent()
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a step while not connected fails with NotConnected`() = runTest {
        val (chat, presenter) = setup()
        chat.connected = false

        presenter.onIntent(RevertIntent.Revert)
        runCurrent()

        assertEquals(RevertError(RevertAction.Stage, ChatFailure.NotConnected), presenter.state.value.error)
        assertEquals(listOf("messages.turnDiff"), chat.calls)
    }

    // Confirm

    @Test
    fun `Confirm does nothing until this turn's revert is staged`() = runTest {
        val (chat, presenter) = setup(revert = SessionRevert(EARLIER))

        presenter.onIntent(RevertIntent.Confirm)
        runCurrent()

        assertEquals(listOf("messages.turnDiff"), chat.calls)
    }

    @Test
    fun `Confirm commits the staged revert and reports Reverted`() = runTest {
        val (chat, presenter) = setup()
        val events = mutableListOf<RevertEvent>()
        backgroundScope.launch { presenter.events.toList(events) }

        presenter.onIntent(RevertIntent.Revert)
        runCurrent()
        assertEquals(emptyList(), events)
        // Confirm waits for the sync to show the stage.
        presenter.onIntent(RevertIntent.Confirm)
        runCurrent()
        assertEquals(listOf("messages.turnDiff", "messages.stageRevert"), chat.calls)
        chat.live(SessionRevert(TURN))
        runCurrent()

        presenter.onIntent(RevertIntent.Confirm)
        runCurrent()

        assertEquals(listOf("messages.turnDiff", "messages.stageRevert", "messages.commitRevert"), chat.calls)
        assertEquals(listOf<RevertEvent>(RevertEvent.Reverted), events)
        assertNull(chat.client.sessions.get(MAIN).revert)
        // Until the sync reports it, the data stays the sync's and the actions wait.
        assertEquals(TURN, presenter.state.value.staged?.messageId)
        assertEquals(RevertAction.Commit, presenter.state.value.pending)
        assertFalse(presenter.state.value.canConfirm)
        assertFalse(presenter.state.value.canCancel)

        chat.live(null)
        runCurrent()
        assertNull(presenter.state.value.staged)
        assertNull(presenter.state.value.pending)
    }

    @Test
    fun `a claimed Confirm commits nothing once the sync has replaced the staged revert`() = runTest {
        val (chat, presenter) = setup(revert = SessionRevert(TURN))
        chat.client.messages.stageRevert(MAIN, EARLIER)
        val events = mutableListOf<RevertEvent>()
        backgroundScope.launch { presenter.events.toList(events) }
        chat.onCall = {
            chat.live(SessionRevert(EARLIER))
            yield()
        }

        presenter.onIntent(RevertIntent.Confirm)
        runCurrent()

        assertFalse("messages.commitRevert" in chat.calls)
        assertEquals(EARLIER, chat.client.sessions.get(MAIN).revert?.messageId)
        assertEquals(emptyList(), events)
        assertNull(presenter.state.value.pending)
        assertNull(presenter.state.value.error)
        assertEquals(StagedRevert(EARLIER, emptyList(), thisTurn = false), presenter.state.value.staged)
        assertTrue(presenter.state.value.canCancel)
    }

    @Test
    fun `a claimed Confirm commits nothing when the replacement is published but not yet collected`() = runTest {
        val (chat, presenter) = setup(revert = SessionRevert(TURN))
        chat.client.messages.stageRevert(MAIN, EARLIER)
        // Published to the session's state; the presenter's collector has not run since.
        chat.onCall = { chat.live(SessionRevert(EARLIER)) }

        presenter.onIntent(RevertIntent.Confirm)
        runCurrent()

        assertFalse("messages.commitRevert" in chat.calls)
        assertEquals(EARLIER, chat.client.sessions.get(MAIN).revert?.messageId)
        assertNull(presenter.state.value.pending)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a claimed Cancel drops nothing when the replacement is published but not yet collected`() = runTest {
        val (chat, presenter) = setup(revert = SessionRevert(EARLIER))
        chat.client.messages.stageRevert(MAIN, TURN)
        chat.onCall = { chat.live(SessionRevert(TURN)) }

        presenter.onIntent(RevertIntent.Cancel)
        runCurrent()

        assertFalse("messages.cancelRevert" in chat.calls)
        assertEquals(TURN, chat.client.sessions.get(MAIN).revert?.messageId)
        assertNull(presenter.state.value.pending)
    }

    @Test
    fun `a claimed Cancel drops nothing once the sync has replaced the staged revert`() = runTest {
        val (chat, presenter) = setup(revert = SessionRevert(EARLIER))
        chat.client.messages.stageRevert(MAIN, TURN)
        chat.onCall = {
            chat.live(SessionRevert(TURN))
            yield()
        }

        presenter.onIntent(RevertIntent.Cancel)
        runCurrent()

        assertFalse("messages.cancelRevert" in chat.calls)
        assertEquals(TURN, chat.client.sessions.get(MAIN).revert?.messageId)
        assertNull(presenter.state.value.pending)
        assertNull(presenter.state.value.error)
        assertTrue(presenter.state.value.canConfirm)
    }

    @Test
    fun `a failed commit keeps the staged revert and sets the error`() = runTest {
        val (chat, presenter) = setup(revert = SessionRevert(TURN))
        val events = mutableListOf<RevertEvent>()
        backgroundScope.launch { presenter.events.toList(events) }
        chat.client.control.failNext(ApiError.Server(500, null))

        presenter.onIntent(RevertIntent.Confirm)
        runCurrent()

        assertEquals(RevertError(RevertAction.Commit, ChatFailure.ServerFailed), presenter.state.value.error)
        assertEquals(StagedRevert(TURN, emptyList(), thisTurn = true), presenter.state.value.staged)
        assertEquals(emptyList(), events)
        // A failure blocks nothing.
        assertNull(presenter.state.value.pending)
        assertTrue(presenter.state.value.canConfirm)
        assertTrue(presenter.state.value.canCancel)
    }

    // Cancel

    @Test
    fun `Cancel drops a staged revert on the server, also another turn's`() = runTest {
        val (chat, presenter) = setup()
        chat.client.messages.stageRevert(MAIN, EARLIER)
        chat.live(SessionRevert(EARLIER))
        runCurrent()

        presenter.onIntent(RevertIntent.Cancel)
        runCurrent()

        assertTrue("messages.cancelRevert" in chat.calls)
        assertNull(chat.client.sessions.get(MAIN).revert)
        assertNull(presenter.state.value.error)
        // The sync has not reported it: its revert still shows, and the actions wait.
        assertEquals(EARLIER, presenter.state.value.staged?.messageId)
        assertEquals(RevertAction.Cancel, presenter.state.value.pending)
        assertFalse(presenter.state.value.canCancel)

        chat.live(null)
        runCurrent()
        assertNull(presenter.state.value.staged)
        assertNull(presenter.state.value.pending)
        assertTrue(presenter.state.value.canRevert)
    }

    @Test
    fun `Cancel with nothing staged sends nothing`() = runTest {
        val (chat, presenter) = setup()

        presenter.onIntent(RevertIntent.Cancel)
        runCurrent()

        assertEquals(listOf("messages.turnDiff"), chat.calls)
    }

    @Test
    fun `a failed cancel keeps the staged revert`() = runTest {
        val (chat, presenter) = setup(revert = SessionRevert(TURN))
        chat.client.control.failNext(ApiError.Network)

        presenter.onIntent(RevertIntent.Cancel)
        runCurrent()

        assertEquals(RevertError(RevertAction.Cancel, ChatFailure.Unreachable), presenter.state.value.error)
        assertEquals(TURN, presenter.state.value.staged?.messageId)
        assertNull(presenter.state.value.pending)
        assertTrue(presenter.state.value.canCancel)
    }

    // Close

    @Test
    fun `Close leaves without touching a staged revert`() = runTest {
        val (chat, presenter) = setup(revert = SessionRevert(TURN))
        val events = mutableListOf<RevertEvent>()
        backgroundScope.launch { presenter.events.toList(events) }

        presenter.onIntent(RevertIntent.Close)
        runCurrent()

        assertEquals(listOf<RevertEvent>(RevertEvent.Close), events)
        assertEquals(listOf("messages.turnDiff"), chat.calls)
    }

    @Test
    fun `the staged data always matches the sync, through a delayed stage and a late clear`() = runTest {
        val (chat, presenter) = setup()
        val shown = mutableListOf<StagedRevert?>()
        backgroundScope.launch { presenter.state.collect { shown += it.staged } }

        presenter.onIntent(RevertIntent.Revert)
        runCurrent()
        // The stage, delayed.
        syncShows(chat, presenter, SessionRevert(TURN))
        presenter.onIntent(RevertIntent.Cancel)
        runCurrent()
        // The cancel's clear, late, after an unrelated update that still holds the stage.
        syncShows(chat, presenter, SessionRevert(TURN))
        assertEquals(RevertAction.Cancel, presenter.state.value.pending)
        syncShows(chat, presenter, null)
        assertNull(presenter.state.value.pending)

        // Staged again, and a stale clear arrives before the new stage.
        presenter.onIntent(RevertIntent.Revert)
        runCurrent()
        syncShows(chat, presenter, null)
        assertEquals(RevertAction.Stage, presenter.state.value.pending)
        syncShows(chat, presenter, SessionRevert(TURN))
        assertNull(presenter.state.value.pending)

        // Never anything the sync did not show.
        assertTrue(shown.all { it == null || it.messageId == TURN })
    }

    @Test
    fun `another revert published before the answer shows, and frees the actions`() = runTest {
        val (chat, presenter) = setup()
        chat.client.control.stall()

        presenter.onIntent(RevertIntent.Revert)
        runCurrent()
        syncShows(chat, presenter, SessionRevert(EARLIER))
        chat.client.control.resume()
        runCurrent()

        assertEquals(StagedRevert(EARLIER, emptyList(), thisTurn = false), presenter.state.value.staged)
        assertNull(presenter.state.value.pending)
        assertTrue(presenter.state.value.canCancel)
    }

    @Test
    fun `a failed step blocks nothing`() = runTest {
        val (chat, presenter) = setup()
        chat.client.control.failNext(ApiError.Network)

        presenter.onIntent(RevertIntent.Revert)
        runCurrent()

        assertNull(presenter.state.value.pending)
        assertTrue(presenter.state.value.canRevert)
        presenter.onIntent(RevertIntent.Revert)
        runCurrent()
        assertEquals(listOf("messages.turnDiff", "messages.stageRevert", "messages.stageRevert"), chat.calls)
        assertEquals(RevertAction.Stage, presenter.state.value.pending)
    }

    /** Has the sync show [revert], and checks the page shows exactly that, bounded. */
    private fun TestScope.syncShows(chat: FakeChatSession, presenter: DefaultRevertPresenter, revert: SessionRevert?) {
        chat.live(revert)
        runCurrent()
        val expected = revert?.let { StagedRevert(it.messageId, TurnDiffBounds.bound(it.files), thisTurn = it.messageId == TURN) }
        assertEquals(expected, presenter.state.value.staged)
    }

    // Bounds and printing

    @Test
    fun `a staged revert's files are bounded`() = runTest {
        val huge = diff("big.bin", "x".repeat(TurnDiffBounds.MAX_PATCH_CHARS + 1))
        val (_, presenter) = setup(revert = SessionRevert(TURN, files = listOf(huge)))

        val held = presenter.state.value.staged?.files.orEmpty()
        assertEquals(listOf("big.bin"), held.map { it.file.path })
        assertNull(held.single().patch)
    }

    @Test
    fun `a staged revert held is the bounded form, never the sync's list`() = runTest {
        val (chat, presenter) = setup()
        val raw = List(TurnDiffBounds.MAX_FILES + 1) { diff("f$it", "x".repeat(TurnDiffBounds.MAX_PATCH_CHARS + 1)) }
        chat.live(SessionRevert(TURN, files = raw))
        runCurrent()

        val held = presenter.state.value.staged?.files.orEmpty()
        assertTrue(held !== raw)
        assertEquals(TurnDiffBounds.MAX_FILES, held.size)
        assertTrue(held.all { it.patch == null })

        chat.live(null)
        runCurrent()
        assertNull(presenter.state.value.staged)
    }

    @Test
    fun `the diff held is bounded`() = runTest {
        val chat = FakeChatSession(MAIN)
        chat.client.sessions.seed(Session(id = MAIN))
        chat.client.messages.seed(MAIN, listOf(Message.User(TURN, MAIN, text = "t")))
        val huge = diff("big.bin", "x".repeat(TurnDiffBounds.MAX_PATCH_CHARS + 1))
        val many = List(TurnDiffBounds.MAX_FILES + 5) { diff("f$it") }
        chat.client.messages.setTurnDiff(MAIN, TURN, listOf(huge) + many)
        chat.live()
        val presenter = DefaultRevertPresenter(ChatRoute.TurnDiff(MAIN.value, TURN.value), backgroundScope, chat)
        runCurrent()

        val held = presenter.state.value.files
        assertEquals(TurnDiffBounds.MAX_FILES, held.size)
        assertEquals("big.bin", held.first().file.path)
        assertNull(held.first().patch)
        assertEquals(1L, held.first().file.additions)
    }

    @Test
    fun `the state prints no paths or patches`() = runTest {
        val (_, presenter) = setup(revert = SessionRevert(TURN, files = files))

        val printed = presenter.state.value.toString()
        assertFalse("src/" in printed)
        assertFalse("@@" in printed)
    }
}
