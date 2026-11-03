package io.github.thewisekd.phopencode.core.api_http.interaction

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.Session
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A small stand-in for the form and permission routes of `opencode serve`, shaped like the
 * recordings: wire names, `{location, data}` envelopes, 204s and `{_tag, message}` error bodies.
 * Only what the interaction contract arranges is supported.
 */
internal class InteractionServer(private val defaultFolder: String) : Dispatcher() {
    private val lock = Any()

    /** Session ID to folder. */
    private val sessions = LinkedHashMap<String, String>()
    private val forms = LinkedHashMap<String, JsonObject>()
    private val settledForms = HashSet<String>()
    private val permissions = LinkedHashMap<String, JsonObject>()
    private var failure: ApiError? = null
    fun givenSession(session: Session) = synchronized(lock) {
        sessions[session.id.value] = session.directory ?: defaultFolder
    }

    fun givenForm(form: Form) = synchronized(lock) { forms[form.id.value] = formWire(form) }

    fun givenPermission(request: PermissionRequest) = synchronized(lock) {
        permissions[request.id.value] = buildJsonObject {
            put("id", request.id.value)
            put("sessionID", request.sessionId.value)
            put("action", request.action)
            putJsonArray("resources") { request.resources.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("save") { request.save.forEach { add(JsonPrimitive(it)) } }
            request.message?.let { put("message", it) }
        }
    }

    fun givenFailure(error: ApiError) = synchronized(lock) { failure = error }

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(lock) {
        failure?.let {
            failure = null
            return failureResponse(it)
        }
        route(request)
    }

    private fun route(request: RecordedRequest): MockResponse {
        val url = request.url
        val segments = url.pathSegments
        val folder = url.queryParameter("location[directory]") ?: defaultFolder
        return when {
            request.method == "GET" && segments == listOf("api", "form") -> listed(
                forms.values.filter { sessions[it.text("sessionID")] == folder },
                folder,
            )
            request.method == "GET" && segments == listOf("api", "permission", "request") -> listed(
                permissions.values.filter { sessions[it.text("sessionID")] == folder },
                folder,
            )
            segments.size >= 4 && segments[0] == "api" && segments[1] == "session" -> sessionRoute(request, segments)
            else -> notFound("Route")
        }
    }

    private fun sessionRoute(request: RecordedRequest, segments: List<String>): MockResponse {
        val sessionId = segments[2]
        if (sessionId !in sessions) return notFound("Session")
        val route = segments.drop(3)
        return when {
            request.method == "GET" && route == listOf("form") ->
                listed(forms.values.filter { it.text("sessionID") == sessionId }, null)
            request.method == "GET" && route == listOf("permission") ->
                listed(permissions.values.filter { it.text("sessionID") == sessionId }, null)
            request.method == "POST" && route.size == 3 && route[0] == "form" && route[2] == "reply" ->
                replyForm(sessionId, route[1], ApiJson.parseToJsonElement(request.body!!.utf8()).jsonObject)
            request.method == "DELETE" && route.size == 2 && route[0] == "form" -> cancelForm(sessionId, route[1])
            request.method == "POST" && route.size == 3 && route[0] == "permission" && route[2] == "reply" -> {
                val found = permissions[route[1]]?.takeIf { it.text("sessionID") == sessionId }
                    ?: return notFound("Permission")
                permissions -= found.text("id")
                noContent()
            }
            else -> notFound("Route")
        }
    }

    private fun replyForm(sessionId: String, formId: String, body: JsonObject): MockResponse {
        if (formId in settledForms) return error(409, "FormAlreadySettledError", "Form already settled")
        val form = forms[formId]?.takeIf { it.text("sessionID") == sessionId } ?: return notFound("Form")
        val answer = body["answer"] as? JsonObject ?: return error(400, "InvalidRequestError", "No answer")
        for (field in form.getValue("fields") as JsonArray) {
            val f = field.jsonObject
            val key = f.text("key")
            val given = answer[key]
            val valid = when (f.text("type")) {
                "external" -> (given as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
                "string" -> given == null || (given as? JsonPrimitive)?.isString == true
                else -> true
            }
            val required = (f["required"] as? JsonPrimitive)?.booleanOrNull == true
            if (!valid || (required && given == null)) {
                return error(400, "FormInvalidAnswerError", "Invalid answer for a form field")
            }
        }
        forms -= formId
        settledForms += formId
        return noContent()
    }

    private fun cancelForm(sessionId: String, formId: String): MockResponse {
        if (formId in settledForms) return error(409, "FormAlreadySettledError", "Form already settled")
        forms[formId]?.takeIf { it.text("sessionID") == sessionId } ?: return notFound("Form")
        forms -= formId
        settledForms += formId
        return noContent()
    }

    private fun listed(items: List<JsonObject>, folder: String?): MockResponse = ok(
        buildJsonObject {
            folder?.let { putJsonObject("location") { put("directory", it) } }
            put("data", buildJsonArray { items.forEach { add(it) } })
        },
    )

    private fun formWire(form: Form): JsonObject = buildJsonObject {
        put("id", form.id.value)
        put("sessionID", form.sessionId.value)
        put("title", form.title ?: "Questions")
        putJsonObject("metadata") {
            when (val kind = form.kind) {
                FormKind.Question -> put("kind", "question")
                is FormKind.Other -> kind.raw?.let { put("kind", it) }
            }
        }
        putJsonArray("fields") {
            form.fields.forEach { field ->
                add(
                    buildJsonObject {
                        put("key", field.key)
                        field.title?.let { put("title", it) }
                        when (field) {
                            is FormField.External -> {
                                put("type", "external")
                                put("url", field.url)
                            }
                            is FormField.FreeText -> {
                                put("type", "string")
                                if (field.required) put("required", true)
                            }
                            else -> error("The interaction contract uses only text and external fields")
                        }
                    },
                )
            }
        }
    }

    private fun failureResponse(error: ApiError): MockResponse = when (error) {
        is ApiError.BadRequest -> error(400, "InvalidRequestError", error.detail ?: "bad request")
        ApiError.Unauthorized -> error(401, "UnauthorizedError", "unauthorized")
        ApiError.Forbidden -> error(403, "ForbiddenError", "forbidden")
        ApiError.NotFound -> notFound("Route")
        is ApiError.Conflict -> error(409, "ConflictError", error.detail ?: "conflict")
        is ApiError.Server -> error(error.status, "ServerError", error.detail ?: "server")
        else -> error(500, "ServerError", "server")
    }

    private fun JsonObject.text(name: String): String = (getValue(name) as JsonPrimitive).content

    private fun ok(body: JsonElement) =
        MockResponse.Builder().code(200).body(ApiJson.encodeToString(JsonElement.serializer(), body)).build()

    private fun noContent() = MockResponse.Builder().code(204).build()

    private fun notFound(what: String) = error(404, "${what}NotFoundError", "$what not found")

    private fun error(status: Int, tag: String, message: String) =
        MockResponse.Builder().code(status).body("""{"_tag":"$tag","message":"$message"}""").build()
}
