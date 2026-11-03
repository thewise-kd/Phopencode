package io.github.thewisekd.phopencode.core.api_http.session

import io.github.thewisekd.phopencode.core.api.CreateSessionRequest
import io.github.thewisekd.phopencode.core.api.SessionParentFilter
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.api.SessionUpdate
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod
import io.github.thewisekd.phopencode.core.api_http.base.LocationWire
import io.github.thewisekd.phopencode.core.api_http.base.toWire
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The requests of the session routes, as recorded from opencode 2.2.5.
 *
 * Repeatable or not, per route. A call that is not repeatable reaches the server at most once, and
 * fails with `ApiError.Network` when the connection drops; the caller decides whether to retry.
 *
 * | Call | Route | Repeatable | Why |
 * |---|---|---|---|
 * | create | `POST /api/session` | no | A second request makes a second session |
 * | list | `GET /api/session` | yes | Reads only |
 * | get | `GET /api/session/{id}` | yes | Reads only |
 * | delete | `DELETE /api/session/{id}` | no | A second request after a delete that worked answers 404, so a success would be reported as `NotFound` |
 * | update | `PATCH /api/session/{id}` | yes | Sets absolute values; repeating the current state is a no-op on the server |
 * | stop | `POST /api/session/{id}/interrupt` | no | A second request answers `interrupted: false`, which would hide that the first one stopped the work |
 * | fork | `POST /api/session/{id}/fork` | no | A second request makes a second copy |
 * | compact | `POST /api/session/{id}/compact` | no | A second request enqueues a second compaction |
 * | activeStatuses | `GET /api/session/active` | yes | Reads only |
 */
internal object SessionRoutes {
    private val SESSIONS = listOf("api", "session")

    private fun session(id: SessionId, vararg rest: String) = SESSIONS + id.value + rest

    /** The body keys and their order follow the spec, as do the recorded bodies. */
    fun create(request: CreateSessionRequest) = ApiRequest(
        method = HttpMethod.POST,
        path = SESSIONS,
        body = buildJsonObject {
            request.parentId?.let { put("parentID", it.value) }
            request.title?.let { put("title", it) }
            request.agent?.let { put("agent", it) }
            request.model?.let { put("model", ApiJson.encodeToJsonElement(ModelRefWire.serializer(), it.toWire())) }
            request.location?.let {
                put("location", ApiJson.encodeToJsonElement(LocationWire.serializer(), it.toWire()))
            }
            request.metadata?.let { put("metadata", it.toJson()) }
            request.permissions?.let { rules -> put("permissions", JsonArray(rules.map { it.toWire() })) }
        },
        repeatable = false,
    )

    /**
     * The first page sends the filters. A cursor already holds them (the recorded cursors carry
     * `order`, `search`, `archived` and `parentID`), so a later page sends only the cursor and the
     * limit, which the cursor does not hold.
     *
     * [SessionQuery.directory] goes in the spec's `directory` filter, not in `location[directory]`.
     */
    fun list(query: SessionQuery): ApiRequest {
        val parameters = buildList {
            val limit = query.limit?.let { "limit" to it.toString() }
            val cursor = query.cursor
            if (cursor != null) {
                limit?.let(::add)
                add("cursor" to cursor.value)
            } else {
                query.search?.let { add("search" to it) }
                when (val parent = query.parent) {
                    SessionParentFilter.Any -> Unit
                    SessionParentFilter.RootsOnly -> add("parentID" to "null")
                    is SessionParentFilter.ChildrenOf -> add("parentID" to parent.parentId.value)
                }
                query.archived?.let { add("archived" to it.toString()) }
                limit?.let(::add)
                query.order?.let {
                    add(
                        "order" to when (it) {
                            SortOrder.Ascending -> "asc"
                            SortOrder.Descending -> "desc"
                        },
                    )
                }
                query.directory?.let { add("directory" to it) }
                query.project?.let { add("project" to it) }
                query.subpath?.let { add("subpath" to it) }
            }
        }
        return ApiRequest(HttpMethod.GET, SESSIONS, query = parameters)
    }

    fun get(id: SessionId) = ApiRequest(HttpMethod.GET, session(id))

    fun delete(id: SessionId) = ApiRequest(HttpMethod.DELETE, session(id), repeatable = false)

    /** Only the fields to change; `{}` when there are none, which the server takes as a no-op. */
    fun update(id: SessionId, update: SessionUpdate) = ApiRequest(
        method = HttpMethod.PATCH,
        path = session(id),
        body = buildJsonObject {
            update.title?.let { put("title", it) }
            update.metadata?.let { put("metadata", it.toJson()) }
            update.permissions?.let { rules -> put("permissions", JsonArray(rules.map { it.toWire() })) }
            update.archived?.let { put("archived", it) }
        },
    )

    /** No body, as recorded; the server's optional `resume` query is left at its default. */
    fun stop(id: SessionId) = ApiRequest(HttpMethod.POST, session(id, "interrupt"), repeatable = false)

    fun fork(id: SessionId, before: MessageId?) = ApiRequest(
        method = HttpMethod.POST,
        path = session(id, "fork"),
        body = buildJsonObject { before?.let { put("before", it.value) } },
        repeatable = false,
    )

    fun compact(id: SessionId) = ApiRequest(
        method = HttpMethod.POST,
        path = session(id, "compact"),
        body = JsonObject(emptyMap()),
        repeatable = false,
    )

    fun active() = ApiRequest(HttpMethod.GET, SESSIONS + "active")
}
