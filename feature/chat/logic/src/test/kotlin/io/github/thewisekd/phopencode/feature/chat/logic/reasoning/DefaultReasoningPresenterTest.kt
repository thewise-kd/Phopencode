package io.github.thewisekd.phopencode.feature.chat.logic.reasoning

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultReasoningPresenterTest {
    private val sessionId = SessionId("ses_1")
    private val msg1 = MessageId("msg_1")
    private val msg2 = MessageId("msg_2")
    private val t0 = Instant.fromEpochSeconds(1_000)

    private class FakeClock(var now: Instant) : Clock {
        override fun now(): Instant = now
    }

    private class FakeChatSession(override val sessionId: SessionId) : ChatSession {
        val mutable = MutableStateFlow(ChatSessionState())
        override val state: StateFlow<ChatSessionState> = mutable.asStateFlow()

        override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T = throw ApiException(ApiError.Closed)
    }

    private fun FakeChatSession.show(vararg messages: Message) {
        mutable.value = ChatSessionState(
            availability = ChatAvailability.Live,
            session = SessionState(session = Session(id = sessionId), messages = messages.toList()),
        )
    }

    private fun assistant(
        id: MessageId,
        vararg blocks: AssistantBlock,
        completedAt: Instant? = null,
        error: StructuredError? = null,
    ) = Message.Assistant(
        id = id,
        sessionId = sessionId,
        createdAt = t0,
        completedAt = completedAt,
        blocks = blocks.toList(),
        error = error,
    )

    private fun TestScope.presenter(
        clock: Clock = FakeClock(t0),
    ): Pair<FakeChatSession, DefaultReasoningPresenter> {
        val session = FakeChatSession(sessionId)
        val presenter = DefaultReasoningPresenter(session = session, scope = backgroundScope, clock = clock)
        return session to presenter
    }

    @Test
    fun `nothing runs in the constructor`() = runTest {
        val session = FakeChatSession(sessionId)
        session.show(assistant(msg1, AssistantBlock.Reasoning("thinking", t0, t0 + 2.seconds)))
        val presenter = DefaultReasoningPresenter(session = session, scope = backgroundScope, clock = FakeClock(t0))

        assertEquals(ReasoningState(), presenter.state.value)
        runCurrent()
        assertEquals(1, presenter.state.value.cards.size)
    }

    @Test
    fun `each reasoning block gets a card keyed by message and block index`() = runTest {
        val (session, presenter) = presenter()
        session.show(
            assistant(
                msg1,
                AssistantBlock.Text("hello"),
                AssistantBlock.Reasoning("first", t0, t0 + 1.seconds),
                AssistantBlock.Reasoning("second", t0 + 1.seconds, t0 + 3.seconds),
                completedAt = t0 + 4.seconds,
            ),
            assistant(msg2, AssistantBlock.Reasoning("third", t0, t0 + 5.seconds), completedAt = t0 + 5.seconds),
        )
        runCurrent()

        assertEquals(
            mapOf(
                TimelineKey.reasoning(msg1, 1) to ReasoningCardState("first", duration = 1.seconds),
                TimelineKey.reasoning(msg1, 2) to ReasoningCardState("second", duration = 2.seconds),
                TimelineKey.reasoning(msg2, 0) to ReasoningCardState("third", duration = 5.seconds),
            ),
            presenter.state.value.cards,
        )
    }

    @Test
    fun `streaming text grows in its own card, in order`() = runTest {
        val clock = FakeClock(t0)
        val (session, presenter) = presenter(clock)
        val key = TimelineKey.reasoning(msg1, 0)
        val other = TimelineKey.reasoning(msg2, 0)
        session.show(
            assistant(msg2, AssistantBlock.Reasoning("done", t0, t0 + 1.seconds), completedAt = t0 + 1.seconds),
            assistant(msg1, AssistantBlock.Reasoning("Let", t0)),
        )
        runCurrent()
        assertEquals("Let", presenter.state.value.cards.getValue(key).text)
        assertTrue(presenter.state.value.cards.getValue(key).streaming)

        session.show(
            assistant(msg2, AssistantBlock.Reasoning("done", t0, t0 + 1.seconds), completedAt = t0 + 1.seconds),
            assistant(msg1, AssistantBlock.Reasoning("Let me think", t0)),
        )
        runCurrent()

        assertEquals("Let me think", presenter.state.value.cards.getValue(key).text)
        assertEquals("done", presenter.state.value.cards.getValue(other).text)
        assertFalse(presenter.state.value.cards.getValue(other).streaming)
    }

    @Test
    fun `a resync with the same text does not duplicate it`() = runTest {
        val (session, presenter) = presenter()
        val key = TimelineKey.reasoning(msg1, 0)
        session.show(assistant(msg1, AssistantBlock.Reasoning("partial", t0)))
        runCurrent()

        // A reconnect drops the session, then the fetched truth replaces it.
        session.mutable.value = session.mutable.value.copy(availability = ChatAvailability.Reconnecting)
        runCurrent()
        session.show(assistant(msg1, AssistantBlock.Reasoning("partial and more", t0)))
        runCurrent()
        session.show(assistant(msg1, AssistantBlock.Reasoning("partial and more", t0)))
        runCurrent()

        assertEquals("partial and more", presenter.state.value.cards.getValue(key).text)
    }

    @Test
    fun `empty or whitespace reasoning gets no card`() = runTest {
        val (session, presenter) = presenter()
        session.show(
            assistant(
                msg1,
                AssistantBlock.Reasoning("", t0, t0 + 1.seconds),
                AssistantBlock.Reasoning("  \n\t ", t0, t0 + 1.seconds),
                AssistantBlock.Reasoning("real", t0, t0 + 1.seconds),
                completedAt = t0 + 1.seconds,
            ),
        )
        runCurrent()

        assertEquals(setOf(TimelineKey.reasoning(msg1, 2)), presenter.state.value.cards.keys)
    }

    @Test
    fun `a streaming block that starts empty shows once text arrives`() = runTest {
        val (session, presenter) = presenter()
        val key = TimelineKey.reasoning(msg1, 0)
        session.show(assistant(msg1, AssistantBlock.Reasoning("", t0)))
        runCurrent()
        assertTrue(presenter.state.value.cards.isEmpty())

        session.show(assistant(msg1, AssistantBlock.Reasoning("now", t0)))
        runCurrent()
        assertEquals("now", presenter.state.value.cards.getValue(key).text)
    }

    @Test
    fun `a completed block measures from its start to its end`() = runTest {
        val clock = FakeClock(t0 + 100.seconds)
        val (session, presenter) = presenter(clock)
        session.show(
            assistant(msg1, AssistantBlock.Reasoning("x", t0, t0 + 7.seconds), completedAt = t0 + 9.seconds),
        )
        runCurrent()

        val card = presenter.state.value.cards.getValue(TimelineKey.reasoning(msg1, 0))
        assertEquals(7.seconds, card.duration)
        assertFalse(card.streaming)
    }

    @Test
    fun `a streaming block measures to the clock and keeps counting`() = runTest {
        val clock = FakeClock(t0 + 3.seconds)
        val (session, presenter) = presenter(clock)
        val key = TimelineKey.reasoning(msg1, 0)
        session.show(assistant(msg1, AssistantBlock.Reasoning("x", t0)))
        runCurrent()
        assertEquals(3.seconds, presenter.state.value.cards.getValue(key).duration)
        assertTrue(presenter.state.value.cards.getValue(key).streaming)

        clock.now = t0 + 5.seconds
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(5.seconds, presenter.state.value.cards.getValue(key).duration)

        session.show(assistant(msg1, AssistantBlock.Reasoning("x", t0, t0 + 6.seconds)))
        runCurrent()
        clock.now = t0 + 60.seconds
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(6.seconds, presenter.state.value.cards.getValue(key).duration)
        assertFalse(presenter.state.value.cards.getValue(key).streaming)
    }

    @Test
    fun `the duration is never negative`() = runTest {
        val clock = FakeClock(t0 - 10.seconds)
        val (session, presenter) = presenter(clock)
        session.show(
            assistant(msg1, AssistantBlock.Reasoning("streaming", t0)),
            assistant(msg2, AssistantBlock.Reasoning("skewed", t0, t0 - 4.seconds), completedAt = t0),
        )
        runCurrent()

        assertEquals(0.seconds, presenter.state.value.cards.getValue(TimelineKey.reasoning(msg1, 0)).duration)
        assertEquals(0.seconds, presenter.state.value.cards.getValue(TimelineKey.reasoning(msg2, 0)).duration)
    }

    @Test
    fun `no start time gives no duration`() = runTest {
        val (session, presenter) = presenter()
        session.show(
            assistant(msg1, AssistantBlock.Reasoning("a", createdAt = null, completedAt = t0), completedAt = t0),
            assistant(msg2, AssistantBlock.Reasoning("b", createdAt = null)),
        )
        runCurrent()

        assertNull(presenter.state.value.cards.getValue(TimelineKey.reasoning(msg1, 0)).duration)
        assertNull(presenter.state.value.cards.getValue(TimelineKey.reasoning(msg2, 0)).duration)
    }

    @Test
    fun `a block without an end stops streaming when its message ends`() = runTest {
        val clock = FakeClock(t0 + 50.seconds)
        val (session, presenter) = presenter(clock)
        session.show(
            assistant(msg1, AssistantBlock.Reasoning("a", t0), completedAt = t0 + 4.seconds),
            assistant(msg2, AssistantBlock.Reasoning("b", t0), error = StructuredError(type = "aborted")),
        )
        runCurrent()

        val done = presenter.state.value.cards.getValue(TimelineKey.reasoning(msg1, 0))
        assertFalse(done.streaming)
        assertEquals(4.seconds, done.duration)
        val failed = presenter.state.value.cards.getValue(TimelineKey.reasoning(msg2, 0))
        assertFalse(failed.streaming)
        assertNull(failed.duration)
    }

    @Test
    fun `toggle expands and collapses a card, and the state survives updates`() = runTest {
        val (session, presenter) = presenter()
        val key = TimelineKey.reasoning(msg1, 0)
        val other = TimelineKey.reasoning(msg2, 0)
        session.show(
            assistant(msg1, AssistantBlock.Reasoning("a", t0)),
            assistant(msg2, AssistantBlock.Reasoning("b", t0)),
        )
        runCurrent()
        assertFalse(presenter.state.value.cards.getValue(key).expanded)

        presenter.onIntent(ReasoningIntent.Toggle(key))
        runCurrent()
        assertTrue(presenter.state.value.cards.getValue(key).expanded)
        assertFalse(presenter.state.value.cards.getValue(other).expanded)

        session.show(
            assistant(msg1, AssistantBlock.Reasoning("a more", t0)),
            assistant(msg2, AssistantBlock.Reasoning("b more", t0)),
        )
        runCurrent()
        assertTrue(presenter.state.value.cards.getValue(key).expanded)
        assertEquals("a more", presenter.state.value.cards.getValue(key).text)

        presenter.onIntent(ReasoningIntent.Toggle(key))
        runCurrent()
        assertFalse(presenter.state.value.cards.getValue(key).expanded)
    }

    @Test
    fun `the expanded state survives the session going away and coming back`() = runTest {
        val (session, presenter) = presenter()
        val key = TimelineKey.reasoning(msg1, 0)
        session.show(assistant(msg1, AssistantBlock.Reasoning("a", t0, t0 + 1.seconds), completedAt = t0))
        runCurrent()
        presenter.onIntent(ReasoningIntent.Toggle(key))
        runCurrent()

        session.mutable.value = ChatSessionState(availability = ChatAvailability.NotConnected, session = null)
        runCurrent()
        assertTrue(presenter.state.value.cards.isEmpty())

        session.show(assistant(msg1, AssistantBlock.Reasoning("a", t0, t0 + 1.seconds), completedAt = t0))
        runCurrent()
        assertTrue(presenter.state.value.cards.getValue(key).expanded)
    }

    @Test
    fun `toggling a key with no card does nothing`() = runTest {
        val (session, presenter) = presenter()
        session.show(assistant(msg1, AssistantBlock.Reasoning("a", t0, t0 + 1.seconds), completedAt = t0))
        runCurrent()
        val before = presenter.state.value

        presenter.onIntent(ReasoningIntent.Toggle(TimelineKey.reasoning(msg2, 0)))
        runCurrent()

        assertEquals(before, presenter.state.value)
    }

    @Test
    fun `printing the state masks the reasoning text`() = runTest {
        val (session, presenter) = presenter()
        session.show(assistant(msg1, AssistantBlock.Reasoning("secret plan", t0, t0 + 1.seconds), completedAt = t0))
        runCurrent()

        assertFalse(presenter.state.value.toString().contains("secret plan"))
    }
}
