package io.github.thewisekd.phopencode.feature.chat.logic.switcher

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.MessageApi
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.AgentMode
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
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
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.AgentOption
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ModelOption
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val DIR = "/work/project"

private val MAIN = SessionId("ses_main")

private val SONNET = ModelRef("anthropic", "sonnet")
private val OPUS = ModelRef("anthropic", "opus")

/**
 * A [ChatSession] the test drives: its state is set by hand, and [call] runs on [client] only while
 * [connected], as `ActiveChatSession` runs it only on the bound server. Switches can be held at
 * [gate] to keep them in flight.
 */
private class FakeChatSession(override val sessionId: SessionId) : ChatSession {
    val client = FakeOpencodeClient()

    val mutableState = MutableStateFlow(ChatSessionState())

    override val state: StateFlow<ChatSessionState> = mutableState

    @Volatile
    var connected = true

    /** Every `agents` and `models` call, with its folder. */
    val catalogCalls = mutableListOf<Pair<String, Location?>>()

    /** Every switch sent: `agent:<id>` or `model:<ref>`. */
    val switches = mutableListOf<String>()

    /** While set, switches wait for it before reaching the server. */
    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    private val recording = object : OpencodeClient by client {
        override val catalog: CatalogApi = object : CatalogApi by client.catalog {
            override suspend fun agents(location: Location?): List<Agent> {
                catalogCalls += "agents" to location
                return client.catalog.agents(location)
            }

            override suspend fun models(location: Location?): List<Model> {
                catalogCalls += "models" to location
                return client.catalog.models(location)
            }
        }

        override val messages: MessageApi = object : MessageApi by client.messages {
            override suspend fun switchAgent(sessionId: SessionId, agent: String) {
                switches += "agent:$agent"
                gate?.await()
                client.messages.switchAgent(sessionId, agent)
            }

            override suspend fun switchModel(sessionId: SessionId, model: ModelRef) {
                switches += "model:$model"
                gate?.await()
                client.messages.switchModel(sessionId, model)
            }
        }
    }

    override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
        if (!connected) throw ApiException(ApiError.Closed)
        return block(recording)
    }

    fun live(agent: String? = "build", model: ModelRef? = SONNET) {
        mutableState.value = ChatSessionState(
            availability = ChatAvailability.Live,
            session = SessionState(Session(id = sessionId, directory = DIR, agent = agent, model = model)),
            phase = SyncPhase.Live,
        )
    }
}

private fun agent(id: String, mode: AgentMode = AgentMode.Primary, hidden: Boolean = false) =
    Agent(id = id, name = id.replaceFirstChar { it.uppercase() }, description = "The $id agent", mode = mode, hidden = hidden)

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSwitcherPresenterTest {
    private val agents = listOf(
        agent("build"),
        agent("compaction", hidden = true),
        agent("plan", mode = AgentMode.All),
        agent("explore", mode = AgentMode.Subagent),
        agent("title", mode = AgentMode.Primary, hidden = true),
    )

    private val models = listOf(
        Model(id = "sonnet", providerId = "anthropic", name = "Sonnet", variants = listOf("high", "max")),
        Model(id = "opus", providerId = "anthropic", name = "Opus"),
    )

    private fun TestScope.setup(live: Boolean = true): Pair<FakeChatSession, DefaultSwitcherPresenter> {
        val chat = FakeChatSession(MAIN)
        chat.client.sessions.seed(Session(id = MAIN, directory = DIR, agent = "build", model = SONNET))
        chat.client.catalog.setAgents(agents, Location(DIR))
        chat.client.catalog.setModels(models, Location(DIR))
        if (live) chat.live()
        val presenter = DefaultSwitcherPresenter(route = ChatRoute.Chat(MAIN.value), scope = backgroundScope, session = chat)
        runCurrent()
        return chat to presenter
    }

    private fun TestScope.opened(): Pair<FakeChatSession, DefaultSwitcherPresenter> {
        val (chat, presenter) = setup()
        presenter.onIntent(SwitcherIntent.Open)
        runCurrent()
        return chat to presenter
    }

    // Following the session

    @Test
    fun `nothing is asked in the constructor`() = runTest {
        val chat = FakeChatSession(MAIN)
        chat.live()
        val presenter = DefaultSwitcherPresenter(route = ChatRoute.Chat(MAIN.value), scope = backgroundScope, session = chat)

        assertEquals(SwitcherState(), presenter.state.value)

        runCurrent()

        assertTrue(presenter.state.value.enabled)
        assertTrue(chat.catalogCalls.isEmpty())
    }

    @Test
    fun `the current agent and model follow the session`() = runTest {
        val (chat, presenter) = setup()

        assertEquals("build", presenter.state.value.agent)
        assertEquals(SONNET, presenter.state.value.model)
        assertTrue(presenter.state.value.enabled)

        chat.live(agent = "plan", model = OPUS.copy(variant = "max"))
        runCurrent()

        assertEquals("plan", presenter.state.value.agent)
        assertEquals(OPUS.copy(variant = "max"), presenter.state.value.model)
    }

    @Test
    fun `the switcher is enabled only while the session is loaded and live`() = runTest {
        val (chat, presenter) = setup(live = false)
        assertFalse(presenter.state.value.enabled)

        chat.live()
        runCurrent()
        assertTrue(presenter.state.value.enabled)

        chat.mutableState.value = chat.mutableState.value.copy(availability = ChatAvailability.Reconnecting)
        runCurrent()
        assertFalse(presenter.state.value.enabled)
        assertEquals("build", presenter.state.value.agent)

        chat.mutableState.value = ChatSessionState(availability = ChatAvailability.OtherServer)
        runCurrent()
        assertFalse(presenter.state.value.enabled)
        assertNull(presenter.state.value.agent)
        assertNull(presenter.state.value.model)
    }

    // The lists

    @Test
    fun `opening asks for the lists of the session's folder, without hidden agents or subagents`() = runTest {
        val (chat, presenter) = opened()
        val state = presenter.state.value

        assertTrue(state.open)
        assertFalse(state.loading)
        assertEquals(setOf("agents" to Location(DIR), "models" to Location(DIR)), chat.catalogCalls.toSet())
        assertEquals(
            listOf(AgentOption("build", "Build", "The build agent"), AgentOption("plan", "Plan", "The plan agent")),
            state.agents,
        )
        assertEquals(
            listOf(
                ModelOption("anthropic", "sonnet", "Sonnet", listOf("high", "max")),
                ModelOption("anthropic", "opus", "Opus"),
            ),
            state.models,
        )
        assertNull(state.error)
    }

    @Test
    fun `the lists are asked for once, not on every opening`() = runTest {
        val (chat, presenter) = opened()
        presenter.onIntent(SwitcherIntent.Close)
        runCurrent()
        assertFalse(presenter.state.value.open)

        presenter.onIntent(SwitcherIntent.Open)
        runCurrent()

        assertEquals(2, chat.catalogCalls.size)
        assertEquals(2, presenter.state.value.agents.size)
    }

    @Test
    fun `the lists show loading while they are asked for`() = runTest {
        val (chat, presenter) = setup()
        chat.client.control.stall()

        presenter.onIntent(SwitcherIntent.Open)
        runCurrent()
        assertTrue(presenter.state.value.loading)

        chat.client.control.resume()
        runCurrent()
        assertFalse(presenter.state.value.loading)
        assertEquals(2, presenter.state.value.models.size)
    }

    @Test
    fun `a failed load shows an error, and retry asks again`() = runTest {
        val (chat, presenter) = setup()
        chat.client.control.failNext(ApiError.Network)

        presenter.onIntent(SwitcherIntent.Open)
        runCurrent()

        assertEquals(SwitcherError(SwitcherAction.Load, ChatFailure.Unreachable), presenter.state.value.error)
        assertFalse(presenter.state.value.loading)
        assertTrue(presenter.state.value.agents.isEmpty())

        presenter.onIntent(SwitcherIntent.Retry)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(2, presenter.state.value.agents.size)
        assertEquals(2, presenter.state.value.models.size)
    }

    @Test
    fun `a load that fails after the sheet closed is asked again on the next opening`() = runTest {
        val (chat, presenter) = setup()
        chat.client.control.stall()
        presenter.onIntent(SwitcherIntent.Open)
        runCurrent()
        assertTrue(presenter.state.value.loading)

        presenter.onIntent(SwitcherIntent.Close)
        chat.client.control.failNext(ApiError.Network)
        chat.client.control.resume()
        runCurrent()
        assertFalse(presenter.state.value.loading)
        assertEquals(1, chat.catalogCalls.size)

        presenter.onIntent(SwitcherIntent.Open)
        runCurrent()

        assertEquals(3, chat.catalogCalls.size)
        assertNull(presenter.state.value.error)
        assertEquals(2, presenter.state.value.agents.size)
        assertEquals(2, presenter.state.value.models.size)
    }

    @Test
    fun `the lists wait for the session to be live`() = runTest {
        val (chat, presenter) = setup(live = false)

        presenter.onIntent(SwitcherIntent.Open)
        runCurrent()
        assertTrue(presenter.state.value.open)
        assertTrue(chat.catalogCalls.isEmpty())
        assertNull(presenter.state.value.error)

        chat.live()
        runCurrent()

        assertEquals(2, presenter.state.value.agents.size)
    }

    // Switching

    @Test
    fun `picking an agent switches it and closes the sheet, and the current agent follows the session`() = runTest {
        val (chat, presenter) = opened()

        presenter.onIntent(SwitcherIntent.PickAgent("plan"))
        runCurrent()

        assertEquals(listOf("agent:plan"), chat.switches)
        assertFalse(presenter.state.value.open)
        assertFalse(presenter.state.value.switching)
        assertNull(presenter.state.value.error)
        // Not before the session says so.
        assertEquals("build", presenter.state.value.agent)

        chat.live(agent = "plan")
        runCurrent()
        assertEquals("plan", presenter.state.value.agent)
    }

    @Test
    fun `picking a model sends its variant`() = runTest {
        val (chat, presenter) = opened()

        presenter.onIntent(SwitcherIntent.PickModel(SONNET.copy(variant = "max")))
        runCurrent()

        assertEquals(listOf("model:${SONNET.copy(variant = "max")}"), chat.switches)
        assertEquals(SONNET.copy(variant = "max"), chat.client.sessions.get(MAIN).model)
        assertFalse(presenter.state.value.open)
        assertEquals(SONNET, presenter.state.value.model)
    }

    @Test
    fun `picking another model switches it`() = runTest {
        val (chat, presenter) = opened()

        presenter.onIntent(SwitcherIntent.PickModel(OPUS))
        runCurrent()

        assertEquals(listOf("model:$OPUS"), chat.switches)
    }

    @Test
    fun `picking the current agent or model does nothing`() = runTest {
        val (chat, presenter) = opened()

        presenter.onIntent(SwitcherIntent.PickAgent("build"))
        presenter.onIntent(SwitcherIntent.PickModel(SONNET))
        runCurrent()

        assertTrue(chat.switches.isEmpty())
        assertTrue(presenter.state.value.open)
    }

    @Test
    fun `a hidden agent, a subagent or an unknown model or variant is never sent`() = runTest {
        val (chat, presenter) = opened()

        presenter.onIntent(SwitcherIntent.PickAgent("compaction"))
        presenter.onIntent(SwitcherIntent.PickAgent("explore"))
        presenter.onIntent(SwitcherIntent.PickAgent("nobody"))
        presenter.onIntent(SwitcherIntent.PickModel(ModelRef("openai", "gpt")))
        presenter.onIntent(SwitcherIntent.PickModel(OPUS.copy(variant = "max")))
        runCurrent()

        assertTrue(chat.switches.isEmpty())
        assertFalse(presenter.state.value.switching)
    }

    @Test
    fun `nothing is switched before the lists are known`() = runTest {
        val (chat, presenter) = setup()

        presenter.onIntent(SwitcherIntent.PickAgent("plan"))
        runCurrent()

        assertTrue(chat.switches.isEmpty())
    }

    @Test
    fun `a second pick while one is in flight is ignored`() = runTest {
        val (chat, presenter) = opened()
        val gate = CompletableDeferred<Unit>()
        chat.gate = gate

        presenter.onIntent(SwitcherIntent.PickAgent("plan"))
        runCurrent()
        assertTrue(presenter.state.value.switching)
        assertFalse(presenter.state.value.canSwitch)

        presenter.onIntent(SwitcherIntent.PickModel(OPUS))
        presenter.onIntent(SwitcherIntent.PickAgent("plan"))
        runCurrent()
        assertEquals(listOf("agent:plan"), chat.switches)

        gate.complete(Unit)
        runCurrent()
        assertFalse(presenter.state.value.switching)
        assertEquals(listOf("agent:plan"), chat.switches)
    }

    @Test
    fun `a failed switch keeps the old selection and the sheet, with an error`() = runTest {
        val (chat, presenter) = opened()
        chat.client.control.failNext(ApiError.Server(500, null))

        presenter.onIntent(SwitcherIntent.PickModel(OPUS))
        runCurrent()

        val state = presenter.state.value
        assertTrue(state.open)
        assertFalse(state.switching)
        assertEquals(SONNET, state.model)
        assertEquals(SwitcherError(SwitcherAction.SwitchModel, ChatFailure.ServerFailed), state.error)
        assertEquals(SONNET, chat.client.sessions.get(MAIN).model)

        presenter.onIntent(SwitcherIntent.DismissError)
        runCurrent()
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a failed agent switch names its action`() = runTest {
        val (chat, presenter) = opened()
        chat.client.control.failNext(ApiError.Conflict(null))

        presenter.onIntent(SwitcherIntent.PickAgent("plan"))
        runCurrent()

        assertEquals(SwitcherError(SwitcherAction.SwitchAgent, ChatFailure.Conflict), presenter.state.value.error)
        assertEquals("build", presenter.state.value.agent)
    }

    @Test
    fun `nothing is switched while the switcher is disabled`() = runTest {
        val (chat, presenter) = opened()
        chat.mutableState.value = chat.mutableState.value.copy(availability = ChatAvailability.Reconnecting)
        runCurrent()

        presenter.onIntent(SwitcherIntent.PickAgent("plan"))
        runCurrent()

        assertTrue(chat.switches.isEmpty())
    }

    @Test
    fun `a switch refused by the session's call keeps the selection, with not connected`() = runTest {
        val (chat, presenter) = opened()
        chat.connected = false

        presenter.onIntent(SwitcherIntent.PickAgent("plan"))
        runCurrent()

        assertEquals(SwitcherError(SwitcherAction.SwitchAgent, ChatFailure.NotConnected), presenter.state.value.error)
        assertTrue(chat.switches.isEmpty())
        assertEquals("build", presenter.state.value.agent)
    }
}
