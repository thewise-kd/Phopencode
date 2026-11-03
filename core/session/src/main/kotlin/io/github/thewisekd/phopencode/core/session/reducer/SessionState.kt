package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.totalUsage

/**
 * One session as its events describe it: a snapshot that [SessionReducer] builds.
 *
 * @property session What the events say about the session. [Session.usage] is the server's last
 *   reported running total (`session.usage.updated`), and [Session.outcome] how the last execution
 *   ended. Fields no event carries stay as the session was seeded.
 * @property deleted Set by `session.deleted`.
 * @property status Running, retrying or idle, from the `session.execution.*` events and
 *   `session.retry.scheduled`.
 * @property error Why the last execution failed; null after one that did not.
 * @property messages The history, oldest first, in the same form as the message list: an assistant
 *   message has `completedAt` null while it streams, and its blocks fill in as deltas arrive.
 * @property inbox Prompts enqueued and not yet delivered or cancelled.
 * @property forms Forms of this session not yet answered or cancelled.
 * @property permissions Permission requests of this session not yet answered.
 */
data class SessionState(
    val session: Session,
    val deleted: Boolean = false,
    val status: SessionStatus = SessionStatus.Idle,
    val error: StructuredError? = null,
    val messages: List<Message> = emptyList(),
    val inbox: List<InboxItem> = emptyList(),
    val forms: List<Form> = emptyList(),
    val permissions: List<PermissionRequest> = emptyList(),
) {
    /**
     * The usage of all messages here, added up exactly. Unlike [Session.usage] this is a recount, and it
     * covers only the messages held, which may be fewer than the whole history.
     */
    val messageUsage: TokenUsage
        get() = messages.totalUsage()

    /** Counts only: messages, forms and the session hold text a user typed or a model wrote. */
    override fun toString(): String =
        "SessionState(session=${session.id.value}, status=$status, messages=${messages.size}, " +
            "inbox=${inbox.size}, forms=${forms.size}, permissions=${permissions.size}, deleted=$deleted)"
}
