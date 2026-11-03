package io.github.thewisekd.phopencode.core.api_http.event.decode

import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.InterruptReason
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.ForkBoundary
import io.github.thewisekd.phopencode.core.model.ForkBoundaryType
import io.github.thewisekd.phopencode.core.model.SessionRevert
import io.github.thewisekd.phopencode.core.model.ToolContent
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import kotlinx.serialization.json.JsonObject

/** The server's name for the assistant message an event belongs to. */
private const val MESSAGE = "assistantMessageID"

/** Decoders for `session.*` events, keyed by their wire type. */
internal val sessionDecoders: Map<String, (JsonObject, EventMeta) -> ServerEvent> = mapOf(
    // Lifecycle
    "session.created" to { d, m ->
        ServerEvent.SessionCreated(
            sessionId = d.sessionId(),
            slug = d.str("slug"),
            version = d.str("version"),
            projectId = d.str("projectID"),
            location = d.obj("location")?.str("directory")?.let(::Location),
            subpath = d.str("subpath"),
            title = d.str("title"),
            parentId = d.optionalSessionId("parentID"),
            agent = d.str("agent"),
            metadata = d.dataMap("metadata"),
            meta = m,
        )
    },
    "session.deleted" to { d, m -> ServerEvent.SessionDeleted(d.sessionId(), m) },
    "session.renamed" to { d, m -> ServerEvent.SessionRenamed(d.sessionId(), d.str("title"), m) },
    "session.archived" to { d, m -> ServerEvent.SessionArchived(d.sessionId(), m) },
    "session.unarchived" to { d, m -> ServerEvent.SessionUnarchived(d.sessionId(), m) },
    "session.forked" to { d, m ->
        ServerEvent.SessionForked(d.sessionId(), d.optionalSessionId("parentID"), forkBoundaryOf(d.obj("boundary")), m)
    },

    // Execution
    "session.execution.started" to { d, m -> ServerEvent.ExecutionStarted(d.sessionId(), m) },
    "session.execution.succeeded" to { d, m -> ServerEvent.ExecutionSucceeded(d.sessionId(), m) },
    "session.execution.failed" to { d, m -> ServerEvent.ExecutionFailed(d.sessionId(), d.error(), m) },
    "session.execution.interrupted" to { d, m ->
        ServerEvent.ExecutionInterrupted(d.sessionId(), d.str("reason")?.let(::interruptReasonOf), m)
    },
    "session.retry.scheduled" to { d, m ->
        ServerEvent.RetryScheduled(d.sessionId(), d.messageId(MESSAGE), d.int("attempt"), d.instant("at"), d.error(), m)
    },

    // Steps
    "session.step.started" to { d, m ->
        ServerEvent.StepStarted(
            sessionId = d.sessionId(),
            messageId = d.messageId(MESSAGE),
            agent = d.str("agent"),
            model = modelRefOf(d.obj("model")),
            snapshot = d.str("snapshot"),
            startedAt = d.instant("started"),
            meta = m,
        )
    },
    "session.step.streamed" to { d, m -> ServerEvent.StepStreamed(d.sessionId(), d.messageId(MESSAGE), m) },
    "session.step.ended" to { d, m ->
        ServerEvent.StepEnded(
            sessionId = d.sessionId(),
            messageId = d.messageId(MESSAGE),
            usage = d.usage(),
            finishReason = d.str("finish"),
            rawFinish = d.str("rawFinish"),
            snapshot = d.str("snapshot"),
            files = d.strings("files"),
            meta = m,
        )
    },
    "session.step.failed" to { d, m ->
        ServerEvent.StepFailed(
            sessionId = d.sessionId(),
            messageId = d.messageId(MESSAGE),
            error = d.error(),
            usage = d.usage(),
            rawFinish = d.str("rawFinish"),
            snapshot = d.str("snapshot"),
            files = d.strings("files"),
            meta = m,
        )
    },

    // Text and reasoning: a delta has `delta`, an ended block has `text`, and neither stands in for the other.
    "session.text.started" to { d, m ->
        ServerEvent.TextStarted(d.sessionId(), d.requireMessageId(MESSAGE), d.requireInt("ordinal"), m)
    },
    "session.text.delta" to { d, m ->
        ServerEvent.TextDelta(d.sessionId(), d.requireMessageId(MESSAGE), d.requireInt("ordinal"), d.requireStr("delta"), m)
    },
    "session.text.ended" to { d, m ->
        ServerEvent.TextEnded(d.sessionId(), d.requireMessageId(MESSAGE), d.requireInt("ordinal"), d.requireStr("text"), m)
    },
    "session.reasoning.started" to { d, m ->
        ServerEvent.ReasoningStarted(d.sessionId(), d.requireMessageId(MESSAGE), d.requireInt("ordinal"), m)
    },
    "session.reasoning.delta" to { d, m ->
        ServerEvent.ReasoningDelta(d.sessionId(), d.requireMessageId(MESSAGE), d.requireInt("ordinal"), d.requireStr("delta"), m)
    },
    "session.reasoning.ended" to { d, m ->
        ServerEvent.ReasoningEnded(d.sessionId(), d.requireMessageId(MESSAGE), d.requireInt("ordinal"), d.requireStr("text"), m)
    },

    // Tools
    "session.tool.input.started" to { d, m ->
        ServerEvent.ToolInputStarted(d.sessionId(), d.messageId(MESSAGE), d.requireStr("id"), d.str("name"), m)
    },
    "session.tool.input.delta" to { d, m ->
        ServerEvent.ToolInputDelta(d.sessionId(), d.messageId(MESSAGE), d.requireStr("id"), d.requireStr("delta"), m)
    },
    "session.tool.input.ended" to { d, m ->
        ServerEvent.ToolInputEnded(d.sessionId(), d.messageId(MESSAGE), d.requireStr("id"), d.str("text"), m)
    },
    "session.tool.called" to { d, m ->
        ServerEvent.ToolCalled(d.sessionId(), d.messageId(MESSAGE), d.requireStr("id"), d.data("input"), d.bool("executed"), m)
    },
    "session.tool.progress" to { d, m ->
        val metadata = d.obj("metadata")
        ServerEvent.ToolProgress(
            sessionId = d.sessionId(),
            messageId = d.messageId(MESSAGE),
            id = d.requireStr("id"),
            childSessionId = metadata?.optionalSessionId("sessionID"),
            status = metadata?.str("status"),
            metadata = d.dataMap("metadata"),
            meta = m,
        )
    },
    "session.tool.success" to { d, m ->
        ServerEvent.ToolSucceeded(
            sessionId = d.sessionId(),
            messageId = d.messageId(MESSAGE),
            id = d.requireStr("id"),
            content = d.objects("content").map(::toolContentOf),
            metadata = d.dataMap("metadata"),
            executed = d.bool("executed"),
            meta = m,
        )
    },
    "session.tool.failed" to { d, m ->
        ServerEvent.ToolFailed(d.sessionId(), d.messageId(MESSAGE), d.requireStr("id"), d.error(), d.bool("executed"), m)
    },

    // Usage: the session's running total, which has no meaning without its counters.
    "session.usage.updated" to { d, m -> ServerEvent.UsageUpdated(d.sessionId(), d.usage() ?: throw MalformedEvent(), m) },

    // Compaction
    "session.compaction.started" to { d, m ->
        ServerEvent.CompactionStarted(d.sessionId(), d.str("reason"), d.messageId("inputID"), m)
    },
    "session.compaction.delta" to { d, m -> ServerEvent.CompactionDelta(d.sessionId(), d.requireStr("text"), m) },
    "session.compaction.ended" to { d, m ->
        ServerEvent.CompactionEnded(d.sessionId(), d.str("reason"), modelRefOf(d.obj("model")), d.str("text"), d.usage(), m)
    },
    "session.compaction.failed" to { d, m ->
        ServerEvent.CompactionFailed(d.sessionId(), d.str("reason"), d.error(), d.messageId("inputID"), d.usage(), m)
    },

    // Revert
    "session.revert.staged" to { d, m -> ServerEvent.RevertStaged(d.sessionId(), revertOf(d.requireObj("revert")), m) },
    "session.revert.cleared" to { d, m -> ServerEvent.RevertCleared(d.sessionId(), m) },
    "session.revert.committed" to { d, m -> ServerEvent.RevertCommitted(d.sessionId(), d.messageId("to"), m) },

    // Shell
    "session.shell.started" to { d, m ->
        val shell = d.obj("shell")
        ServerEvent.ShellStarted(d.sessionId(), shell?.str("id"), shell?.str("command"), m)
    },
    "session.shell.ended" to { d, m ->
        val shell = d.obj("shell")
        ServerEvent.ShellEnded(
            sessionId = d.sessionId(),
            shellId = shell?.str("id"),
            command = shell?.str("command"),
            output = d.obj("output")?.str("output"),
            exitCode = shell?.int("exit"),
            meta = m,
        )
    },

    // Inbox
    "session.inbox.enqueued" to { d, m -> ServerEvent.InboxEnqueued(inboxItemOf(d), m) },
    "session.inbox.delivered" to { d, m -> ServerEvent.InboxDelivered(d.sessionId(), d.requireMessageId("inboxID"), m) },
    "session.inbox.cancelled" to { d, m -> ServerEvent.InboxCancelled(d.sessionId(), d.requireMessageId("inboxID"), m) },
    "session.inbox.delivery.changed" to { d, m ->
        ServerEvent.InboxDeliveryChanged(d.sessionId(), d.requireMessageId("inboxID"), d.str("delivery")?.let(::deliveryOf), m)
    },

    // Selection
    "session.agent.selected" to { d, m -> ServerEvent.AgentSelected(d.sessionId(), d.requireStr("agent"), d.str("previous"), m) },
    "session.model.selected" to { d, m ->
        ServerEvent.ModelSelected(d.sessionId(), modelRefOf(d.obj("model")) ?: throw MalformedEvent(), m)
    },
)

private fun forkBoundaryOf(boundary: JsonObject?): ForkBoundary? {
    if (boundary == null) return null
    val raw = boundary.str("type") ?: return null
    val type = when (raw) {
        "through" -> ForkBoundaryType.Through
        "before" -> ForkBoundaryType.Before
        else -> ForkBoundaryType.Unknown(raw)
    }
    val messageId = boundary.messageId("messageID") ?: return null
    return ForkBoundary(type, messageId)
}

private fun interruptReasonOf(raw: String): InterruptReason = when (raw) {
    "user" -> InterruptReason.User
    "shutdown" -> InterruptReason.Shutdown
    else -> InterruptReason.Unknown(raw)
}

/** `{type: text, text}` or `{type: file, uri, mime, name?}`; anything else, or either one incomplete, is unknown. */
private fun toolContentOf(content: JsonObject): ToolContent {
    val type = content.str("type").orEmpty()
    return when (type) {
        "text" -> content.str("text")?.let(ToolContent::Text)
        "file" -> {
            val uri = content.str("uri")
            val mime = content.str("mime")
            if (uri != null && mime != null) ToolContent.File(uri, mime, content.str("name")) else null
        }
        else -> null
    } ?: ToolContent.Unknown(type)
}

/** `{messageID, snapshot?, files[{file, status, additions?, deletions?, patch?}]}`. Entries with no path are left out. */
private fun revertOf(revert: JsonObject): SessionRevert = SessionRevert(
    messageId = revert.requireMessageId("messageID"),
    snapshot = revert.str("snapshot"),
    files = revert.objects("files").mapNotNull(::fileDiffOf),
)

private fun fileDiffOf(entry: JsonObject): FileDiff? {
    val path = entry.str("file") ?: return null
    val file = VcsFile(path, vcsStatusOf(entry.str("status").orEmpty()), entry.long("additions"), entry.long("deletions"))
    return FileDiff(file = file, patch = entry.str("patch"))
}

private fun vcsStatusOf(raw: String): VcsFileStatus = when (raw) {
    "added" -> VcsFileStatus.Added
    "modified" -> VcsFileStatus.Modified
    "deleted" -> VcsFileStatus.Deleted
    "renamed" -> VcsFileStatus.Renamed
    "untracked" -> VcsFileStatus.Untracked
    else -> VcsFileStatus.Unknown(raw)
}

internal fun deliveryOf(raw: String): PromptDelivery? = when (raw) {
    "steer" -> PromptDelivery.Steer
    "queue" -> PromptDelivery.Queue
    else -> null
}

/** `{inboxID, sessionID, item{type, payload, delivery}}`: the item's ID and session sit beside it. */
private fun inboxItemOf(data: JsonObject): InboxItem {
    val item = data.requireObj("item")
    return InboxItem(
        id = data.requireMessageId("inboxID"),
        sessionId = data.sessionId(),
        createdAt = null,
        content = inboxContentOf(item),
        delivery = item.str("delivery")?.let(::deliveryOf),
    )
}

/**
 * `user` and `synthetic` require `payload.text`, as the spec does. Attachments are listed by name:
 * a file's `data` is never read.
 */
private fun inboxContentOf(item: JsonObject): InboxContent {
    val payload = item.obj("payload")
    return when (val type = item.requireStr("type")) {
        "user" -> {
            val user = payload ?: throw MalformedEvent()
            InboxContent.User(
                text = user.requireStr("text"),
                files = user.objects("files").mapNotNull { it.str("name") },
                agents = user.objects("agents").mapNotNull { it.str("name") },
                skills = user.objects("skills").mapNotNull { it.str("name") },
            )
        }
        "synthetic" -> InboxContent.Synthetic((payload ?: throw MalformedEvent()).requireStr("text"))
        "compaction" -> InboxContent.Compaction
        else -> InboxContent.Unknown(type)
    }
}
