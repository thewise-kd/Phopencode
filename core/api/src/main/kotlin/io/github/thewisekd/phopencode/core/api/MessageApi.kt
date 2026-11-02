package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId

/** A session's conversation. Errors are described on [OpencodeClient]. */
interface MessageApi {
    /** Returns once the server has accepted the prompt; the answer arrives on the event stream. */
    suspend fun prompt(sessionId: SessionId, prompt: Prompt)

    suspend fun list(sessionId: SessionId, query: MessageQuery = MessageQuery()): Page<Message>

    /** Runs a command by name; see [CatalogApi.commands]. */
    suspend fun command(sessionId: SessionId, name: String, text: String = "")

    suspend fun switchAgent(sessionId: SessionId, agent: String)

    /** [ModelRef.variant] selects the variant too. */
    suspend fun switchModel(sessionId: SessionId, model: ModelRef)

    /**
     * What a turn changed.
     *
     * @param from Where the range starts; null means the start of the session.
     * @param to Where the range ends; null means the latest state.
     * @param context The number of unchanged lines around each change; null is the server's default.
     */
    suspend fun turnDiff(
        sessionId: SessionId,
        from: MessageId? = null,
        to: MessageId? = null,
        context: Int? = null,
    ): List<FileDiff>

    /** Prepares undoing [messageId] and all that follows. Nothing is final until [commitRevert]. */
    suspend fun stageRevert(sessionId: SessionId, messageId: MessageId)

    /** Makes the staged revert final. */
    suspend fun commitRevert(sessionId: SessionId)

    /** Drops the staged revert. */
    suspend fun cancelRevert(sessionId: SessionId)
}

/**
 * What to send.
 *
 * @property files Attached file paths.
 * @property agents Agents mentioned, by name.
 * @property skills Skills attached, by name.
 * @property delivery How to hand this over while the session runs; null is the server's default.
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
 * @property type A server message type, for example `user`.
 * @property cursor [Page.nextCursor] of the previous page; null for the first.
 */
data class MessageQuery(
    val limit: Int? = null,
    val order: SortOrder? = null,
    val type: String? = null,
    val cursor: PageCursor? = null,
)
