package io.github.thewisekd.phopencode.feature.chat.logic.subagent

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.MessageQuery
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.Page
import io.github.thewisekd.phopencode.core.api.PageCursor
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionParentFilter
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.ToolStatus
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentNode
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentStatus
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentTools
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The subagent tree of the route's session (P7.6), as `SubagentContract` describes it. Every route
 * shows the same state, about [ChatSession.sessionId].
 *
 * - Followed only while [session] is [ChatAvailability.Live], and always through
 *   [ChatSession.call], which refuses any server but the bound one. One call holds the event stream
 *   and does all the loading on the same client. A client closed under it is followed again once the
 *   chat's state changes; it never spins. Another server or a lost session clears the tree.
 * - A full load (on every `ServerEvent.Connected`, a new subagent anywhere in the tree, and
 *   [SubagentIntent.Retry]) reads the header, the active list and then the tree, all pages of each,
 *   and publishes it whole; a failure keeps what was shown and sets [SubagentState.error].
 * - The tree is guarded: a session is shown once even if the parent links make a cycle, the depth
 *   stops at [MAX_DEPTH] and the whole tree at [MAX_NODES]; a list stops at [MAX_PAGES] or a cursor
 *   it has seen.
 * - An execution event of a subagent sets its status at once; an end, or a tool event, then reads
 *   that one subagent again for its times, tokens and tools.
 * - A running subagent's duration counts to [clock] and is refreshed every second, by a ticker that
 *   runs only while one runs.
 *
 * Nothing runs in the constructor: work starts in [scope] after a `yield()`. Nothing here logs, and
 * the state prints titles and tool names masked.
 *
 * @param route The destination's route; its session is [session]'s.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 * @param clock A running subagent's duration counts to its `now()`.
 */
class DefaultSubagentPresenter internal constructor(
    @Suppress("unused") private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
    private val clock: Clock,
) : SubagentPresenter {
    constructor(route: ChatRoute, scope: CoroutineScope, session: ChatSession) :
        this(route, scope, session, Clock.System)

    private sealed interface Work {
        data class Event(val event: ServerEvent) : Work

        data object Retry : Work
    }

    private val mutableState = MutableStateFlow(SubagentState())

    override val state: StateFlow<SubagentState> = mutableState.asStateFlow()

    private val eventChannel = Channel<SubagentEvent>(Channel.BUFFERED)

    override val events: Flow<SubagentEvent> = eventChannel.receiveAsFlow()

    /** Events of the stream and retries, handled one batch at a time by the follower. */
    private val work = Channel<Work>(Channel.UNLIMITED)

    private val root: SessionId
        get() = session.sessionId

    init {
        scope.launch {
            // The screen's scope runs immediately: let construction finish before any work.
            yield()
            launch { tick() }
            session.state.map { it.availability }.distinctUntilChanged().collectLatest { availability ->
                when (availability) {
                    ChatAvailability.Live -> follow()
                    ChatAvailability.NotFound, ChatAvailability.OtherServer ->
                        mutableState.value = SubagentState(loading = false)
                    else -> Unit
                }
            }
        }
    }

    override fun onIntent(intent: SubagentIntent) {
        when (intent) {
            SubagentIntent.OpenList -> eventChannel.trySend(SubagentEvent.OpenList(root))
            is SubagentIntent.Open -> eventChannel.trySend(SubagentEvent.OpenChild(intent.sessionId))
            SubagentIntent.OpenParent -> {
                val current = mutableState.value
                val parent = current.parentId ?: return
                eventChannel.trySend(SubagentEvent.OpenParent(parent, current.parentIsRoot))
            }
            SubagentIntent.Retry -> work.trySend(Work.Retry)
            SubagentIntent.Close -> eventChannel.trySend(SubagentEvent.Close)
        }
    }

    /** Refreshes the durations of the running subagents every second, only while one runs. */
    private suspend fun tick() {
        mutableState.map { it.runningCount > 0 }.distinctUntilChanged().collectLatest { running ->
            while (running) {
                delay(TICK)
                val now = clock.now()
                mutableState.update { it.copy(subagents = it.subagents.withRunningDurations(now)) }
            }
        }
    }

    /** Follows the tree while the chat is live; cancelled when it is not. */
    private suspend fun follow() {
        while (true) {
            val before = session.state.value
            val failure = try {
                session.call { client -> watch(client) }
                ApiError.Closed
            } catch (e: ApiException) {
                e.error
            }
            if (failure == ApiError.Closed) {
                // The client went, or the chat refused: try again once the chat has seen a change.
                session.state.first { it !== before }
            } else {
                // The stream failed for good: wait for the user's retry.
                mutableState.update { it.copy(loading = false, error = ChatFailure.of(failure)) }
                while (work.receive() != Work.Retry) Unit
            }
        }
    }

    /** Collects the events on [client] and handles them and the retries, until the stream fails. */
    private suspend fun watch(client: OpencodeClient) {
        coroutineScope {
            launch { client.events.events(EVENT_TYPES).collect { work.send(Work.Event(it)) } }
            while (true) {
                val batch = mutableListOf(work.receive())
                while (true) batch += work.tryReceive().getOrNull() ?: break
                handle(client, batch)
            }
        }
    }

    private suspend fun handle(client: OpencodeClient, batch: List<Work>) {
        var reload = false
        val refresh = LinkedHashSet<SessionId>()
        for (item in batch) {
            val event = when (item) {
                Work.Retry -> {
                    reload = true
                    continue
                }
                is Work.Event -> item.event
            }
            val known = mutableState.value.subagents.ids()
            when (event) {
                is ServerEvent.Connected -> reload = true
                is ServerEvent.SessionCreated -> {
                    val parent = event.parentId
                    if (parent != null && (parent == root || parent in known)) reload = true
                }
                is ServerEvent.ExecutionStarted -> if (event.sessionId in known) setStatus(event.sessionId, SubagentStatus.Running)
                is ServerEvent.ExecutionSucceeded -> if (event.sessionId in known) {
                    setStatus(event.sessionId, SubagentStatus.Succeeded)
                    refresh += event.sessionId
                }
                is ServerEvent.ExecutionFailed -> if (event.sessionId in known) {
                    setStatus(event.sessionId, SubagentStatus.Failed)
                    refresh += event.sessionId
                }
                is ServerEvent.ExecutionInterrupted -> if (event.sessionId in known) {
                    setStatus(event.sessionId, SubagentStatus.Interrupted)
                    refresh += event.sessionId
                }
                is ServerEvent.ToolCalled -> if (event.sessionId in known) refresh += event.sessionId
                is ServerEvent.ToolSucceeded -> if (event.sessionId in known) refresh += event.sessionId
                is ServerEvent.ToolFailed -> if (event.sessionId in known) refresh += event.sessionId
                else -> Unit
            }
        }
        guarded {
            if (reload) {
                loadAll(client)
            } else {
                refresh.forEach { refreshNode(client, it) }
            }
        }
    }

    /** Runs [block]; a failure other than a closed client is shown, and what was loaded stays. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiException) {
            if (e.error == ApiError.Closed) throw e
            mutableState.update { it.copy(loading = false, error = ChatFailure.of(e.error)) }
        }
    }

    private suspend fun loadAll(client: OpencodeClient) {
        val self = client.sessions.get(root)
        val parent = self.parentId?.let { client.sessions.get(it) }
        val active = client.sessions.activeStatuses()
        val visited = mutableSetOf(root)
        val now = lazy { clock.now() }
        val subagents = children(client, root, depth = 1, active = active.keys, visited = visited, now = now)
        mutableState.value = SubagentState(
            loading = false,
            title = self.title,
            agent = self.agent,
            parentId = self.parentId,
            parentIsRoot = parent == null || parent.parentId == null,
            subagents = subagents,
            error = null,
        )
    }

    private suspend fun children(
        client: OpencodeClient,
        parent: SessionId,
        depth: Int,
        active: Set<SessionId>,
        visited: MutableSet<SessionId>,
        now: Lazy<Instant>,
    ): List<SubagentNode> {
        if (depth > MAX_DEPTH || visited.size > MAX_NODES) return emptyList()
        val sessions = childSessions(client, parent)
            .filter { it.parentId == parent && visited.size <= MAX_NODES && visited.add(it.id) }
            .sortedWith(compareBy(nullsLast<Instant>()) { it.createdAt })
        return sessions.map { child ->
            val status = statusOf(child, child.id in active)
            SubagentNode(
                sessionId = child.id,
                title = child.title,
                agent = child.agent,
                status = status,
                startedAt = child.createdAt,
                duration = durationOf(child, status, now),
                usage = child.usage,
                tools = tools(client, child.id),
                children = children(client, child.id, depth + 1, active, visited, now),
            )
        }
    }

    /** Reads one subagent again; its status and children stay. */
    private suspend fun refreshNode(client: OpencodeClient, id: SessionId) {
        val child = try {
            client.sessions.get(id)
        } catch (e: ApiException) {
            if (e.error != ApiError.NotFound) throw e
            mutableState.update { it.copy(subagents = it.subagents.without(id)) }
            return
        }
        val tools = tools(client, id)
        val now = lazy { clock.now() }
        mutableState.update { current ->
            current.copy(
                subagents = current.subagents.mapNode(id) { node ->
                    node.copy(
                        title = child.title,
                        agent = child.agent,
                        startedAt = child.createdAt,
                        duration = durationOf(child, node.status, now),
                        usage = child.usage,
                        tools = tools,
                    )
                },
            )
        }
    }

    private fun setStatus(id: SessionId, status: SubagentStatus) {
        mutableState.update { current ->
            current.copy(
                subagents = current.subagents.mapNode(id) { node ->
                    val duration = if (status == SubagentStatus.Running) running(node.startedAt, clock.now()) else node.duration
                    node.copy(status = status, duration = duration)
                },
            )
        }
    }

    private suspend fun childSessions(client: OpencodeClient, parent: SessionId): List<Session> = allPages { cursor ->
        client.sessions.list(
            SessionQuery(parent = SessionParentFilter.ChildrenOf(parent), order = SortOrder.Ascending, cursor = cursor),
        )
    }

    /** The tool calls of [id]'s messages, each once, as its latest block says. */
    private suspend fun tools(client: OpencodeClient, id: SessionId): SubagentTools {
        val messages = allPages { cursor -> client.messages.list(id, MessageQuery(order = SortOrder.Ascending, cursor = cursor)) }
        val calls = LinkedHashMap<String, AssistantBlock.Tool>()
        for (message in messages) {
            if (message !is Message.Assistant) continue
            for (block in message.blocks) {
                if (block !is AssistantBlock.Tool) continue
                // The latest block of a call wins, and moves it to where it last appeared.
                calls.remove(block.id)
                calls[block.id] = block
            }
        }
        var completed = 0
        var failed = 0
        var running = 0
        var current: String? = null
        for (call in calls.values) {
            when (call.status) {
                ToolStatus.Completed -> completed++
                ToolStatus.Failed -> failed++
                ToolStatus.Running, ToolStatus.Streaming -> {
                    running++
                    current = call.name
                }
                is ToolStatus.Unknown -> Unit
            }
        }
        return SubagentTools(completed = completed, failed = failed, running = running, current = current)
    }

    /** Every item of a paged list, up to [MAX_PAGES] pages, stopping at a cursor seen before. */
    private suspend fun <T> allPages(fetch: suspend (PageCursor?) -> Page<T>): List<T> {
        val items = mutableListOf<T>()
        val seen = mutableSetOf<PageCursor>()
        var cursor: PageCursor? = null
        repeat(MAX_PAGES) {
            val page = fetch(cursor)
            items += page.items
            val next = page.nextCursor
            if (page.isEnd || next == null || !seen.add(next)) return items
            cursor = next
        }
        return items
    }

    private fun statusOf(child: Session, active: Boolean): SubagentStatus = when {
        active -> SubagentStatus.Running
        else -> when (child.outcome) {
            SessionOutcome.Succeeded -> SubagentStatus.Succeeded
            SessionOutcome.Failed -> SubagentStatus.Failed
            SessionOutcome.Interrupted -> SubagentStatus.Interrupted
            is SessionOutcome.Unknown, null -> SubagentStatus.Idle
        }
    }

    private fun durationOf(child: Session, status: SubagentStatus, now: Lazy<Instant>): Duration? =
        if (status == SubagentStatus.Running) {
            running(child.createdAt, now.value)
        } else {
            val start = child.createdAt
            val end = child.updatedAt
            if (start == null || end == null) null else (end - start).coerceAtLeast(Duration.ZERO)
        }

    private fun running(start: Instant?, now: Instant): Duration? = start?.let { (now - it).coerceAtLeast(Duration.ZERO) }

    private fun List<SubagentNode>.withRunningDurations(now: Instant): List<SubagentNode> = map { node ->
        node.copy(
            duration = if (node.status == SubagentStatus.Running) running(node.startedAt, now) else node.duration,
            children = node.children.withRunningDurations(now),
        )
    }

    private fun List<SubagentNode>.ids(): Set<SessionId> {
        val ids = mutableSetOf<SessionId>()
        fun walk(nodes: List<SubagentNode>) {
            nodes.forEach {
                ids += it.sessionId
                walk(it.children)
            }
        }
        walk(this)
        return ids
    }

    private fun List<SubagentNode>.mapNode(id: SessionId, change: (SubagentNode) -> SubagentNode): List<SubagentNode> =
        map { node ->
            val changed = if (node.sessionId == id) change(node) else node
            changed.copy(children = changed.children.mapNode(id, change))
        }

    private fun List<SubagentNode>.without(id: SessionId): List<SubagentNode> =
        filter { it.sessionId != id }.map { it.copy(children = it.children.without(id)) }

    private companion object {
        val TICK = 1.seconds

        /** Levels of subagents below the route's session. */
        const val MAX_DEPTH = 10

        /** Subagents in the whole tree. */
        const val MAX_NODES = 500

        /** Pages read of one list. */
        const val MAX_PAGES = 100

        /** What keeps the tree live; `server.connected` passes any filter. */
        val EVENT_TYPES = setOf(
            "session.created",
            "session.execution.started",
            "session.execution.succeeded",
            "session.execution.failed",
            "session.execution.interrupted",
            "session.tool.called",
            "session.tool.success",
            "session.tool.failed",
        )
    }
}
