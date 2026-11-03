package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.LocationWire
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.ToolContent
import io.github.thewisekd.phopencode.core.model.ToolStatus
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * `Session.Message.Info` and its parts, as `GET /api/session/{id}/message` sends them.
 *
 * A message carries no `sessionID` on the wire: the session is the one the list was asked for.
 * Each item is read by its `type`. A type this app does not know becomes `Message.Unknown`, and so
 * does a known type whose shape no longer fits: V2 changes often, and one odd message must not
 * hide a whole conversation. Only an item without an `id` and a `type` fails the page, because
 * nothing at all can be shown for it.
 */

/** What every message has; enough for `Message.Unknown`. */
@Serializable
private class MessageHeadWire(val id: String, val type: String)

/** Read on its own, so that an odd `time` costs only the times. */
@Serializable
private class TimedWire(val time: TimeWire? = null)

/** A file a user attached, as `Prompt.FileAttachment`. Its `data` is not declared, so not kept. */
@Serializable
internal class FileAttachmentWire(
    val source: FileSourceWire? = null,
    val name: String? = null,
    val uri: String? = null,
) {
    @Serializable
    class FileSourceWire(val type: String? = null, val uri: String? = null)

    /** The path or URI the user attached, else the file's name; null when neither was given. */
    fun path(): String? = source?.uri ?: uri ?: name
}

@Serializable
internal class AgentAttachmentWire(val name: String)

@Serializable
internal class SkillAttachmentWire(val id: String)

@Serializable
private class UserWire(
    val text: String,
    val files: List<FileAttachmentWire> = emptyList(),
    val agents: List<AgentAttachmentWire> = emptyList(),
    val skills: List<SkillAttachmentWire> = emptyList(),
)

@Serializable
private class TextWire(val text: String)

@Serializable
private class SkillWire(val name: String)

@Serializable
private class ShellWire(val command: String, val output: OutputWire? = null) {
    @Serializable
    class OutputWire(val output: String? = null)
}

@Serializable
private class AssistantWire(
    val agent: String? = null,
    val model: ModelRefWire? = null,
    val content: List<JsonObject> = emptyList(),
    val finish: String? = null,
    val cost: JsonElement? = null,
    val tokens: TokensWire? = null,
    val error: StructuredErrorWire? = null,
)

@Serializable
private class CompactionWire(
    val status: String? = null,
    val reason: String? = null,
    val model: ModelRefWire? = null,
    val summary: String? = null,
    val cost: JsonElement? = null,
    val tokens: TokensWire? = null,
    val error: StructuredErrorWire? = null,
)

@Serializable
private class IdleWire(val outcome: String? = null, val error: StructuredErrorWire? = null)

@Serializable
private class AgentSwitchedWire(val agent: String, val previous: String? = null)

@Serializable
private class ModelSwitchedWire(val model: ModelRefWire, val previous: ModelRefWire? = null)

@Serializable
private class LocationSwitchedWire(val location: LocationWire)

@Serializable
private class BlockTextWire(val text: String, val time: TimeWire? = null)

@Serializable
private class ToolWire(
    val id: String,
    val name: String,
    val executed: Boolean? = null,
    val state: StateWire,
    val time: TimeWire? = null,
) {
    @Serializable
    class StateWire(
        val status: String,
        val input: JsonElement? = null,
        val content: List<JsonObject> = emptyList(),
        val error: StructuredErrorWire? = null,
        val metadata: JsonObject? = null,
    )
}

@Serializable
private class FileContentWire(val uri: String, val mime: String, val name: String? = null)

/**
 * Maps one list item. Throws [SerializationException] or [IllegalArgumentException] only when the
 * item has no `id` or `type`; the caller turns that into `ApiError.MalformedResponse`.
 */
internal fun JsonObject.toMessage(sessionId: SessionId): Message {
    val head = read(MessageHeadWire.serializer())
    val id = MessageId(head.id)
    val time = orNull { read(TimedWire.serializer()).time }
    val unknown = Message.Unknown(id, sessionId, instantOf(time?.created), head.type)
    return orNull { toKnownMessage(head.type, id, sessionId, time) } ?: unknown
}

private fun <T> JsonObject.read(deserializer: DeserializationStrategy<T>): T =
    ApiJson.decodeFromJsonElement(deserializer, this)

/** [block]'s result, or null when the JSON does not fit it. */
private inline fun <T> orNull(block: () -> T?): T? = try {
    block()
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun JsonObject.toKnownMessage(type: String, id: MessageId, sessionId: SessionId, time: TimeWire?): Message? {
    val createdAt = instantOf(time?.created)
    return when (type) {
        "user" -> read(UserWire.serializer()).let { w ->
            Message.User(
                id = id,
                sessionId = sessionId,
                createdAt = createdAt,
                text = w.text,
                files = w.files.mapNotNull { it.path() },
                agents = w.agents.map { it.name },
                skills = w.skills.map { it.id },
            )
        }
        "assistant" -> read(AssistantWire.serializer()).let { w ->
            Message.Assistant(
                id = id,
                sessionId = sessionId,
                createdAt = createdAt,
                completedAt = instantOf(time?.completed),
                agent = w.agent,
                model = w.model?.toModel(),
                blocks = w.content.map { it.toBlock() },
                usage = usageOf(w.tokens, w.cost),
                finishReason = w.finish,
                error = w.error?.toModel(),
            )
        }
        "synthetic" -> Message.Synthetic(id, sessionId, createdAt, read(TextWire.serializer()).text)
        "system" -> Message.System(id, sessionId, createdAt, read(TextWire.serializer()).text)
        "skill" -> Message.SkillInvoked(id, sessionId, createdAt, read(SkillWire.serializer()).name)
        "shell" -> read(ShellWire.serializer()).let { w ->
            Message.Shell(id, sessionId, createdAt, command = w.command, output = w.output?.output)
        }
        "compaction" -> read(CompactionWire.serializer()).let { w ->
            Message.Compaction(
                id = id,
                sessionId = sessionId,
                createdAt = createdAt,
                status = w.status,
                reason = w.reason,
                model = w.model?.toModel(),
                summary = w.summary,
                usage = usageOf(w.tokens, w.cost),
                error = w.error?.toModel(),
            )
        }
        "idle" -> read(IdleWire.serializer()).let { w ->
            Message.Idle(id, sessionId, createdAt, outcome = w.outcome?.let(::outcomeOf), error = w.error?.toModel())
        }
        "agent-switched" -> read(AgentSwitchedWire.serializer()).let { w ->
            Message.AgentSwitched(id, sessionId, createdAt, agent = w.agent, previous = w.previous)
        }
        "model-switched" -> read(ModelSwitchedWire.serializer()).let { w ->
            Message.ModelSwitched(id, sessionId, createdAt, model = w.model.toModel(), previous = w.previous?.toModel())
        }
        "location-switched" -> Message.LocationSwitched(
            id,
            sessionId,
            createdAt,
            directory = read(LocationSwitchedWire.serializer()).location.directory,
        )
        else -> null
    }
}

private fun outcomeOf(raw: String): SessionOutcome = when (raw) {
    "succeeded" -> SessionOutcome.Succeeded
    "failed" -> SessionOutcome.Failed
    "interrupted" -> SessionOutcome.Interrupted
    else -> SessionOutcome.Unknown(raw)
}

/** One item of an assistant's `content`; one that does not fit becomes `AssistantBlock.Unknown`. */
private fun JsonObject.toBlock(): AssistantBlock {
    val type = (get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: UNKNOWN_TYPE
    val block = orNull {
        when (type) {
            "text" -> read(BlockTextWire.serializer()).let { AssistantBlock.Text(it.text) }
            "reasoning" -> read(BlockTextWire.serializer()).let {
                AssistantBlock.Reasoning(it.text, instantOf(it.time?.created), instantOf(it.time?.completed))
            }
            "tool" -> read(ToolWire.serializer()).toBlock()
            else -> null
        }
    }
    return block ?: AssistantBlock.Unknown(type)
}

private fun ToolWire.toBlock(): AssistantBlock.Tool {
    val metadata = state.metadata.orEmpty()
    return AssistantBlock.Tool(
        id = id,
        name = name,
        status = when (state.status) {
            "streaming" -> ToolStatus.Streaming
            "running" -> ToolStatus.Running
            "completed" -> ToolStatus.Completed
            "error" -> ToolStatus.Failed
            else -> ToolStatus.Unknown(state.status)
        },
        input = state.input?.toDataValue(),
        content = state.content.map { it.toToolContent() },
        error = state.error?.toModel(),
        createdAt = instantOf(time?.created),
        startedAt = instantOf(time?.ran),
        completedAt = instantOf(time?.completed),
        childSessionId = (metadata["sessionID"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::SessionId),
        executed = executed,
        metadata = metadata.mapValues { (_, value) -> value.toDataValue() },
    )
}

private fun JsonObject.toToolContent(): ToolContent {
    val type = (get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: UNKNOWN_TYPE
    val content = orNull {
        when (type) {
            "text" -> ToolContent.Text(read(TextWire.serializer()).text)
            "file" -> read(FileContentWire.serializer()).let { ToolContent.File(it.uri, it.mime, it.name) }
            else -> null
        }
    }
    return content ?: ToolContent.Unknown(type)
}

/** The raw type given to an item that has no `type` text. */
private const val UNKNOWN_TYPE = ""
