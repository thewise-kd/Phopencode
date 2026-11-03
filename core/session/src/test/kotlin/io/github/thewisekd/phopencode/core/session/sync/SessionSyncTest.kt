package io.github.thewisekd.phopencode.core.session.sync

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.MessageQuery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionUpdate
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.session.reducer.SessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class SessionSyncTest {
    private val fetchCalls = listOf(
        "sessions.get",
        "messages.list",
        "interactions.listForms",
        "interactions.listPermissions",
        "sessions.activeStatuses",
    )

    // Fetching on connect and reconnect

    @Test
    fun `on connect the session, every message page and the pending items are fetched`() = runTest {
        val f = SyncFixture(pageSize = 2)
        f.seedMessages(user(1), assistant(2, "answer two"), user(3), assistant(4, "answer four"), user(5))
        f.client.interactions.seedForm(form(1))
        f.client.interactions.seedForm(form(9, OTHER_SESSION))
        f.client.interactions.seedPermission(permission(1))
        f.client.sessions.setActivity(SESSION, SessionActivity.Running)
        assertEquals(SyncPhase.Connecting, f.phase)
        assertNull(f.sync.state.value.session)

        f.start(backgroundScope)
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(
            listOf(user(1), assistant(2, "answer two"), user(3), assistant(4, "answer four"), user(5)),
            f.session.messages,
        )
        assertEquals("first title", f.session.session.title)
        assertEquals(listOf(form(1)), f.session.forms)
        assertEquals(listOf(permission(1)), f.session.permissions)
        assertEquals(SessionStatus.Running, f.session.status)
        assertTrue(f.client.control.calls.count { it == "messages.list" } >= 3, "follows every cursor")
    }

    @Test
    fun `on reconnect messages and pending items are fetched again`() = runTest {
        val f = SyncFixture()
        f.start(backgroundScope)
        runCurrent()
        val before = f.client.control.calls.size

        f.client.events.drop()
        runCurrent()
        assertEquals(SyncPhase.Offline(ApiError.Network), f.phase)
        assertEquals(before, f.client.control.calls.size, "nothing is fetched while down")

        f.client.events.reconnect()
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertTrue(f.callsSince(before).containsAll(fetchCalls), "fetched again: ${f.callsSince(before)}")
    }

    // A missed event

    @Test
    fun `a missed event leaves the state equal to the fetched truth`() = runTest {
        val f = SyncFixture()
        f.seedMessages(user(1), assistant(2, "two"), user(3), assistant(4, "four"))
        f.client.interactions.seedForm(form(1))
        f.client.interactions.seedPermission(permission(1))
        f.client.sessions.setActivity(SESSION, SessionActivity.Running)
        f.start(backgroundScope)
        runCurrent()
        assertEquals(4, f.session.messages.size)

        f.client.events.drop()
        runCurrent()
        // While the stream is down, each of these sends an event that nobody receives.
        f.client.sessions.update(SESSION, SessionUpdate(title = "renamed"))
        f.client.interactions.replyForm(SESSION, form(1).id, emptyMap())
        f.client.interactions.replyPermission(SESSION, permission(1).id, PermissionDecision.Once)
        f.client.messages.stageRevert(SESSION, MessageId("msg_3"))
        f.client.messages.commitRevert(SESSION)
        f.client.interactions.seedForm(form(2))
        f.client.sessions.setActivity(SESSION, null)
        f.client.events.reconnect()
        runCurrent()

        val truth = SessionReducer(
            SESSION,
            f.client.sessions.get(SESSION),
            f.client.messages.list(SESSION, MessageQuery(order = SortOrder.Ascending)).items,
        ).state
        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(listOf(user(1), assistant(2, "two")), f.session.messages)
        assertEquals(truth.messages, f.session.messages)
        assertEquals(truth.session, f.session.session)
        assertEquals("renamed", f.session.session.title)
        assertNull(f.session.session.revert)
        assertEquals(listOf(form(2)), f.session.forms)
        assertEquals(emptyList(), f.session.permissions)
        assertEquals(SessionStatus.Idle, f.session.status)
    }

    @Test
    fun `a missed event of a running message is healed by the fetched message`() = runTest {
        val f = SyncFixture()
        f.start(backgroundScope)
        runCurrent()
        f.emit(ServerEvent.StepStarted(SESSION, MessageId("msg_2"), meta = meta("evt_step", 2)))
        f.emit(ServerEvent.TextStarted(SESSION, MessageId("msg_2"), 0, meta("evt_start", 2)))
        f.emit(ServerEvent.TextDelta(SESSION, MessageId("msg_2"), 0, "Hel", meta("evt_d1", 2)))
        runCurrent()
        assertEquals("Hel", f.session.messages.single().firstText())

        f.client.events.drop()
        runCurrent()
        // The rest of the text and the step's end are missed; the server stored the message.
        f.seedMessages(assistant(2, "Hello there"))
        f.client.events.reconnect()
        runCurrent()

        assertEquals(listOf(assistant(2, "Hello there")), f.session.messages)
    }

    // Duplicates

    @Test
    fun `a duplicate event has no effect`() = runTest {
        val f = SyncFixture()
        f.start(backgroundScope)
        runCurrent()
        val delta = ServerEvent.TextDelta(SESSION, MessageId("msg_2"), 0, "Hi", meta("evt_delta", 2))
        val form = ServerEvent.FormCreated(form(5), meta("evt_form", 2))
        val reply = ServerEvent.FormReplied(SESSION, form(5).id, meta = meta("evt_reply", 3))

        f.emit(delta)
        f.emit(form)
        runCurrent()
        val once = f.sync.state.value
        f.emit(delta)
        f.emit(form)
        runCurrent()

        assertSame(once, f.sync.state.value)
        assertEquals("Hi", f.session.messages.single().firstText())

        f.emit(reply)
        f.emit(form)
        runCurrent()
        assertEquals(emptyList(), f.session.forms)
    }

    @Test
    fun `an event repeated after a reconnect has no effect`() = runTest {
        val f = SyncFixture()
        f.start(backgroundScope)
        runCurrent()
        val delta = ServerEvent.TextDelta(SESSION, MessageId("msg_2"), 0, "Hi", meta("evt_delta", 2))
        val asked = ServerEvent.PermissionAsked(permission(4), meta("evt_asked", 2))
        f.emit(delta)
        f.emit(asked)
        runCurrent()

        f.client.events.drop()
        runCurrent()
        // Meanwhile the server settled the request and stored the message.
        f.seedMessages(assistant(2, "Hi"))
        f.client.events.reconnect()
        runCurrent()
        val synced = f.sync.state.value
        f.emit(delta)
        f.emit(asked)
        runCurrent()

        assertSame(synced, f.sync.state.value)
        assertEquals(listOf(assistant(2, "Hi")), f.session.messages)
        assertEquals(emptyList(), f.session.permissions)
    }

    // Events during the fetch

    @Test
    fun `events arriving during the fetch are applied on top of it`() = runTest {
        val f = SyncFixture()
        f.seedMessages(user(1))
        f.client.interactions.seedPermission(permission(1))
        // The step below runs on the server, which says so when asked.
        f.client.sessions.setActivity(SESSION, SessionActivity.Running)
        f.client.control.stall()
        f.start(backgroundScope)
        runCurrent()
        assertEquals(SyncPhase.Syncing, f.phase)

        f.emit(ServerEvent.PermissionReplied(SESSION, permission(1).id, PermissionDecision.Once, meta("evt_p", 2)))
        f.emit(ServerEvent.FormCreated(form(2), meta("evt_f", 2)))
        f.emit(ServerEvent.StepStarted(SESSION, MessageId("msg_3"), meta = meta("evt_s", 3)))
        f.emit(ServerEvent.TextDelta(SESSION, MessageId("msg_3"), 0, "streamed", meta("evt_d", 3)))
        f.emit(ServerEvent.SessionRenamed(OTHER_SESSION, "not ours", meta("evt_o", 3)))
        runCurrent()
        assertNull(f.sync.state.value.session, "nothing half-built is shown")

        f.client.control.resume()
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(listOf(MessageId("msg_1"), MessageId("msg_3")), f.session.messages.map { it.id })
        assertEquals("streamed", f.session.messages[1].firstText())
        assertEquals(listOf(form(2)), f.session.forms)
        assertEquals(emptyList(), f.session.permissions)
        assertEquals("first title", f.session.session.title)
        assertEquals(SessionStatus.Running, f.session.status)
    }

    @Test
    fun `during a resync the previous state stays and takes live events`() = runTest {
        val f = SyncFixture()
        f.seedMessages(user(1))
        f.start(backgroundScope)
        runCurrent()

        f.client.events.drop()
        runCurrent()
        f.client.control.stall()
        f.client.events.reconnect()
        runCurrent()
        assertEquals(SyncPhase.Syncing, f.phase)
        f.emit(ServerEvent.FormCreated(form(3), meta("evt_f", 3)))
        runCurrent()

        assertEquals(listOf(user(1)), f.session.messages)
        assertEquals(listOf(form(3)), f.session.forms)

        f.client.control.resume()
        runCurrent()
        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(listOf(form(3)), f.session.forms, "the buffered event is applied to the fetched truth too")
    }

    // Failures

    @Test
    fun `a fetch failing midway keeps the previous state and retries from the start`() = runTest {
        val f = SyncFixture(pageSize = 2)
        f.seedMessages(user(1))
        f.start(backgroundScope)
        runCurrent()
        val previous = f.sync.state.value.session

        f.client.events.drop()
        runCurrent()
        f.seedMessages(assistant(2, "two"), user(3), assistant(4, "four"), user(5))
        f.messages.arm(failOn = 2)
        val before = f.client.control.calls.size
        f.client.events.reconnect()
        runCurrent()

        val retrying = assertIs<SyncPhase.Retrying>(f.phase)
        assertEquals(ApiError.Network, retrying.error)
        assertEquals(1, retrying.attempt)
        assertSame(previous, f.sync.state.value.session, "no page of the failed attempt is merged")

        advanceTimeBy(1.seconds - 1.seconds / 1000)
        runCurrent()
        assertIs<SyncPhase.Retrying>(f.phase)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(listOf(user(1), assistant(2, "two"), user(3), assistant(4, "four"), user(5)), f.session.messages)
        assertEquals(2, f.callsSince(before).count { it == "sessions.get" }, "the retry starts over")
    }

    @Test
    fun `retries wait longer each time up to the maximum`() = runTest {
        val f = SyncFixture()
        f.client.control.failNext(ApiError.Server(503), times = 4)
        f.start(backgroundScope)
        runCurrent()
        val start = testScheduler.currentTime
        val attempts = mutableListOf<Pair<Int, Long>>()
        while (f.phase != SyncPhase.Live) {
            val phase = assertIs<SyncPhase.Retrying>(f.phase)
            if (attempts.lastOrNull()?.first != phase.attempt) {
                attempts += phase.attempt to testScheduler.currentTime - start
            }
            advanceTimeBy(100)
            runCurrent()
        }

        assertEquals(listOf(1, 2, 3, 4), attempts.map { it.first })
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), attempts.map { it.second })
        assertEquals(11_000L, testScheduler.currentTime - start)
    }

    @Test
    fun `a fetch error that retrying cannot help ends the sync`() = runTest {
        val f = SyncFixture()
        f.client.control.failNext(ApiError.Unauthorized)
        val run = backgroundScope.async { assertFailsWith<ApiException> { f.sync.run() } }
        runCurrent()

        assertEquals(ApiError.Unauthorized, run.await().error)
        assertEquals(SyncPhase.Failed(ApiError.Unauthorized), f.phase)
        assertEquals(0, f.client.events.activeConnections.value)
    }

    @Test
    fun `a stream that fails ends the sync with its error`() = runTest {
        val f = SyncFixture()
        val run = backgroundScope.async { assertFailsWith<ApiException> { f.sync.run() } }
        runCurrent()
        f.client.events.fail(ApiError.Unauthorized)
        runCurrent()

        assertEquals(ApiError.Unauthorized, run.await().error)
        assertEquals(SyncPhase.Failed(ApiError.Unauthorized), f.phase)
    }

    // Repeated reconnects

    @Test
    fun `repeated reconnects each fetch the newest truth`() = runTest {
        val f = SyncFixture()
        f.start(backgroundScope)
        runCurrent()

        for (n in 1..3) {
            f.client.events.drop()
            runCurrent()
            f.seedMessages(user(n))
            f.client.events.reconnect()
            runCurrent()
            assertEquals(SyncPhase.Live, f.phase)
            assertEquals((1..n).map { user(it) }, f.session.messages)
        }
        assertEquals(4, f.client.control.calls.count { it == "sessions.get" })
        assertEquals(1, f.client.events.activeConnections.value)
    }

    @Test
    fun `a reconnect during a fetch abandons it for a new one`() = runTest {
        val f = SyncFixture()
        f.seedMessages(user(1))
        f.client.control.stall()
        f.start(backgroundScope)
        runCurrent()
        f.emit(ServerEvent.FormCreated(form(7), meta("evt_old", 1)))
        runCurrent()

        f.client.events.drop()
        runCurrent()
        assertEquals(SyncPhase.Offline(ApiError.Network), f.phase)
        assertNull(f.sync.state.value.session)
        f.seedMessages(user(2))
        f.client.events.reconnect()
        runCurrent()
        f.client.control.resume()
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(listOf(user(1), user(2)), f.session.messages)
        assertEquals(emptyList(), f.session.forms, "an event of the abandoned attempt is not applied")
        assertEquals(2, f.client.control.calls.count { it == "sessions.get" })
    }

    // Cancellation

    @Test
    fun `cancelling during a fetch leaves nothing running`() = runTest {
        val f = SyncFixture()
        f.client.control.stall()
        val job = f.start(backgroundScope)
        runCurrent()
        f.client.control.awaitRunningCalls(1)

        job.cancel()
        runCurrent()
        val calls = f.client.control.calls.size
        f.client.control.resume()
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(0, f.client.events.activeConnections.value)
        assertEquals(calls, f.client.control.calls.size, "no call is made after cancelling")
    }

    @Test
    fun `cancelling while waiting to retry leaves nothing running`() = runTest {
        val f = SyncFixture()
        f.client.control.failNext(ApiError.Timeout)
        val job = f.start(backgroundScope)
        runCurrent()
        assertIs<SyncPhase.Retrying>(f.phase)

        job.cancel()
        runCurrent()
        val calls = f.client.control.calls.size
        advanceTimeBy(60.seconds)
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals(0, f.client.events.activeConnections.value)
        assertEquals(calls, f.client.control.calls.size)
    }

    @Test
    fun `the state prints no message content`() = runTest {
        val f = SyncFixture()
        f.seedMessages(user(1, "secret prompt text"))
        f.start(backgroundScope)
        runCurrent()

        val printed = f.sync.state.value.toString()
        assertTrue("secret" !in printed, printed)
        assertTrue("first title" !in printed, printed)
    }
}
