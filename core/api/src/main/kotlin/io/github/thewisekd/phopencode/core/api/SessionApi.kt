package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionRule
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionId

/** Sessions. Errors are described on [OpencodeClient]. */
interface SessionApi {
    suspend fun create(request: CreateSessionRequest = CreateSessionRequest()): Session

    suspend fun list(query: SessionQuery = SessionQuery()): Page<Session>

    suspend fun get(id: SessionId): Session

    /** Deletes the session and its child sessions, irreversibly. */
    suspend fun delete(id: SessionId)

    suspend fun update(id: SessionId, update: SessionUpdate)

    /**
     * Interrupts the running work. The session itself stays.
     *
     * @return Whether anything was running here to interrupt.
     */
    suspend fun stop(id: SessionId): Boolean

    /** @param before The copy holds the history before this message; null copies all of it. */
    suspend fun fork(id: SessionId, before: MessageId? = null): Session

    /**
     * Returns the inbox item once the server has accepted the request. Accepted is not done: the result
     * arrives as [ServerEvent.CompactionEnded] or [ServerEvent.CompactionFailed].
     */
    suspend fun compact(id: SessionId): InboxItem

    /**
     * The sessions that are running, child sessions included; an idle session is absent. The server
     * has no other status today. Together with the `session.execution.*` events, this is where
     * busy and idle come from.
     */
    suspend fun activeStatuses(): Map<SessionId, SessionActivity>
}

/**
 * @property parentId Makes the new session a child of this one.
 */
data class CreateSessionRequest(
    val parentId: SessionId? = null,
    val title: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val location: Location? = null,
    val metadata: Map<String, DataValue>? = null,
    val permissions: List<PermissionRule>? = null,
)

/**
 * What to list. A null field means no restriction, or the server's default.
 *
 * @property archived Archived sessions are listed by default, so pass false to hide them.
 * @property directory A folder on the server.
 * @property project The server's project ID.
 * @property subpath A path inside the project.
 * @property cursor [Page.nextCursor] of the previous page; null for the first.
 */
data class SessionQuery(
    val limit: Int? = null,
    val order: SortOrder? = null,
    val search: String? = null,
    val parent: SessionParentFilter = SessionParentFilter.Any,
    val archived: Boolean? = null,
    val directory: String? = null,
    val project: String? = null,
    val subpath: String? = null,
    val cursor: PageCursor? = null,
)

/** Which sessions a list holds, by their place in the parent tree. */
sealed interface SessionParentFilter {
    data object Any : SessionParentFilter

    /** Sessions with no parent. */
    data object RootsOnly : SessionParentFilter

    /** The direct children of [parentId]: the subagents of that session. */
    data class ChildrenOf(val parentId: SessionId) : SessionParentFilter
}

/**
 * The fields to change. A null field stays as it is.
 *
 * @property archived False un-archives, which sends [ServerEvent.SessionUnarchived].
 */
data class SessionUpdate(
    val title: String? = null,
    val archived: Boolean? = null,
    val metadata: Map<String, DataValue>? = null,
    val permissions: List<PermissionRule>? = null,
)
