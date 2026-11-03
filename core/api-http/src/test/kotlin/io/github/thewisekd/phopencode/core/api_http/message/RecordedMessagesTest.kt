package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.api.MessageQuery
import io.github.thewisekd.phopencode.core.api.Page
import io.github.thewisekd.phopencode.core.api.PageCursor
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.ToolContent
import io.github.thewisekd.phopencode.core.model.ToolStatus
import io.github.thewisekd.phopencode.core.model.TokenUsage
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Every recorded message page decodes, completely and exactly. */
class RecordedMessagesTest {
    private suspend fun decode(body: String, session: SessionId = SESSION): Page<Message> = TestServer().use { t ->
        t.enqueue(body = body)
        HttpMessageApi(t.transport).list(session)
    }

    private suspend fun messages(path: String): List<Message> = decode(fixture(path)).items

    @Test
    fun `every recorded page decodes in order, with nothing unknown`() = runTest {
        var assistants = 0
        for (exchange in Recordings.messagePages) {
            val raw = ApiJson.parseToJsonElement(exchange.responseBody).jsonObject
            val data = raw.getValue("data").jsonArray.map { it.jsonObject }
            val session = SessionId(exchange.sessionId)
            val page = decode(exchange.responseBody, session)

            assertEquals(data.map { it.text("id") }, page.items.map { it.id.value }, "$exchange ids")
            for ((wire, message) in data.zip(page.items)) {
                val where = "$exchange ${message.id.value}"
                assertEquals(session, message.sessionId, where)
                assertEquals(Instant.fromEpochMilliseconds(wire.time("created")!!), message.createdAt, where)
                assertTrue(message !is Message.Unknown, "$where decoded as unknown")
                if (message is Message.Assistant) {
                    assistants++
                    assertTrue(message.blocks.none { it is AssistantBlock.Unknown }, where)
                    assertTrue(
                        message.blocks.filterIsInstance<AssistantBlock.Tool>().none { it.status is ToolStatus.Unknown },
                        where,
                    )
                }
                checkUsage(wire, message, where)
            }
            val cursor = raw["cursor"]?.jsonObject
            assertEquals(cursor?.get("previous")?.jsonPrimitive?.content?.let(::PageCursor), page.previousCursor, "$exchange")
            assertEquals(cursor?.get("next")?.jsonPrimitive?.content?.let(::PageCursor), page.nextCursor, "$exchange")
        }
        assertTrue(assistants > 20)
    }

    /** Counters and the exact cost text, for every message that reports them. */
    private fun checkUsage(wire: JsonObject, message: Message, where: String) {
        val usage = when (message) {
            is Message.Assistant -> message.usage
            is Message.Compaction -> message.usage
            else -> return
        }
        val tokens = wire["tokens"]?.jsonObject
        val cost = wire["cost"]?.jsonPrimitive?.content
        if (tokens == null && cost == null) {
            assertNull(usage, where)
            return
        }
        assertNotNull(usage, where)
        assertEquals(cost?.let(Cost::parse), usage.cost, "$where cost")
        assertEquals(cost == null, usage.hasMissingCost, "$where missing cost")
        if (tokens != null) {
            val cache = tokens.getValue("cache").jsonObject
            assertEquals(tokens.text("input").toBigInteger(), usage.input, where)
            assertEquals(tokens.text("output").toBigInteger(), usage.output, where)
            assertEquals(tokens.text("reasoning").toBigInteger(), usage.reasoning, where)
            assertEquals(cache.text("read").toBigInteger(), usage.cacheRead, where)
            assertEquals(cache.text("write").toBigInteger(), usage.cacheWrite, where)
        }
    }

    @Test
    fun `a text answer keeps its text, model and usage`() = runTest {
        val page = decode(fixture("text/http/05-messages-page-2.json"))
        val assistant = assertIs<Message.Assistant>(page.items.single())
        assertEquals(listOf<AssistantBlock>(AssistantBlock.Text("Hello from the simulated model. Türkçe: ığüşöç.")), assistant.blocks)
        assertEquals("build", assistant.agent)
        assertEquals(ModelRef("sim", "sim-1"), assistant.model)
        assertEquals("stop", assistant.finishReason)
        assertEquals(Instant.fromEpochMilliseconds(1700000010504), assistant.completedAt)
        assertEquals(TokenUsage.of(input = 120, output = 16, cost = Cost.parse("0.000276")), assistant.usage)
        assertEquals("0.000276", assistant.usage!!.cost.toString())
    }

    @Test
    fun `reasoning keeps its text, times and the cached and reasoning tokens`() = runTest {
        val assistant = messages("reasoning/http/05-final-messages.json")
            .filterIsInstance<Message.Assistant>().single { it.id == MessageId("msg_00000000000000000000000008") }
        assertEquals(
            AssistantBlock.Reasoning(
                "Weighing the options.",
                Instant.fromEpochMilliseconds(1700000011496),
                Instant.fromEpochMilliseconds(1700000013925),
            ),
            assistant.blocks[0],
        )
        assertEquals(AssistantBlock.Text("The answer is 42."), assistant.blocks[1])
        val usage = assistant.usage!!
        assertEquals(BigInteger.valueOf(25), usage.reasoning)
        assertEquals(BigInteger.valueOf(50), usage.cacheRead)
        assertEquals("0.0004725", usage.cost.toString())
    }

    @Test
    fun `tools keep input, content, metadata, errors and times`() = runTest {
        val tools = messages("tool/http/04-final-messages.json")
            .filterIsInstance<Message.Assistant>().flatMap { it.blocks }.filterIsInstance<AssistantBlock.Tool>()
            .associateBy { it.id }

        val shell = tools.getValue("call_shell")
        assertEquals("shell", shell.name)
        assertEquals(ToolStatus.Completed, shell.status)
        assertEquals(false, shell.executed)
        assertEquals(DataValue.Fields(mapOf("command" to DataValue.Text("printf 'one\\ntwo\\n'"))), shell.input)
        assertEquals(listOf<ToolContent>(ToolContent.Text("one\ntwo\n")), shell.content)
        assertEquals(DataValue.Num(java.math.BigDecimal.ZERO), shell.metadata["exit"])
        assertEquals(DataValue.Bool(false), shell.metadata["truncated"])
        assertEquals(Instant.fromEpochMilliseconds(1700000017833), shell.createdAt)
        assertEquals(Instant.fromEpochMilliseconds(1700000017875), shell.startedAt)
        assertEquals(Instant.fromEpochMilliseconds(1700000017916), shell.completedAt)
        assertNull(shell.childSessionId)

        val missing = tools.getValue("call_read_missing")
        assertEquals(ToolStatus.Failed, missing.status)
        assertEquals(StructuredError("tool.execution", "File not found: missing.txt"), missing.error)
        assertTrue(missing.content.isEmpty())
    }

    @Test
    fun `a subagent tool names its child session`() = runTest {
        val tool = messages("subagent/http/08-final-messages.json")
            .filterIsInstance<Message.Assistant>().flatMap { it.blocks }.filterIsInstance<AssistantBlock.Tool>().single()
        assertEquals("subagent", tool.name)
        assertEquals(SessionId("ses_00000000000000000000000007"), tool.childSessionId)
    }

    @Test
    fun `failures keep the structured error on the step and the idle marker`() = runTest {
        val messages = messages("error/http/04-final-messages.json")
        val idle = assertIs<Message.Idle>(messages[0])
        assertEquals(SessionOutcome.Failed, idle.outcome)
        val error = idle.error!!
        assertEquals("provider.invalid-request", error.type)
        assertEquals("Simulated invalid request", error.message)
        assertEquals(200, error.status)
        assertTrue(error.responseBody!!.contains("invalid_request_error"))

        val assistant = assertIs<Message.Assistant>(messages[1])
        assertEquals(error, assistant.error)
        assertEquals("error", assistant.finishReason)
        assertTrue(assistant.blocks.isEmpty())
        assertNull(assistant.usage, "no usage was reported")
        assertNull(assistant.totalUsage.cost)
    }

    @Test
    fun `compactions keep status, summary, usage and errors`() = runTest {
        val compactions = messages("compaction/http/06-final-messages.json").filterIsInstance<Message.Compaction>()
        val done = compactions.single { it.status == "completed" }
        assertEquals("manual", done.reason)
        assertEquals(ModelRef("sim", "sim-1"), done.model)
        assertTrue(done.summary!!.startsWith("## Objective"))
        assertEquals("0.00804", done.usage!!.cost.toString())

        val failed = compactions.single { it.status == "failed" }
        assertEquals("compaction.failed", failed.error!!.type)
        assertEquals(Cost.ZERO, failed.usage!!.cost)
        assertNull(failed.summary)
    }

    @Test
    fun `shell, switches, synthetic and user messages keep their fields`() = runTest {
        val commands = messages("commands/http/09-final-messages.json")
        val shell = commands.filterIsInstance<Message.Shell>().single()
        assertEquals("printf 'from shell\\n'", shell.command)
        assertEquals("from shell\n", shell.output)

        val agents = commands.filterIsInstance<Message.AgentSwitched>()
        assertTrue(agents.any { it.agent == "plan" })
        assertTrue(agents.any { it.agent == "build" && it.previous == "plan" })

        val models = commands.filterIsInstance<Message.ModelSwitched>()
        assertTrue(models.any { it.model == ModelRef("sim", "sim-2") })
        val variant = commands.filterIsInstance<Message.Assistant>().first { it.model?.modelId == "sim-2" }
        assertEquals(ModelRef("sim", "sim-2", "default"), variant.model)

        val retry = messages("retry/http/04-final-messages.json")
        assertTrue(retry.filterIsInstance<Message.Synthetic>().single().text.startsWith("The previous response"))
        val user = retry.filterIsInstance<Message.User>().single()
        assertEquals("Survive a dropped provider stream.", user.text)
        assertTrue(user.files.isEmpty() && user.agents.isEmpty() && user.skills.isEmpty())
    }

    @Test
    fun `the first page of a paged list carries both cursors`() = runTest {
        val page = decode(fixture("text/http/04-messages-page-1.json"))
        val idle = assertIs<Message.Idle>(page.items.single())
        assertEquals(SessionOutcome.Succeeded, idle.outcome)
        assertNotNull(page.previousCursor)
        assertNotNull(page.nextCursor)
        assertTrue(!page.isEnd)
    }

    @Test
    fun `list sends the query it was given`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = """{"data":[],"cursor":{"previous":null,"next":null}}""")
            val query = MessageQuery(limit = 5, order = SortOrder.Descending, type = "user")
            val page = HttpMessageApi(t.transport).list(SESSION, query)
            assertEquals("/api/session/ses_1/message?limit=5&order=desc&type=user", t.server.takeRequest().target)
            assertTrue(page.isEnd)
            assertNull(page.nextCursor)
        }
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.time(name: String): Long? =
        get("time")?.jsonObject?.get(name)?.jsonPrimitive?.content?.toBigDecimal()?.toLong()

    private companion object {
        val SESSION = SessionId("ses_1")
    }
}
