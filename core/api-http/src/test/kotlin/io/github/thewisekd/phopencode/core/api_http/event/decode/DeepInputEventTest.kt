package io.github.thewisekd.phopencode.core.api_http.event.decode

import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.model.DataValue
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Event data nested past kotlinx-serialization's parser depth limit (`maxNestingDepth`, 400 by
 * default) is rejected by the parser before the recursive `toDataValue` runs, so the event ends as
 * malformed and never escapes as an `Error` such as StackOverflowError (audit finding A05-2, closed
 * as not reproducible). If a library upgrade drops or raises that limit, these tests show it.
 */
class DeepInputEventTest {
    @OptIn(ExperimentalSerializationApi::class)
    private val parserDepthLimit: Int = ApiJson.configuration.maxNestingDepth

    private fun arrays(depth: Int): String = "[".repeat(depth) + "0" + "]".repeat(depth)

    private fun objects(depth: Int): String = """{"a":""".repeat(depth) + "0" + "}".repeat(depth)

    private fun toolCalled(input: String): String =
        """{"id":"evt_1","type":"session.tool.called","data":{"sessionID":"ses_1","id":"call_1","input":$input}}"""

    private fun decode(text: String): DecodeResult {
        val outcome = runCatching { EventDecoder.decodeResult("session.tool.called", text, "evt_1") }
        val error = outcome.exceptionOrNull()
        assertTrue(error == null, "deep input escaped as ${error?.javaClass?.name}")
        return outcome.getOrThrow()
    }

    @Test
    fun `tool input past the parser depth limit is a malformed event, not an Error`() {
        for (deep in listOf(arrays(100_000), objects(100_000), arrays(parserDepthLimit + 1), objects(parserDepthLimit + 1))) {
            assertEquals(DecodeResult.Malformed("session.tool.called", "evt_1"), decode(toolCalled(deep)))
        }
    }

    @Test
    fun `tool input at a realistic depth still decodes exactly`() {
        val depth = 50
        val number = "0.100000000000000000000000000001"
        val result = assertIs<DecodeResult.Decoded>(decode(toolCalled(arrays(depth).replace("0", number))))
        var value = assertIs<ServerEvent.ToolCalled>(result.event).input
        repeat(depth) { value = assertIs<DataValue.Items>(value).values.single() }
        assertEquals(DataValue.Num(BigDecimal(number)), value)
    }
}
