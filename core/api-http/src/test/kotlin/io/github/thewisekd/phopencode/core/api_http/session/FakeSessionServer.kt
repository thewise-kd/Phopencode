package io.github.thewisekd.phopencode.core.api_http.session

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Session
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * An in-memory opencode server for the session routes, shaped after the recordings: `{data}` and
 * `{data, cursor}` replies, 204 for delete and update, `{interrupted}` for interrupt, a cursor that
 * carries the query of its first page and a `next` cursor on every page, the last one included.
 */
internal class FakeSessionServer(private val defaultDirectory: String) : Dispatcher() {
    private class Stored(
        val id: String,
        val parentId: String?,
        var title: String?,
        val directory: String,
        var archived: Boolean,
        var metadata: JsonObject?,
        val fork: JsonObject? = null,
    )

    private val lock = Any()
    private val sessions = LinkedHashMap<String, Stored>()
    private val running = mutableSetOf<String>()
    private var nextId = 1
    private var failure: ApiError? = null

    @Volatile
    private var stalled = false
    private val release = CountDownLatch(1)
    private val waiting = AtomicInteger()

    val waitingCalls: Int
        get() = waiting.get()

    fun givenSession(session: Session) = synchronized(lock) {
        sessions[session.id.value] = Stored(
            id = session.id.value,
            parentId = session.parentId?.value,
            title = session.title,
            directory = session.directory ?: defaultDirectory,
            archived = session.archived,
            metadata = session.metadata.takeIf { it.isNotEmpty() }?.let { JsonObject(it.mapValues { (_, v) -> v.json() }) },
        )
    }

    fun givenRunning(id: String) = synchronized(lock) { running += id }

    fun givenFailure(error: ApiError) = synchronized(lock) { failure = error }

    fun stall() {
        stalled = true
    }

    /** Lets stalled calls finish, so that the server can shut down. */
    fun releaseAll() {
        release.countDown()
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (stalled) {
            waiting.incrementAndGet()
            release.await(STALL_LIMIT_SECONDS, TimeUnit.SECONDS)
            return MockResponse.Builder().code(503).build()
        }
        return synchronized(lock) {
            failure?.let {
                failure = null
                return@synchronized failureReply(it)
            }
            route(request)
        }
    }

    private fun route(request: RecordedRequest): MockResponse {
        val segments = request.url.pathSegments
        val body = request.body?.utf8()?.takeIf { it.isNotEmpty() }?.let { ApiJson.parseToJsonElement(it).jsonObject }
        check(segments.take(2) == listOf("api", "session")) { "unexpected path" }
        val rest = segments.drop(2)
        return when {
            rest.isEmpty() && request.method == "POST" -> create(checkNotNull(body))
            rest.isEmpty() && request.method == "GET" -> list(request)
            rest == listOf("active") && request.method == "GET" ->
                data(JsonObject(running.associateWith { buildJsonObject { put("type", "running") } }))
            else -> {
                val stored = sessions[rest[0]] ?: return notFound(rest[0])
                when (rest.drop(1) to request.method) {
                    emptyList<String>() to "GET" -> data(stored.json())
                    emptyList<String>() to "DELETE" -> delete(stored.id).let { noContent() }
                    emptyList<String>() to "PATCH" -> update(stored, checkNotNull(body)).let { noContent() }
                    listOf("interrupt") to "POST" ->
                        json(buildJsonObject { put("interrupted", running.remove(stored.id)) })
                    listOf("fork") to "POST" -> fork(stored)
                    listOf("compact") to "POST" -> data(compaction(stored.id))
                    else -> error("unexpected route")
                }
            }
        }
    }

    private fun create(body: JsonObject): MockResponse {
        val parentId = body["parentID"]?.jsonPrimitive?.contentOrNull
        if (parentId != null && parentId !in sessions) return notFound(parentId)
        val stored = Stored(
            id = "ses_fake_${nextId++}",
            parentId = parentId,
            title = body["title"]?.jsonPrimitive?.contentOrNull,
            directory = body["location"]?.jsonObject?.get("directory")?.jsonPrimitive?.content ?: defaultDirectory,
            archived = false,
            metadata = body["metadata"]?.jsonObject,
        )
        sessions[stored.id] = stored
        return data(stored.json())
    }

    private fun list(request: RecordedRequest): MockResponse {
        val url = request.url
        val cursor = url.queryParameter("cursor")?.let(::decodeCursor)
        val query: Map<String, String> = cursor?.query
            ?: url.queryParameterNames.filter { it in FILTERS }.associateWith { checkNotNull(url.queryParameter(it)) }
        val offset = cursor?.offset ?: 0
        val limit = url.queryParameter("limit")?.toInt() ?: DEFAULT_LIMIT
        val matching = sessions.values.filter { s ->
            when (val parent = query["parentID"]) {
                null -> true
                "null" -> s.parentId == null
                else -> s.parentId == parent
            } &&
                query["archived"]?.let { it.toBooleanStrict() == s.archived } != false &&
                query["directory"]?.let { it == s.directory } != false &&
                query["search"]?.let { s.title?.contains(it) == true } != false
        }.let { if (query["order"] == "asc") it else it.reversed() }
        val page = matching.drop(offset).take(limit)
        return json(
            buildJsonObject {
                put("data", JsonArray(page.map { it.json() }))
                put(
                    "cursor",
                    buildJsonObject {
                        put("previous", encodeCursor(query, maxOf(0, offset - limit)))
                        put("next", encodeCursor(query, offset + page.size))
                    },
                )
            },
        )
    }

    private fun delete(id: String) {
        sessions.values.filter { it.parentId == id }.map { it.id }.forEach(::delete)
        sessions.remove(id)
        running.remove(id)
    }

    private fun update(stored: Stored, body: JsonObject) {
        body["title"]?.jsonPrimitive?.contentOrNull?.let { stored.title = it }
        body["archived"]?.jsonPrimitive?.booleanOrNull?.let { stored.archived = it }
        (body["metadata"] as? JsonObject)?.let { stored.metadata = it }
    }

    private fun fork(stored: Stored): MockResponse {
        val copy = Stored(
            id = "ses_fake_${nextId++}",
            parentId = null,
            title = "${stored.title} (fork #1)",
            directory = stored.directory,
            archived = false,
            metadata = stored.metadata,
            fork = buildJsonObject {
                put("sessionID", stored.id)
                put("boundary", buildJsonObject { put("type", "through"); put("messageID", "msg_last") })
            },
        )
        sessions[copy.id] = copy
        return data(copy.json())
    }

    private fun compaction(sessionId: String) = buildJsonObject {
        put("id", "msg_fake_${nextId++}")
        put("sessionID", sessionId)
        put("time", buildJsonObject { put("created", CREATED) })
        put("type", "compaction")
        put("payload", JsonObject(emptyMap()))
        put("delivery", "steer")
    }

    private fun Stored.json() = buildJsonObject {
        put("id", id)
        parentId?.let { put("parentID", it) }
        fork?.let { put("fork", it) }
        put("projectID", "project-fake")
        put("cost", 0)
        put(
            "tokens",
            ApiJson.parseToJsonElement("""{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}"""),
        )
        put(
            "time",
            buildJsonObject {
                put("created", CREATED)
                put("updated", CREATED)
                if (archived) put("archived", CREATED)
            },
        )
        title?.let { put("title", it) }
        metadata?.let { put("metadata", it) }
        put("location", buildJsonObject { put("directory", directory) })
    }

    private class Cursor(val query: Map<String, String>, val offset: Int)

    private fun encodeCursor(query: Map<String, String>, offset: Int): String {
        val json = buildJsonObject {
            query.forEach { (k, v) -> put(k, v) }
            put("offset", offset)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
    }

    private fun decodeCursor(text: String): Cursor {
        val json = ApiJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(text))).jsonObject
        return Cursor(
            query = json.filterKeys { it != "offset" }.mapValues { it.value.jsonPrimitive.content },
            offset = json.getValue("offset").jsonPrimitive.content.toInt(),
        )
    }

    private fun data(value: JsonElement) = json(buildJsonObject { put("data", value) })

    private fun json(value: JsonElement, status: Int = 200) = MockResponse.Builder()
        .code(status)
        .addHeader("Content-Type", "application/json")
        .body(value.toString())
        .build()

    private fun noContent() = MockResponse.Builder().code(204).build()

    private fun notFound(id: String) = json(
        buildJsonObject {
            put("_tag", "SessionNotFoundError")
            put("sessionID", id)
            put("message", "Session not found: $id")
        },
        404,
    )

    /** A reply that [io.github.thewisekd.phopencode.core.api_http.base.ErrorMapping] maps back to [failure]. */
    private fun failureReply(failure: ApiError): MockResponse {
        val status = when (failure) {
            ApiError.Unauthorized -> 401
            ApiError.Forbidden -> 403
            ApiError.NotFound -> 404
            is ApiError.BadRequest -> 400
            is ApiError.Conflict -> 409
            is ApiError.Server -> failure.status
            else -> error("no reply gives $failure")
        }
        return json(buildJsonObject { put("_tag", "FakeError") }, status)
    }

    private fun DataValue.json(): JsonElement = when (this) {
        DataValue.Null -> JsonNull
        is DataValue.Bool -> JsonPrimitive(value)
        is DataValue.Num -> JsonPrimitive(value)
        is DataValue.Text -> JsonPrimitive(value)
        is DataValue.Items -> JsonArray(values.map { it.json() })
        is DataValue.Fields -> JsonObject(values.mapValues { it.value.json() })
    }

    private companion object {
        val FILTERS = setOf("order", "search", "parentID", "archived", "directory", "project", "subpath")
        const val DEFAULT_LIMIT = 50
        const val CREATED = 1700000000000
        const val STALL_LIMIT_SECONDS = 30L
    }
}
