package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.EventDurable
import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlin.time.Instant

internal val SESSION = SessionId("ses_test")
internal val OTHER_SESSION = SessionId("ses_other")
internal val MSG_A = MessageId("msg_a")
internal val MSG_B = MessageId("msg_b")

/** Builds event metadata the way the server stamps it: unique IDs, rising times and, for durable events, rising `seq`. */
internal class EventScript(private val session: SessionId = SESSION) {
    private var count = 0
    private var seq = 0L

    /** A durable event: it has an ID, a time and a `seq`. */
    fun durable(): EventMeta {
        count++
        return EventMeta(id = "evt_$count", createdAt = at(count), durable = EventDurable(session.value, seq++, 1))
    }

    /** A live event, such as a delta: an ID and a time, no `seq`. */
    fun live(): EventMeta {
        count++
        return EventMeta(id = "evt_$count", createdAt = at(count))
    }

    companion object {
        fun at(n: Int): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L + n * 10L)
    }
}

internal fun SessionReducer.assistant(id: MessageId): Message.Assistant =
    state.messages.filterIsInstance<Message.Assistant>().single { it.id == id }

internal fun SessionReducer.texts(id: MessageId): List<String> =
    assistant(id).blocks.filterIsInstance<AssistantBlock.Text>().map { it.text }

internal fun SessionReducer.tool(id: MessageId, callId: String): AssistantBlock.Tool =
    assistant(id).blocks.filterIsInstance<AssistantBlock.Tool>().single { it.id == callId }
