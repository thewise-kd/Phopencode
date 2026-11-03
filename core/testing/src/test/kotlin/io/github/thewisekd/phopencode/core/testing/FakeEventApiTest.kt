package io.github.thewisekd.phopencode.core.testing

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.InterruptReason
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionRevert
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.testing.api.FakeEventApi
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.core.testing.api.wireType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class FakeEventApiTest {
    private val session = SessionId("session")
    private val message = MessageId("message")

    /** One of every event, so that a new type is added here and to the filter's table. */
    private val everyEvent: List<ServerEvent> = run {
        val form = Form(FormId("f"), session, FormKind.Question)
        val request = PermissionRequest(PermissionRequestId("p"), session, "bash")
        val model = ModelRef("provider", "model")
        val item = InboxItem(message, session, content = InboxContent.Synthetic("x"), delivery = PromptDelivery.Steer)
        listOf(
            ServerEvent.Unknown("something.new"),
            ServerEvent.SessionCreated(session),
            ServerEvent.SessionDeleted(session),
            ServerEvent.SessionRenamed(session),
            ServerEvent.SessionArchived(session),
            ServerEvent.SessionUnarchived(session),
            ServerEvent.SessionForked(session),
            ServerEvent.SessionStatusChanged(session, SessionStatus.Idle),
            ServerEvent.ExecutionStarted(session),
            ServerEvent.ExecutionSucceeded(session),
            ServerEvent.ExecutionFailed(session),
            ServerEvent.ExecutionInterrupted(session, InterruptReason.User),
            ServerEvent.RetryScheduled(session),
            ServerEvent.StepStarted(session),
            ServerEvent.StepStreamed(session),
            ServerEvent.StepEnded(session),
            ServerEvent.StepFailed(session),
            ServerEvent.TextStarted(session, message, 0),
            ServerEvent.TextDelta(session, message, 0, "x"),
            ServerEvent.TextEnded(session, message, 0, "x"),
            ServerEvent.ReasoningStarted(session, message, 0),
            ServerEvent.ReasoningDelta(session, message, 0, "x"),
            ServerEvent.ReasoningEnded(session, message, 0, "x"),
            ServerEvent.ToolInputStarted(session, id = "t"),
            ServerEvent.ToolInputDelta(session, id = "t", delta = "x"),
            ServerEvent.ToolInputEnded(session, id = "t"),
            ServerEvent.ToolCalled(session, id = "t"),
            ServerEvent.ToolProgress(session, id = "t"),
            ServerEvent.ToolSucceeded(session, id = "t"),
            ServerEvent.ToolFailed(session, id = "t"),
            ServerEvent.UsageUpdated(session, TokenUsage()),
            ServerEvent.FormCreated(form),
            ServerEvent.FormReplied(session, form.id, mapOf("q0" to DataValue.Bool(true))),
            ServerEvent.FormCancelled(session, form.id),
            ServerEvent.PermissionAsked(request),
            ServerEvent.PermissionReplied(session, request.id, PermissionDecision.Once),
            ServerEvent.CompactionStarted(session),
            ServerEvent.CompactionDelta(session, "x"),
            ServerEvent.CompactionEnded(session),
            ServerEvent.CompactionFailed(session),
            ServerEvent.RevertStaged(session, SessionRevert(message)),
            ServerEvent.RevertCleared(session),
            ServerEvent.RevertCommitted(session),
            ServerEvent.ShellStarted(session),
            ServerEvent.ShellEnded(session),
            ServerEvent.InboxEnqueued(item),
            ServerEvent.InboxDelivered(session, message),
            ServerEvent.InboxCancelled(session, message),
            ServerEvent.InboxDeliveryChanged(session, message),
            ServerEvent.AgentSelected(session, "build"),
            ServerEvent.ModelSelected(session, model),
        )
    }

    @Test
    fun `a test can emit any event`() = runTest {
        val api = FakeEventApi()
        api.events().test {
            assertIs<ServerEvent.Connected>(awaitItem())
            everyEvent.forEach { event ->
                api.emit(event)
                assertEquals(event, awaitItem())
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the filter's table gives each event its own server type`() {
        val types = everyEvent.map { it.wireType() }
        assertEquals(types.size, types.toSet().size, "Two events share a type: $types")
        assertEquals("session.tool.success", ServerEvent.ToolSucceeded(session, id = "t").wireType())
        assertEquals("form.created", everyEvent.first { it is ServerEvent.FormCreated }.wireType())
        assertEquals("something.new", ServerEvent.Unknown("something.new").wireType())
    }

    @Test
    fun `the filter's types are all in the recordings`() {
        val manifest = checkNotNull(javaClass.getResource("/fixtures/manifest.json")).readText()
        val unrecorded = setOf(
            // Listed in docs/api/README.md as in the spec, but never sent by opencode 2.2.5.
            "session.status",
            "session.tool.input.delta",
            // The test's own invented type.
            "something.new",
        )
        everyEvent.map { it.wireType() }.filter { it !in unrecorded }.forEach { type ->
            assert("\"$type\"" in manifest) { "$type is not a recorded event type" }
        }
    }

    @Test
    fun `the test decides when the stream ends`() = runTest {
        val api = FakeEventApi()
        api.events().test {
            assertIs<ServerEvent.Connected>(awaitItem())
            api.end()
            awaitComplete()
        }
        assertEquals(0, api.activeConnections.value)
    }

    @Test
    fun `the test decides when the stream fails`() = runTest {
        val api = FakeEventApi()
        api.events().test {
            assertIs<ServerEvent.Connected>(awaitItem())
            api.fail(ApiError.Unauthorized)
            assertEquals(ApiError.Unauthorized, assertIs<ApiException>(awaitError()).error)
        }
        assertEquals(0, api.activeConnections.value)
    }

    @Test
    fun `fail refuses what a real stream would retry`() {
        val api = FakeEventApi()
        listOf(ApiError.Network, ApiError.Timeout, ApiError.Server(503)).forEach { error ->
            assertFailsWith<IllegalArgumentException> { api.fail(error) }
        }
    }

    @Test
    fun `events stay in order, and what is sent before a failure arrives first`() = runTest {
        val api = FakeEventApi()
        val first = ServerEvent.ExecutionStarted(session)
        api.events().test {
            assertIs<ServerEvent.Connected>(awaitItem())
            api.emit(first)
            api.fail(ApiError.Forbidden)
            assertEquals<ServerEvent>(first, awaitItem())
            assertEquals(ApiError.Forbidden, assertIs<ApiException>(awaitError()).error)
        }
    }

    @Test
    fun `a drop carries its reason, and each reconnect counts as a connection`() = runTest {
        val api = FakeEventApi()
        api.events().test {
            assertIs<ServerEvent.Connected>(awaitItem())
            api.drop(ApiError.Timeout)
            assertEquals(ApiError.Timeout, assertIs<ServerEvent.Disconnected>(awaitItem()).reason)
            api.reconnect()
            assertIs<ServerEvent.Connected>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, api.connectionsOpened)
    }

    @Test
    fun `emitting Disconnected and Connected by hand drops and reconnects`() = runTest {
        val api = FakeEventApi()
        val during = ServerEvent.ExecutionStarted(session)
        val after = ServerEvent.ExecutionSucceeded(session)
        api.events().test {
            assertIs<ServerEvent.Connected>(awaitItem())
            api.emit(ServerEvent.Disconnected(ApiError.Network))
            assertIs<ServerEvent.Disconnected>(awaitItem())
            api.emit(during)
            api.emit(ServerEvent.Connected())
            assertIs<ServerEvent.Connected>(awaitItem())
            api.emit(after)
            assertEquals<ServerEvent>(after, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an event with no connection is lost, as the server replays nothing`() = runTest {
        val api = FakeEventApi()
        api.emit(ServerEvent.ExecutionStarted(session))
        assertIs<ServerEvent.Connected>(api.events().first())
    }

    @Test
    fun `a cancelled collection leaves no connection for events to pile up on`() = runTest {
        val api = FakeEventApi()
        api.events().first()
        assertEquals(0, api.registeredConnections)
    }

    @Test
    fun `a collector that ends inside a send does not disturb the others`() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeEventApi()
        val stopping = launch { api.events().first { it is ServerEvent.Disconnected || it !is ServerEvent.Connected } }
        val staying = async { api.events().first { it is ServerEvent.Unknown } }
        api.emit(ServerEvent.ExecutionStarted(session))
        stopping.join()
        val connected = launch { api.events().first { it is ServerEvent.Connected } }
        api.emit(ServerEvent.Connected())
        connected.join()
        api.emit(ServerEvent.Unknown("x"))
        assertIs<ServerEvent.Unknown>(staying.await())
        assertEquals(0, api.registeredConnections)
    }

    @Test
    fun `a collector that takes the session lock inside a send does not block other emitters`() {
        val client = FakeOpencodeClient().apply { sessions.seed(Session(session)) }
        val inCollector = CountDownLatch(1)
        val seen = CopyOnWriteArrayList<ServerEvent>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val collector = scope.launch {
            client.events.events().collect { event ->
                seen += event
                if (event is ServerEvent.ExecutionStarted) {
                    inCollector.countDown()
                    client.sessions.get(session)
                }
            }
        }
        lateinit var sender: Thread
        lateinit var emitter: Thread
        // This thread plays a call that holds the session lock and is about to emit.
        client.sessions.withLock {
            sender = thread { client.events.emit(ServerEvent.ExecutionStarted(session)) }
            assertTrue(inCollector.await(10, TimeUnit.SECONDS))
            sender.awaitBlockedOrDone()
            emitter = thread { client.events.emit(ServerEvent.ExecutionSucceeded(session)) }
            emitter.join(10_000)
            assertFalse(emitter.isAlive, "An emitter must not wait for a collector that runs inside a send")
        }
        sender.join()
        emitter.join()
        collector.cancel()
        assertEquals(
            listOf<Class<*>>(
                ServerEvent.Connected::class.java,
                ServerEvent.ExecutionStarted::class.java,
                ServerEvent.ExecutionSucceeded::class.java,
            ),
            seen.map { it.javaClass },
        )
    }

    @Test
    fun `a waiter woken by a new connection does not run under the event lock`() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeEventApi()
        var emitterWasFree = false
        val waiter = launch {
            api.awaitConnections(1)
            val emitter = thread { api.emit(ServerEvent.ExecutionStarted(session)) }
            emitter.join(10_000)
            emitterWasFree = !emitter.isAlive
        }
        api.events().first()
        waiter.join()
        assertTrue(emitterWasFree, "The waiter ran while holding the lock that emit needs")
    }

    @Test
    fun `an event emitted inside a send does not overtake the one being delivered`() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeEventApi()
        val started = ServerEvent.ExecutionStarted(session)
        val succeeded = ServerEvent.ExecutionSucceeded(session)
        val reacting = launch {
            api.events().collect { if (it == started) api.emit(succeeded) }
        }
        val seen = mutableListOf<ServerEvent>()
        val watching = launch { api.events().collect { seen += it } }
        api.emit(started)
        reacting.cancel()
        watching.cancel()
        assertEquals(listOf<ServerEvent>(started, succeeded), seen.drop(1))
    }

    @Test
    fun `an end requested inside a send comes after the events before it`() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeEventApi()
        val started = ServerEvent.ExecutionStarted(session)
        val reacting = launch {
            api.events().collect { if (it == started) api.end() }
        }
        val seen = mutableListOf<ServerEvent>()
        val watching = launch { api.events().collect { seen += it } }
        api.emit(started)
        watching.join()
        reacting.join()
        assertEquals(listOf<ServerEvent>(started), seen.drop(1))
        assertEquals(0, api.registeredConnections)
    }

    @Test
    fun `the requested types are recorded`() = runTest {
        val api = FakeEventApi()
        api.events(setOf("form.created")).first()
        assertEquals(listOf(setOf("form.created")), api.requestedTypes)
    }
}
