package io.github.thewisekd.phopencode.feature.sessions.logic.create

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.CreateFolderError
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSessionCreatePresenterTest {
    private val catalogCalls = setOf("catalog.agents", "catalog.models", "catalog.defaultModel")

    /** Connects [PROFILE] with a catalog for `/srv/app` and one for the server's default folder. */
    private suspend fun TestScope.connected(): CreateFixture {
        val fixture = CreateFixture(this)
        fixture.clients.prepare = { client ->
            client.catalog.setAgents(listOf(BUILD, GENERAL, PLAN, TITLER), Location("/srv/app"))
            client.catalog.setModels(listOf(SONNET, GPT), Location("/srv/app"))
            client.catalog.setDefaultModel(SONNET_REF, Location("/srv/app"))
            client.catalog.setAgents(listOf(BUILD), null)
            client.catalog.setModels(listOf(GPT), null)
            client.catalog.setDefaultModel(GPT_REF, null)
        }
        fixture.connect()
        settle()
        return fixture
    }

    /**
     * Runs what is due, then lets a typing pause pass. Not advanceUntilIdle: the presenter and the
     * connection run in the background scope, which it does not wait for.
     */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
    }

    /**
     * Queues what it is given while [held], and hands it to the test scheduler otherwise; a
     * presenter on it misses whatever happens while it is held, as a busy main thread does.
     */
    private class GateDispatcher(private val queue: CoroutineDispatcher) : CoroutineDispatcher() {
        private val waiting = mutableListOf<Pair<CoroutineContext, Runnable>>()

        @Volatile
        var held = false
            set(value) {
                field = value
                if (!value) {
                    val tasks = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
                    tasks.forEach { (context, block) -> queue.dispatch(context, block) }
                }
            }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) synchronized(waiting) { waiting += context to block } else queue.dispatch(context, block)
        }
    }

    private fun CreateFixture.createCalls(): Int = client.control.calls.count { it == "sessions.create" }

    @Test
    fun `nothing runs in the constructor, even on an immediate dispatcher`() = runTest {
        val fixture = connected()
        // Like Dispatchers.Main.immediate on the main thread: a launch runs at once, a yield dispatches.
        val queue = StandardTestDispatcher(testScheduler)
        val dispatcher = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

            override fun dispatch(context: CoroutineContext, block: Runnable) = queue.dispatch(context, block)
        }
        val parent = backgroundScope.coroutineContext
        val immediate = CoroutineScope(parent + Job(parent[Job]) + dispatcher)

        val presenter = fixture.presenter(directory = "/srv/app", scope = immediate)

        assertEquals(SessionCreateState(directory = "/srv/app"), presenter.state.value)
        assertTrue(fixture.client.control.calls.none { it in catalogCalls })
        settle()
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `the catalog of the route folder loads, with only the agents a session can start with`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter(directory = "/srv/app", skillId = "skill-1")

        settle()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertEquals(listOf(BUILD, PLAN), state.agents)
        assertEquals(listOf(SONNET, GPT), state.models)
        assertEquals(SONNET_REF, state.defaultModel)
        assertEquals("skill-1", state.skillId)
        assertNull(state.catalogError)
        assertTrue(state.canCreate)
    }

    @Test
    fun `without a folder the server's default catalog loads`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter()

        settle()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertEquals(listOf(BUILD), state.agents)
        assertEquals(GPT_REF, state.defaultModel)
        assertFalse(state.canCreate)
    }

    @Test
    fun `the recent folders are the connected profile's, newest first`() = runTest {
        val fixture = connected()
        fixture.recent.add(PROFILE_ID, "/srv/old")
        fixture.recent.add(PROFILE_ID, "/srv/new")
        fixture.recent.add(OTHER_PROFILE_ID, "/srv/elsewhere")
        val presenter = fixture.presenter()

        settle()
        assertEquals(listOf("/srv/new", "/srv/old"), presenter.state.value.recentFolders)

        fixture.recent.add(PROFILE_ID, "/srv/newest")
        settle()
        assertEquals(listOf("/srv/newest", "/srv/new", "/srv/old"), presenter.state.value.recentFolders)
    }

    @Test
    fun `a failing recent folder store leaves the list empty, creation working, and is read again`() = runTest {
        val fixture = connected()
        fixture.recent.add(PROFILE_ID, "/srv/app")
        fixture.recent.failObserve = true
        val presenter = fixture.presenter(directory = "/srv/app")

        // Not advanceUntilIdle: a store that keeps failing is read again forever.
        runCurrent()
        assertEquals(emptyList(), presenter.state.value.recentFolders)
        assertTrue(presenter.state.value.canCreate)

        fixture.recent.failObserve = false
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(listOf("/srv/app"), presenter.state.value.recentFolders)
    }

    @Test
    fun `changing the folder loads that folder's catalog and keeps choices it still offers`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter()
        settle()
        presenter.onIntent(SessionCreateIntent.AgentSelected("build"))
        presenter.onIntent(SessionCreateIntent.ModelSelected(GPT_REF))

        presenter.onIntent(SessionCreateIntent.FolderChanged("/srv/app"))
        assertEquals("/srv/app", presenter.state.value.directory)
        assertTrue(presenter.state.value.loading)
        settle()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertEquals(listOf(BUILD, PLAN), state.agents)
        assertEquals("build", state.selectedAgent)
        assertEquals(GPT_REF, state.selectedModel)
        assertEquals(SONNET_REF, state.defaultModel)
    }

    @Test
    fun `a choice the new folder does not offer is dropped`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter(directory = "/srv/app")
        settle()
        presenter.onIntent(SessionCreateIntent.AgentSelected("plan"))
        presenter.onIntent(SessionCreateIntent.ModelSelected(SONNET_REF))

        presenter.onIntent(SessionCreateIntent.FolderChanged("/srv/other"))
        settle()

        assertNull(presenter.state.value.selectedAgent)
        assertNull(presenter.state.value.selectedModel)
    }

    @Test
    fun `typing a folder loads its catalog once, after the typing stops`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter()
        settle()
        val before = fixture.client.control.calls.count { it == "catalog.agents" }

        for (prefix in listOf("/", "/s", "/sr", "/srv", "/srv/", "/srv/a", "/srv/ap", "/srv/app")) {
            presenter.onIntent(SessionCreateIntent.FolderChanged(prefix))
        }
        settle()

        assertEquals(before + 1, fixture.client.control.calls.count { it == "catalog.agents" })
        assertEquals(listOf(BUILD, PLAN), presenter.state.value.agents)
    }

    @Test
    fun `an agent or model the catalog does not offer cannot be selected, and null goes back`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter(directory = "/srv/app")
        settle()

        presenter.onIntent(SessionCreateIntent.AgentSelected("general"))
        presenter.onIntent(SessionCreateIntent.AgentSelected("title"))
        presenter.onIntent(SessionCreateIntent.ModelSelected(ModelRef("nobody", "nothing")))
        presenter.onIntent(SessionCreateIntent.ModelSelected(GPT_REF.copy(variant = "unknown")))
        assertNull(presenter.state.value.selectedAgent)
        assertNull(presenter.state.value.selectedModel)

        presenter.onIntent(SessionCreateIntent.AgentSelected("plan"))
        presenter.onIntent(SessionCreateIntent.ModelSelected(GPT_REF.copy(variant = "high")))
        assertEquals("plan", presenter.state.value.selectedAgent)
        assertEquals(GPT_REF.copy(variant = "high"), presenter.state.value.selectedModel)

        presenter.onIntent(SessionCreateIntent.AgentSelected(null))
        presenter.onIntent(SessionCreateIntent.ModelSelected(null))
        assertNull(presenter.state.value.selectedAgent)
        assertNull(presenter.state.value.selectedModel)
    }

    @Test
    fun `creating sends the folder and the choices, stores the folder and opens the chat`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter(directory = "/srv/app")
        val events = fixture.events(presenter)
        settle()
        presenter.onIntent(SessionCreateIntent.AgentSelected("plan"))
        presenter.onIntent(SessionCreateIntent.ModelSelected(GPT_REF))
        presenter.onIntent(SessionCreateIntent.TitleChanged("  Fix the build  "))

        presenter.onIntent(SessionCreateIntent.Create)
        assertTrue(presenter.state.value.creating)
        settle()

        val event = events.single() as SessionCreateEvent.OpenChat
        val session = fixture.client.sessions.get(event.id)
        assertEquals("/srv/app", session.directory)
        assertEquals("Fix the build", session.title)
        assertEquals("plan", session.agent)
        assertEquals(GPT_REF, session.model)
        assertEquals(listOf("/srv/app"), fixture.recent.list(PROFILE_ID))
        assertEquals(emptyList(), fixture.recent.list(OTHER_PROFILE_ID))
    }

    @Test
    fun `choices left alone and a blank title are left to the server`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter(directory = "/srv/app")
        val events = fixture.events(presenter)
        settle()
        presenter.onIntent(SessionCreateIntent.TitleChanged("   "))

        presenter.onIntent(SessionCreateIntent.Create)
        settle()

        val session = fixture.client.sessions.get((events.single() as SessionCreateEvent.OpenChat).id)
        assertNull(session.title)
        assertNull(session.agent)
        assertNull(session.model)
    }

    @Test
    fun `a repeated create sends one creation and opens one chat`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter(directory = "/srv/app")
        val events = fixture.events(presenter)
        settle()

        presenter.onIntent(SessionCreateIntent.Create)
        presenter.onIntent(SessionCreateIntent.Create)
        settle()
        presenter.onIntent(SessionCreateIntent.Create)
        settle()

        assertEquals(1, fixture.createCalls())
        assertEquals(1, events.size)
    }

    @Test
    fun `a missing or relative folder is refused without asking the server`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter()
        val events = fixture.events(presenter)
        settle()

        presenter.onIntent(SessionCreateIntent.Create)
        assertEquals(CreateFolderError.Missing, presenter.state.value.folderError)

        presenter.onIntent(SessionCreateIntent.FolderChanged("srv/app"))
        assertEquals(CreateFolderError.NotAbsolute, presenter.state.value.folderError)
        settle()
        presenter.onIntent(SessionCreateIntent.Create)
        settle()
        assertEquals(CreateFolderError.NotAbsolute, presenter.state.value.folderError)

        presenter.onIntent(SessionCreateIntent.FolderChanged("/srv/app"))
        assertNull(presenter.state.value.folderError)

        assertEquals(0, fixture.createCalls())
        assertEquals(emptyList(), events)
    }

    @Test
    fun `a failed create shows the error, opens nothing, and can be tried again`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter(directory = "/srv/app")
        val events = fixture.events(presenter)
        settle()
        fixture.client.control.failNext(ApiError.Server(500, "internal detail"))

        presenter.onIntent(SessionCreateIntent.Create)
        settle()

        assertEquals(SessionsFailure.ServerFailed, presenter.state.value.error)
        assertFalse(presenter.state.value.creating)
        assertEquals(emptyList(), events)
        assertEquals(emptyList(), fixture.recent.list(PROFILE_ID))

        presenter.onIntent(SessionCreateIntent.DismissError)
        assertNull(presenter.state.value.error)

        presenter.onIntent(SessionCreateIntent.Create)
        settle()
        assertEquals(2, fixture.createCalls())
        assertTrue(events.single() is SessionCreateEvent.OpenChat)
    }

    @Test
    fun `a folder that cannot be stored still opens the created session`() = runTest {
        val fixture = connected()
        fixture.recent.failAdds = true
        val presenter = fixture.presenter(directory = "/srv/app")
        val events = fixture.events(presenter)
        settle()

        presenter.onIntent(SessionCreateIntent.Create)
        settle()

        assertTrue(events.single() is SessionCreateEvent.OpenChat)
    }

    @Test
    fun `a connection that closes during the create shows not connected and opens nothing`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter(directory = "/srv/app")
        val events = fixture.events(presenter)
        settle()
        val client = fixture.client
        client.control.stall()

        presenter.onIntent(SessionCreateIntent.Create)
        runCurrent()
        client.control.awaitRunningCalls(1)
        assertTrue(presenter.state.value.creating)
        fixture.manager.disconnect()
        settle()

        assertEquals(SessionsFailure.NotConnected, presenter.state.value.error)
        assertFalse(presenter.state.value.creating)
        assertEquals(emptyList(), events)
        assertEquals(emptyList(), fixture.recent.list(PROFILE_ID))
    }

    @Test
    fun `without a connection the catalog and creating show not connected`() = runTest {
        val fixture = CreateFixture(this)
        val presenter = fixture.presenter(directory = "/srv/app")
        val events = fixture.events(presenter)

        settle()
        assertFalse(presenter.state.value.loading)
        assertEquals(SessionsFailure.NotConnected, presenter.state.value.catalogError)

        presenter.onIntent(SessionCreateIntent.Create)
        settle()
        assertEquals(SessionsFailure.NotConnected, presenter.state.value.error)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `the catalog loads once the connection stands`() = runTest {
        val fixture = CreateFixture(this)
        fixture.clients.prepare = { it.catalog.setAgents(listOf(BUILD), Location("/srv/app")) }
        val presenter = fixture.presenter(directory = "/srv/app")
        settle()
        assertEquals(SessionsFailure.NotConnected, presenter.state.value.catalogError)

        fixture.connect()
        settle()

        assertNull(presenter.state.value.catalogError)
        assertEquals(listOf(BUILD), presenter.state.value.agents)
        assertEquals(emptyList(), fixture.recent.list(PROFILE_ID))
    }

    @Test
    fun `a failed catalog shows its error, still allows creating, and loads again on retry`() = runTest {
        val fixture = connected()
        fixture.client.control.failNext(ApiError.Network)
        val presenter = fixture.presenter(directory = "/srv/app")
        val events = fixture.events(presenter)

        settle()
        val failed = presenter.state.value
        assertFalse(failed.loading)
        assertEquals(SessionsFailure.Unreachable, failed.catalogError)
        assertEquals(emptyList(), failed.agents)
        assertTrue(failed.canCreate)

        presenter.onIntent(SessionCreateIntent.Retry)
        settle()
        assertNull(presenter.state.value.catalogError)
        assertEquals(listOf(BUILD, PLAN), presenter.state.value.agents)

        presenter.onIntent(SessionCreateIntent.Create)
        settle()
        assertTrue(events.single() is SessionCreateEvent.OpenChat)
    }

    @Test
    fun `choices made on one server are not sent to another`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter(directory = "/srv/app")
        val events = fixture.events(presenter)
        settle()
        presenter.onIntent(SessionCreateIntent.AgentSelected("plan"))
        presenter.onIntent(SessionCreateIntent.ModelSelected(GPT_REF))

        fixture.clients.prepare = { it.catalog.setAgents(listOf(BUILD), Location("/srv/app")) }
        fixture.connect(OTHER_PROFILE)
        settle()
        assertNull(presenter.state.value.selectedAgent)
        assertNull(presenter.state.value.selectedModel)

        presenter.onIntent(SessionCreateIntent.Create)
        settle()

        val session = fixture.client.sessions.get((events.single() as SessionCreateEvent.OpenChat).id)
        assertNull(session.agent)
        assertNull(session.model)
        assertEquals(listOf("/srv/app"), fixture.recent.list(OTHER_PROFILE_ID))
        assertEquals(emptyList(), fixture.recent.list(PROFILE_ID))
    }

    @Test
    fun `cancel closes the screen`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter()
        val events = fixture.events(presenter)

        presenter.onIntent(SessionCreateIntent.Cancel)

        assertEquals(listOf<SessionCreateEvent>(SessionCreateEvent.Close), events)
    }

    @Test
    fun `a screen that goes away during the create opens nothing`() = runTest {
        val fixture = connected()
        val scope = fixture.screenScope()
        val presenter = fixture.presenter(directory = "/srv/app", scope = scope)
        val events = fixture.events(presenter)
        settle()
        val client = fixture.client
        client.control.stall()

        presenter.onIntent(SessionCreateIntent.Create)
        // Without advancing time: the connection's own checks would stall too.
        runCurrent()
        client.control.awaitRunningCalls(1)
        scope.cancel()
        client.control.resume()
        runCurrent()

        assertEquals(emptyList(), events)
        assertEquals(emptyList(), fixture.recent.list(PROFILE_ID))
    }

    @Test
    fun `the title is kept as typed and never printed`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter()
        val title = "private " + "title"

        presenter.onIntent(SessionCreateIntent.TitleChanged(title))

        assertEquals(title, presenter.state.value.title)
        assertFalse(presenter.state.value.toString().contains(title))
    }
    @Test
    fun `going back to the folder last loaded before the screen catches up still ends loading`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter()
        settle()
        presenter.onIntent(SessionCreateIntent.FolderChanged("/srv/app"))
        settle()
        assertFalse(presenter.state.value.loading)

        presenter.onIntent(SessionCreateIntent.FolderChanged("/srv/other"))
        presenter.onIntent(SessionCreateIntent.FolderChanged("/srv/app"))
        settle()

        assertFalse(presenter.state.value.loading)
        assertEquals(listOf(BUILD, PLAN), presenter.state.value.agents)
        assertTrue(presenter.state.value.canCreate)
    }

    @Test
    fun `choices are dropped on another connection even when the screen never saw a disconnect`() = runTest {
        val fixture = connected()
        val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
        val parent = backgroundScope.coroutineContext
        val presenter = fixture.presenter(
            directory = "/srv/app",
            scope = CoroutineScope(parent + Job(parent[Job]) + gate),
        )
        val events = fixture.events(presenter)
        settle()
        presenter.onIntent(SessionCreateIntent.AgentSelected("plan"))
        presenter.onIntent(SessionCreateIntent.ModelSelected(GPT_REF))

        // The other server offers the very same agent and model.
        gate.held = true
        fixture.connect(OTHER_PROFILE)
        settle()
        assertEquals(OTHER_PROFILE_ID, (fixture.manager.state.value as ConnectionState.Connected).server.profileId)
        gate.held = false
        settle()

        assertEquals(listOf(BUILD, PLAN), presenter.state.value.agents)
        assertNull(presenter.state.value.selectedAgent)
        assertNull(presenter.state.value.selectedModel)
        presenter.onIntent(SessionCreateIntent.Create)
        settle()
        val session = fixture.client.sessions.get((events.single() as SessionCreateEvent.OpenChat).id)
        assertNull(session.agent)
        assertNull(session.model)
    }

    @Test
    fun `a session created before a disconnect is never created again and opens once its server is back`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter(directory = "/srv/app")
        val events = fixture.events(presenter)
        settle()
        val first = fixture.client
        val gate = CompletableDeferred<Unit>()
        fixture.recent.addGate = gate

        presenter.onIntent(SessionCreateIntent.Create)
        runCurrent()
        assertEquals(1, first.control.calls.count { it == "sessions.create" })
        val created = first.sessions.list().items.single()
        fixture.manager.disconnect()
        gate.complete(Unit)
        settle()

        assertEquals(emptyList(), events)
        assertEquals(SessionsFailure.NotConnected, presenter.state.value.error)
        assertFalse(presenter.state.value.canCreate)

        fixture.recent.addGate = null
        fixture.connect(OTHER_PROFILE)
        settle()
        presenter.onIntent(SessionCreateIntent.DismissError)
        presenter.onIntent(SessionCreateIntent.Create)
        settle()
        assertEquals(emptyList(), events)
        assertEquals(0, fixture.createCalls())

        fixture.connect()
        settle()
        presenter.onIntent(SessionCreateIntent.Create)
        settle()

        assertEquals(listOf<SessionCreateEvent>(SessionCreateEvent.OpenChat(created.id)), events)
        assertEquals(0, fixture.createCalls())
        assertNull(presenter.state.value.error)
    }
    @Test
    fun `a catalog for an older request does not end loading or let create send the new folder`() = runTest {
        val fixture = connected()
        val presenter = fixture.presenter(directory = "/srv/app")
        val events = fixture.events(presenter)
        settle()
        presenter.onIntent(SessionCreateIntent.AgentSelected("plan"))
        val client = fixture.client
        client.control.stall()
        presenter.onIntent(SessionCreateIntent.Retry)
        // Without advancing time: the connection's own checks would stall too.
        runCurrent()
        client.control.awaitRunningCalls(1)

        // The older load resumes first, then a create, then the screen sees the folder change.
        client.control.resume()
        launch { presenter.onIntent(SessionCreateIntent.Create) }
        presenter.onIntent(SessionCreateIntent.FolderChanged("/srv/other"))
        runCurrent()

        assertTrue(presenter.state.value.loading)
        assertEquals(0, fixture.createCalls())
        settle()
        assertFalse(presenter.state.value.loading)
        assertEquals(listOf(BUILD), presenter.state.value.agents)
        assertNull(presenter.state.value.selectedAgent)
        assertEquals(0, fixture.createCalls())
        assertEquals(emptyList(), events)
    }
}
