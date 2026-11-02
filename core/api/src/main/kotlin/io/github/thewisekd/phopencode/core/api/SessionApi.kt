package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionStatus

/** Sessions. Errors are described on [OpencodeClient]. */
interface SessionApi {
    suspend fun create(request: CreateSessionRequest = CreateSessionRequest()): Session

    suspend fun list(query: SessionQuery = SessionQuery()): Page<Session>

    suspend fun get(id: SessionId): Session

    /** Deletes the session and its child sessions, irreversibly. */
    suspend fun delete(id: SessionId)

    suspend fun update(id: SessionId, update: SessionUpdate)

    /** Interrupts the running work. The session itself stays. */
    suspend fun stop(id: SessionId)

    /** @param before The copy holds the history before this message; null copies all of it. */
    suspend fun fork(id: SessionId, before: MessageId? = null): Session

    suspend fun compact(id: SessionId)

    /** The status of each session that is not idle. */
    suspend fun activeStatuses(): Map<SessionId, SessionStatus>
}

/**
 * @property parentId Makes the new session a child of this one.
 * @property permissions Permission rules, in the server's own shape.
 */
data class CreateSessionRequest(
    val parentId: SessionId? = null,
    val title: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val location: Location? = null,
    val metadata: Map<String, DataValue>? = null,
    val permissions: DataValue? = null,
)

/**
 * What to list. A null field means no restriction, or the server's default.
 *
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

/** The fields to change. A null field stays as it is. */
data class SessionUpdate(
    val title: String? = null,
    val archived: Boolean? = null,
    val metadata: Map<String, DataValue>? = null,
    val permissions: DataValue? = null,
)
