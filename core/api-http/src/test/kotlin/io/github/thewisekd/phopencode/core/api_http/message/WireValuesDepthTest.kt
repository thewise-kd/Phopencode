package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.apiError
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.SessionId
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException

/**
 * A tool part's `input` and a part's `metadata` come from the model and the tools, so they can be
 * nested as deeply as anyone likes. [toDataValue] is recursive, yet deep input cannot overflow the
 * stack: kotlinx-serialization's parser rejects nesting beyond `JsonConfiguration.maxNestingDepth`
 * (400 by default; `ApiJson` keeps the default) before the converter ever runs, and the transport
 * maps that rejection to [ApiError.MalformedResponse].
 *
 * These tests pin that behaviour (audit finding A05-2, closed as not reproducible). If a library
 * upgrade drops or raises the parser limit, the `parser depth limit` tests fail first: the
 * converter then needs its own depth cap.
 */
class WireValuesDepthTest {
    private val session = SessionId("ses_1")

    @OptIn(ExperimentalSerializationApi::class)
    private val parserDepthLimit: Int = ApiJson.configuration.maxNestingDepth

    private fun arrays(depth: Int, inner: String = "0"): String = "[".repeat(depth) + inner + "]".repeat(depth)

    private fun objects(depth: Int, inner: String = "0"): String = """{"a":""".repeat(depth) + inner + "}".repeat(depth)

    private fun user(id: String) = """{"id":"$id","time":{"created":1},"type":"user","text":"hi"}"""

    private fun assistant(id: String, state: String) =
        """{"id":"$id","time":{"created":1},"type":"assistant","agent":"build",
        "content":[{"type":"text","text":"before"},{"type":"tool","id":"c1","name":"x","state":$state,"time":{"created":1}}]}"""

    private suspend fun list(vararg items: String): List<Message> = TestServer().use { t ->
        t.enqueue(body = """{"data":[${items.joinToString(",")}],"cursor":{}}""")
        HttpMessageApi(t.transport).list(session).items
    }

    /** The whole list fails as malformed; no `Error` such as StackOverflowError escapes. */
    private suspend fun assertDeepListIsMalformed(state: String) {
        val thrown = runCatching { apiError { list(user("msg_1"), assistant("msg_2", state), user("msg_3")) } }
        val error = thrown.exceptionOrNull()
        assertTrue(error == null, "deep input escaped as ${error?.javaClass?.name}")
        assertEquals(ApiError.MalformedResponse, thrown.getOrThrow())
    }

    @Test
    fun `parser depth limit is what keeps deep input away from the recursive converter`() {
        // The converter survives this depth easily; far beyond it, it would not. A limit that is
        // gone or much higher means toDataValue needs its own depth cap (audit finding A05-2).
        assertTrue(parserDepthLimit in 1..1_000, "ApiJson parser depth limit is now $parserDepthLimit")
    }

    @Test
    fun `parser depth limit rejects deep arrays and objects before conversion`() {
        for (deep in listOf(arrays(parserDepthLimit + 1), objects(parserDepthLimit + 1), arrays(100_000), objects(100_000))) {
            val failure = runCatching { ApiJson.parseToJsonElement(deep) }.exceptionOrNull()
            assertIs<SerializationException>(failure)
        }
    }

    @Test
    fun `converter maps a value at the parser depth limit without overflowing`() {
        var value = ApiJson.parseToJsonElement(arrays(parserDepthLimit - 1)).toDataValue()
        repeat(parserDepthLimit - 1) { value = assertIs<DataValue.Items>(value).values.single() }
        assertEquals(DataValue.Num(BigDecimal("0")), value)
    }

    @Test
    fun `tool input nested 100,000 arrays deep is a malformed response, not an Error`() = runTest {
        assertDeepListIsMalformed("""{"status":"running","input":${arrays(100_000)}}""")
    }

    @Test
    fun `tool input nested 100,000 objects deep is a malformed response, not an Error`() = runTest {
        assertDeepListIsMalformed("""{"status":"running","input":${objects(100_000)}}""")
    }

    @Test
    fun `part metadata nested 100,000 arrays deep is a malformed response, not an Error`() = runTest {
        assertDeepListIsMalformed("""{"status":"completed","input":{},"metadata":{"m":${arrays(100_000)}}}""")
    }

    @Test
    fun `part metadata nested 100,000 objects deep is a malformed response, not an Error`() = runTest {
        assertDeepListIsMalformed("""{"status":"completed","input":{},"metadata":{"m":${objects(100_000)}}}""")
    }

    @Test
    fun `tool input just past the parser depth limit is a malformed response, not an Error`() = runTest {
        assertDeepListIsMalformed("""{"status":"running","input":${arrays(parserDepthLimit + 1)}}""")
    }

    @Test
    fun `tool input at a realistic depth maps exactly, numbers exact and field order kept`() = runTest {
        val depth = 50
        val number = "0.100000000000000000000000000001"
        val innerJson = """{"z":$number,"a":"t","m":true,"n":null,"big":123456789012345678901234567890}"""
        val inner = DataValue.Fields(
            linkedMapOf(
                "z" to DataValue.Num(BigDecimal(number)),
                "a" to DataValue.Text("t"),
                "m" to DataValue.Bool(true),
                "n" to DataValue.Null,
                "big" to DataValue.Num(BigDecimal("123456789012345678901234567890")),
            ),
        )
        var expected: DataValue = inner
        repeat(depth) { expected = DataValue.Fields(mapOf("a" to DataValue.Items(listOf(expected)))) }
        val input = """{"a":[""".repeat(depth) + innerJson + "]}".repeat(depth)

        val messages = list(user("msg_1"), assistant("msg_2", """{"status":"running","input":$input}"""), user("msg_3"))

        assertEquals(listOf("msg_1", "msg_2", "msg_3"), messages.map { it.id.value })
        val blocks = assertIs<Message.Assistant>(messages[1]).blocks
        assertEquals(AssistantBlock.Text("before"), blocks[0])
        val actual = assertIs<AssistantBlock.Tool>(blocks[1]).input
        assertEquals(expected, actual)

        var innermost = actual
        repeat(depth) { innermost = assertIs<DataValue.Items>(assertIs<DataValue.Fields>(innermost).values.getValue("a")).values.single() }
        assertEquals(listOf("z", "a", "m", "n", "big"), assertIs<DataValue.Fields>(innermost).values.keys.toList())
    }
}
