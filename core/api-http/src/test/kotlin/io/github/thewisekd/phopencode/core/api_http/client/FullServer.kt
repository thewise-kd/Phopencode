package io.github.thewisekd.phopencode.core.api_http.client

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.InterruptReason
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.fs.FakeFsServer
import io.github.thewisekd.phopencode.core.api_http.interaction.InteractionServer
import io.github.thewisekd.phopencode.core.api_http.message.MessageServer
import io.github.thewisekd.phopencode.core.api_http.session.FakeSessionServer
import io.github.thewisekd.phopencode.core.api_http.vcs.FakeVcsServer
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
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
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * The whole of `opencode serve` for the client contracts: `/api/info`, `/api/event` and every API
 * route. The route fakes of the API packages answer their own routes; this joins them where one
 * call changes what another route shows, as the real server does:
 *
 * - A session made by create or fork exists for the message and interaction routes too; a fork
 *   copies the history, up to `before` when given.
 * - `GET /api/session/{id}` carries the revert the message routes staged.
 * - Running sessions live here: stop ends them, and so does dismissing a question, which also sends
 *   `session.execution.interrupted` with reason `shutdown`.
 * - Un-archiving sends `session.unarchived`.
 */
internal class FullServer(private val defaultFolder: String) : Dispatcher() {
    private val sessionRoutes = FakeSessionServer(defaultFolder)
    private val messageRoutes = MessageServer()
    private val interactionRoutes = InteractionServer(defaultFolder)
    private val vcsRoutes = FakeVcsServer()
    private val fsRoutes = FakeFsServer()

    private val lock = Any()
    private val running = mutableSetOf<String>()
    private val messages = HashMap<String, List<Message>>()
    private val forms = HashMap<String, Form>()
    private val commands = HashMap<String, List<Command>>()
    private var failure: ApiError? = null
    private var version = "2.2.5"

    private val bodies = mutableListOf<EventBody>()
    private val allBodies = mutableListOf<EventBody>()
    private var holdReconnects = false
    private var refusal: ApiError? = null
    private val eventIds = AtomicInteger()

    /** Requests to `/api/event`, answered or not. */
    val eventRequests = AtomicInteger()

    @Volatile
    private var stalled = false
    private val release = CountDownLatch(1)
    private val waiting = AtomicInteger()

    val waitingCalls: Int
        get() = waiting.get()

    fun givenServerVersion(version: String) = synchronized(lock) { this.version = version }

    fun givenFailure(error: ApiError) = synchronized(lock) { failure = error }

    fun stall() {
        stalled = true
    }

    fun givenSession(session: Session) {
        sessionRoutes.givenSession(session)
        messageRoutes.givenSession(session.id)
        interactionRoutes.givenSession(session)
    }

    fun givenMessages(sessionId: SessionId, list: List<Message>) {
        synchronized(lock) { messages[sessionId.value] = messages[sessionId.value].orEmpty() + list }
        messageRoutes.givenMessages(sessionId, list)
    }

    fun givenRunning(sessionId: SessionId) = synchronized(lock) { running += sessionId.value }

    fun givenForm(form: Form) {
        synchronized(lock) { forms[form.id.value] = form }
        interactionRoutes.givenForm(form)
    }

    fun givenPermission(request: PermissionRequest) = interactionRoutes.givenPermission(request)

    fun givenCommands(list: List<Command>, folder: String?) = synchronized(lock) {
        commands[folder ?: defaultFolder] = list
    }

    fun givenFiles(folder: String, files: List<FileEntry>) = fsRoutes.givenFiles(folder, files)

    fun givenBranches(folder: String, branches: List<String>) = vcsRoutes.givenBranches(folder, branches)

    fun givenTurnDiff(sessionId: SessionId, from: MessageId, diffs: List<FileDiff>) =
        messageRoutes.givenTurnDiff(sessionId, from, diffs)

    // Events

    fun serveEvent(event: ServerEvent) {
        val (type, envelope) = wire(event)
        synchronized(lock) { bodies.toList() }.forEach { it.send(type, envelope) }
    }

    fun serveHeartbeat() = synchronized(lock) { bodies.toList() }.forEach(EventBody::heartbeat)

    /** Ends every connection; reconnects get 503 until [acceptReconnects]. */
    fun dropEventConnections() = synchronized(lock) {
        holdReconnects = true
        bodies.forEach(EventBody::end)
        bodies.clear()
    }

    fun acceptReconnects() = synchronized(lock) { holdReconnects = false }

    fun refuseEventConnections(error: ApiError) = synchronized(lock) {
        refusal = error
        bodies.forEach(EventBody::end)
        bodies.clear()
    }

    /** Asks every connection for a write, so that one the client dropped notices it. */
    fun pingEventConnections() = synchronized(lock) { bodies.toList() }.forEach(EventBody::ping)

    fun openEventConnections(): Int = synchronized(lock) { bodies.count { it.isOpen } }

    /** Lets every stalled call and open stream go, so that the server can shut down. */
    fun releaseAll() {
        release.countDown()
        synchronized(lock) { allBodies.toList() }.forEach(EventBody::release)
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val segments = request.url.pathSegments
        if (segments == listOf("api", "event")) return event(request)
        if (stalled) {
            waiting.incrementAndGet()
            release.await(STALL_LIMIT_SECONDS, TimeUnit.SECONDS)
            return MockResponse.Builder().code(503).build()
        }
        synchronized(lock) {
            failure?.let {
                failure = null
                return failureReply(it)
            }
        }
        // A fake that throws would leave the client waiting on a dropped connection; a 500 fails fast.
        return try {
            route(request, segments)
        } catch (_: RuntimeException) {
            MockResponse.Builder().code(500).body("""{"_tag":"FakeServerBroke"}""").build()
        }
    }

    private fun event(request: RecordedRequest): MockResponse {
        eventRequests.incrementAndGet()
        return synchronized(lock) {
            refusal?.let { return failureReply(it) }
            if (holdReconnects) return MockResponse.Builder().code(503).build()
            val body = EventBody(request.url.queryParameterValues("types").filterNotNull().toSet(), "evt_c${eventIds.incrementAndGet()}")
            bodies += body
            allBodies += body
            body.response()
        }
    }

    private fun route(request: RecordedRequest, segments: List<String>): MockResponse = when {
        segments == listOf("api", "info") -> info()
        segments == listOf("api", "session", "active") -> synchronized(lock) {
            ok(buildJsonObject { put("data", JsonObject(running.associateWith { buildJsonObject { put("type", "running") } })) })
        }
        segments == listOf("api", "session") ->
            sessionRoutes.dispatch(request).also { if (request.method == "POST") created(it) }
        segments.size >= 3 && segments.take(2) == listOf("api", "session") -> sessionRoute(request, segments[2], segments.drop(3))
        segments == listOf("api", "form") || segments == listOf("api", "permission", "request") ->
            interactionRoutes.dispatch(request)
        segments == listOf("api", "command") -> catalog(request) {
            JsonArray(
                commands[it].orEmpty().map { command ->
                    buildJsonObject {
                        put("name", command.name)
                        command.description?.let { d -> put("description", d) }
                    }
                },
            )
        }
        segments == listOf("api", "model", "default") -> catalog(request) { JsonNull }
        segments.take(2) == listOf("api", "vcs") -> vcsRoutes.dispatch(request)
        segments == listOf("api", "fs", "find") -> fsRoutes.dispatch(request)
        else -> notFound()
    }

    private fun sessionRoute(request: RecordedRequest, id: String, rest: List<String>): MockResponse {
        val first = rest.firstOrNull()
        return when {
            first == null && request.method == "GET" -> withRevert(request, sessionRoutes.dispatch(request))
            first == null && request.method == "PATCH" -> sessionRoutes.dispatch(request).also { updated(request, id, it) }
            first == null -> sessionRoutes.dispatch(request)
            first == "interrupt" -> {
                val reply = sessionRoutes.dispatch(request)
                if (reply.code != 200) {
                    reply
                } else {
                    ok(buildJsonObject { put("interrupted", synchronized(lock) { running.remove(id) }) })
                }
            }
            first == "fork" -> sessionRoutes.dispatch(request).also { forked(request, id, it) }
            first == "compact" -> sessionRoutes.dispatch(request)
            first in MESSAGE_ROUTES -> messageRoutes.dispatch(request)
            first == "form" || first == "permission" -> interactionRoutes.dispatch(request).also { settled(request, id, rest, it) }
            else -> notFound()
        }
    }

    private fun created(reply: MockResponse) {
        if (reply.code != 200) return
        val data = reply.json().getValue("data").jsonObject
        register(data)
    }

    private fun register(data: JsonObject) {
        val id = SessionId(data.getValue("id").jsonPrimitive.content)
        val folder = data["location"]?.jsonObject?.get("directory")?.jsonPrimitive?.contentOrNull ?: defaultFolder
        messageRoutes.givenSession(id)
        interactionRoutes.givenSession(Session(id, directory = folder))
    }

    private fun forked(request: RecordedRequest, from: String, reply: MockResponse) {
        if (reply.code != 200) return
        val data = reply.json().getValue("data").jsonObject
        register(data)
        val before = request.body?.utf8()?.takeIf { it.isNotEmpty() }
            ?.let { ApiJson.parseToJsonElement(it).jsonObject["before"]?.jsonPrimitive?.contentOrNull }
        val history = synchronized(lock) { messages[from].orEmpty() }
        val copied = if (before == null) history else history.takeWhile { it.id.value != before }
        givenMessages(SessionId(data.getValue("id").jsonPrimitive.content), copied)
    }

    private fun updated(request: RecordedRequest, id: String, reply: MockResponse) {
        if (reply.code !in 200..299) return
        val body = request.body?.utf8()?.takeIf { it.isNotEmpty() }?.let { ApiJson.parseToJsonElement(it).jsonObject }
        if (body?.get("archived")?.jsonPrimitive?.booleanOrNull == false) {
            serveEvent(ServerEvent.SessionUnarchived(SessionId(id)))
        }
    }

    /** A dismissed question ends the whole execution. */
    private fun settled(request: RecordedRequest, id: String, rest: List<String>, reply: MockResponse) {
        if (request.method != "DELETE" || rest.size != 2 || rest[0] != "form" || reply.code !in 200..299) return
        val ended = synchronized(lock) { forms[rest[1]]?.kind == FormKind.Question && running.remove(id) }
        if (ended) serveEvent(ServerEvent.ExecutionInterrupted(SessionId(id), InterruptReason.Shutdown))
    }

    private fun withRevert(request: RecordedRequest, reply: MockResponse): MockResponse {
        if (reply.code != 200) return reply
        val revert = messageRoutes.dispatch(request).takeIf { it.code == 200 }
            ?.json()?.get("data")?.jsonObject?.get("revert")
        val session = reply.json()
        val data = session.getValue("data").jsonObject
        val merged = if (revert == null) data else JsonObject(data + ("revert" to revert))
        return ok(JsonObject(session + ("data" to merged)))
    }

    private fun info(): MockResponse = ok(
        buildJsonObject {
            put("version", synchronized(lock) { version })
            put("pid", 4242)
            putJsonObject("paths") { put("tmp", "/tmp/opencode") }
            putJsonObject("capabilities") { put("persistentPty", true) }
        },
    )

    private fun catalog(request: RecordedRequest, data: (String) -> JsonElement): MockResponse {
        val folder = request.url.queryParameter("location[directory]") ?: defaultFolder
        val value = synchronized(lock) { data(folder) }
        return ok(
            buildJsonObject {
                putJsonObject("location") { put("directory", folder) }
                put("data", value)
            },
        )
    }

    /** The event as the server writes it: its type, and the `{type, data}` envelope. */
    private fun wire(event: ServerEvent): Pair<String, String> {
        val (type, data) = when (event) {
            is ServerEvent.SessionDeleted -> "session.deleted" to session(event.sessionId)
            is ServerEvent.SessionUnarchived -> "session.unarchived" to session(event.sessionId)
            is ServerEvent.ExecutionStarted -> "session.execution.started" to session(event.sessionId)
            is ServerEvent.ExecutionInterrupted -> "session.execution.interrupted" to JsonObject(
                session(event.sessionId) + ("reason" to JsonPrimitive(reason(checkNotNull(event.reason)))),
            )
            is ServerEvent.FormCancelled -> "form.cancelled" to JsonObject(
                session(event.sessionId) + ("id" to JsonPrimitive(event.formId.value)),
            )
            is ServerEvent.Unknown -> event.rawType to JsonObject(emptyMap())
            else -> error("The contracts serve no ${event::class.simpleName}")
        }
        check(event.meta == io.github.thewisekd.phopencode.core.api.EventMeta()) { "events with metadata are not served" }
        val envelope = buildJsonObject {
            put("type", type)
            put("data", data)
        }
        return type to ApiJson.encodeToString(JsonObject.serializer(), envelope)
    }

    private fun session(id: SessionId) = buildJsonObject { put("sessionID", id.value) }

    private fun reason(reason: InterruptReason): String = when (reason) {
        InterruptReason.User -> "user"
        InterruptReason.Shutdown -> "shutdown"
        is InterruptReason.Unknown -> reason.raw
    }

    private fun failureReply(error: ApiError): MockResponse {
        val status = when (error) {
            ApiError.Unauthorized -> 401
            ApiError.Forbidden -> 403
            ApiError.NotFound -> 404
            is ApiError.BadRequest -> 400
            is ApiError.Conflict -> 409
            is ApiError.Server -> error.status
            else -> error("no reply gives $error")
        }
        return MockResponse.Builder().code(status).body("""{"_tag":"FakeError"}""").build()
    }

    private fun MockResponse.json(): JsonObject =
        ApiJson.parseToJsonElement(Buffer().also { body?.writeTo(it) }.readUtf8()).jsonObject

    private fun ok(body: JsonObject) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(ApiJson.encodeToString(JsonObject.serializer(), body))
        .build()

    private fun notFound() = MockResponse.Builder().code(404).body("""{"_tag":"NotFound","message":"Route not found"}""").build()

    private companion object {
        val MESSAGE_ROUTES = setOf("message", "prompt", "agent", "model", "command", "diff", "revert")
        const val STALL_LIMIT_SECONDS = 30L
    }
}
