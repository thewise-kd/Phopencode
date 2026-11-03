package io.github.thewisekd.phopencode.core.testing.api

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.MessageApi
import io.github.thewisekd.phopencode.core.api.MessageQuery
import io.github.thewisekd.phopencode.core.api.Page
import io.github.thewisekd.phopencode.core.api.Prompt
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionRevert

/** A command that [MessageApi.command] ran. */
data class CommandRun(val sessionId: SessionId, val name: String, val text: String)

/**
 * Conversations in memory, kept with the sessions of [sessions].
 *
 * The answer to a prompt is the test's to send: nothing here plays a model. [prompt] leaves an inbox
 * item, [deliver] turns it into a message, and [seed] adds what a test wants to read back.
 * Committing or cancelling with no staged revert fails with [ApiError.Conflict].
 *
 * @property revertPreview What the next [stageRevert] says it would change, unless told not to.
 */
class FakeMessageApi(
    private val control: FakeApiControl = FakeApiControl(),
    private val sessions: FakeSessionApi = FakeSessionApi(control),
) : MessageApi {
    private data class TurnKey(val sessionId: SessionId, val from: MessageId)

    private val turnDiffs = HashMap<TurnKey, List<FileDiff>>()
    private val runs = mutableListOf<CommandRun>()

    init {
        sessions.onDelete { id -> turnDiffs.keys.removeAll { it.sessionId == id } }
    }

    @Volatile
    var revertPreview: List<FileDiff> = emptyList()

    val commandsRun: List<CommandRun>
        get() = sessions.withLock { runs.toList() }

    /** Appends [messages], oldest first, to a session that exists. Sends no event. */
    fun seed(sessionId: SessionId, messages: List<Message>) {
        sessions.withLock {
            check(sessions.peek(sessionId) != null) { "Seed the session first" }
            sessions.historyOf(sessionId) += messages
        }
    }

    /** What [turnDiff] gives for the turn that [from] starts. */
    fun setTurnDiff(sessionId: SessionId, from: MessageId, diffs: List<FileDiff>) {
        sessions.withLock { turnDiffs[TurnKey(sessionId, from)] = diffs }
    }

    /** Hands an inbox item to its session: it becomes a message with the same ID. */
    fun deliver(inboxId: MessageId): Message = sessions.withLock {
        val item = sessions.takeInbox(inboxId)
        val message = item.toMessage()
        sessions.historyOf(item.sessionId) += message
        sessions.events.emit(ServerEvent.InboxDelivered(item.sessionId, item.id))
        message
    }

    private fun InboxItem.toMessage(): Message = when (val content = content) {
        is InboxContent.User -> Message.User(id, sessionId, createdAt, content.text, content.files, content.agents, content.skills)
        is InboxContent.Synthetic -> Message.Synthetic(id, sessionId, createdAt, content.text)
        InboxContent.Compaction -> Message.Compaction(id, sessionId, createdAt)
        is InboxContent.Unknown -> Message.Unknown(id, sessionId, createdAt, content.rawType)
    }

    override suspend fun prompt(sessionId: SessionId, prompt: Prompt): InboxItem = control.call("messages.prompt") {
        sessions.serving {
            sessions.require(sessionId)
            val content = InboxContent.User(prompt.text, prompt.files, prompt.agents, prompt.skills)
            sessions.enqueue(sessionId, content, prompt.delivery ?: PromptDelivery.Steer)
        }
    }

    override suspend fun list(sessionId: SessionId, query: MessageQuery): Page<Message> =
        control.call("messages.list") {
            sessions.serving {
                sessions.require(sessionId)
                val matching = sessions.historyOf(sessionId).filter { query.type == null || it.serverType() == query.type }
                when (query.order ?: SortOrder.Descending) {
                    SortOrder.Ascending -> matching
                    SortOrder.Descending -> matching.reversed()
                }.page(query.limit, query.cursor)
            }
        }

    override suspend fun command(sessionId: SessionId, name: String, text: String): Unit =
        control.call("messages.command") {
            sessions.serving {
                sessions.require(sessionId)
                runs += CommandRun(sessionId, name, text)
            }
        }

    override suspend fun switchAgent(sessionId: SessionId, agent: String): Unit = control.call("messages.switchAgent") {
        sessions.serving {
            val session = sessions.require(sessionId)
            sessions.put(session.copy(agent = agent))
            sessions.events.emit(ServerEvent.AgentSelected(sessionId, agent, session.agent))
        }
    }

    /** Takes any model, as the server does. */
    override suspend fun switchModel(sessionId: SessionId, model: ModelRef): Unit = control.call("messages.switchModel") {
        sessions.serving {
            sessions.put(sessions.require(sessionId).copy(model = model))
            sessions.events.emit(ServerEvent.ModelSelected(sessionId, model))
        }
    }

    /** Gives the diff of the turn [from] starts; [to] and [context] change nothing here. */
    override suspend fun turnDiff(
        sessionId: SessionId,
        from: MessageId?,
        to: MessageId?,
        context: Int?,
    ): List<FileDiff> = control.call("messages.turnDiff") {
        sessions.serving {
            sessions.require(sessionId)
            val history = sessions.historyOf(sessionId)
            val start = from ?: history.lastOrNull { it is Message.User }?.id
            if (from != null && history.none { it.id == from }) throw ApiException(ApiError.NotFound)
            start?.let { turnDiffs[TurnKey(sessionId, it)] }.orEmpty()
        }
    }

    override suspend fun stageRevert(sessionId: SessionId, messageId: MessageId, files: Boolean?): SessionRevert =
        control.call("messages.stageRevert") {
            sessions.serving {
                val session = sessions.require(sessionId)
                val message = sessions.historyOf(sessionId).firstOrNull { it.id == messageId }
                    ?: throw ApiException(ApiError.NotFound)
                if (message !is Message.User) throw ApiException(ApiError.BadRequest("Not a user message"))
                val revert = SessionRevert(messageId, "fake-snapshot", if (files == false) emptyList() else revertPreview)
                sessions.put(session.copy(revert = revert))
                sessions.events.emit(ServerEvent.RevertStaged(sessionId, revert))
                revert
            }
        }

    override suspend fun commitRevert(sessionId: SessionId): Unit = control.call("messages.commitRevert") {
        sessions.serving {
            val session = sessions.require(sessionId)
            val revert = session.revert ?: throw ApiException(ApiError.Conflict("No revert is staged"))
            val history = sessions.historyOf(sessionId)
            val cut = history.indexOfFirst { it.id == revert.messageId }
            if (cut >= 0) history.subList(cut, history.size).clear()
            sessions.put(session.copy(revert = null))
            sessions.events.emit(ServerEvent.RevertCommitted(sessionId, revert.messageId))
        }
    }

    override suspend fun cancelRevert(sessionId: SessionId): Unit = control.call("messages.cancelRevert") {
        sessions.serving {
            val session = sessions.require(sessionId)
            if (session.revert == null) throw ApiException(ApiError.Conflict("No revert is staged"))
            sessions.put(session.copy(revert = null))
            sessions.events.emit(ServerEvent.RevertCleared(sessionId))
            sessions.events.emit(ServerEvent.ExecutionStarted(sessionId))
            sessions.events.emit(ServerEvent.ExecutionSucceeded(sessionId))
        }
    }

    private fun Message.serverType(): String = when (this) {
        is Message.User -> "user"
        is Message.Assistant -> "assistant"
        is Message.Synthetic -> "synthetic"
        is Message.System -> "system"
        is Message.SkillInvoked -> "skill"
        is Message.Shell -> "shell"
        is Message.Compaction -> "compaction"
        is Message.Idle -> "idle"
        is Message.AgentSwitched -> "agent-switched"
        is Message.ModelSwitched -> "model-switched"
        is Message.LocationSwitched -> "location-switched"
        is Message.Unknown -> rawType
    }
}
