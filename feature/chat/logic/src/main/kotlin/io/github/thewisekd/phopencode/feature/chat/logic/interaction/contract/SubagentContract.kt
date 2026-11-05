package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.Presenter
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A session's subagents (P7.6 builds them, P7.7 draws them): a badge on the chat, the
 * `ChatRoute.Subagents` list, and the header of a `ChatRoute.ChildSession` with its way back to
 * the parent. The same state on each, from a presenter of its own, about the session of its
 * route.
 *
 * Where it comes from, all through `ChatSession.call`:
 * - The tree: `SessionApi.list(SessionQuery(parent = SessionParentFilter.ChildrenOf(id)))`, asked
 *   again for each child, so nested subagents sit under their parent. All pages are read.
 * - Status: `SessionApi.activeStatuses()` says which run; one that does not run ended with its
 *   `Session.outcome`. Kept live by the `session.execution.*` and `session.created` events from
 *   `client.events.events(types)`, collected inside `ChatSession.call`, and asked again on every
 *   `ServerEvent.Connected`.
 * - Duration: from `Session.createdAt` to `Session.updatedAt` once ended; while running, to now,
 *   refreshed by the presenter about once a second.
 * - Tokens: `Session.usage`.
 * - Tool progress: the tool blocks of the child's messages (`MessageApi.list`).
 * - The header: this session's own title, agent and parent; the parent's `parentId` tells whether
 *   it is a root session.
 *
 * @property loading Nothing is loaded yet.
 * @property title This session's title. Prints masked.
 * @property agent This session's agent.
 * @property parentId This session's parent; null for a root session.
 * @property parentIsRoot The parent is a root session, opened as `ChatRoute.Chat`; else as
 *   `ChatRoute.ChildSession`.
 * @property subagents The direct subagents, oldest first, each with its own below it.
 * @property error Loading the tree failed; what was loaded stays.
 */
data class SubagentState(
    val loading: Boolean = true,
    val title: String? = null,
    val agent: String? = null,
    val parentId: SessionId? = null,
    val parentIsRoot: Boolean = true,
    val subagents: List<SubagentNode> = emptyList(),
    val error: ChatFailure? = null,
) {
    /** Subagents running now, at any depth: the live badge. */
    val runningCount: Int
        get() = subagents.sumOf { it.runningCount }

    /** Subagents at any depth. */
    val totalCount: Int
        get() = subagents.sumOf { it.totalCount }

    override fun toString(): String =
        "SubagentState(loading=$loading, title=***, agent=$agent, parentId=${parentId?.value}, " +
            "parentIsRoot=$parentIsRoot, subagents=$subagents, error=$error)"
}

/**
 * One subagent: a child session.
 *
 * @property title The child session's title, often the task the parent gave it. Prints masked.
 * @property agent The agent it runs as.
 * @property startedAt When the child session was made.
 * @property duration How long it has run, or ran; null when not known.
 * @property usage Its tokens and cost; null when none is reported.
 * @property children Its own subagents, oldest first.
 */
data class SubagentNode(
    val sessionId: SessionId,
    val title: String? = null,
    val agent: String? = null,
    val status: SubagentStatus = SubagentStatus.Idle,
    val startedAt: Instant? = null,
    val duration: Duration? = null,
    val usage: TokenUsage? = null,
    val tools: SubagentTools = SubagentTools(),
    val children: List<SubagentNode> = emptyList(),
) {
    val runningCount: Int
        get() = (if (status == SubagentStatus.Running) 1 else 0) + children.sumOf { it.runningCount }

    val totalCount: Int
        get() = 1 + children.sumOf { it.totalCount }

    override fun toString(): String =
        "SubagentNode(sessionId=${sessionId.value}, title=***, agent=$agent, status=$status, startedAt=$startedAt, " +
            "duration=$duration, usage=$usage, tools=$tools, children=$children)"
}

/**
 * A subagent's tool calls so far.
 *
 * @property current The name of the tool running now, if one is. Prints masked: an MCP tool's name
 *   can name a user's server.
 */
data class SubagentTools(
    val completed: Int = 0,
    val failed: Int = 0,
    val running: Int = 0,
    val current: String? = null,
) {
    val total: Int
        get() = completed + failed + running

    override fun toString(): String =
        "SubagentTools(completed=$completed, failed=$failed, running=$running, " +
            "current=${if (current == null) "null" else "***"})"
}

enum class SubagentStatus {
    Running,
    Succeeded,
    Failed,
    Interrupted,

    /** Not running, with no known outcome. */
    Idle,
}

sealed interface SubagentIntent {
    /** Opens the subagent list of this session. */
    data object OpenList : SubagentIntent

    /** Opens the timeline of the subagent [sessionId]. */
    data class Open(val sessionId: SessionId) : SubagentIntent

    /** Goes to this session's parent; does nothing for a root session. */
    data object OpenParent : SubagentIntent

    /** Loads again after [SubagentState.error]. */
    data object Retry : SubagentIntent

    /** Leaves the list. */
    data object Close : SubagentIntent
}

sealed interface SubagentEvent {
    /** Opens `ChatRoute.Subagents` for [sessionId]. */
    data class OpenList(val sessionId: SessionId) : SubagentEvent

    /** Opens `ChatRoute.ChildSession` for [sessionId]. */
    data class OpenChild(val sessionId: SessionId) : SubagentEvent

    /**
     * Back to the parent [sessionId]: to its screen if it is on the back stack, else opens it, as
     * `ChatRoute.Chat` when [root], else as `ChatRoute.ChildSession`.
     */
    data class OpenParent(val sessionId: SessionId, val root: Boolean) : SubagentEvent

    data object Close : SubagentEvent
}

interface SubagentPresenter : Presenter<SubagentState, SubagentIntent, SubagentEvent>
