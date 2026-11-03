package io.github.thewisekd.phopencode.core.session.reducer

import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionEvent
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionFork
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.ToolStatus
import kotlin.time.Instant

/**
 * Folds the event stream of one session into a [SessionState].
 *
 * Events of other sessions are skipped: a subagent's child session gets a reducer of its own. So are
 * [ServerEvent.Unknown], the connection events, and `session.shell.*`, which name no message.
 *
 * Built to give the same result whatever the order and repetition of events:
 *
 * - A repeated event (same ID, else same durable position) is skipped.
 * - Events are matched on the IDs they carry: message ID, block kind and ordinal, tool call ID,
 *   compaction (`inputID`), inbox item. An event for something not seen yet creates it; the event
 *   that starts it fills it in.
 * - Deltas, text and tool input alike, are joined by their time. Once a block's `*.ended` text
 *   arrives, it replaces the deltas and later deltas are ignored; of two `*.ended` events the newer
 *   wins. A tool's status only moves forward, and of two endings of a call the newer wins.
 * - Status: a step start counts as a running write, so an older retry or start cannot override it.
 *   Tool progress metadata follows event order too.
 * - A value that later events replace (title, status, outcome, reported usage, revert, agent, model,
 *   an item's delivery) is replaced only by an event that is not older, by durable `seq`, else by
 *   time; see [Stamp].
 * - Messages are ordered by their own time, else the earliest event seen for them; blocks by when they
 *   started. Ties keep arrival order.
 * - A delivery change or a compaction failure that arrives before what it names is kept and applied
 *   once that appears.
 *
 * Execution: [SessionState.status] follows the newest execution event, while the session's outcome
 * and [SessionState.error] follow the newest execution end. So the late end of an earlier execution
 * still records how that one ended, without ending the one running now.
 *
 * Compaction deltas and ends name no compaction; see [CompactionLedger] for how they are placed and
 * what is dropped rather than guessed.
 *
 * A committed revert removes its boundary message for good, and hides every message whose time (its
 * own, else the earliest event seen for it) is at or after the boundary's and before the commit
 * event's. That holds for messages that arrive after the commit too, and messages from after the
 * commit stay. Hidden messages still take their events, and the window is applied to their earliest
 * known time each time the state is read: a message whose only known time came from a late event, such
 * as its end, shows again once an earlier event places it before the boundary. So a message is never
 * removed on a time that an earlier event can still correct. The
 * boundary is the commit's `to`; without one, it is the message of the latest stage before the commit,
 * by `seq` else time, provided no clear lies between them; until such a stage arrives, the commit waits.
 * The boundary's time is its own if held, else the time of the first event naming it that arrives
 * later. What cannot be decided:
 *
 * - Until the boundary's time is known, only the boundary itself is removed; a message of the
 *   reverted turn that arrives before then stays until it is.
 * - A commit with no time has no upper bound: it removes the boundary and what is held after it at
 *   that moment, and of later arrivals only the boundary.
 * - A message with no time at all is never removed by time.
 * - A commit without `to` waits while any stage or clear cannot be ordered against it. Once it has
 *   been applied, a stage or clear arriving later between that stage and the commit cannot undo it.
 *
 * Deltas without an ID or a durable position cannot be told from new ones, so a repeat of one is
 * applied again. Recorded deltas all have IDs.
 *
 * Not thread-safe: confine an instance to one coroutine. It never logs.
 *
 * @param session What is known of the session already, such as from `SessionApi.get`.
 * @param messages A fetched message list, oldest first. Its messages count as final until an event
 *   replaces them.
 */
class SessionReducer(
    val sessionId: SessionId,
    session: Session? = null,
    messages: List<Message> = emptyList(),
) {
    private sealed class Entry(val id: MessageId, val arrival: Long) {
        var firstSeenAt: Instant? = null

        abstract val createdAt: Instant?

        class Fixed(val message: Message, arrival: Long) : Entry(message.id, arrival) {
            override val createdAt: Instant?
                get() = message.createdAt
        }

        class Assistant(val draft: AssistantDraft, arrival: Long) : Entry(draft.id, arrival) {
            override val createdAt: Instant?
                get() = draft.createdAt
        }

        class Compaction(val draft: CompactionDraft, arrival: Long) : Entry(draft.id, arrival) {
            override val createdAt: Instant?
                get() = draft.createdAt
        }
    }

    private class Delivered(val at: Instant?, val stamp: Stamp)

    private class Stage(val stamp: Stamp, val boundary: MessageId)

    private class PendingCommit(val stamp: Stamp, val at: Instant?)

    /** A committed revert: messages timed in [from, until) are gone. [from] is null until the boundary's time is known. */
    private class RevertWindow(val boundary: MessageId, var from: Instant?, val until: Instant)

    private val seen = SeenEvents()
    private var arrivals = 0L
    private val entries = LinkedHashMap<MessageId, Entry>()
    private val removed = HashSet<MessageId>()

    private var info: Session = session?.copy(id = sessionId) ?: Session(id = sessionId)
    private var deleted = false
    private var status: SessionStatus = SessionStatus.Idle
    private var error: StructuredError? = null

    private var titleStamp: Stamp? = null
    private var archiveStamp: Stamp? = null
    private var statusStamp: Stamp? = null
    private var outcomeStamp: Stamp? = null
    private var usageStamp: Stamp? = null
    private var agentStamp: Stamp? = null
    private var modelStamp: Stamp? = null
    private var revertStamp: Stamp? = null

    private val inbox = LinkedHashMap<MessageId, InboxItem>()

    /** The stamp of the delivery each pending item holds, from its enqueue or its last change. */
    private val deliveryStamps = HashMap<MessageId, Stamp>()

    /** Delivery changes that arrived before their item. */
    private val earlyDeliveries = HashMap<MessageId, Pair<PromptDelivery, Stamp>>()
    private val deliveredEarly = HashMap<MessageId, Delivered>()
    private val cancelled = HashSet<MessageId>()

    private val compactions = CompactionLedger()

    /** Compactions a revert removed. */
    private val goneCompactions = HashMap<MessageId, CompactionDraft>()

    private val stages = ArrayList<Stage>()
    private val clears = ArrayList<Stamp>()
    private val pendingCommits = ArrayList<PendingCommit>()
    private val revertWindows = ArrayList<RevertWindow>()

    private val forms = LinkedHashMap<FormId, Form>()
    private val settledForms = HashSet<FormId>()
    private val permissions = LinkedHashMap<PermissionRequestId, PermissionRequest>()
    private val settledPermissions = HashSet<PermissionRequestId>()

    private var snapshot: SessionState? = null

    init {
        for (message in messages) {
            if (message.sessionId != sessionId || message.id in entries) continue
            entries[message.id] = when (message) {
                is Message.Assistant -> Entry.Assistant(AssistantDraft.seeded(message), arrivals++)
                is Message.Compaction ->
                    Entry.Compaction(CompactionDraft(message.id, sessionId).also { it.seeded = message }, arrivals++)
                else -> Entry.Fixed(message, arrivals++)
            }
        }
    }

    /** The session as of the events applied so far. */
    val state: SessionState
        get() = snapshot ?: build().also { snapshot = it }

    /**
     * Applies one event.
     *
     * @return False when the event was skipped: another session's, unknown, repeated, or one that names
     *   no message where it needs one. True when it was used, even if it changed nothing.
     */
    fun apply(event: ServerEvent): Boolean {
        if (event !is SessionEvent || event.sessionId != sessionId) return false
        if (!seen.firstTime(event.meta)) return false
        if (learnBoundaryTime(event)) snapshot = null
        goneCompactionStarted(event)
        if (!placeable(event)) return false
        snapshot = null
        reduce(event, Stamp.of(event.meta, sessionId))
        return true
    }

    fun applyAll(events: Iterable<ServerEvent>) {
        events.forEach(::apply)
    }

    /** Whether the event names what it applies to, and that is not something a revert removed. */
    private fun placeable(event: SessionEvent): Boolean {
        val message = messageOf(event) ?: return event.javaClass !in NEEDS_MESSAGE
        return message !in removed
    }

    private fun messageOf(event: SessionEvent): MessageId? = when (event) {
        is ServerEvent.StepStarted -> event.messageId
        is ServerEvent.StepStreamed -> event.messageId
        is ServerEvent.StepEnded -> event.messageId
        is ServerEvent.StepFailed -> event.messageId
        is ServerEvent.TextStarted -> event.messageId
        is ServerEvent.TextDelta -> event.messageId
        is ServerEvent.TextEnded -> event.messageId
        is ServerEvent.ReasoningStarted -> event.messageId
        is ServerEvent.ReasoningDelta -> event.messageId
        is ServerEvent.ReasoningEnded -> event.messageId
        is ServerEvent.ToolInputStarted -> event.messageId
        is ServerEvent.ToolInputDelta -> event.messageId
        is ServerEvent.ToolInputEnded -> event.messageId
        is ServerEvent.ToolCalled -> event.messageId
        is ServerEvent.ToolProgress -> event.messageId
        is ServerEvent.ToolSucceeded -> event.messageId
        is ServerEvent.ToolFailed -> event.messageId
        is ServerEvent.InboxDelivered -> event.inboxId
        is ServerEvent.CompactionStarted -> event.inputId
        is ServerEvent.CompactionFailed -> event.inputId
        else -> null
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun reduce(event: SessionEvent, stamp: Stamp) {
        val at = event.meta.createdAt
        when (event) {
            is ServerEvent.SessionCreated -> created(event, stamp)
            is ServerEvent.SessionDeleted -> deleted = true
            is ServerEvent.SessionRenamed -> if (newer(stamp, titleStamp)) {
                titleStamp = stamp
                info = info.copy(title = event.title)
            }
            is ServerEvent.SessionArchived -> archive(true, stamp)
            is ServerEvent.SessionUnarchived -> archive(false, stamp)
            is ServerEvent.SessionForked -> forked(event)

            is ServerEvent.SessionStatusChanged -> setStatus(stamp, event.status)
            is ServerEvent.ExecutionStarted -> setStatus(stamp, SessionStatus.Running)
            is ServerEvent.ExecutionSucceeded -> executionEnded(stamp, SessionOutcome.Succeeded, null)
            is ServerEvent.ExecutionFailed -> executionEnded(stamp, SessionOutcome.Failed, event.error)
            is ServerEvent.ExecutionInterrupted -> executionEnded(stamp, SessionOutcome.Interrupted, null)
            is ServerEvent.RetryScheduled ->
                setStatus(stamp, SessionStatus.Retrying(event.attempt, event.error?.message, event.nextAttemptAt))

            is ServerEvent.StepStarted -> assistant(event.messageId!!, at).run {
                if (createdAt == null) createdAt = event.startedAt ?: at
                if (agent == null) agent = event.agent
                if (model == null) model = event.model
                // A step runs, so an older retry or start must not win over it.
                setStatus(stamp, SessionStatus.Running)
            }
            is ServerEvent.StepStreamed -> assistant(event.messageId!!, at)
            is ServerEvent.StepEnded -> assistant(event.messageId!!, at).run {
                if (newer(stamp, endStamp)) {
                    endStamp = stamp
                    usage = event.usage
                    finishReason = event.finishReason
                    error = null
                    completedAt = at
                }
            }
            is ServerEvent.StepFailed -> assistant(event.messageId!!, at).run {
                if (newer(stamp, endStamp)) {
                    endStamp = stamp
                    usage = event.usage
                    finishReason = FINISH_ERROR
                    error = event.error
                    completedAt = at
                }
            }

            is ServerEvent.TextStarted -> start(text(event.messageId, TextKind.Text, event.ordinal, at), at)
            is ServerEvent.TextDelta -> text(event.messageId, TextKind.Text, event.ordinal, at).addDelta(at, event.delta)
            is ServerEvent.TextEnded -> end(text(event.messageId, TextKind.Text, event.ordinal, at), event.text, at, stamp)
            is ServerEvent.ReasoningStarted -> start(text(event.messageId, TextKind.Reasoning, event.ordinal, at), at)
            is ServerEvent.ReasoningDelta ->
                text(event.messageId, TextKind.Reasoning, event.ordinal, at).addDelta(at, event.delta)
            is ServerEvent.ReasoningEnded ->
                end(text(event.messageId, TextKind.Reasoning, event.ordinal, at), event.text, at, stamp)

            is ServerEvent.ToolInputStarted -> tool(event.messageId!!, event.id, at).run {
                if (name.isNullOrEmpty()) name = event.name
                if (startedAt == null) startedAt = at
            }
            is ServerEvent.ToolInputDelta -> tool(event.messageId!!, event.id, at).addInputDelta(at, event.delta)
            is ServerEvent.ToolInputEnded -> tool(event.messageId!!, event.id, at).endInput(event.text, stamp)
            is ServerEvent.ToolCalled -> tool(event.messageId!!, event.id, at).run {
                call(event.input)
                if (calledAt == null) calledAt = at
                if (calledExecuted == null) calledExecuted = event.executed
                advance(ToolStatus.Running)
            }
            is ServerEvent.ToolProgress -> tool(event.messageId!!, event.id, at).run {
                if (childSessionId == null) childSessionId = event.childSessionId
                if (!ended && newer(stamp, progressStamp)) {
                    progressStamp = stamp
                    metadata = event.metadata
                }
                advance(ToolStatus.Running)
            }
            is ServerEvent.ToolSucceeded -> tool(event.messageId!!, event.id, at).run {
                if (newer(stamp, endStamp)) {
                    endStamp = stamp
                    status = ToolStatus.Completed
                    error = null
                    content = event.content
                    metadata = event.metadata
                    endedExecuted = event.executed
                    completedAt = at
                    (event.metadata["sessionID"] as? DataValue.Text)?.let { childSessionId = SessionId(it.value) }
                }
            }
            is ServerEvent.ToolFailed -> tool(event.messageId!!, event.id, at).run {
                if (newer(stamp, endStamp)) {
                    endStamp = stamp
                    status = ToolStatus.Failed
                    error = event.error
                    content = emptyList()
                    // The stored state of a failed call has no metadata.
                    metadata = emptyMap()
                    endedExecuted = event.executed
                    completedAt = at
                }
            }

            is ServerEvent.UsageUpdated -> if (newer(stamp, usageStamp)) {
                usageStamp = stamp
                info = info.copy(usage = event.usage)
            }

            is ServerEvent.FormCreated -> if (event.form.id !in settledForms) forms[event.form.id] = event.form
            is ServerEvent.FormReplied -> settle(forms, settledForms, event.formId)
            is ServerEvent.FormCancelled -> settle(forms, settledForms, event.formId)
            is ServerEvent.PermissionAsked ->
                if (event.request.id !in settledPermissions) permissions[event.request.id] = event.request
            is ServerEvent.PermissionReplied -> settle(permissions, settledPermissions, event.requestId)

            is ServerEvent.CompactionStarted -> compaction(event.inputId ?: return, at)?.run {
                if (startStamp == null) {
                    startStamp = stamp
                    startedAt = at
                }
                if (reason == null) reason = event.reason
            }
            is ServerEvent.CompactionDelta -> compactions.delta(stamp, at, event.text)
            is ServerEvent.CompactionEnded -> compactions.end(stamp, event)
            is ServerEvent.CompactionFailed -> {
                val id = event.inputId
                if (id == null) {
                    compactions.unnamedFailure(stamp, event)
                } else {
                    compaction(id, at)?.run {
                        if (newer(stamp, failureStamp)) {
                            failureStamp = stamp
                            failure = event
                        }
                    }
                }
            }

            is ServerEvent.RevertStaged -> {
                setRevert(stamp) { info.copy(revert = event.revert) }
                stages += Stage(stamp, event.revert.messageId)
                resolveCommits()
            }
            is ServerEvent.RevertCleared -> {
                setRevert(stamp) { info.copy(revert = null) }
                clears += stamp
                resolveCommits()
            }
            is ServerEvent.RevertCommitted -> {
                val to = event.to
                if (to != null) commitRevert(to, at) else pendingCommits += PendingCommit(stamp, at)
                resolveCommits()
                setRevert(stamp) { info.copy(revert = null) }
            }

            is ServerEvent.InboxEnqueued -> enqueued(event.item, at, stamp)
            is ServerEvent.InboxDelivered -> delivered(event.inboxId, at, stamp)
            is ServerEvent.InboxCancelled -> {
                inbox.remove(event.inboxId)
                if (event.inboxId !in entries) cancelled += event.inboxId
            }
            is ServerEvent.InboxDeliveryChanged -> deliveryChanged(event.inboxId, event.delivery, stamp)

            is ServerEvent.AgentSelected -> if (newer(stamp, agentStamp)) {
                agentStamp = stamp
                info = info.copy(agent = event.agent)
            }
            is ServerEvent.ModelSelected -> if (newer(stamp, modelStamp)) {
                modelStamp = stamp
                info = info.copy(model = event.model)
            }

            is ServerEvent.ShellStarted, is ServerEvent.ShellEnded -> Unit
        }
    }

    private fun newer(stamp: Stamp, current: Stamp?): Boolean = !stamp.isOlderThan(current)

    private fun created(event: ServerEvent.SessionCreated, stamp: Stamp) {
        info = info.copy(
            parentId = event.parentId ?: info.parentId,
            directory = event.location?.directory ?: info.directory,
            projectId = event.projectId ?: info.projectId,
            subpath = event.subpath ?: info.subpath,
            createdAt = info.createdAt ?: event.meta.createdAt,
            metadata = info.metadata.ifEmpty { event.metadata },
        )
        if (event.title != null && newer(stamp, titleStamp)) {
            titleStamp = stamp
            info = info.copy(title = event.title)
        }
        if (event.agent != null && newer(stamp, agentStamp)) {
            agentStamp = stamp
            info = info.copy(agent = event.agent)
        }
    }

    private fun archive(archived: Boolean, stamp: Stamp) {
        if (!newer(stamp, archiveStamp)) return
        archiveStamp = stamp
        info = info.copy(archived = archived)
    }

    private fun forked(event: ServerEvent.SessionForked) {
        val parent = event.parentId ?: return
        info = info.copy(
            parentId = info.parentId ?: parent,
            fork = info.fork ?: event.boundary?.let { SessionFork(parent, it) },
        )
    }

    private fun setStatus(stamp: Stamp, next: SessionStatus) {
        if (!newer(stamp, statusStamp)) return
        statusStamp = stamp
        status = next
    }

    /** The outcome is history, kept apart from the status, which a newer execution may already own. */
    private fun executionEnded(stamp: Stamp, outcome: SessionOutcome, failure: StructuredError?) {
        setStatus(stamp, SessionStatus.Idle)
        if (!newer(stamp, outcomeStamp)) return
        outcomeStamp = stamp
        info = info.copy(outcome = outcome)
        error = failure
    }

    private inline fun setRevert(stamp: Stamp, update: () -> Session) {
        if (!newer(stamp, revertStamp)) return
        revertStamp = stamp
        info = update()
    }

    private fun <K> settle(pending: MutableMap<K, *>, settled: MutableSet<K>, key: K) {
        pending.remove(key)
        settled += key
    }

    // Messages

    private fun seen(entry: Entry, at: Instant?) {
        entry.firstSeenAt = earliest(entry.firstSeenAt, at)
    }

    private fun assistant(id: MessageId, at: Instant?): AssistantDraft {
        val entry = entries.getOrPut(id) { Entry.Assistant(AssistantDraft(id, sessionId), arrivals++) }
        seen(entry, at)
        return when (entry) {
            is Entry.Assistant -> entry.draft
            // An ID the list had as another type: keep that message, and fold the event into a detached
            // draft that nothing shows.
            is Entry.Fixed, is Entry.Compaction -> AssistantDraft(id, sessionId)
        }
    }

    /** The compaction [id], created if new; null when the ID belongs to another kind of message. */
    private fun compaction(id: MessageId, at: Instant?): CompactionDraft? {
        if (id !in entries) {
            inbox.remove(id)
            deliveryStamps.remove(id)
            val draft = CompactionDraft(id, sessionId)
            deliveredEarly.remove(id)?.let {
                draft.deliveredAt = it.at
                draft.deliveredStamp = it.stamp
            }
            entries[id] = Entry.Compaction(draft, arrivals++)
        }
        val entry = entries.getValue(id)
        seen(entry, at)
        return (entry as? Entry.Compaction)?.draft
    }

    private fun text(id: MessageId, kind: TextKind, ordinal: Int, at: Instant?): TextDraft =
        assistant(id, at).text(kind, ordinal).also { it.seen(at) }

    private fun tool(id: MessageId, callId: String, at: Instant?): ToolDraft =
        assistant(id, at).tool(callId).also { it.seen(at) }

    private fun start(block: TextDraft, at: Instant?) {
        if (block.startedAt == null) block.startedAt = at
    }

    private fun end(block: TextDraft, text: String, at: Instant?, stamp: Stamp) {
        if (!newer(stamp, block.endStamp)) return
        block.endStamp = stamp
        block.final = text
        block.completedAt = at
    }

    private fun enqueued(item: InboxItem, at: Instant?, stamp: Stamp) {
        val early = earlyDeliveries.remove(item.id)
        if (item.id in cancelled || item.id in entries || item.id in removed) return
        deliveredEarly.remove(item.id)?.let {
            deliver(item, it.at, it.stamp)
            return
        }
        var pending = if (item.createdAt == null) item.copy(createdAt = at) else item
        var held = stamp
        if (early != null && newer(early.second, stamp)) {
            pending = pending.copy(delivery = early.first)
            held = early.second
        }
        inbox[item.id] = pending
        deliveryStamps[item.id] = held
    }

    /** A change to an item not enqueued yet is kept for its enqueue; the newer of the two wins there. */
    private fun deliveryChanged(id: MessageId, delivery: PromptDelivery?, stamp: Stamp) {
        if (delivery == null) return
        val item = inbox[id]
        if (item != null) {
            if (!newer(stamp, deliveryStamps[id])) return
            inbox[id] = item.copy(delivery = delivery)
            deliveryStamps[id] = stamp
        } else if (id !in entries && id !in removed && id !in cancelled) {
            val early = earlyDeliveries[id]
            if (early == null || newer(stamp, early.second)) earlyDeliveries[id] = delivery to stamp
        }
    }

    private fun delivered(id: MessageId, at: Instant?, stamp: Stamp) {
        val item = inbox.remove(id)
        deliveryStamps.remove(id)
        earlyDeliveries.remove(id)
        when {
            item != null -> deliver(item, at, stamp)
            id !in entries -> deliveredEarly[id] = Delivered(at, stamp)
            else -> (entries[id] as? Entry.Compaction)?.draft?.let { draft ->
                // A compaction its start or failure created first: the list times it at its delivery.
                if (draft.deliveredStamp == null) {
                    draft.deliveredAt = at
                    draft.deliveredStamp = stamp
                }
            }
        }
    }

    /** The message a delivered item becomes; its time is the delivery's, as in the message list. */
    private fun deliver(item: InboxItem, at: Instant?, stamp: Stamp) {
        if (item.id in entries) return
        val entry = when (val content = item.content) {
            is InboxContent.User -> Entry.Fixed(
                Message.User(item.id, sessionId, at, content.text, content.files, content.agents, content.skills),
                arrivals++,
            )
            is InboxContent.Synthetic -> Entry.Fixed(Message.Synthetic(item.id, sessionId, at, content.text), arrivals++)
            InboxContent.Compaction -> Entry.Compaction(
                CompactionDraft(item.id, sessionId).also {
                    it.deliveredAt = at
                    it.deliveredStamp = stamp
                },
                arrivals++,
            )
            is InboxContent.Unknown -> return
        }
        entries[item.id] = entry.also { seen(it, at) }
    }

    // Reverts

    /** Applies each waiting commit without `to` whose stage can now be told; see the class KDoc. */
    private fun resolveCommits() {
        val iterator = pendingCommits.iterator()
        while (iterator.hasNext()) {
            val commit = iterator.next()
            val boundary = stageBefore(commit.stamp) ?: continue
            iterator.remove()
            commitRevert(boundary, commit.at)
        }
    }

    /** The latest stage before [commit] with no clear after it; null while that cannot be decided. */
    private fun stageBefore(commit: Stamp): MessageId? {
        var best: Stage? = null
        for (stage in stages) {
            val order = stage.stamp.compareWith(commit) ?: return null
            if (order >= 0) continue
            val current = best
            best = when {
                current == null -> stage
                else -> when (val versus = stage.stamp.compareWith(current.stamp) ?: return null) {
                    0 -> return null
                    else -> if (versus > 0) stage else current
                }
            }
        }
        val stage = best ?: return null
        for (clear in clears) {
            val afterStage = clear.compareWith(stage.stamp) ?: return null
            val beforeCommit = clear.compareWith(commit) ?: return null
            if (afterStage > 0 && beforeCommit < 0) return null
        }
        return stage.boundary
    }

    private fun commitRevert(boundary: MessageId, committedAt: Instant?) {
        val from = entries[boundary]?.let(::timeOf)
        if (committedAt == null) {
            // No upper bound: only what is held now can be placed after the boundary.
            val ordered = orderedEntries()
            val index = ordered.indexOfFirst { it.id == boundary }
            val gone = if (index >= 0) ordered.drop(index).map { it.id } else listOf(boundary)
            gone.forEach(::drop)
            return
        }
        drop(boundary)
        revertWindows += RevertWindow(boundary, from, committedAt)
    }

    /** Takes the boundary's time from the first event naming it after the commit. True if one was learned. */
    private fun learnBoundaryTime(event: SessionEvent): Boolean {
        if (revertWindows.isEmpty()) return false
        val message = messageOf(event) ?: return false
        val at = event.meta.createdAt ?: return false
        var learned = false
        for (window in revertWindows) {
            if (window.boundary == message && window.from == null) {
                window.from = at
                learned = true
            }
        }
        return learned
    }

    /** Whether a committed revert covers [entry] at its earliest known time; see the class KDoc. */
    private fun reverted(entry: Entry): Boolean {
        if (revertWindows.isEmpty()) return false
        val time = timeOf(entry) ?: return false
        return revertWindows.any { window -> window.from.let { it != null && time >= it } && time < window.until }
    }

    /**
     * Removes a message and keeps later events from bringing it back. A pending prompt goes too, since
     * its delivery will be rejected. A compaction stays known to [compactions] as gone, so that its
     * unnamed delta or end is dropped rather than matched to the one before it.
     */
    private fun drop(id: MessageId) {
        (entries.remove(id) as? Entry.Compaction)?.let { goneCompactions[id] = it.draft }
        removed += id
        inbox.remove(id)
        deliveryStamps.remove(id)
        earlyDeliveries.remove(id)
        deliveredEarly.remove(id)
    }

    /** A start of a compaction already gone, which still marks where its unnamed events belong. */
    private fun goneCompactionStarted(event: SessionEvent) {
        val started = event as? ServerEvent.CompactionStarted ?: return
        val id = started.inputId ?: return
        if (id !in removed) return
        val draft = goneCompactions.getOrPut(id) { CompactionDraft(id, sessionId) }
        if (draft.startStamp == null) {
            draft.startStamp = Stamp.of(event.meta, sessionId)
            draft.startedAt = event.meta.createdAt
            snapshot = null
        }
    }

    private fun timeOf(entry: Entry): Instant? = entry.createdAt ?: entry.firstSeenAt

    private fun orderedEntries(): List<Entry> = entries.values.sortedWith(ENTRY_ORDER)

    private fun build(): SessionState {
        val (hidden, shown) = orderedEntries().partition(::reverted)
        val compacted = compactions.messages(
            shown.filterIsInstance<Entry.Compaction>().map { it.draft },
            goneCompactions.values + hidden.filterIsInstance<Entry.Compaction>().map { it.draft },
        )
        return SessionState(
            session = info,
            deleted = deleted,
            status = status,
            error = error,
            messages = shown.map { entry ->
                when (entry) {
                    is Entry.Fixed -> entry.message
                    is Entry.Assistant -> entry.draft.toMessage()
                    is Entry.Compaction -> compacted.getValue(entry.id)
                }
            },
            inbox = inbox.values.toList(),
            forms = forms.values.toList(),
            permissions = permissions.values.toList(),
        )
    }

    private companion object {
        const val FINISH_ERROR = "error"

        val ENTRY_ORDER: Comparator<Entry> = orderOf({ it.createdAt ?: it.firstSeenAt }, { it.arrival })

        /** Events that are nothing without their message. */
        val NEEDS_MESSAGE: Set<Class<*>> = setOf(
            ServerEvent.StepStarted::class.java,
            ServerEvent.StepStreamed::class.java,
            ServerEvent.StepEnded::class.java,
            ServerEvent.StepFailed::class.java,
            ServerEvent.ToolInputStarted::class.java,
            ServerEvent.ToolInputDelta::class.java,
            ServerEvent.ToolInputEnded::class.java,
            ServerEvent.ToolCalled::class.java,
            ServerEvent.ToolProgress::class.java,
            ServerEvent.ToolSucceeded::class.java,
            ServerEvent.ToolFailed::class.java,
        )
    }
}
