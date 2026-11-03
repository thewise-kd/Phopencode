package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.EventDurable
import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.InterruptReason
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.ForkBoundary
import io.github.thewisekd.phopencode.core.model.ForkBoundaryType
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.SessionRevert
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.ToolContent
import io.github.thewisekd.phopencode.core.model.ToolStatus
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import java.math.BigInteger
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A flow recorded by P1.7, read from the fixtures that core/testing ships as resources.
 *
 * The app's decoder (`core/api-http/.../event/decode`) and message mapper (`.../message`) are internal
 * to core/api-http, which this module does not depend on. So this reads the same JSON into the same
 * core/api types, following those two mappers field by field, and maps to [ServerEvent.Unknown] every
 * type they do not decode: `session.status`, `session.synthetic`, `session.instructions.updated` and
 * the non-session events. Forms and permissions keep only what the reducer uses; their fields are not
 * read.
 */
internal class RecordedFlow(val name: String) {
    private val exchanges: List<JsonObject> = lines("exchanges.jsonl").map { it.jsonObject() }

    /** Every event of the flow, in arrival order. */
    val events: List<ServerEvent> = lines("events.jsonl").map { RecordedEvents.decode(it.jsonObject()) }

    /** How many events had arrived when the response labelled [label] did. */
    fun eventsBefore(label: String): Int = exchange(label).int("after_event")!!

    /** The session a message list was asked for, from the path of the response labelled [label]. */
    fun sessionOf(label: String): SessionId =
        SessionId(exchange(label).str("path")!!.substringAfter("/api/session/").substringBefore('/'))

    /** The message list of the response labelled [label], oldest first. The server sends newest first unless asked for `order=asc`. */
    fun messages(label: String): List<Message> {
        val exchange = exchange(label)
        val body = resource(exchange.str("response")!!).jsonObject()
        val sessionId = sessionOf(label)
        val listed = body.array("data")!!.map { RecordedMessages.toMessage(it as JsonObject, sessionId) }
        return if ("order=asc" in exchange.str("path")!!) listed else listed.reversed()
    }

    private fun exchange(label: String): JsonObject = exchanges.single { it.str("label") == label }

    private fun lines(file: String): List<String> = resource(file).lines().filter { it.isNotBlank() }

    private fun resource(file: String): String {
        val path = "/fixtures/$name/$file"
        val stream = RecordedFlow::class.java.getResourceAsStream(path) ?: error("No fixture $path")
        return stream.use { it.readBytes().decodeToString() }
    }
}

private fun String.jsonObject(): JsonObject = Json.parseToJsonElement(this) as JsonObject

private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

private fun JsonObject.number(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content

private fun JsonObject.int(key: String): Int? = number(key)?.toIntOrNull()

private fun JsonObject.long(key: String): Long? = number(key)?.toLongOrNull()

private fun JsonObject.bool(key: String): Boolean? = number(key)?.toBooleanStrictOrNull()

private fun JsonObject.instant(key: String): Instant? =
    number(key)?.toBigDecimalOrNull()?.toLong()?.let(Instant::fromEpochMilliseconds)

private fun JsonObject.objects(key: String): List<JsonObject> = array(key).orEmpty().filterIsInstance<JsonObject>()

private fun JsonObject.strings(key: String): List<String> =
    array(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

private fun JsonObject.sessionId(): SessionId = SessionId(str("sessionID")!!)

private fun JsonObject.messageId(key: String): MessageId? = str(key)?.let(::MessageId)

private fun JsonObject.dataMap(key: String): Map<String, DataValue> =
    obj(key)?.mapValues { (_, value) -> value.toDataValue() }.orEmpty()

private fun JsonElement.toDataValue(): DataValue = when (this) {
    is JsonNull -> DataValue.Null
    is JsonObject -> DataValue.Fields(mapValues { (_, value) -> value.toDataValue() })
    is JsonArray -> DataValue.Items(map { it.toDataValue() })
    is JsonPrimitive -> when {
        isString -> DataValue.Text(content)
        content == "true" -> DataValue.Bool(true)
        content == "false" -> DataValue.Bool(false)
        else -> DataValue.Num(content.toBigDecimal())
    }
}

private fun modelOf(model: JsonObject?): ModelRef? {
    model ?: return null
    return ModelRef(providerId = model.str("providerID") ?: return null, modelId = model.str("id") ?: return null, variant = model.str("variant"))
}

private fun errorOf(error: JsonObject?): StructuredError? {
    error ?: return null
    return StructuredError(
        type = error.str("type") ?: return null,
        message = error.str("message"),
        status = error.int("status"),
        responseBody = error.obj("response")?.str("body"),
        retryAfterMs = error.long("retryAfterMs"),
    )
}

/** `tokens` with `cost` beside it, read exactly as both mappers do: the cost from the number's own text. */
private fun usageOf(owner: JsonObject): TokenUsage? {
    val tokens = owner.obj("tokens") ?: return null
    val cache = tokens.obj("cache")
    fun count(source: JsonObject?, key: String) = source?.number(key)?.toBigDecimal()?.toBigIntegerExact() ?: BigInteger.ZERO
    return TokenUsage(
        input = count(tokens, "input"),
        output = count(tokens, "output"),
        reasoning = count(tokens, "reasoning"),
        cacheRead = count(cache, "read"),
        cacheWrite = count(cache, "write"),
        cost = owner.number("cost")?.let(Cost::parse),
    )
}

private fun toolContentOf(content: JsonObject): ToolContent = when (val type = content.str("type").orEmpty()) {
    "text" -> ToolContent.Text(content.str("text")!!)
    "file" -> ToolContent.File(content.str("uri")!!, content.str("mime")!!, content.str("name"))
    else -> ToolContent.Unknown(type)
}

private object RecordedEvents {
    private const val MESSAGE = "assistantMessageID"

    fun decode(envelope: JsonObject): ServerEvent {
        val type = envelope.str("type")!!
        val meta = EventMeta(
            id = envelope.str("id"),
            createdAt = envelope.instant("created"),
            location = envelope.obj("location")?.str("directory")?.let(::Location),
            metadata = envelope.dataMap("metadata"),
            durable = envelope.obj("durable")?.let {
                EventDurable(it.str("aggregateID")!!, it.long("seq")!!, it.int("version")!!)
            },
        )
        val d = envelope.obj("data") ?: JsonObject(emptyMap())
        return session(type, d, meta) ?: interaction(type, d, meta) ?: when (type) {
            "server.connected" -> ServerEvent.Connected(meta)
            else -> ServerEvent.Unknown(type, meta)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun session(type: String, d: JsonObject, m: EventMeta): ServerEvent? = when (type) {
        "session.created" -> ServerEvent.SessionCreated(
            sessionId = d.sessionId(),
            slug = d.str("slug"),
            version = d.str("version"),
            projectId = d.str("projectID"),
            location = d.obj("location")?.str("directory")?.let(::Location),
            subpath = d.str("subpath"),
            title = d.str("title"),
            parentId = d.str("parentID")?.let(::SessionId),
            agent = d.str("agent"),
            metadata = d.dataMap("metadata"),
            meta = m,
        )
        "session.deleted" -> ServerEvent.SessionDeleted(d.sessionId(), m)
        "session.renamed" -> ServerEvent.SessionRenamed(d.sessionId(), d.str("title"), m)
        "session.archived" -> ServerEvent.SessionArchived(d.sessionId(), m)
        "session.unarchived" -> ServerEvent.SessionUnarchived(d.sessionId(), m)
        "session.forked" -> ServerEvent.SessionForked(
            d.sessionId(),
            d.str("parentID")?.let(::SessionId),
            d.obj("boundary")?.let { b ->
                val boundaryType = when (val raw = b.str("type")!!) {
                    "through" -> ForkBoundaryType.Through
                    "before" -> ForkBoundaryType.Before
                    else -> ForkBoundaryType.Unknown(raw)
                }
                ForkBoundary(boundaryType, MessageId(b.str("messageID")!!))
            },
            m,
        )
        "session.execution.started" -> ServerEvent.ExecutionStarted(d.sessionId(), m)
        "session.execution.succeeded" -> ServerEvent.ExecutionSucceeded(d.sessionId(), m)
        "session.execution.failed" -> ServerEvent.ExecutionFailed(d.sessionId(), errorOf(d.obj("error")), m)
        "session.execution.interrupted" -> ServerEvent.ExecutionInterrupted(
            d.sessionId(),
            d.str("reason")?.let {
                when (it) {
                    "user" -> InterruptReason.User
                    "shutdown" -> InterruptReason.Shutdown
                    else -> InterruptReason.Unknown(it)
                }
            },
            m,
        )
        "session.retry.scheduled" -> ServerEvent.RetryScheduled(
            d.sessionId(), d.messageId(MESSAGE), d.int("attempt"), d.instant("at"), errorOf(d.obj("error")), m,
        )
        "session.step.started" -> ServerEvent.StepStarted(
            d.sessionId(), d.messageId(MESSAGE), d.str("agent"), modelOf(d.obj("model")), d.str("snapshot"), d.instant("started"), m,
        )
        "session.step.streamed" -> ServerEvent.StepStreamed(d.sessionId(), d.messageId(MESSAGE), m)
        "session.step.ended" -> ServerEvent.StepEnded(
            d.sessionId(), d.messageId(MESSAGE), usageOf(d), d.str("finish"), d.str("rawFinish"), d.str("snapshot"), d.strings("files"), m,
        )
        "session.step.failed" -> ServerEvent.StepFailed(
            d.sessionId(), d.messageId(MESSAGE), errorOf(d.obj("error")), usageOf(d), d.str("rawFinish"), d.str("snapshot"), d.strings("files"), m,
        )
        "session.text.started" -> ServerEvent.TextStarted(d.sessionId(), d.messageId(MESSAGE)!!, d.int("ordinal")!!, m)
        "session.text.delta" -> ServerEvent.TextDelta(d.sessionId(), d.messageId(MESSAGE)!!, d.int("ordinal")!!, d.str("delta")!!, m)
        "session.text.ended" -> ServerEvent.TextEnded(d.sessionId(), d.messageId(MESSAGE)!!, d.int("ordinal")!!, d.str("text")!!, m)
        "session.reasoning.started" -> ServerEvent.ReasoningStarted(d.sessionId(), d.messageId(MESSAGE)!!, d.int("ordinal")!!, m)
        "session.reasoning.delta" ->
            ServerEvent.ReasoningDelta(d.sessionId(), d.messageId(MESSAGE)!!, d.int("ordinal")!!, d.str("delta")!!, m)
        "session.reasoning.ended" ->
            ServerEvent.ReasoningEnded(d.sessionId(), d.messageId(MESSAGE)!!, d.int("ordinal")!!, d.str("text")!!, m)
        "session.tool.input.started" -> ServerEvent.ToolInputStarted(d.sessionId(), d.messageId(MESSAGE), d.str("id")!!, d.str("name"), m)
        "session.tool.input.delta" -> ServerEvent.ToolInputDelta(d.sessionId(), d.messageId(MESSAGE), d.str("id")!!, d.str("delta")!!, m)
        "session.tool.input.ended" -> ServerEvent.ToolInputEnded(d.sessionId(), d.messageId(MESSAGE), d.str("id")!!, d.str("text"), m)
        "session.tool.called" ->
            ServerEvent.ToolCalled(d.sessionId(), d.messageId(MESSAGE), d.str("id")!!, d["input"]?.toDataValue(), d.bool("executed"), m)
        "session.tool.progress" -> ServerEvent.ToolProgress(
            sessionId = d.sessionId(),
            messageId = d.messageId(MESSAGE),
            id = d.str("id")!!,
            childSessionId = d.obj("metadata")?.str("sessionID")?.let(::SessionId),
            status = d.obj("metadata")?.str("status"),
            metadata = d.dataMap("metadata"),
            meta = m,
        )
        "session.tool.success" -> ServerEvent.ToolSucceeded(
            d.sessionId(), d.messageId(MESSAGE), d.str("id")!!, d.objects("content").map(::toolContentOf), d.dataMap("metadata"),
            d.bool("executed"), m,
        )
        "session.tool.failed" ->
            ServerEvent.ToolFailed(d.sessionId(), d.messageId(MESSAGE), d.str("id")!!, errorOf(d.obj("error")), d.bool("executed"), m)
        "session.usage.updated" -> ServerEvent.UsageUpdated(d.sessionId(), usageOf(d)!!, m)
        "session.compaction.started" -> ServerEvent.CompactionStarted(d.sessionId(), d.str("reason"), d.messageId("inputID"), m)
        "session.compaction.delta" -> ServerEvent.CompactionDelta(d.sessionId(), d.str("text")!!, m)
        "session.compaction.ended" ->
            ServerEvent.CompactionEnded(d.sessionId(), d.str("reason"), modelOf(d.obj("model")), d.str("text"), usageOf(d), m)
        "session.compaction.failed" ->
            ServerEvent.CompactionFailed(d.sessionId(), d.str("reason"), errorOf(d.obj("error")), d.messageId("inputID"), usageOf(d), m)
        "session.revert.staged" -> d.obj("revert")!!.let { r ->
            ServerEvent.RevertStaged(
                d.sessionId(),
                SessionRevert(
                    messageId = MessageId(r.str("messageID")!!),
                    snapshot = r.str("snapshot"),
                    files = r.objects("files").mapNotNull { f ->
                        val path = f.str("file") ?: return@mapNotNull null
                        val status = when (val raw = f.str("status").orEmpty()) {
                            "added" -> VcsFileStatus.Added
                            "modified" -> VcsFileStatus.Modified
                            "deleted" -> VcsFileStatus.Deleted
                            else -> VcsFileStatus.Unknown(raw)
                        }
                        FileDiff(VcsFile(path, status, f.long("additions"), f.long("deletions")), f.str("patch"))
                    },
                ),
                m,
            )
        }
        "session.revert.cleared" -> ServerEvent.RevertCleared(d.sessionId(), m)
        "session.revert.committed" -> ServerEvent.RevertCommitted(d.sessionId(), d.messageId("to"), m)
        "session.shell.started" -> ServerEvent.ShellStarted(d.sessionId(), d.obj("shell")?.str("id"), d.obj("shell")?.str("command"), m)
        "session.shell.ended" -> ServerEvent.ShellEnded(
            d.sessionId(), d.obj("shell")?.str("id"), d.obj("shell")?.str("command"), d.obj("output")?.str("output"),
            d.obj("shell")?.int("exit"), m,
        )
        "session.inbox.enqueued" -> ServerEvent.InboxEnqueued(inboxItemOf(d), m)
        "session.inbox.delivered" -> ServerEvent.InboxDelivered(d.sessionId(), d.messageId("inboxID")!!, m)
        "session.inbox.cancelled" -> ServerEvent.InboxCancelled(d.sessionId(), d.messageId("inboxID")!!, m)
        "session.inbox.delivery.changed" ->
            ServerEvent.InboxDeliveryChanged(d.sessionId(), d.messageId("inboxID")!!, d.str("delivery")?.let(::deliveryOf), m)
        "session.agent.selected" -> ServerEvent.AgentSelected(d.sessionId(), d.str("agent")!!, d.str("previous"), m)
        "session.model.selected" -> ServerEvent.ModelSelected(d.sessionId(), modelOf(d.obj("model"))!!, m)
        else -> null
    }

    private fun deliveryOf(raw: String): PromptDelivery? = when (raw) {
        "steer" -> PromptDelivery.Steer
        "queue" -> PromptDelivery.Queue
        else -> null
    }

    private fun inboxItemOf(d: JsonObject): InboxItem {
        val item = d.obj("item")!!
        val payload = item.obj("payload")
        val content = when (val type = item.str("type")!!) {
            "user" -> InboxContent.User(
                text = payload!!.str("text")!!,
                files = payload.objects("files").mapNotNull { it.str("name") },
                agents = payload.objects("agents").mapNotNull { it.str("name") },
                skills = payload.objects("skills").mapNotNull { it.str("name") },
            )
            "synthetic" -> InboxContent.Synthetic(payload!!.str("text")!!)
            "compaction" -> InboxContent.Compaction
            else -> InboxContent.Unknown(type)
        }
        return InboxItem(MessageId(d.str("inboxID")!!), d.sessionId(), null, content, item.str("delivery")?.let(::deliveryOf))
    }

    /** Forms and permissions with only their identity: the reducer tracks which are pending, not their fields. */
    private fun interaction(type: String, d: JsonObject, m: EventMeta): ServerEvent? = when (type) {
        "form.created" -> d.obj("form")!!.let { form ->
            val kind = form.obj("metadata")?.str("kind")
            ServerEvent.FormCreated(
                Form(
                    id = FormId(form.str("id")!!),
                    sessionId = form.sessionId(),
                    kind = if (kind == "question") FormKind.Question else FormKind.Other(kind),
                    title = form.str("title"),
                ),
                m,
            )
        }
        "form.replied" -> ServerEvent.FormReplied(d.sessionId(), FormId(d.str("id")!!), d.dataMap("answer"), m)
        "form.cancelled" -> ServerEvent.FormCancelled(d.sessionId(), FormId(d.str("id")!!), m)
        "permission.asked" -> ServerEvent.PermissionAsked(
            PermissionRequest(
                id = PermissionRequestId(d.str("id")!!),
                sessionId = d.sessionId(),
                action = d.str("action")!!,
                resources = d.strings("resources"),
            ),
            m,
        )
        "permission.replied" -> ServerEvent.PermissionReplied(
            d.sessionId(),
            PermissionRequestId(d.str("requestID")!!),
            when (d.str("reply")) {
                "once" -> PermissionDecision.Once
                "always" -> PermissionDecision.Always
                "reject" -> PermissionDecision.Reject
                else -> null
            },
            m,
        )
        else -> null
    }
}

/** Follows `MessageWire.toMessage`: the list item as the app's message list holds it. */
private object RecordedMessages {
    fun toMessage(item: JsonObject, sessionId: SessionId): Message {
        val id = MessageId(item.str("id")!!)
        val time = item.obj("time")
        val createdAt = time?.instant("created")
        return when (val type = item.str("type")!!) {
            "user" -> Message.User(
                id = id,
                sessionId = sessionId,
                createdAt = createdAt,
                text = item.str("text")!!,
                files = item.objects("files").mapNotNull { f -> f.obj("source")?.str("uri") ?: f.str("uri") ?: f.str("name") },
                agents = item.objects("agents").map { it.str("name")!! },
                skills = item.objects("skills").map { it.str("id")!! },
            )
            "assistant" -> Message.Assistant(
                id = id,
                sessionId = sessionId,
                createdAt = createdAt,
                completedAt = time?.instant("completed"),
                agent = item.str("agent"),
                model = modelOf(item.obj("model")),
                blocks = item.objects("content").map(::blockOf),
                usage = listUsageOf(item),
                finishReason = item.str("finish"),
                error = errorOf(item.obj("error")),
            )
            "synthetic" -> Message.Synthetic(id, sessionId, createdAt, item.str("text")!!)
            "system" -> Message.System(id, sessionId, createdAt, item.str("text")!!)
            "shell" -> Message.Shell(id, sessionId, createdAt, item.str("command")!!, item.obj("output")?.str("output"))
            "compaction" -> Message.Compaction(
                id = id,
                sessionId = sessionId,
                createdAt = createdAt,
                status = item.str("status"),
                reason = item.str("reason"),
                model = modelOf(item.obj("model")),
                summary = item.str("summary"),
                usage = listUsageOf(item),
                error = errorOf(item.obj("error")),
            )
            "idle" -> Message.Idle(
                id = id,
                sessionId = sessionId,
                createdAt = createdAt,
                outcome = when (val raw = item.str("outcome")) {
                    null -> null
                    "succeeded" -> SessionOutcome.Succeeded
                    "failed" -> SessionOutcome.Failed
                    "interrupted" -> SessionOutcome.Interrupted
                    else -> SessionOutcome.Unknown(raw)
                },
                error = errorOf(item.obj("error")),
            )
            "agent-switched" -> Message.AgentSwitched(id, sessionId, createdAt, item.str("agent")!!, item.str("previous"))
            "model-switched" -> Message.ModelSwitched(id, sessionId, createdAt, modelOf(item.obj("model"))!!, modelOf(item.obj("previous")))
            else -> Message.Unknown(id, sessionId, createdAt, type)
        }
    }

    /** As `usageOf` of the message mapper: a cost with no `tokens` still makes a report, with zero counts. */
    private fun listUsageOf(item: JsonObject): TokenUsage? =
        usageOf(item) ?: item.number("cost")?.let { TokenUsage(cost = Cost.parse(it)) }

    private fun blockOf(block: JsonObject): AssistantBlock = when (val type = block.str("type").orEmpty()) {
        "text" -> AssistantBlock.Text(block.str("text")!!)
        "reasoning" -> AssistantBlock.Reasoning(
            block.str("text")!!,
            block.obj("time")?.instant("created"),
            block.obj("time")?.instant("completed"),
        )
        "tool" -> {
            val state = block.obj("state")!!
            val metadata = state.obj("metadata").orEmpty()
            AssistantBlock.Tool(
                id = block.str("id")!!,
                name = block.str("name")!!,
                status = when (val raw = state.str("status")!!) {
                    "streaming" -> ToolStatus.Streaming
                    "running" -> ToolStatus.Running
                    "completed" -> ToolStatus.Completed
                    "error" -> ToolStatus.Failed
                    else -> ToolStatus.Unknown(raw)
                },
                input = state["input"]?.toDataValue(),
                content = state.objects("content").map(::toolContentOf),
                error = errorOf(state.obj("error")),
                createdAt = block.obj("time")?.instant("created"),
                startedAt = block.obj("time")?.instant("ran"),
                completedAt = block.obj("time")?.instant("completed"),
                childSessionId = (metadata["sessionID"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::SessionId),
                executed = block.bool("executed"),
                metadata = metadata.mapValues { (_, value) -> value.toDataValue() },
            )
        }
        else -> AssistantBlock.Unknown(type)
    }
}
