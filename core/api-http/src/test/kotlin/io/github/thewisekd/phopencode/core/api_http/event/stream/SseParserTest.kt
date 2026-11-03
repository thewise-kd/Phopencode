package io.github.thewisekd.phopencode.core.api_http.event.stream

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SseParserTest {
    private fun parse(wire: String, maxEventBytes: Int = SseParser.DEFAULT_MAX_EVENT_BYTES): List<SseItem> {
        val parser = SseParser(maxEventBytes)
        return wire.split("\n").map { it.removeSuffix("\r") }.mapNotNull(parser::feed)
    }

    private fun events(wire: String) = parse(wire).filterIsInstance<SseEvent>()

    @Test
    fun `every recorded event is read in order and the heartbeat is a comment`() {
        for (flow in listOf("startup", "text")) {
            val items = parse(fixture("$flow/sse-wire.txt"))
            val expected = fixture("$flow/events.jsonl").lines().filter { it.isNotEmpty() }
            assertEquals(expected, items.filterIsInstance<SseEvent>().map { it.data }, flow)
            assertTrue(items.filterIsInstance<SseEvent>().all { it.type == null && it.id == null }, flow)
        }
        val startup = parse(fixture("startup/sse-wire.txt"))
        assertEquals(SseComment("heartbeat"), startup[1])
        assertEquals(1, startup.count { it is SseComment })
    }

    @Test
    fun `a heartbeat comment is a comment, with or without the space`() {
        assertEquals(listOf(SseComment("heartbeat"), SseComment("heartbeat"), SseComment("")), parse(": heartbeat\n:heartbeat\n:\n"))
    }

    @Test
    fun `a comment inside an event does not end it`() {
        assertEquals(listOf(SseComment("x"), SseEvent(null, null, "a\nb")), parse("data: a\n: x\ndata: b\n\n"))
    }

    @Test
    fun `data lines join with a newline, and only one leading space is removed`() {
        assertEquals(listOf("a\n b\n"), events("data: a\ndata:  b\ndata\n\n").map { it.data })
    }

    @Test
    fun `event and id fields are read, and the id carries over to later events`() {
        val read = events("event: one\nid: 7\ndata: x\n\ndata: y\n\nid\ndata: z\n\n")
        assertEquals(listOf(SseEvent("one", "7", "x"), SseEvent(null, "7", "y"), SseEvent(null, "", "z")), read)
    }

    @Test
    fun `an id holding NUL is ignored`() {
        assertEquals(listOf(SseEvent(null, null, "x")), events("id: a\u0000b\ndata: x\n\n"))
    }

    @Test
    fun `a blank line without data dispatches nothing and resets the type`() {
        assertEquals(listOf(SseEvent(null, null, "x")), events("event: lost\n\n\ndata: x\n\n"))
    }

    @Test
    fun `retry and unknown fields are ignored, and an event is only sent on a blank line`() {
        assertEquals(emptyList(), parse("retry: 10\nfoo: bar\ndata: unfinished"))
    }

    @Test
    fun `a leading byte order mark is dropped from the first line only`() {
        assertEquals(listOf(SseEvent(null, null, "\uFEFFx")), events("\uFEFFdata: x\n\ndata: \uFEFFx\n\n").drop(1))
        assertEquals("x", events("\uFEFFdata: x\n\n").single().data)
    }

    @Test
    fun `CRLF line endings are accepted`() {
        assertEquals(listOf(SseEvent(null, null, "x")), events("data: x\r\n\r\n"))
    }

    @Test
    fun `an event one char over the limit is a malformed reply`() {
        for (lines in listOf(listOf("01234567890"), listOf("12345", "67890"))) {
            val parser = SseParser(maxEventBytes = 10)
            val failure = assertFailsWith<ApiException>("$lines") { lines.forEach { parser.feed("data: $it") } }
            assertEquals(ApiError.MalformedResponse, failure.error)
            assertNull(failure.cause)
        }
    }

    @Test
    fun `an event exactly at the limit passes, on one line or several`() {
        for (data in listOf("0123456789", "12345\n6789", "1\n2\n3\n4\n5\n")) {
            val wire = data.split("\n").joinToString("") { "data: $it\n" } + "\n"
            assertEquals(data, parse(wire, maxEventBytes = 10).filterIsInstance<SseEvent>().single().data, wire)
        }
    }

    @Test
    fun `the limit counts UTF-8 bytes, exactly the limit passes and one byte more fails`() {
        // ç and ş take 2 bytes, the emoji 4 (a surrogate pair of 2 chars).
        val fits = listOf("ççççab", "ş🙂şab", "🙂🙂\na", "ç\nç🙂a", "şş\nşş\n")
        val tooBig = listOf("ççççça", "🙂🙂abc", "🙂🙂\nab", "çç\nç🙂", "şş\nşş\na")
        for (data in fits) {
            assertEquals(10, (data.toByteArray(Charsets.UTF_8).size), "test data $data")
            val wire = data.split("\n").joinToString("") { "data: $it\n" } + "\n"
            assertEquals(data, parse(wire, maxEventBytes = 10).filterIsInstance<SseEvent>().single().data, data)
        }
        for (data in tooBig) {
            assertEquals(11, (data.toByteArray(Charsets.UTF_8).size), "test data $data")
            val parser = SseParser(maxEventBytes = 10)
            val failure = assertFailsWith<ApiException>(data) { data.split("\n").forEach { parser.feed("data: $it") } }
            assertEquals(ApiError.MalformedResponse, failure.error)
        }
    }

    @Test
    fun `printing an event never shows its data`() {
        val printed = SseEvent("t", "i", "secret-file-content").toString()
        assertFalse("secret-file-content" in printed, printed)
        assertTrue("19" in printed, printed)
    }
}
