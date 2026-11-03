package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.api.MessageQuery
import io.github.thewisekd.phopencode.core.api.PageCursor
import io.github.thewisekd.phopencode.core.api.Prompt
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Replays every recorded call of the message routes: the request this module sends must be the one
 * the recording sent, path, query and body alike, and the recorded answer must decode.
 */
class RecordedRequestsTest {
    private suspend fun replay(exchange: Exchange, call: suspend HttpMessageApi.(SessionId) -> Unit) {
        TestServer().use { t ->
            t.enqueue(status = exchange.status, body = exchange.response?.let { exchange.responseBody } ?: "")
            HttpMessageApi(t.transport).call(SessionId(exchange.sessionId))

            val sent = t.server.takeRequest()
            assertEquals(exchange.method, sent.method, "$exchange method")
            assertEquals(exchange.target, sent.target, "$exchange target")
            val expectedBody = exchange.request?.let(Recordings::compact) ?: ""
            assertEquals(expectedBody, sent.body?.utf8() ?: "", "$exchange body")
        }
    }

    private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.content

    @Test
    fun `prompts match the recorded requests`() = runTest {
        assertTrue(Recordings.prompts.size >= 20)
        val deliveries = mutableSetOf<String?>()
        for (exchange in Recordings.prompts) {
            val body = exchange.request!!.jsonObject
            deliveries += body.string("delivery")
            val prompt = Prompt(
                text = body.string("text")!!,
                delivery = when (body.string("delivery")) {
                    "steer" -> PromptDelivery.Steer
                    "queue" -> PromptDelivery.Queue
                    else -> null
                },
            )
            replay(exchange) { prompt(it, prompt) }
        }
        assertEquals(setOf(null, "steer", "queue"), deliveries, "every recorded delivery was replayed")
    }

    @Test
    fun `commands match the recorded requests`() = runTest {
        assertTrue(Recordings.commands.isNotEmpty())
        for (exchange in Recordings.commands) {
            val body = exchange.request!!.jsonObject
            replay(exchange) { command(it, body.string("name")!!, body.string("text")!!) }
        }
    }

    @Test
    fun `agent switches match the recorded requests`() = runTest {
        assertTrue(Recordings.agentSwitches.size >= 2)
        for (exchange in Recordings.agentSwitches) {
            replay(exchange) { switchAgent(it, exchange.request!!.jsonObject.string("agent")!!) }
        }
    }

    @Test
    fun `model switches match the recorded requests`() = runTest {
        assertTrue(Recordings.modelSwitches.size >= 2)
        for (exchange in Recordings.modelSwitches) {
            val model = exchange.request!!.jsonObject.getValue("model").jsonObject
            val ref = ModelRef(model.string("providerID")!!, model.string("id")!!, model.string("variant"))
            replay(exchange) { switchModel(it, ref) }
        }
    }

    @Test
    fun `turn diffs match the recorded requests and decode`() = runTest {
        assertEquals(4, Recordings.diffs.size)
        for (exchange in Recordings.diffs) {
            val query = Recordings.query(exchange.target).toMap()
            replay(exchange) {
                val diffs = turnDiff(
                    it,
                    from = query["from"]?.let(::MessageId),
                    to = query["to"]?.let(::MessageId),
                    context = query["context"]?.toInt(),
                )
                val recorded = ApiJson.parseToJsonElement(exchange.responseBody).jsonObject.getValue("data").jsonArray
                assertEquals(recorded.size, diffs.size, "$exchange")
                assertTrue(diffs.all { d -> d.file.path.isNotEmpty() && d.patch!!.startsWith("diff --git") }, "$exchange")
            }
        }
    }

    @Test
    fun `revert stages match the recorded requests`() = runTest {
        assertEquals(2, Recordings.revertStages.size)
        for (exchange in Recordings.revertStages) {
            val body = exchange.request!!.jsonObject
            replay(exchange) {
                val revert = stageRevert(it, MessageId(body.string("messageID")!!), body["files"]?.jsonPrimitive?.boolean)
                assertEquals(body.string("messageID"), revert.messageId.value)
            }
        }
    }

    @Test
    fun `revert commit and clear match the recorded requests`() = runTest {
        assertEquals(1, Recordings.revertCommits.size)
        assertEquals(1, Recordings.revertClears.size)
        Recordings.revertCommits.forEach { replay(it) { id -> commitRevert(id) } }
        Recordings.revertClears.forEach { replay(it) { id -> cancelRevert(id) } }
    }

    @Test
    fun `message lists match the recorded requests`() = runTest {
        assertTrue(Recordings.messagePages.size >= 20)
        for (exchange in Recordings.messagePages) {
            val query = Recordings.query(exchange.target).toMap()
            val messageQuery = MessageQuery(
                limit = query["limit"]?.toInt(),
                order = when (query["order"]) {
                    "asc" -> SortOrder.Ascending
                    "desc" -> SortOrder.Descending
                    else -> null
                },
                type = query["type"],
                cursor = query["cursor"]?.let(::PageCursor),
            )
            replay(exchange) { list(it, messageQuery) }
        }
    }

    @Test
    fun `a cursor is sent without the order, which it already carries`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = """{"data":[],"cursor":{}}""")
            val query = MessageQuery(limit = 2, order = SortOrder.Ascending, type = "user", cursor = PageCursor("c+/="))
            HttpMessageApi(t.transport).list(SessionId("ses_1"), query)
            assertEquals("/api/session/ses_1/message?limit=2&type=user&cursor=c%2B%2F%3D", t.server.takeRequest().target)
        }
    }

    @Test
    fun `a prompt sends attachments and resume in the server's shape`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = Recordings.prompts.first().responseBody)
            val prompt = Prompt("hi", listOf("src/a.kt"), listOf("plan"), listOf("report"), PromptDelivery.Queue, resume = false)
            HttpMessageApi(t.transport).prompt(SessionId("ses_1"), prompt)
            assertEquals(
                """{"text":"hi","files":[{"uri":"src/a.kt"}],"agents":[{"name":"plan"}],"skills":[{"id":"report"}],""" +
                    """"delivery":"queue","resume":false}""",
                t.server.takeRequest().body!!.utf8(),
            )
        }
    }

    @Test
    fun `a model switch sends the variant`() = runTest {
        TestServer().use { t ->
            t.enqueue(status = 204)
            HttpMessageApi(t.transport).switchModel(SessionId("ses_1"), ModelRef("sim", "sim-2", "high"))
            assertEquals("""{"model":{"id":"sim-2","providerID":"sim","variant":"high"}}""", t.server.takeRequest().body!!.utf8())
        }
    }
}
