package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionRevert

/** A session's conversation. Errors are described on [OpencodeClient]. */
interface MessageApi {
    /**
     * Returns the inbox item once the server has accepted the prompt; the answer arrives on the event
     * stream. The first prompt after a server start can block for several seconds while the location
     * starts, and other calls can fail with [ApiError.Server] status 503 in that window.
     */
    suspend fun prompt(sessionId: SessionId, prompt: Prompt): InboxItem

    suspend fun list(sessionId: SessionId, query: MessageQuery = MessageQuery()): Page<Message>

    /** Runs a command by name; see [CatalogApi.commands]. */
    suspend fun command(sessionId: SessionId, name: String, text: String = "")

    suspend fun switchAgent(sessionId: SessionId, agent: String)

    /**
     * [ModelRef.variant] selects the variant too. The server accepts a model that does not exist: the
     * failure comes at the next prompt, as [ServerEvent.ExecutionFailed].
     */
    suspend fun switchModel(sessionId: SessionId, model: ModelRef)

    /**
     * What a turn changed.
     *
     * @param from The user message whose turn starts the range; null means the newest user turn.
     * @param to A later user message whose turn ends the range; null means [from]'s own turn ends it.
     * @param context The number of unchanged lines around each change; null is the server's default.
     */
    suspend fun turnDiff(
        sessionId: SessionId,
        from: MessageId? = null,
        to: MessageId? = null,
        context: Int? = null,
    ): List<FileDiff>

    /**
     * Prepares undoing [messageId] and all that follows. Nothing is final until [commitRevert]. The
     * session then carries the returned value as [io.github.thewisekd.phopencode.core.model.Session.revert].
     *
     * @param messageId A user message.
     * @param files Whether to undo file changes too; null is the server's default.
     */
    suspend fun stageRevert(sessionId: SessionId, messageId: MessageId, files: Boolean? = null): SessionRevert

    /** Makes the staged revert final: removes the message and all after it. Events follow the return. */
    suspend fun commitRevert(sessionId: SessionId)

    /**
     * Drops the staged revert. This also runs a short execution with no model request, so wait for it
     * to end before reading the session.
     */
    suspend fun cancelRevert(sessionId: SessionId)
}

/**
 * What to send.
 *
 * @property files Attached file paths.
 * @property agents Agents mentioned, by name.
 * @property skills Skills attached, by name.
 * @property delivery How to hand this over while the session runs; null is the server's default,
 *   [PromptDelivery.Steer].
 * @property resume Whether to continue the session's work; null is the server's default.
 */
data class Prompt(
    val text: String,
    val files: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val delivery: PromptDelivery? = null,
    val resume: Boolean? = null,
)

enum class PromptDelivery {
    /** Interject in the running work. */
    Steer,

    /** Wait until the running work ends. */
    Queue,
}

/**
 * Which messages to read. A null field means the server's default.
 *
 * @property order The server's default is newest first.
 * @property type A server message type, for example `user`.
 * @property cursor [Page.nextCursor] of the previous page; null for the first.
 */
data class MessageQuery(
    val limit: Int? = null,
    val order: SortOrder? = null,
    val type: String? = null,
    val cursor: PageCursor? = null,
)
