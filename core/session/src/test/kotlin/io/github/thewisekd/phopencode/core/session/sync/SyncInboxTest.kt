package io.github.thewisekd.phopencode.core.session.sync

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.Prompt
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A resync reads the inbox too, so queued prompts survive a reconnect and settled ones go. */
class SyncInboxTest {
    private suspend fun SyncFixture.prompt(text: String, delivery: PromptDelivery = PromptDelivery.Queue): InboxItem =
        client.messages.prompt(SESSION, Prompt(text, delivery = delivery))

    private fun SyncFixture.inboxIds(): List<MessageId> = session.inbox.map { it.id }

    private fun SyncFixture.messageIds(): List<MessageId> = session.messages.map { it.id }

    @Test
    fun `on connect the prompts already queued on the server are shown`() = runTest {
        val f = SyncFixture()
        f.seedMessages(user(1))
        val first = f.prompt("first")
        val second = f.prompt("second", PromptDelivery.Steer)

        f.start(backgroundScope)
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(listOf(first, second), f.session.inbox)
        assertEquals(listOf(MessageId("msg_1")), f.messageIds())
    }

    @Test
    fun `the inbox is read before the messages`() = runTest {
        val f = SyncFixture()
        f.start(backgroundScope)
        runCurrent()

        val calls = f.client.control.calls
        assertTrue(calls.indexOf("messages.inbox") in 0 until calls.indexOf("messages.list"), "$calls")
    }

    @Test
    fun `a reconnect shows the prompts queued while the stream was down`() = runTest {
        val f = SyncFixture()
        f.start(backgroundScope)
        runCurrent()
        f.client.events.drop()
        runCurrent()

        // Each sends an enqueue event that nobody receives.
        val missed = f.prompt("queued while offline")
        assertEquals(emptyList(), f.session.inbox)
        f.client.events.reconnect()
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(listOf(missed), f.session.inbox)
    }

    @Test
    fun `items delivered or cancelled while the stream was down are gone after the reconnect`() = runTest {
        val f = SyncFixture()
        f.start(backgroundScope)
        runCurrent()
        val delivered = f.prompt("delivered later")
        val cancelled = f.prompt("cancelled later")
        val kept = f.prompt("still queued")
        runCurrent()
        assertEquals(listOf(delivered.id, cancelled.id, kept.id), f.inboxIds())

        f.client.events.drop()
        runCurrent()
        f.client.messages.deliver(delivered.id)
        f.client.messages.cancel(cancelled.id)
        f.client.events.reconnect()
        runCurrent()

        assertEquals(listOf(kept.id), f.inboxIds())
        val message = assertIs<Message.User>(f.session.messages.single())
        assertEquals(delivered.id, message.id)
        assertEquals("delivered later", message.text)
    }

    @Test
    fun `an item delivered after the inbox was read is shown once, as its message`() = runTest {
        val f = SyncFixture()
        val item = f.prompt("hello")
        f.messages.afterInbox = { f.client.messages.deliver(item.id) }

        f.start(backgroundScope)
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(emptyList(), f.session.inbox)
        assertEquals(listOf(item.id), f.messageIds())
    }

    @Test
    fun `an item delivered after the messages were read is not lost`() = runTest {
        val f = SyncFixture()
        val item = f.prompt("hello")
        f.messages.afterList = { f.client.messages.deliver(item.id) }

        f.start(backgroundScope)
        runCurrent()

        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(emptyList(), f.session.inbox)
        assertEquals(listOf(item.id), f.messageIds())
    }

    @Test
    fun `an item queued during the fetch is shown once`() = runTest {
        val f = SyncFixture()
        lateinit var queued: InboxItem
        f.messages.afterInbox = {
            queued = InboxItem(MessageId("msg_late"), SESSION, at(5), InboxContent.User("late"), PromptDelivery.Queue)
            f.emit(ServerEvent.InboxEnqueued(queued.copy(createdAt = null), meta("evt_late", 5)))
        }

        f.start(backgroundScope)
        runCurrent()

        assertEquals(listOf(queued.id), f.inboxIds())
    }

    @Test
    fun `a repeat of an enqueue seen before the drop does not bring back an item delivered meanwhile`() = runTest {
        val f = SyncFixture()
        f.start(backgroundScope)
        runCurrent()
        val item = InboxItem(MessageId("msg_7"), SESSION, null, InboxContent.User("seven"), PromptDelivery.Queue)
        val enqueued = ServerEvent.InboxEnqueued(item, meta("evt_enqueued_7", 7))
        f.emit(enqueued)
        runCurrent()
        assertEquals(listOf(item.id), f.inboxIds())

        f.client.events.drop()
        runCurrent()
        f.seedMessages(Message.User(item.id, SESSION, at(8), "seven"))
        f.client.events.reconnect()
        runCurrent()
        val synced = f.sync.state.value
        f.emit(enqueued)
        runCurrent()

        assertSame(synced, f.sync.state.value)
        assertEquals(emptyList(), f.session.inbox)
        assertEquals(listOf(item.id), f.messageIds())
    }

    @Test
    fun `a failed inbox read is retried and nothing partial is shown`() = runTest {
        val f = SyncFixture()
        f.start(backgroundScope)
        runCurrent()
        val queued = f.prompt("queued")
        runCurrent()
        f.client.events.drop()
        runCurrent()
        f.client.messages.cancel(queued.id)
        val later = f.prompt("later")
        val shown = f.session

        f.messages.afterInbox = { throw ApiException(ApiError.Network) }
        f.client.events.reconnect()
        runCurrent()
        assertIs<SyncPhase.Retrying>(f.phase)
        assertSame(shown, f.session)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(SyncPhase.Live, f.phase)
        assertEquals(listOf(later.id), f.inboxIds())
    }
}
