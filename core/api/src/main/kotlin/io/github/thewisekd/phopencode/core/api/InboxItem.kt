package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlin.time.Instant

/**
 * Something handed to a session and not yet part of its history: what `prompt` and `compact` return,
 * and what `session.inbox.enqueued` carries. Its [id] becomes the message's ID once delivered.
 *
 * @property createdAt Null on the event, whose item has no time. [ServerEvent.meta] has one.
 * @property delivery Null when the server sent a delivery this app does not know.
 */
data class InboxItem(
    val id: MessageId,
    val sessionId: SessionId,
    val createdAt: Instant? = null,
    val content: InboxContent,
    val delivery: PromptDelivery?,
) {
    /** The text can hold what the user typed, so it stays out of logs. */
    override fun toString(): String = "InboxItem(id=${id.value}, session=${sessionId.value})"
}

/** The server's `type` and `payload`. Here a user prompt keeps its text in `payload.text`. */
sealed interface InboxContent {
    data class User(
        val text: String,
        val files: List<String> = emptyList(),
        val agents: List<String> = emptyList(),
        val skills: List<String> = emptyList(),
    ) : InboxContent

    data class Synthetic(val text: String) : InboxContent

    data object Compaction : InboxContent

    /** A type this app does not handle, such as `move`. */
    data class Unknown(val rawType: String) : InboxContent
}
