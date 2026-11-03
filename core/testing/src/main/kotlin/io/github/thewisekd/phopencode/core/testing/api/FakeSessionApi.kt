package io.github.thewisekd.phopencode.core.testing.api

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CreateSessionRequest
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.InterruptReason
import io.github.thewisekd.phopencode.core.api.Page
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionApi
import io.github.thewisekd.phopencode.core.api.SessionParentFilter
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.api.SessionUpdate
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.model.ForkBoundary
import io.github.thewisekd.phopencode.core.model.ForkBoundaryType
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionRule
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionFork
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import kotlin.time.Instant

/**
 * Sessions in memory. Lists keep the order sessions were added in, so that a test does not depend
 * on clocks; the default order is newest first.
 *
 * A change made through the API sends its event, as a server would. [seed] and [setActivity] arrange
 * the state and send nothing.
 */
class FakeSessionApi(
    private val control: FakeApiControl = FakeApiControl(),
    internal val events: FakeEventApi = FakeEventApi(control),
) : SessionApi {
    private val lock = Any()
    private val sessions = LinkedHashMap<SessionId, Session>()
    private val activity = LinkedHashMap<SessionId, SessionActivity>()
    private val rules = HashMap<SessionId, List<PermissionRule>>()
    private val history = HashMap<SessionId, MutableList<Message>>()
    private val inbox = LinkedHashMap<MessageId, InboxItem>()
    private val deleteActions = mutableListOf<(SessionId) -> Unit>()
    private var nextId = 1

    /** The items accepted and not delivered yet, oldest first. */
    val inboxItems: List<InboxItem>
        get() = synchronized(lock) { inbox.values.toList() }

    /** Adds a session, or replaces the one with the same ID. Sends no event. */
    fun seed(session: Session) {
        synchronized(lock) {
            sessions[session.id] = session
            history.getOrPut(session.id) { mutableListOf() }
        }
    }

    /** Sets what [activeStatuses] says about [id]; null makes it idle. Sends no event. */
    fun setActivity(id: SessionId, status: SessionActivity?) {
        synchronized(lock) {
            if (status == null) activity.remove(id) else activity[id] = status
        }
    }

    /** The rules last set with [update], or null. */
    fun permissionsOf(id: SessionId): List<PermissionRule>? = synchronized(lock) { rules[id] }

    /** Lets the fake that owns more of a session's state drop it, under the state lock. */
    internal fun onDelete(action: (SessionId) -> Unit) {
        synchronized(lock) { deleteActions += action }
    }

    /** The state lock, for the test's own arranging and reading, which works after close too. */
    internal fun <T> withLock(block: () -> T): T = synchronized(lock, block)

    /**
     * The state lock for a call. A call can wait here while the client closes, so this is the last
     * check before it touches the state. Close does not take the lock: a call already past the
     * check finishes, as one that won the race.
     */
    internal fun <T> serving(block: () -> T): T = synchronized(lock) {
        control.checkOpen()
        block()
    }

    internal fun require(id: SessionId): Session = sessions[id] ?: throw ApiException(ApiError.NotFound)

    internal fun peek(id: SessionId): Session? = sessions[id]

    internal fun put(session: Session) {
        sessions[session.id] = session
    }

    /** The messages of [id], oldest first, for the fake that owns them to change. */
    internal fun historyOf(id: SessionId): MutableList<Message> = history.getOrPut(id) { mutableListOf() }

    /** The next generated ID that no session has, seeded ones included. */
    private fun newSessionId(): SessionId =
        generateSequence { SessionId("ses_fake_${nextId++}") }.first { it !in sessions }

    /** The next generated ID that no message or inbox item has, in any session, seeded ones included. */
    internal fun newMessageId(): MessageId = generateSequence { MessageId("msg_fake_${nextId++}") }
        .first { id -> id !in inbox && history.values.none { messages -> messages.any { it.id == id } } }

    internal fun now(): Instant = Instant.fromEpochSeconds(nextId.toLong())

    /** Adds an item to the inbox and tells the stream, whose copy has no time. */
    internal fun enqueue(sessionId: SessionId, content: InboxContent, delivery: PromptDelivery?): InboxItem {
        val item = InboxItem(newMessageId(), sessionId, now(), content, delivery)
        inbox[item.id] = item
        events.emit(ServerEvent.InboxEnqueued(item.copy(createdAt = null)))
        return item
    }

    internal fun takeInbox(id: MessageId): InboxItem = inbox.remove(id) ?: throw ApiException(ApiError.NotFound)

    /** Ends what runs in [id], with the event of [reason]. Whether anything ran. */
    internal fun interrupt(id: SessionId, reason: InterruptReason): Boolean {
        if (activity.remove(id) == null) return false
        sessions[id]?.let { sessions[id] = it.copy(outcome = SessionOutcome.Interrupted) }
        events.emit(ServerEvent.ExecutionInterrupted(id, reason))
        return true
    }

    override suspend fun create(request: CreateSessionRequest): Session = control.call("sessions.create") {
        serving {
            request.parentId?.let(::require)
            val session = Session(
                id = newSessionId(),
                parentId = request.parentId,
                title = request.title,
                directory = request.location?.directory,
                agent = request.agent,
                model = request.model,
                createdAt = now(),
                updatedAt = now(),
                metadata = request.metadata.orEmpty(),
            )
            seed(session)
            request.permissions?.let { rules[session.id] = it }
            events.emit(
                ServerEvent.SessionCreated(
                    sessionId = session.id,
                    location = request.location,
                    title = session.title,
                    parentId = session.parentId,
                    agent = session.agent,
                    metadata = session.metadata,
                ),
            )
            session
        }
    }

    override suspend fun list(query: SessionQuery): Page<Session> = control.call("sessions.list") {
        serving {
            val matching = sessions.values.filter { it.matches(query) }
            when (query.order ?: SortOrder.Descending) {
                SortOrder.Ascending -> matching
                SortOrder.Descending -> matching.reversed()
            }.page(query.limit, query.cursor)
        }
    }

    private fun Session.matches(query: SessionQuery): Boolean {
        val parentMatches = when (val filter = query.parent) {
            SessionParentFilter.Any -> true
            SessionParentFilter.RootsOnly -> isRoot
            is SessionParentFilter.ChildrenOf -> parentId == filter.parentId
        }
        return parentMatches &&
            (query.archived == null || query.archived == archived) &&
            (query.search == null || title?.contains(query.search.orEmpty(), ignoreCase = true) == true) &&
            (query.directory == null || query.directory == directory) &&
            (query.project == null || query.project == projectId) &&
            (query.subpath == null || query.subpath == subpath)
    }

    override suspend fun get(id: SessionId): Session = control.call("sessions.get") { serving { require(id) } }

    override suspend fun delete(id: SessionId): Unit = control.call("sessions.delete") {
        serving {
            require(id)
            val family = family(id)
            family.forEach { member ->
                sessions.remove(member)
                activity.remove(member)
                history.remove(member)
                rules.remove(member)
                inbox.values.removeAll { it.sessionId == member }
                deleteActions.forEach { it(member) }
            }
            family.forEach { events.emit(ServerEvent.SessionDeleted(it)) }
        }
    }

    /** [id] and everything below it, children first. */
    private fun family(id: SessionId): List<SessionId> =
        sessions.values.filter { it.parentId == id }.flatMap { family(it.id) } + id

    override suspend fun update(id: SessionId, update: SessionUpdate): Unit = control.call("sessions.update") {
        serving {
            val old = require(id)
            val new = old.copy(
                title = update.title ?: old.title,
                archived = update.archived ?: old.archived,
                metadata = update.metadata ?: old.metadata,
            )
            sessions[id] = new
            update.permissions?.let { rules[id] = it }
            if (new.title != old.title) events.emit(ServerEvent.SessionRenamed(id, new.title))
            if (new.archived != old.archived) {
                events.emit(if (new.archived) ServerEvent.SessionArchived(id) else ServerEvent.SessionUnarchived(id))
            }
        }
    }

    override suspend fun stop(id: SessionId): Boolean = control.call("sessions.stop") {
        serving {
            require(id)
            interrupt(id, InterruptReason.User)
        }
    }

    override suspend fun fork(id: SessionId, before: MessageId?): Session = control.call("sessions.fork") {
        serving {
            val source = require(id)
            val all = historyOf(id)
            val cut = if (before == null) all.size else all.indexOfFirst { it.id == before }
            if (cut < 0) throw ApiException(ApiError.NotFound)
            val boundary = when {
                before != null -> ForkBoundary(ForkBoundaryType.Before, before)
                else -> all.lastOrNull()?.let { ForkBoundary(ForkBoundaryType.Through, it.id) }
            }
            val copy = source.copy(
                id = newSessionId(),
                fork = boundary?.let { SessionFork(id, it) },
                revert = null,
                createdAt = now(),
                updatedAt = now(),
            )
            seed(copy)
            historyOf(copy.id) += all.take(cut).map { it.inSession(copy.id) }
            events.emit(ServerEvent.SessionForked(copy.id, parentId = id, boundary = boundary))
            copy
        }
    }

    override suspend fun compact(id: SessionId): InboxItem = control.call("sessions.compact") {
        serving {
            require(id)
            enqueue(id, InboxContent.Compaction, PromptDelivery.Steer)
        }
    }

    override suspend fun activeStatuses(): Map<SessionId, SessionActivity> =
        control.call("sessions.activeStatuses") { serving { activity.toMap() } }
}

private fun Message.inSession(id: SessionId): Message = when (this) {
    is Message.User -> copy(sessionId = id)
    is Message.Assistant -> copy(sessionId = id)
    is Message.Synthetic -> copy(sessionId = id)
    is Message.System -> copy(sessionId = id)
    is Message.SkillInvoked -> copy(sessionId = id)
    is Message.Shell -> copy(sessionId = id)
    is Message.Compaction -> copy(sessionId = id)
    is Message.Idle -> copy(sessionId = id)
    is Message.AgentSwitched -> copy(sessionId = id)
    is Message.ModelSwitched -> copy(sessionId = id)
    is Message.LocationSwitched -> copy(sessionId = id)
    is Message.Unknown -> copy(sessionId = id)
}
