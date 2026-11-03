package io.github.thewisekd.phopencode.core.api_http.interaction

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.apiError
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FieldCondition
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormAnswers
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.FormOption
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.PermissionSource
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TextFormat
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Replays every recorded call of the form and permission routes: the request this module sends must
 * be the one the recording sent, method, path, query and body alike, and the recorded answer must
 * decode to the model, or fail with the recorded error.
 */
class RecordedInteractionTest {
    private class Exchange(
        val flow: String,
        val label: String,
        val method: String,
        val target: String,
        val request: JsonElement?,
        val status: Int,
        val response: String?,
    ) {
        val body: String
            get() = response?.let { fixture("$flow/$it") } ?: ""

        val path: List<String>
            get() = target.substringBefore('?').split('/').drop(1)

        override fun toString(): String = "$flow/$label"
    }

    private val exchanges: List<Exchange> by lazy {
        val flows = ApiJson.parseToJsonElement(fixture("manifest.json")).jsonObject.getValue("flows").jsonObject.keys
        flows.flatMap { flow ->
            fixture("$flow/exchanges.jsonl").lineSequence().filter { it.isNotBlank() }.map { line ->
                val o = ApiJson.parseToJsonElement(line).jsonObject
                Exchange(
                    flow = flow,
                    label = o.getValue("label").jsonPrimitive.content,
                    method = o.getValue("method").jsonPrimitive.content,
                    target = o.getValue("path").jsonPrimitive.content,
                    request = o["request"]?.takeUnless { it is JsonNull },
                    status = o.getValue("status").jsonPrimitive.int,
                    response = o["response"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                )
            }.toList()
        }
    }

    private fun matching(method: String, predicate: (List<String>) -> Boolean) =
        exchanges.filter { it.method == method && predicate(it.path) }

    private val formLists by lazy {
        matching("GET") { it == listOf("api", "form") || (it.size == 4 && it[1] == "session" && it[3] == "form") }
    }
    private val formReplies by lazy {
        matching("POST") { it.size == 6 && it[1] == "session" && it[3] == "form" && it[5] == "reply" }
    }
    private val formCancels by lazy { matching("DELETE") { it.size == 5 && it[1] == "session" && it[3] == "form" } }
    private val permissionLists by lazy {
        matching("GET") {
            it == listOf("api", "permission", "request") || (it.size == 4 && it[1] == "session" && it[3] == "permission")
        }
    }
    private val permissionReplies by lazy {
        matching("POST") { it.size == 6 && it[1] == "session" && it[3] == "permission" && it[5] == "reply" }
    }

    /** Sends [call] against the recorded answer of [exchange], and checks the request matches it. */
    private suspend fun <T> replay(exchange: Exchange, call: suspend HttpInteractionApi.() -> T): Result<T> =
        TestServer().use { t ->
            t.enqueue(status = exchange.status, body = exchange.body)
            val result = runCatching { HttpInteractionApi(t.transport).call() }
            val sent = t.server.takeRequest()
            assertEquals(exchange.method, sent.method, "$exchange method")
            assertEquals(exchange.target, sent.target, "$exchange target")
            val expected = exchange.request?.let { ApiJson.encodeToString(JsonElement.serializer(), it) } ?: ""
            assertEquals(expected, sent.body?.utf8() ?: "", "$exchange body")
            result
        }

    private fun locationOf(exchange: Exchange): Location? =
        exchange.target.substringAfter("location%5Bdirectory%5D=", "").takeIf { it.isNotEmpty() }
            ?.let { Location(java.net.URLDecoder.decode(it, Charsets.UTF_8)) }

    private fun sessionOf(exchange: Exchange): SessionId? =
        exchange.path.takeIf { it[1] == "session" }?.let { SessionId(it[2]) }

    /** The recorded answer as the model's answers: the inverse of the encoding under test. */
    private fun answersOf(request: JsonElement): FormAnswers =
        request.jsonObject.getValue("answer").jsonObject.mapValues { (_, value) -> dataOf(value) }

    private fun dataOf(value: JsonElement): DataValue = when (value) {
        is JsonArray -> DataValue.Items(value.map(::dataOf))
        is JsonPrimitive -> when {
            value.isString -> DataValue.Text(value.content)
            value.booleanOrNull != null -> DataValue.Bool(value.booleanOrNull!!)
            else -> DataValue.Num(BigDecimal(value.content))
        }
        else -> error("Form answers hold no objects or nulls")
    }

    private val question = Form(
        id = FormId("frm_00000000000000000000000001"),
        sessionId = SessionId("ses_00000000000000000000000010"),
        kind = FormKind.Question,
        title = "Questions",
        fields = listOf(
            FormField.SingleChoice(
                key = "q0",
                title = "Colour",
                description = "Which colour?",
                options = listOf(FormOption("Red", "Red", "Warm"), FormOption("Blue", "Blue", "Cool")),
                allowCustom = true,
            ),
            FormField.MultipleChoice(
                key = "q1",
                title = "Sizes",
                description = "Which sizes?",
                options = listOf(
                    FormOption("S", "S", "Small"),
                    FormOption("M", "M", "Medium"),
                    FormOption("L", "L", "Large"),
                ),
                allowCustom = true,
            ),
        ),
        metadata = mapOf(
            "kind" to DataValue.Text("question"),
            "tool" to DataValue.Fields(
                mapOf(
                    "messageID" to DataValue.Text("msg_00000000000000000000000035"),
                    "id" to DataValue.Text("call_question"),
                ),
            ),
        ),
    )

    @Test
    fun `form lists match the recorded requests and decode`() = runTest {
        assertTrue(formLists.size >= 2)
        for (exchange in formLists) {
            val forms = replay(exchange) { listForms(sessionOf(exchange), locationOf(exchange)) }.getOrThrow()
            assertEquals(recordedCount(exchange), forms.size, "$exchange")
            assertTrue(forms.all { it.state == null }, "$exchange: list routes carry no state")
        }
    }

    @Test
    fun `the recorded question form decodes to the model`() = runTest {
        val all = formLists.single { it.flow == "form-question" && it.path == listOf("api", "form") }
        assertEquals(listOf(question), replay(all) { listForms(location = locationOf(all)) }.getOrThrow())
        val bySession = formLists.single { it.flow == "form-question" && it.path.size == 4 }
        assertEquals(listOf(question), replay(bySession) { listForms(sessionOf(bySession)) }.getOrThrow())
    }

    @Test
    fun `q0 answers encode as the recording sent them, for string and multiselect`() = runTest {
        val reply = formReplies.single { it.flow == "form-question" && it.label == "reply" }
        assertEquals("""{"answer":{"q0":"Red","q1":["S","L"]}}""", ApiJson.encodeToString(JsonElement.serializer(), reply.request!!))
        val answers = mapOf(
            "q0" to DataValue.Text("Red"),
            "q1" to DataValue.Items(listOf(DataValue.Text("S"), DataValue.Text("L"))),
        )
        replay(reply) { replyForm(sessionOf(reply)!!, FormId(reply.path[4]), answers) }.getOrThrow()
    }

    @Test
    fun `every recorded form reply is sent as recorded, and fails as recorded`() = runTest {
        assertTrue(formReplies.size >= 6)
        for (exchange in formReplies) {
            val result = replay(exchange) {
                replyForm(sessionOf(exchange)!!, FormId(exchange.path[4]), answersOf(exchange.request!!))
            }
            assertOutcome(exchange, result)
        }
    }

    @Test
    fun `recorded form cancels are sent as recorded`() = runTest {
        assertTrue(formCancels.size >= 2)
        for (exchange in formCancels) {
            assertOutcome(exchange, replay(exchange) { cancelForm(sessionOf(exchange)!!, FormId(exchange.path[4])) })
        }
    }

    @Test
    fun `permission lists match the recorded requests and decode`() = runTest {
        assertTrue(permissionLists.size >= 3)
        for (exchange in permissionLists) {
            val requests = replay(exchange) { listPermissions(sessionOf(exchange), locationOf(exchange)) }.getOrThrow()
            assertEquals(recordedCount(exchange), requests.size, "$exchange")
            assertTrue(requests.all { it.resources == listOf(".env") }, "$exchange")
        }
    }

    @Test
    fun `the recorded permission request decodes to the model`() = runTest {
        val exchange = permissionLists.single { it.flow == "permission" && it.path.size == 4 }
        assertEquals(
            listOf(
                PermissionRequest(
                    id = PermissionRequestId("per_00000000000000000000000002"),
                    sessionId = SessionId("ses_00000000000000000000000012"),
                    action = "read",
                    resources = listOf(".env"),
                    save = listOf("*"),
                    source = PermissionSource("tool", MessageId("msg_00000000000000000000000041"), "call_env1"),
                ),
            ),
            replay(exchange) { listPermissions(sessionOf(exchange)) }.getOrThrow(),
        )
    }

    @Test
    fun `a child session's permission is listed under the child`() = runTest {
        val exchange = permissionLists.single { it.flow == "subagent-permission" }
        val request = replay(exchange) { listPermissions(location = locationOf(exchange)) }.getOrThrow().single()
        assertEquals(SessionId("ses_00000000000000000000000009"), request.sessionId)
    }

    @Test
    fun `every recorded permission reply is sent as recorded, and fails as recorded`() = runTest {
        assertTrue(permissionReplies.size >= 5)
        val decisions = mutableSetOf<PermissionDecision>()
        for (exchange in permissionReplies) {
            val body = exchange.request!!.jsonObject
            val decision = when (body.getValue("decision").jsonPrimitive.content) {
                "once" -> PermissionDecision.Once
                "always" -> PermissionDecision.Always
                "reject" -> PermissionDecision.Reject
                else -> error("unknown decision in $exchange")
            }
            decisions += decision
            val message = body["message"]?.jsonPrimitive?.content
            val result = replay(exchange) {
                replyPermission(sessionOf(exchange)!!, PermissionRequestId(exchange.path[4]), decision, message)
            }
            assertOutcome(exchange, result)
        }
        assertEquals(PermissionDecision.entries.toSet(), decisions, "every decision was replayed")
    }

    @Test
    fun `the recorded form of every field type decodes`() = runTest {
        TestServer().use { t ->
            val detail = ApiJson.parseToJsonElement(fixture("form-fields/http/03-detail.json")).jsonObject
            t.enqueue(body = """{"data":[${detail.getValue("data")}]}""")
            val form = HttpInteractionApi(t.transport).listForms(SessionId("ses_00000000000000000000000011")).single()
            assertEquals(FormKind.Other("fixture"), form.kind)
            assertEquals(
                listOf(
                    FormField.FreeText(
                        key = "name", title = "Name", description = "Your name", required = true,
                        minLength = 1, maxLength = 20, pattern = "^[A-Za-z]+$", placeholder = "Ada", defaultValue = "Ada",
                    ),
                    FormField.FreeText(key = "mail", title = "Mail", format = TextFormat.Email),
                    FormField.SingleChoice(
                        key = "plan", title = "Plan",
                        options = listOf(FormOption("free", "Free"), FormOption("pro", "Pro")), allowCustom = true,
                    ),
                    FormField.Number(
                        key = "seats", title = "Seats",
                        visibleWhen = listOf(FieldCondition.Equals("plan", DataValue.Text("pro"))),
                        integerOnly = true, min = BigDecimal.ONE, max = BigDecimal.TEN, defaultValue = BigDecimal(2),
                    ),
                    FormField.Number(
                        key = "ratio", title = "Ratio",
                        visibleWhen = listOf(FieldCondition.NotEquals("plan", DataValue.Text("free"))),
                        min = BigDecimal.ZERO, max = BigDecimal.ONE, defaultValue = BigDecimal("0.5"),
                    ),
                    FormField.YesNo(key = "agree", title = "Agree", defaultValue = false),
                    FormField.MultipleChoice(
                        key = "extras", title = "Extras",
                        options = listOf(FormOption("a", "A"), FormOption("b", "B"), FormOption("c", "C")),
                        minSelected = 1, maxSelected = 2,
                    ),
                    FormField.FreeText(key = "hidden", hidden = true, defaultValue = "secretless"),
                ),
                form.fields,
            )
        }
    }

    @Test
    fun `the recorded external field decodes`() = runTest {
        TestServer().use { t ->
            val detail = ApiJson.parseToJsonElement(fixture("form-fields/http/07-detail-external.json")).jsonObject
            t.enqueue(body = """{"data":[${detail.getValue("data")}]}""")
            val form = HttpInteractionApi(t.transport).listForms(SessionId("ses_00000000000000000000000011")).single()
            assertEquals(FormKind.Other(null), form.kind)
            assertEquals(
                listOf(FormField.External("login", "Sign in", "Opens a page", "https://example.invalid/login")),
                form.fields,
            )
        }
    }

    private fun recordedCount(exchange: Exchange): Int =
        ApiJson.parseToJsonElement(exchange.body).jsonObject.getValue("data").jsonArray.size

    /** A 2xx exchange must succeed; any other must fail with the error its status maps to. */
    private fun assertOutcome(exchange: Exchange, result: Result<*>) {
        if (exchange.status in 200..299) {
            result.getOrThrow()
            return
        }
        val failure = result.exceptionOrNull()
        val error = (failure as? ApiException)?.error
        val message = exchange.body.let { ApiJson.parseToJsonElement(it).jsonObject["message"]?.jsonPrimitive?.content }
        val expected: ApiError = when (exchange.status) {
            400 -> ApiError.BadRequest(message)
            404 -> ApiError.NotFound
            409 -> ApiError.Conflict(message)
            else -> error("unexpected recorded status in $exchange")
        }
        assertEquals(expected, error, "$exchange")
    }

    @Test
    fun `a recorded error answer carries no low-level cause`() = runTest {
        val exchange = formReplies.first { it.status == 409 }
        TestServer().use { t ->
            t.enqueue(status = exchange.status, body = exchange.body)
            val error = apiError {
                HttpInteractionApi(t.transport).replyForm(sessionOf(exchange)!!, FormId(exchange.path[4]), emptyMap())
            }
            assertTrue(error is ApiError.Conflict)
        }
    }
}
