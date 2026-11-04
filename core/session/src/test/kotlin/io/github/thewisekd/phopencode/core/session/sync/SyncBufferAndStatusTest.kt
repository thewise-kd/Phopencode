package io.github.thewisekd.phopencode.core.session.sync

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.Prompt
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** How the events of a running fetch are held, and where the fetched status stands among them. */
class SyncBufferAndStatusTest {
    private fun delta(n: Int) = ServerEvent.TextDelta(SESSION, MessageId("msg_9"), 0, "x", meta("evt_delta_$n", 9))

    private fun formEvent(n: Int) = ServerEvent.FormCreated(form(n), meta("evt_form_$n", 1))

    private fun SyncFixture.gets() = client.control.calls.count { it == "sessions.get" }

    // A bounded buffer, and a fetch that is never starved

    @Test
    fun `a fetch that completes while events arrive back to back is applied at once`() = runTest {
        val total = 5_000
        // Every event is waiting before the sync starts, so one is always ready to receive.
        val incoming = Channel<ServerEvent>(Channel.UNLIMITED)
        incoming.trySend(ServerEvent.Connected())
        repeat(total) { incoming.trySend(delta(it)) }
        val f = SyncFixture(maxBuffered = 1_000, events = incoming.consumeAsFlow())

        f.start(backgroundScope)
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, f.gets(), "the fetch was never restarted")
        assertTrue(f.sync.bufferHighWater < 100, "buffered ${f.sync.bufferHighWater}")
        assertEquals(total, f.session.messages.single().firstText()?.length)
    }

    @Test
    fun `a full buffer during the fetch starts the fetch over and stays within its bound`() = runTest {
        val f = SyncFixture(maxBuffered = 8)
        f.client.control.stall()
        f.start(backgroundScope)
        runCurrent()

        repeat(20) { f.emit(formEvent(it)) }
        runCurrent()

        assertTrue(f.sync.bufferHighWater <= 8, "buffered ${f.sync.bufferHighWater}")
        assertEquals(3, f.gets(), "started over at the 9th and the 18th event")
        f.client.control.resume()
        runCurrent()
        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(listOf(form(18), form(19)), f.session.forms, "the events after the last start")
    }

    @Test
    fun `a full buffer while waiting to retry is dropped without fetching early`() = runTest {
        val f = SyncFixture(maxBuffered = 8)
        f.client.control.failNext(ApiError.Network)
        f.start(backgroundScope)
        runCurrent()
        assertIs<SyncPhase.Retrying>(f.phase)
        val calls = f.client.control.calls.size

        repeat(20) { f.emit(formEvent(it)) }
        runCurrent()

        assertTrue(f.sync.bufferHighWater <= 8, "buffered ${f.sync.bufferHighWater}")
        assertEquals(calls, f.client.control.calls.size)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(listOf(form(18), form(19)), f.session.forms)
    }

    @Test
    fun `an enqueued item that arrived before a full buffer is still pending after the fetch starts over`() = runTest {
        val f = SyncFixture(maxBuffered = 8)
        f.client.control.stall()
        f.start(backgroundScope)
        runCurrent()
        // Stalled, the server still takes them through its own side: their events reach the buffer.
        f.client.control.resume()
        val queued = f.client.messages.prompt(SESSION, Prompt("later", delivery = PromptDelivery.Queue))
        val changed = f.client.messages.prompt(SESSION, Prompt("steer", delivery = PromptDelivery.Queue))
        f.client.control.stall()
        f.messages.changedDeliveries[changed.id] = PromptDelivery.Steer
        f.emit(ServerEvent.InboxDeliveryChanged(SESSION, changed.id, PromptDelivery.Steer, meta("evt_changed", 2)))
        repeat(20) { f.emit(formEvent(it)) }
        runCurrent()
        assertTrue(f.gets() >= 2, "the fetch started over")
        f.client.control.resume()
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(listOf(queued.id, changed.id), f.session.inbox.map { it.id })
        assertEquals(PromptDelivery.Queue, f.session.inbox[0].delivery)
        assertEquals(PromptDelivery.Steer, f.session.inbox[1].delivery)
    }

    @Test
    fun `an item cancelled before a full buffer stays gone after the fetch starts over`() = runTest {
        val f = SyncFixture(maxBuffered = 4)
        f.client.control.stall()
        f.start(backgroundScope)
        runCurrent()
        f.client.control.resume()
        val item = f.client.messages.prompt(SESSION, Prompt("now"))
        f.client.control.stall()
        f.client.messages.cancel(item.id)
        repeat(10) { f.emit(formEvent(it)) }
        runCurrent()
        f.client.control.resume()
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(emptyList(), f.session.inbox)
    }

    // The fetched status

    @Test
    fun `an end received but not yet handled when the status is asked for cannot override it`() = runTest {
        // Both are received from the stream before the fetch runs; the end is still queued then.
        val incoming = Channel<ServerEvent>(Channel.UNLIMITED)
        incoming.trySend(ServerEvent.Connected())
        incoming.trySend(ServerEvent.ExecutionSucceeded(SESSION, meta("evt_end", 50)))
        val f = SyncFixture(events = incoming.consumeAsFlow())
        f.client.sessions.setActivity(SESSION, SessionActivity.Running)

        f.start(backgroundScope)
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(SessionStatus.Running, f.session.status)
    }

    @Test
    fun `an end that arrived before the status was read cannot stop the run the server reports`() = runTest {
        val f = SyncFixture()
        f.client.sessions.setActivity(SESSION, SessionActivity.Running)
        f.client.control.stall()
        f.start(backgroundScope)
        runCurrent()

        f.emit(ServerEvent.ExecutionSucceeded(SESSION, meta("evt_end", 50)))
        runCurrent()
        f.client.control.resume()
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(SessionStatus.Running, f.session.status)
    }

    @Test
    fun `a start that arrived before the status was read cannot outlive the idle the server reports`() = runTest {
        val f = SyncFixture()
        f.client.control.stall()
        f.start(backgroundScope)
        runCurrent()

        f.emit(ServerEvent.ExecutionStarted(SESSION, meta("evt_start", 50)))
        runCurrent()
        f.client.control.resume()
        runCurrent()

        assertEquals(SessionStatus.Idle, f.session.status)
    }

    @Test
    fun `an end that arrives after the status was read still applies`() = runTest {
        val f = SyncFixture()
        f.client.sessions.setActivity(SESSION, SessionActivity.Running)
        val gate = CompletableDeferred<Unit>()
        f.sessions.gate = gate
        f.start(backgroundScope)
        runCurrent()
        f.sessions.waiting.await()

        f.emit(ServerEvent.ExecutionSucceeded(SESSION, meta("evt_end", 1)))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(SessionStatus.Idle, f.session.status)
    }
}
