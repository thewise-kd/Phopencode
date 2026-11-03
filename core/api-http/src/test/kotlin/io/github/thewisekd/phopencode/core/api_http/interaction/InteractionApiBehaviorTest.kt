package io.github.thewisekd.phopencode.core.api_http.interaction

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.apiError
import io.github.thewisekd.phopencode.core.api_http.base.assertApiError
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FieldCondition
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.FormOption
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect

class InteractionApiBehaviorTest {
    private val session = SessionId("ses_1")
    private val form = FormId("frm_1")
    private val request = PermissionRequestId("per_1")

    private suspend fun listForms(fields: String, extra: String = ""): Form = TestServer().use { t ->
        t.enqueue(body = """{"data":[{"id":"frm_1","sessionID":"ses_1","title":"T"$extra,"fields":[$fields]}]}""")
        HttpInteractionApi(t.transport).listForms(session).single()
    }

    private suspend fun field(json: String): FormField = listForms(json).fields.single()

    private suspend fun formsFailWith(error: ApiError, body: String) = TestServer().use { t ->
        t.enqueue(body = body)
        assertApiError(error) { HttpInteractionApi(t.transport).listForms(session) }
    }

    private suspend fun permissionsFailWith(error: ApiError, body: String) = TestServer().use { t ->
        t.enqueue(body = body)
        assertApiError(error) { HttpInteractionApi(t.transport).listPermissions(session) }
    }

    private suspend fun sentAnswer(answers: Map<String, DataValue>): String = TestServer().use { t ->
        t.enqueue(status = 204)
        HttpInteractionApi(t.transport).replyForm(session, form, answers)
        t.server.takeRequest().body!!.utf8()
    }

    // Routes

    @Test
    fun `lists go to the session's route with a session, and to the folder's route without one`() = runTest {
        TestServer().use { t ->
            repeat(6) { t.enqueue(body = """{"data":[]}""") }
            val api = HttpInteractionApi(t.transport)
            api.listForms(session, Location("/srv/ignored"))
            api.listForms(location = Location("/srv/a b"))
            api.listForms()
            api.listPermissions(session, Location("/srv/ignored"))
            api.listPermissions(location = Location("/srv/a b"))
            api.listPermissions()
            val targets = List(6) { t.server.takeRequest().target }
            assertEquals(
                listOf(
                    "/api/session/ses_1/form",
                    "/api/form?location%5Bdirectory%5D=%2Fsrv%2Fa%20b",
                    "/api/form",
                    "/api/session/ses_1/permission",
                    "/api/permission/request?location%5Bdirectory%5D=%2Fsrv%2Fa%20b",
                    "/api/permission/request",
                ),
                targets,
            )
        }
    }

    @Test
    fun `replies and cancels go to the item's own session`() = runTest {
        TestServer().use { t ->
            repeat(3) { t.enqueue(status = 204) }
            val api = HttpInteractionApi(t.transport)
            api.replyForm(session, form, mapOf("q0" to DataValue.Text("a")))
            api.cancelForm(session, form)
            api.replyPermission(session, request, PermissionDecision.Always)
            val sent = List(3) { t.server.takeRequest() }
            assertEquals(
                listOf(
                    "POST /api/session/ses_1/form/frm_1/reply",
                    "DELETE /api/session/ses_1/form/frm_1",
                    "POST /api/session/ses_1/permission/per_1/reply",
                ),
                sent.map { "${it.method} ${it.target}" },
            )
            assertEquals("", sent[1].body?.utf8().orEmpty(), "a cancel has no body")
            assertEquals("""{"decision":"always"}""", sent[2].body!!.utf8())
        }
    }

    @Test
    fun `a rejection carries its message, and no message is left out rather than sent as null`() = runTest {
        TestServer().use { t ->
            repeat(2) { t.enqueue(status = 204) }
            val api = HttpInteractionApi(t.transport)
            api.replyPermission(session, request, PermissionDecision.Reject, "Not \"this\" file.")
            api.replyPermission(session, request, PermissionDecision.Once)
            assertEquals("""{"decision":"reject","message":"Not \"this\" file."}""", t.server.takeRequest().body!!.utf8())
            assertEquals("""{"decision":"once"}""", t.server.takeRequest().body!!.utf8())
        }
    }

    @Test
    fun `an ID that would change the route is refused before anything is sent`() = runTest {
        TestServer().use { t ->
            val api = HttpInteractionApi(t.transport)
            val calls: List<suspend () -> Unit> = listOf(
                { api.listForms(SessionId("..")) },
                { api.replyForm(session, FormId("."), mapOf("q0" to DataValue.Text("a"))) },
                { api.cancelForm(SessionId("%2e%2e"), form) },
                { api.listPermissions(SessionId("")) },
                { api.replyPermission(session, PermissionRequestId(".."), PermissionDecision.Once) },
            )
            for (call in calls) assertTrue(apiError { call() } is ApiError.BadRequest)
            assertEquals(0, t.server.requestCount)
        }
    }

    // Answer encoding

    @Test
    fun `a q0 string answer is a JSON string, and a q0 multiselect answer a JSON array of strings`() = runTest {
        assertEquals("""{"answer":{"q0":"Red"}}""", sentAnswer(mapOf("q0" to DataValue.Text("Red"))))
        assertEquals(
            """{"answer":{"q0":["S","L"]}}""",
            sentAnswer(mapOf("q0" to DataValue.Items(listOf(DataValue.Text("S"), DataValue.Text("L"))))),
        )
        assertEquals("""{"answer":{"q0":[]}}""", sentAnswer(mapOf("q0" to DataValue.Items(emptyList()))))
    }

    @Test
    fun `answers keep their order, and text is escaped, not interpreted`() = runTest {
        val text = "say \"hi\"\n\\ ünï 🙂"
        assertEquals(
            """{"answer":{"q1":"say \"hi\"\n\\ ünï 🙂","q0":["a,b"]}}""",
            sentAnswer(linkedMapOf("q1" to DataValue.Text(text), "q0" to DataValue.Items(listOf(DataValue.Text("a,b"))))),
        )
    }

    @Test
    fun `numbers go out exactly, booleans as booleans, and no answer as an empty object`() = runTest {
        assertEquals(
            """{"answer":{"seats":3,"ratio":0.25,"big":12345678901234567890.000000000000000001,"agree":true,"login":false}}""",
            sentAnswer(
                linkedMapOf(
                    "seats" to DataValue.Num(BigDecimal(3)),
                    "ratio" to DataValue.Num(BigDecimal("0.25")),
                    "big" to DataValue.Num(BigDecimal("12345678901234567890.000000000000000001")),
                    "agree" to DataValue.Bool(true),
                    "login" to DataValue.Bool(false),
                ),
            ),
        )
        assertEquals("""{"answer":{}}""", sentAnswer(emptyMap()))
    }

    @Test
    fun `an answer the server cannot take is refused before anything is sent, naming no value`() = runTest {
        val invalid = listOf(
            DataValue.Null,
            DataValue.Fields(mapOf("a" to DataValue.Text("secret answer"))),
            DataValue.Items(listOf(DataValue.Text("a"), DataValue.Num(BigDecimal.ONE))),
            DataValue.Items(listOf(DataValue.Items(emptyList()))),
            DataValue.Items(listOf(DataValue.Null)),
        )
        for (value in invalid) {
            TestServer().use { t ->
                val error = apiError { HttpInteractionApi(t.transport).replyForm(session, form, mapOf("q0" to value)) }
                assertTrue(error is ApiError.BadRequest, "$value gave $error")
                assertFalse("secret" in error.toString(), "the error names no value")
                assertEquals(0, t.server.requestCount)
            }
        }
    }

    // Sending at most once

    /** A pooled connection that the server drops after reading the next request. */
    private suspend fun TestServer.dropAfterReading() {
        enqueue(body = "{}")
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        enqueue(status = 204)
        transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info")))
    }

    @Test
    fun `answers and cancels reach the server at most once after a dropped connection`() = runTest {
        val once: Map<String, suspend HttpInteractionApi.() -> Unit> = mapOf(
            "replyForm" to { replyForm(session, form, mapOf("q0" to DataValue.Text("a"))) },
            "cancelForm" to { cancelForm(session, form) },
            "replyPermission" to { replyPermission(session, request, PermissionDecision.Always) },
        )
        for ((name, call) in once) {
            TestServer().use { t ->
                t.dropAfterReading()
                assertApiError(ApiError.Network) { HttpInteractionApi(t.transport).call() }
                // The warm-up request, then the one attempt the server dropped.
                assertEquals(2, t.server.requestCount, name)
            }
        }
    }

    @Test
    fun `lists only read, so they may be resent after a dropped connection`() = runTest {
        val lists: Map<String, suspend HttpInteractionApi.() -> Unit> = mapOf(
            "listForms" to { listForms(session) },
            "listPermissions" to { listPermissions(location = Location("/srv/a")) },
        )
        for ((name, call) in lists) {
            TestServer().use { t ->
                t.enqueue(body = "{}")
                t.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
                t.enqueue(body = """{"data":[]}""")
                t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info")))
                HttpInteractionApi(t.transport).call()
                assertEquals(3, t.server.requestCount, name)
            }
        }
    }

    // Form decoding

    @Test
    fun `a question form is told by its metadata kind, and any other kind is kept raw`() = runTest {
        assertEquals(FormKind.Question, listForms("", ""","metadata":{"kind":"question"}""").kind)
        assertEquals(FormKind.Other("survey"), listForms("", ""","metadata":{"kind":"survey"}""").kind)
        assertEquals(FormKind.Other(null), listForms("", ""","metadata":{"kind":7}""").kind)
        assertEquals(FormKind.Other(null), listForms("").kind)
    }

    @Test
    fun `metadata keeps nested values and exact numbers`() = runTest {
        val metadata = listForms("", ""","metadata":{"n":0.1000,"deep":[[{"x":null}]],"b":true}""").metadata
        assertEquals(
            mapOf(
                "n" to DataValue.Num(BigDecimal("0.1")),
                "deep" to DataValue.Items(listOf(DataValue.Items(listOf(DataValue.Fields(mapOf("x" to DataValue.Null)))))),
                "b" to DataValue.Bool(true),
            ),
            metadata,
        )
        assertEquals("0.1000", (metadata.getValue("n") as DataValue.Num).value.toPlainString())
    }

    @Test
    fun `deeply nested metadata does not overflow the stack`() = runTest {
        val depth = 20_000
        val nested = "[".repeat(depth) + "]".repeat(depth)
        TestServer().use { t ->
            t.enqueue(body = """{"data":[{"id":"frm_1","sessionID":"ses_1","fields":[],"metadata":{"d":$nested}}]}""")
            // The JSON parser may refuse such depth itself; either way the call ends with a typed error or a form.
            runCatching { HttpInteractionApi(t.transport).listForms(session) }.exceptionOrNull()?.let {
                assertTrue(it is io.github.thewisekd.phopencode.core.api.ApiException, "got $it")
            }
        }
    }

    @Test
    fun `a field of an unknown type, or an external one with no url, is Unknown`() = runTest {
        assertEquals(
            FormField.Unknown(key = "k", title = "T", required = true, rawType = "colour"),
            field("""{"key":"k","title":"T","type":"colour","required":true}"""),
        )
        assertEquals(FormField.Unknown(key = "k", rawType = "external"), field("""{"key":"k","type":"external"}"""))
        assertEquals(FormField.Unknown(key = "k", rawType = ""), field("""{"key":"k"}"""))
    }

    @Test
    fun `options with no value are left out, and a missing label is the value`() = runTest {
        assertEquals(
            FormField.SingleChoice(key = "q0", options = listOf(FormOption("b", "b"))),
            field("""{"key":"q0","type":"string","options":[{"label":"A"},{"value":"b"}]}"""),
        )
    }

    @Test
    fun `a multiselect keeps its default picks and its custom flag`() = runTest {
        assertEquals(
            FormField.MultipleChoice(
                key = "q0",
                options = listOf(FormOption("a", "A")),
                allowCustom = true,
                defaultValue = listOf("a"),
            ),
            field("""{"key":"q0","type":"multiselect","options":[{"value":"a","label":"A"}],"custom":true,"default":["a"]}"""),
        )
    }

    @Test
    fun `conditions the app cannot evaluate are Unknown, described by their operator only`() = runTest {
        val f = field(
            """{"key":"k","type":"boolean","when":[{"key":"a","op":"gt","value":"secret"},{"key":"a","op":"eq"},
            {"key":"a","op":"eq","value":false},{"key":"a","op":"neq","value":2.50}]}""",
        ) as FormField.YesNo
        assertEquals(
            listOf(
                FieldCondition.Unknown("gt"),
                FieldCondition.Unknown("eq"),
                FieldCondition.Equals("a", DataValue.Bool(false)),
                FieldCondition.NotEquals("a", DataValue.Num(BigDecimal("2.5"))),
            ),
            f.visibleWhen,
        )
    }

    @Test
    fun `Infinity and NaN limits become null`() = runTest {
        assertEquals(
            FormField.Number(key = "n", min = null, max = null, defaultValue = null),
            field("""{"key":"n","type":"number","minimum":"-Infinity","maximum":"Infinity","default":"NaN"}"""),
        )
    }

    @Test
    fun `a length limit beyond Int is clamped, as nothing typed on a phone comes near it`() = runTest {
        val f = field("""{"key":"s","type":"string","minLength":0,"maxLength":9007199254740991}""") as FormField.FreeText
        assertEquals(0, f.minLength)
        assertEquals(Int.MAX_VALUE, f.maxLength)
    }

    @Test
    fun `list routes carry no state, so none is set`() = runTest {
        assertNull(listForms("", ""","state":{"status":"answered","answer":{}}""").state)
    }

    // Malformed answers

    @Test
    fun `a form with no ID, session or fields, or a field with no key, is malformed`() = runTest {
        val malformed = listOf(
            """{"data":[{"sessionID":"ses_1","fields":[]}]}""",
            """{"data":[{"id":"frm_1","fields":[]}]}""",
            """{"data":[{"id":"frm_1","sessionID":"ses_1"}]}""",
            """{"data":[{"id":"frm_1","sessionID":"ses_1","fields":[{"type":"string"}]}]}""",
            """{"data":[{"id":"frm_1","sessionID":"ses_1","fields":["q0"]}]}""",
            """{"data":[{"id":1,"sessionID":"ses_1","fields":[]}]}""",
            """{"data":[{"id":"frm_1","sessionID":"ses_1","fields":[{"key":"k","type":"string","required":"yes"}]}]}""",
            """{"data":[{"id":"frm_1","sessionID":"ses_1","fields":[{"key":"k","type":"string","minLength":1.5}]}]}""",
            """{"data":[{"id":"frm_1","sessionID":"ses_1","fields":[{"key":"k","type":"string","maxLength":-1}]}]}""",
            """{"data":[{"id":"frm_1","sessionID":"ses_1","fields":[{"key":"k","type":"number","minimum":"lots"}]}]}""",
            """{"data":"none"}""",
            """{"data":[7]}""",
            """{}""",
            """not json""",
        )
        for (body in malformed) formsFailWith(ApiError.MalformedResponse, body)
    }

    /**
     * Out of bounds: too small a scale, too large a scale, a huge exponent, 10,000 digits, and
     * exponents beyond what [BigDecimal] can hold at all.
     */
    private val outOfBounds = listOf(
        "1e-101",
        "1e101",
        "1e-100000000",
        "9".repeat(10_000),
        "1e2147483648",
        "1e-2147483649",
        "1E+9999999999",
    )

    @Test
    fun `an out-of-bounds number in an optional place reads as absent, quickly`() = runTest {
        for (number in outOfBounds) {
            val started = TimeSource.Monotonic.markNow()
            val form = listForms(
                """{"key":"n","type":"number","minimum":$number,"maximum":$number,"default":$number},
                {"key":"s","type":"string","minLength":$number,"maxLength":$number},
                {"key":"m","type":"multiselect","options":[],"minItems":$number,"maxItems":$number},
                {"key":"b","type":"boolean","when":[{"key":"a","op":"eq","value":$number}]}""",
                ""","metadata":{"kind":"question","n":$number,"list":[1,$number,2]}""",
            )
            assertEquals(
                listOf(
                    FormField.Number(key = "n"),
                    FormField.FreeText(key = "s"),
                    FormField.MultipleChoice(key = "m", options = emptyList()),
                    FormField.YesNo(key = "b", visibleWhen = listOf(FieldCondition.Unknown("eq"))),
                ),
                form.fields,
                number.take(20),
            )
            assertEquals(FormKind.Question, form.kind)
            assertEquals(
                mapOf(
                    "kind" to DataValue.Text("question"),
                    "n" to DataValue.Null,
                    "list" to DataValue.Items(
                        listOf(DataValue.Num(BigDecimal.ONE), DataValue.Null, DataValue.Num(BigDecimal(2))),
                    ),
                ),
                form.metadata,
                number.take(20),
            )

            TestServer().use { t ->
                t.enqueue(
                    body = """{"data":[{"id":"per_1","sessionID":"ses_1","action":"read","resources":[],
                    "metadata":{"n":$number}}]}""",
                )
                val permission = HttpInteractionApi(t.transport).listPermissions(session).single()
                assertEquals(mapOf("n" to DataValue.Null), permission.metadata, number.take(20))
            }
            assertTrue(started.elapsedNow() < 10.seconds, "${number.take(20)} took ${started.elapsedNow()}")
        }
    }

    @Test
    fun `numbers just inside the bounds are kept exactly`() = runTest {
        val f = field("""{"key":"n","type":"number","minimum":1e-100,"maximum":1e100}""") as FormField.Number
        assertEquals(BigDecimal("1e-100"), f.min)
        assertEquals(BigDecimal("1e100"), f.max)
    }

    @Test
    fun `one form with an out-of-bounds number leaves the other forms intact`() = runTest {
        val cases = listOf(
            "1e-100000000" to "1e101",
            "1e2147483648" to "1E+9999999999",
            "1e-2147483649" to "1e-2147483649",
        )
        for ((limit, value) in cases) {
            TestServer().use { t ->
                t.enqueue(
                    body = """{"data":[
                    {"id":"frm_1","sessionID":"ses_1","fields":[{"key":"q0","type":"string","options":[{"value":"a","label":"A"}]}]},
                    {"id":"frm_2","sessionID":"ses_1","fields":[{"key":"n","type":"integer","maximum":$limit,"minimum":$value}],"metadata":{"x":$value}},
                    {"id":"frm_3","sessionID":"ses_2","fields":[{"key":"q0","type":"multiselect","options":[{"value":"b","label":"B"}]}]}
                    ]}""",
                )
                val forms = HttpInteractionApi(t.transport).listForms(location = Location("/srv/a"))
                assertEquals(listOf("frm_1", "frm_2", "frm_3"), forms.map { it.id.value })
                assertEquals(
                    FormField.SingleChoice(key = "q0", options = listOf(FormOption("a", "A"))),
                    forms[0].fields.single(),
                )
                assertEquals(FormField.Number(key = "n", integerOnly = true), forms[1].fields.single())
                assertEquals(mapOf("x" to DataValue.Null), forms[1].metadata)
                assertEquals(
                    FormField.MultipleChoice(key = "q0", options = listOf(FormOption("b", "B"))),
                    forms[2].fields.single(),
                )
            }
        }
    }

    @Test
    fun `text or a boolean where a number belongs is still malformed`() = runTest {
        val wrong = listOf(
            """{"key":"n","type":"number","maximum":true}""",
            """{"key":"n","type":"number","maximum":"9"}""",
            """{"key":"s","type":"string","maxLength":"9"}""",
            """{"key":"s","type":"string","maxLength":false}""",
        )
        for (f in wrong) {
            formsFailWith(ApiError.MalformedResponse, """{"data":[{"id":"frm_1","sessionID":"ses_1","fields":[$f]}]}""")
        }
    }

    // Permission decoding

    @Test
    fun `a permission keeps its message and metadata, and a partial source is dropped`() = runTest {
        TestServer().use { t ->
            t.enqueue(
                body = """{"location":{"directory":"/srv/a"},"data":[{"id":"per_1","sessionID":"ses_2","action":"bash",
                "resources":["ls -la"],"message":"Run ls?","metadata":{"n":1},"source":{"type":"tool","id":"call_1"},
                "future":true}]}""",
            )
            assertEquals(
                listOf(
                    PermissionRequest(
                        id = PermissionRequestId("per_1"),
                        sessionId = SessionId("ses_2"),
                        action = "bash",
                        resources = listOf("ls -la"),
                        message = "Run ls?",
                        metadata = mapOf("n" to DataValue.Num(BigDecimal.ONE)),
                    ),
                ),
                HttpInteractionApi(t.transport).listPermissions(location = Location("/srv/a")),
            )
        }
    }

    @Test
    fun `a permission with no ID, session or action is malformed`() = runTest {
        val malformed = listOf(
            """{"data":[{"sessionID":"ses_1","action":"read","resources":[]}]}""",
            """{"data":[{"id":"per_1","action":"read","resources":[]}]}""",
            """{"data":[{"id":"per_1","sessionID":"ses_1","resources":[]}]}""",
            """{"data":[{"id":"per_1","sessionID":"ses_1","action":"read","resources":[1]}]}""",
            """{"data":[{"id":"per_1","sessionID":"ses_1","action":"read","resources":"x"}]}""",
            """{"data":[{"id":"per_1","sessionID":"ses_1","action":"read","resources":[],"save":"*"}]}""",
        )
        for (body in malformed) permissionsFailWith(ApiError.MalformedResponse, body)
    }

    // Errors and logging

    @Test
    fun `server refusals map to typed errors`() = runTest {
        val cases: List<Triple<Int, String, ApiError>> = listOf(
            Triple(400, """{"_tag":"FormInvalidAnswerError","message":"Expected string for form field: q0"}""",
                ApiError.BadRequest("Expected string for form field: q0")),
            Triple(409, """{"_tag":"FormAlreadySettledError","message":"Form already settled"}""",
                ApiError.Conflict("Form already settled")),
            Triple(404, """{"_tag":"FormNotFoundError","message":"Form not found"}""", ApiError.NotFound),
        )
        for ((status, body, expected) in cases) {
            TestServer().use { t ->
                t.enqueue(status = status, body = body)
                assertApiError(expected) {
                    HttpInteractionApi(t.transport).replyForm(session, form, mapOf("q0" to DataValue.Text("a")))
                }
            }
        }
    }

    @Test
    fun `no answer text, permission pattern or body reaches the log`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = """{"data":[{"id":"per_1","sessionID":"ses_1","action":"read","resources":["/secret/pattern"],"save":["/secret/*"]}]}""")
            t.enqueue(status = 204)
            t.enqueue(status = 204)
            val api = HttpInteractionApi(t.transport)
            api.listPermissions(location = Location("/secret/folder"))
            api.replyForm(session, form, mapOf("q0" to DataValue.Text("secret answer")))
            api.replyPermission(session, request, PermissionDecision.Reject, "secret reason")
            assertEquals(3, t.logger.lines.size)
            assertTrue(t.logger.lines.none { "secret" in it }, "${t.logger.lines}")
            assertFalse("secret" in api.toString())
        }
    }
}
