package io.github.thewisekd.phopencode.feature.chat.logic.usage

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelLimit
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import java.math.BigDecimal
import java.math.BigInteger
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

/** A [ChatSession] the test drives; [call] runs on [client] only while [connected]. */
private class FakeChatSession(override val sessionId: SessionId) : ChatSession {
    val client = FakeOpencodeClient()

    val mutableState = MutableStateFlow(ChatSessionState())

    override val state: StateFlow<ChatSessionState> = mutableState

    @Volatile
    var connected = true

    override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
        if (!connected) throw ApiException(ApiError.Closed)
        return block(client)
    }

    val modelCalls: Int
        get() = client.control.calls.count { it == "catalog.models" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultUsagePresenterTest {
    private val sessionId = SessionId("ses_usage")
    private val folder = "/srv/project"
    private val sonnet = ModelRef(providerId = "anthropic", modelId = "sonnet")
    private val opus = ModelRef(providerId = "anthropic", modelId = "opus")
    private var nextId = 0

    private fun models(vararg entries: Pair<ModelRef, Long?>) = entries.map { (ref, context) ->
        Model(id = ref.modelId, providerId = ref.providerId, name = ref.modelId, limit = ModelLimit(context = context))
    }

    private fun assistant(usage: TokenUsage?, model: ModelRef? = sonnet) = Message.Assistant(
        id = MessageId("msg_${nextId++}"),
        sessionId = sessionId,
        model = model,
        usage = usage,
    )

    private fun cost(value: String) = Cost.of(BigDecimal(value))!!

    private fun FakeChatSession.show(
        vararg messages: Message,
        reported: TokenUsage? = null,
        model: ModelRef? = null,
        directory: String? = folder,
        availability: ChatAvailability = ChatAvailability.Live,
    ) {
        mutableState.value = ChatSessionState(
            availability = availability,
            session = SessionState(
                session = Session(id = sessionId, directory = directory, model = model, usage = reported),
                messages = messages.toList(),
            ),
            phase = SyncPhase.Live,
        )
    }

    private fun TestScope.setup(): Pair<FakeChatSession, DefaultUsagePresenter> {
        val chat = FakeChatSession(sessionId)
        chat.client.catalog.setModels(models(sonnet to 10_000L, opus to null), Location(folder))
        val presenter = DefaultUsagePresenter(session = chat, scope = backgroundScope)
        return chat to presenter
    }

    @Test
    fun `nothing runs in the constructor`() = runTest {
        val (chat, presenter) = setup()
        chat.show(assistant(TokenUsage.of(input = 10)))

        assertEquals(UsageState(), presenter.state.value)
        assertEquals(0, chat.modelCalls)

        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertEquals(BigInteger.TEN, presenter.state.value.usage?.input)
    }

    @Test
    fun `loading until the session is fetched`() = runTest {
        val (_, presenter) = setup()
        runCurrent()

        assertTrue(presenter.state.value.loading)
        assertNull(presenter.state.value.usage)
    }

    @Test
    fun `totals add up the messages when the server reported none`() = runTest {
        val (chat, presenter) = setup()
        chat.show(
            assistant(TokenUsage.of(input = 100, output = 20, reasoning = 5, cacheRead = 30, cacheWrite = 7, cost = cost("0.01"))),
            assistant(TokenUsage.of(input = 50, output = 10, reasoning = 1, cacheRead = 3, cacheWrite = 2, cost = cost("0.02"))),
            Message.Compaction(id = MessageId("cmp"), sessionId = sessionId, usage = TokenUsage.of(input = 1, cost = cost("0.001"))),
        )
        runCurrent()

        val usage = presenter.state.value.usage!!
        assertEquals(BigInteger.valueOf(151), usage.input)
        assertEquals(BigInteger.valueOf(30), usage.output)
        assertEquals(BigInteger.valueOf(6), usage.reasoning)
        assertEquals(BigInteger.valueOf(33), usage.cacheRead)
        assertEquals(BigInteger.valueOf(9), usage.cacheWrite)
        assertEquals(cost("0.031"), usage.cost)
        assertFalse(usage.hasMissingCost)
    }

    @Test
    fun `the reported running total wins over the messages and is not added twice`() = runTest {
        val (chat, presenter) = setup()
        val reported = TokenUsage.of(input = 1_000, output = 200, cost = cost("1.5"))
        val message = assistant(TokenUsage.of(input = 100, output = 20, cost = cost("0.1")))
        chat.show(message, reported = reported)
        runCurrent()
        assertEquals(reported, presenter.state.value.usage)

        // A resync brings the same session again, and a `UsageUpdated` later raises the total.
        chat.show(message, reported = reported)
        runCurrent()
        assertEquals(reported, presenter.state.value.usage)

        val updated = TokenUsage.of(input = 1_100, output = 220, cost = cost("1.6"))
        chat.show(message, reported = updated)
        runCurrent()
        assertEquals(updated, presenter.state.value.usage)
    }

    @Test
    fun `a session with no usage at all has no totals`() = runTest {
        val (chat, presenter) = setup()
        chat.show()
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertNull(presenter.state.value.usage)
    }

    @Test
    fun `context percentage is the newest step's context tokens of the model's limit`() = runTest {
        val (chat, presenter) = setup()
        chat.show(
            assistant(TokenUsage.of(input = 9_000, output = 900)),
            // Prompt 200 + 600 + 100 cached, written 150 + 50 reasoning: 1100 in context.
            assistant(TokenUsage.of(input = 200, output = 150, reasoning = 50, cacheRead = 600, cacheWrite = 100)),
            // Still streaming: no usage yet, so the previous step's fill stays.
            assistant(usage = null),
        )
        runCurrent()

        val state = presenter.state.value
        assertEquals(sonnet, state.model)
        assertEquals(10_000L, state.contextLimit)
        assertEquals(BigInteger.valueOf(1_100), state.contextTokens)
        assertEquals(11, state.contextPercent)
        assertTrue(state.showsContext)
        assertNull(state.error)
        assertEquals(1, chat.modelCalls)
    }

    @Test
    fun `the model list is asked for the session's folder`() = runTest {
        val chat = FakeChatSession(sessionId)
        chat.client.catalog.setModels(models(sonnet to 50_000L))
        chat.client.catalog.setModels(models(sonnet to 10_000L), Location(folder))
        val presenter = DefaultUsagePresenter(session = chat, scope = backgroundScope)
        chat.show(assistant(TokenUsage.of(input = 1_000)))
        runCurrent()

        assertEquals(10_000L, presenter.state.value.contextLimit)
    }

    @Test
    fun `the model is the newest assistant's, else the session's`() = runTest {
        val (chat, presenter) = setup()
        chat.show(model = sonnet)
        runCurrent()
        assertEquals(sonnet, presenter.state.value.model)
        assertEquals(10_000L, presenter.state.value.contextLimit)
        assertEquals(BigInteger.ZERO, presenter.state.value.contextTokens)
        assertEquals(0, presenter.state.value.contextPercent)

        chat.show(assistant(TokenUsage.of(input = 500), model = opus), model = sonnet)
        runCurrent()
        assertEquals(opus, presenter.state.value.model)
        assertNull(presenter.state.value.contextLimit)
        assertFalse(presenter.state.value.showsContext)
    }

    @Test
    fun `a newest assistant without a model falls back to the session's, never an older assistant's`() = runTest {
        val (chat, presenter) = setup()
        chat.show(
            assistant(TokenUsage.of(input = 500), model = opus),
            assistant(TokenUsage.of(input = 1_000), model = null),
            model = sonnet,
        )
        runCurrent()

        assertEquals(sonnet, presenter.state.value.model)
        assertEquals(10_000L, presenter.state.value.contextLimit)
        assertEquals(10, presenter.state.value.contextPercent)
    }

    @Test
    fun `no limit hides the percentage but keeps the totals`() = runTest {
        val (chat, presenter) = setup()
        chat.show(assistant(TokenUsage.of(input = 500), model = opus))
        runCurrent()

        val state = presenter.state.value
        assertNull(state.contextLimit)
        assertNull(state.contextPercent)
        assertFalse(state.showsContext)
        assertEquals(BigInteger.valueOf(500), state.usage?.input)
        assertNull(state.error)
    }

    @Test
    fun `a model missing from the list or with a zero limit shows no percentage`() = runTest {
        val chat = FakeChatSession(sessionId)
        val unknown = ModelRef("other", "x")
        chat.client.catalog.setModels(models(sonnet to 0L), Location(folder))
        val presenter = DefaultUsagePresenter(session = chat, scope = backgroundScope)
        chat.show(assistant(TokenUsage.of(input = 500)))
        runCurrent()
        assertNull(presenter.state.value.contextLimit)
        assertFalse(presenter.state.value.showsContext)

        chat.show(assistant(TokenUsage.of(input = 500), model = unknown))
        runCurrent()
        assertNull(presenter.state.value.contextLimit)
        assertFalse(presenter.state.value.showsContext)
    }

    @Test
    fun `no model at all asks nothing`() = runTest {
        val (chat, presenter) = setup()
        chat.show(assistant(TokenUsage.of(input = 5), model = null))
        runCurrent()

        assertNull(presenter.state.value.model)
        assertFalse(presenter.state.value.showsContext)
        assertEquals(0, chat.modelCalls)
    }

    @Test
    fun `the percentage is clamped to 100`() = runTest {
        val (chat, presenter) = setup()
        chat.show(assistant(TokenUsage.of(input = 25_000, output = 1_000)))
        runCurrent()

        assertEquals(BigInteger.valueOf(26_000), presenter.state.value.contextTokens)
        assertEquals(100, presenter.state.value.contextPercent)
    }

    @Test
    fun `the model list is asked once across resyncs and updates`() = runTest {
        val (chat, presenter) = setup()
        repeat(3) { i ->
            chat.show(assistant(TokenUsage.of(input = 1_000L * (i + 1))))
            runCurrent()
        }

        assertEquals(30, presenter.state.value.contextPercent)
        assertEquals(1, chat.modelCalls)
    }

    @Test
    fun `a failed model list keeps the totals and offers Retry`() = runTest {
        val (chat, presenter) = setup()
        chat.client.control.failNext(ApiError.Network)
        chat.show(assistant(TokenUsage.of(input = 2_000)))
        runCurrent()

        val failed = presenter.state.value
        assertEquals(ChatFailure.Unreachable, failed.error)
        assertEquals(BigInteger.valueOf(2_000), failed.usage?.input)
        assertNull(failed.contextLimit)
        assertFalse(failed.showsContext)

        // An update alone does not ask again.
        chat.show(assistant(TokenUsage.of(input = 3_000)))
        runCurrent()
        assertEquals(1, chat.modelCalls)

        presenter.onIntent(UsageIntent.Retry)
        runCurrent()

        val retried = presenter.state.value
        assertNull(retried.error)
        assertEquals(10_000L, retried.contextLimit)
        assertEquals(30, retried.contextPercent)
        assertEquals(2, chat.modelCalls)
    }

    @Test
    fun `nothing is asked while the chat is not live, and a refused call offers Retry`() = runTest {
        val (chat, presenter) = setup()
        chat.show(assistant(TokenUsage.of(input = 1_000)), availability = ChatAvailability.Reconnecting)
        runCurrent()
        assertEquals(0, chat.modelCalls)
        assertEquals(BigInteger.valueOf(1_000), presenter.state.value.usage?.input)

        chat.connected = false
        chat.show(assistant(TokenUsage.of(input = 1_000)))
        runCurrent()
        assertEquals(ChatFailure.NotConnected, presenter.state.value.error)

        chat.connected = true
        presenter.onIntent(UsageIntent.Retry)
        runCurrent()
        assertNull(presenter.state.value.error)
        assertEquals(10, presenter.state.value.contextPercent)
    }

    @Test
    fun `coming back live after a failure asks again`() = runTest {
        val (chat, presenter) = setup()
        chat.client.control.failNext(ApiError.Timeout)
        chat.show(assistant(TokenUsage.of(input = 1_000)))
        runCurrent()
        assertEquals(ChatFailure.Unreachable, presenter.state.value.error)

        chat.show(assistant(TokenUsage.of(input = 1_000)), availability = ChatAvailability.Reconnecting)
        runCurrent()
        chat.show(assistant(TokenUsage.of(input = 1_000)))
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(10, presenter.state.value.contextPercent)
        assertEquals(2, chat.modelCalls)
    }

    @Test
    fun `a request that fails after a reconnect it outlived is asked again`() = runTest {
        val (chat, presenter) = setup()
        chat.client.control.stall()
        chat.show(assistant(TokenUsage.of(input = 1_000)))
        runCurrent()
        assertEquals(1, chat.modelCalls)

        chat.show(assistant(TokenUsage.of(input = 1_000)), availability = ChatAvailability.Reconnecting)
        runCurrent()
        chat.show(assistant(TokenUsage.of(input = 1_000)))
        runCurrent()

        chat.client.control.failNext(ApiError.Network)
        chat.client.control.resume()
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(10, presenter.state.value.contextPercent)
        assertEquals(2, chat.modelCalls)
    }

    @Test
    fun `a new folder asks for its own model list`() = runTest {
        val (chat, presenter) = setup()
        chat.client.catalog.setModels(models(sonnet to 2_000L), Location("/srv/other"))
        chat.show(assistant(TokenUsage.of(input = 1_000)))
        runCurrent()
        assertEquals(10, presenter.state.value.contextPercent)

        chat.show(assistant(TokenUsage.of(input = 1_000)), directory = "/srv/other")
        runCurrent()
        assertEquals(2_000L, presenter.state.value.contextLimit)
        assertEquals(50, presenter.state.value.contextPercent)
        assertEquals(2, chat.modelCalls)
    }

    @Test
    fun `a lost session clears the totals`() = runTest {
        val (chat, presenter) = setup()
        chat.show(assistant(TokenUsage.of(input = 1_000)))
        runCurrent()

        chat.mutableState.value = ChatSessionState(availability = ChatAvailability.OtherServer)
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.usage)
        assertFalse(state.showsContext)
    }

    @Test
    fun `open and close become events`() = runTest {
        val (_, presenter) = setup()
        val seen = mutableListOf<UsageEvent>()
        presenter.onIntent(UsageIntent.OpenDetail)
        backgroundScope.launch { presenter.events.collect { seen += it } }
        runCurrent()
        presenter.onIntent(UsageIntent.Close)
        runCurrent()

        assertEquals(listOf(UsageEvent.OpenDetail, UsageEvent.Close), seen)
    }
}
