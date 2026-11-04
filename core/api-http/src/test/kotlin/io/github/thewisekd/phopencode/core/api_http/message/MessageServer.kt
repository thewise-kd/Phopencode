package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import java.util.Base64
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A small stand-in for the session and message routes of `opencode serve`, shaped like the
 * recordings: wire names, envelopes, cursors on every page, 204s and error bodies. Only what the
 * message contract arranges is supported.
 */
internal class MessageServer : Dispatcher() {
    private class SessionState {
        val messages = mutableListOf<Message>()
        var revert: JsonObject? = null
        var inboxCount = 0

        /** What prompts enqueued, oldest first; nothing here delivers them. */
        val inbox = mutableListOf<JsonObject>()
    }

    private val lock = Any()
    private val sessions = LinkedHashMap<String, SessionState>()
    private val diffs = HashMap<Pair<String, String>, List<FileDiff>>()

    fun givenSession(id: SessionId) {
        synchronized(lock) { sessions.getOrPut(id.value) { SessionState() } }
    }

    fun givenMessages(id: SessionId, messages: List<Message>) {
        synchronized(lock) { sessions.getOrPut(id.value) { SessionState() }.messages += messages }
    }

    fun givenTurnDiff(id: SessionId, from: MessageId, diffs: List<FileDiff>) {
        synchronized(lock) { this.diffs[id.value to from.value] = diffs }
    }

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(lock) {
        val url = request.url
        val segments = url.pathSegments
        if (segments.size < 3 || segments[0] != "api" || segments[1] != "session") return notFound("Route")
        val session = sessions[segments[2]] ?: return notFound("Session")
        val body = request.body?.utf8()?.takeIf { it.isNotEmpty() }?.let { ApiJson.parseToJsonElement(it).jsonObject }
        val route = segments.drop(3)
        when (request.method to route) {
            "GET" to emptyList<String>() -> ok(
                buildJsonObject {
                    putJsonObject("data") {
                        put("id", segments[2])
                        session.revert?.let { put("revert", it) }
                    }
                },
            )
            "POST" to listOf("prompt") -> {
                val item = inboxItem(segments[2], session, body!!)
                session.inbox += item
                ok(buildJsonObject { put("data", item) })
            }
            "GET" to listOf("inbox") -> ok(buildJsonObject { put("data", JsonArray(session.inbox.toList())) })
            "GET" to listOf("message") -> ok(page(session, url))
            "POST" to listOf("agent"), "POST" to listOf("model"), "POST" to listOf("command") -> noContent()
            "GET" to listOf("diff") -> {
                val from = url.queryParameter("from")
                    ?: session.messages.lastOrNull { it is Message.User }?.id?.value
                val found = from?.let { diffs[segments[2] to it] }.orEmpty()
                ok(buildJsonObject { put("data", JsonArray(found.map(::diff))) })
            }
            "POST" to listOf("revert", "stage") -> {
                val target = body!!.getValue("messageID").jsonPrimitive.content
                if (session.messages.none { it.id.value == target }) return notFound("Message")
                val revert = buildJsonObject {
                    put("messageID", target)
                    put("snapshot", "0000000000000000000000000000000000000001")
                    put("files", JsonArray(emptyList()))
                }
                session.revert = revert
                ok(buildJsonObject { put("data", revert) })
            }
            "POST" to listOf("revert", "commit") -> {
                val target = session.revert?.getValue("messageID")?.jsonPrimitive?.content
                    ?: return error(409, "SessionBusyError", "Nothing is staged")
                val index = session.messages.indexOfFirst { it.id.value == target }
                if (index >= 0) session.messages.subList(index, session.messages.size).clear()
                session.revert = null
                noContent()
            }
            "DELETE" to listOf("revert") -> {
                session.revert = null
                noContent()
            }
            else -> notFound("Route")
        }
    }

    private fun inboxItem(sessionId: String, session: SessionState, body: JsonObject): JsonObject = buildJsonObject {
        put("id", "msg_inbox_${++session.inboxCount}")
        put("sessionID", sessionId)
        putJsonObject("time") { put("created", 1700000000000 + session.inboxCount) }
        put("type", "user")
        putJsonObject("payload") {
            put("text", body.getValue("text"))
            body["files"]?.let { files ->
                putJsonArray("files") {
                    files.jsonArray.forEach { file ->
                        add(
                            buildJsonObject {
                                put("data", "")
                                put("mime", "text/plain")
                                putJsonObject("source") {
                                    put("type", "uri")
                                    put("uri", file.jsonObject.getValue("uri"))
                                }
                            },
                        )
                    }
                }
            }
            body["agents"]?.let { put("agents", it) }
            body["skills"]?.let { skills ->
                putJsonArray("skills") {
                    skills.jsonArray.forEach { skill ->
                        val id = skill.jsonObject.getValue("id")
                        add(buildJsonObject { put("id", id); put("name", id) })
                    }
                }
            }
        }
        put("delivery", body["delivery"] ?: JsonPrimitive("steer"))
    }

    /** Cursors carry the order and the last ID, as the server's do; every page has both. */
    private fun page(session: SessionState, url: HttpUrl): JsonObject {
        val limit = url.queryParameter("limit")
        val order = url.queryParameter("order")
        val type = url.queryParameter("type")
        val cursor = url.queryParameter("cursor")
        val (direction, after) = cursor?.let {
            String(Base64.getUrlDecoder().decode(it)).split('|', limit = 2).let { (o, id) -> o to id }
        } ?: ((order ?: "desc") to null)
        val filtered = session.messages.filter { type == null || wireType(it) == type }
        val ordered = if (direction == "asc") filtered else filtered.reversed()
        val start = after?.let { id -> ordered.indexOfFirst { it.id.value == id } + 1 } ?: 0
        val items = ordered.drop(start).take(limit?.toInt() ?: Int.MAX_VALUE)
        return buildJsonObject {
            put("data", JsonArray(items.map(::message)))
            putJsonObject("cursor") {
                // Like the server's, a page with items has a next cursor even when it is the last.
                val back = if (direction == "asc") "desc" else "asc"
                val previous = items.firstOrNull()?.let { encode(back, it.id.value) }
                val next = items.lastOrNull()?.let { encode(direction, it.id.value) }
                put("previous", previous?.let(::JsonPrimitive) ?: JsonNull)
                put("next", next?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
    }

    private fun encode(order: String, id: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$order|$id".toByteArray())

    private fun wireType(message: Message): String = when (message) {
        is Message.User -> "user"
        is Message.Assistant -> "assistant"
        else -> error("The message contract uses only user and assistant messages")
    }

    private fun message(message: Message): JsonElement = buildJsonObject {
        put("id", message.id.value)
        putJsonObject("time") { put("created", 1700000000000) }
        put("type", wireType(message))
        when (message) {
            is Message.User -> put("text", message.text)
            is Message.Assistant -> {
                put("agent", "build")
                putJsonObject("model") {
                    put("id", "sim-1")
                    put("providerID", "sim")
                }
                put("content", buildJsonArray { })
            }
            else -> Unit
        }
    }

    private fun diff(diff: FileDiff): JsonElement = buildJsonObject {
        put("file", diff.file.path)
        diff.patch?.let { put("patch", it) }
        diff.file.additions?.let { put("additions", it) }
        diff.file.deletions?.let { put("deletions", it) }
        put(
            "status",
            when (diff.file.status) {
                VcsFileStatus.Added -> "added"
                VcsFileStatus.Deleted -> "deleted"
                else -> "modified"
            },
        )
    }

    private fun ok(body: JsonObject) =
        MockResponse.Builder().code(200).body(ApiJson.encodeToString(JsonObject.serializer(), body)).build()

    private fun noContent() = MockResponse.Builder().code(204).build()

    private fun notFound(what: String) = error(404, "${what}NotFoundError", "$what not found")

    private fun error(status: Int, tag: String, message: String) =
        MockResponse.Builder().code(status).body("""{"_tag":"$tag","message":"$message"}""").build()
}
