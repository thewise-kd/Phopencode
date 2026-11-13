package io.github.thewisekd.phopencode.feature.preview.logic.console

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleKind
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The console's pure steps: what it collects, the ring, the text cut, the filter and clearing. */
class ConsoleLogTest {
    private fun log(text: String, level: ConsoleLevel = ConsoleLevel.Log) =
        ConsoleEntries.message(level, text, source = null, line = null)

    // What it collects

    @Test
    fun `a console message keeps its level, text, source and line`() {
        val entry = ConsoleEntries.message(ConsoleLevel.Warning, "careful", source = "/app.js", line = 12)

        assertEquals(ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Warning, "careful", "/app.js", 12), entry)
    }

    @Test
    fun `an unknown line or an empty source is left out`() {
        val entry = ConsoleEntries.message(ConsoleLevel.Info, "hi", source = "", line = 0)

        assertNull(entry.source)
        assertNull(entry.line)
        assertNull(ConsoleEntries.message(ConsoleLevel.Info, "hi", source = null, line = -3).line)
    }

    @Test
    fun `a load error of the main frame or a sub-resource is an error naming the address`() {
        val main = ConsoleEntries.loadError(url = "http://127.0.0.1:4000/", description = "net::ERR_FAILED")
        val sub = ConsoleEntries.loadError(url = "http://127.0.0.1:4000/app.js", description = "net::ERR_ABORTED")

        assertEquals(
            ConsoleEntry(ConsoleKind.LoadError, ConsoleLevel.Error, "net::ERR_FAILED", "http://127.0.0.1:4000/", null),
            main,
        )
        assertEquals(ConsoleKind.LoadError, sub.kind)
        assertEquals(ConsoleLevel.Error, sub.level)
        assertEquals("http://127.0.0.1:4000/app.js", sub.source)
    }

    @Test
    fun `a load error without a description still says something`() {
        val entry = ConsoleEntries.loadError(url = "http://127.0.0.1:4000/", description = " ")

        assertTrue(entry.text.isNotBlank())
        assertTrue(ConsoleEntries.loadError(url = "http://127.0.0.1:4000/", description = null).text.isNotBlank())
    }

    @Test
    fun `an http error is an error with its status and the address`() {
        val entry = ConsoleEntries.httpError(url = "http://127.0.0.1:4000/api", status = 404, reason = "Not Found")

        assertEquals(
            ConsoleEntry(ConsoleKind.HttpError, ConsoleLevel.Error, "HTTP 404 Not Found", "http://127.0.0.1:4000/api", null),
            entry,
        )
        assertEquals("HTTP 500", ConsoleEntries.httpError("http://127.0.0.1:4000/", 500, reason = null).text)
        assertEquals("HTTP 502", ConsoleEntries.httpError("http://127.0.0.1:4000/", 502, reason = " ").text)
    }

    @Test
    fun `an http status outside 100 to 599 is refused`() {
        listOf(0, 99, 600, -1).forEach { status ->
            assertFailsWith<IllegalArgumentException> { ConsoleEntries.httpError("http://127.0.0.1:4000/", status, null) }
        }
    }

    @Test
    fun `a request refused off the origin is an error naming the address`() {
        val entry = ConsoleEntries.refused(url = "https://cdn.example/x.js")

        assertEquals(
            ConsoleEntry(ConsoleKind.RefusedRequest, ConsoleLevel.Error, "https://cdn.example/x.js", null, null),
            entry,
        )
    }

    @Test
    fun `the WebView's level names map to console levels`() {
        assertEquals(ConsoleLevel.Debug, ConsoleEntries.levelOf("DEBUG"))
        assertEquals(ConsoleLevel.Log, ConsoleEntries.levelOf("LOG"))
        assertEquals(ConsoleLevel.Info, ConsoleEntries.levelOf("TIP"))
        assertEquals(ConsoleLevel.Warning, ConsoleEntries.levelOf("WARNING"))
        assertEquals(ConsoleLevel.Error, ConsoleEntries.levelOf("ERROR"))
        assertEquals(ConsoleLevel.Log, ConsoleEntries.levelOf("SOMETHING_NEW"))
    }

    // Text cut

    @Test
    fun `text and source longer than 4 KiB are cut to 4 KiB`() {
        val long = "x".repeat(ConsoleState.MAX_TEXT_LENGTH + 100)
        val entry = ConsoleEntries.message(ConsoleLevel.Log, long, source = long, line = 1)

        assertEquals(ConsoleState.MAX_TEXT_LENGTH, entry.text.length)
        assertEquals(ConsoleState.MAX_TEXT_LENGTH, entry.source!!.length)
        assertEquals(ConsoleState.MAX_TEXT_LENGTH, ConsoleEntries.refused(long).text.length)
        assertEquals(ConsoleState.MAX_TEXT_LENGTH, ConsoleEntries.loadError(long, long).source!!.length)
        assertEquals(ConsoleState.MAX_TEXT_LENGTH, ConsoleEntries.loadError(long, long).text.length)
        assertEquals(ConsoleState.MAX_TEXT_LENGTH, ConsoleEntries.httpError(long, 500, long).text.length)
    }

    @Test
    fun `text of exactly 4 KiB is kept whole`() {
        val exact = "y".repeat(ConsoleState.MAX_TEXT_LENGTH)

        assertEquals(exact, log(exact).text)
    }

    @Test
    fun `the cut never splits a surrogate pair`() {
        val text = "a".repeat(ConsoleState.MAX_TEXT_LENGTH - 1) + "😀" + "tail"
        val cut = log(text).text

        assertEquals(ConsoleState.MAX_TEXT_LENGTH - 1, cut.length)
        assertFalse(cut.last().isHighSurrogate())
    }

    @Test
    fun `an entry built by hand is cut when it is recorded`() {
        val long = "z".repeat(ConsoleState.MAX_TEXT_LENGTH * 2)
        val raw = ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Log, long, long, 0)

        val kept = ConsoleState().recorded(raw).entries.single()

        assertEquals(ConsoleState.MAX_TEXT_LENGTH, kept.text.length)
        assertEquals(ConsoleState.MAX_TEXT_LENGTH, kept.source!!.length)
        assertNull(kept.line)
    }

    // Ring

    @Test
    fun `entries are kept oldest first`() {
        val state = ConsoleState().recorded(log("one")).recorded(log("two")).recorded(log("three"))

        assertEquals(listOf("one", "two", "three"), state.entries.map { it.text })
    }

    @Test
    fun `the ring keeps at most 500 entries and drops the oldest first`() {
        var state = ConsoleState()
        repeat(ConsoleState.MAX_ENTRIES + 25) { state = state.recorded(log("m$it")) }

        assertEquals(ConsoleState.MAX_ENTRIES, state.entries.size)
        assertEquals("m25", state.entries.first().text)
        assertEquals("m${ConsoleState.MAX_ENTRIES + 24}", state.entries.last().text)
    }

    @Test
    fun `a state already over the limit is brought back to it`() {
        val over = ConsoleState(entries = List(ConsoleState.MAX_ENTRIES + 10) { log("o$it") })

        val state = over.recorded(log("new"))

        assertEquals(ConsoleState.MAX_ENTRIES, state.entries.size)
        assertEquals("new", state.entries.last().text)
    }

    @Test
    fun `recording keeps the filter`() {
        val state = ConsoleState(filter = ConsoleLevel.Error).recorded(log("x"))

        assertEquals(ConsoleLevel.Error, state.filter)
    }

    // Filter

    @Test
    fun `the filter shows only one level and null shows all`() {
        val state = ConsoleState()
            .recorded(log("a", ConsoleLevel.Log))
            .recorded(log("b", ConsoleLevel.Error))
            .recorded(ConsoleEntries.refused("https://cdn.example/"))
            .recorded(log("c", ConsoleLevel.Warning))

        val errors = state.filteredBy(ConsoleLevel.Error)
        assertEquals(listOf("b", "https://cdn.example/"), errors.shown.map { it.text })
        assertEquals(4, errors.entries.size)
        assertEquals(listOf("c"), state.filteredBy(ConsoleLevel.Warning).shown.map { it.text })
        assertEquals(4, errors.filteredBy(null).shown.size)
    }

    // Error count

    @Test
    fun `the error count counts every error whatever the filter`() {
        val state = ConsoleState()
            .recorded(log("a", ConsoleLevel.Error))
            .recorded(ConsoleEntries.loadError("http://127.0.0.1:4000/x", "net::ERR_FAILED"))
            .recorded(ConsoleEntries.httpError("http://127.0.0.1:4000/y", 500, null))
            .recorded(ConsoleEntries.refused("https://cdn.example/"))
            .recorded(log("b", ConsoleLevel.Warning))
            .filteredBy(ConsoleLevel.Warning)

        assertEquals(4, state.errorCount)
    }

    @Test
    fun `the error count follows the ring`() {
        var state = ConsoleState().recorded(log("old", ConsoleLevel.Error))
        repeat(ConsoleState.MAX_ENTRIES) { state = state.recorded(log("new$it")) }

        assertEquals(0, state.errorCount)
    }

    // Clear

    @Test
    fun `clear drops every entry and keeps the filter`() {
        val state = ConsoleState()
            .recorded(log("a", ConsoleLevel.Error))
            .filteredBy(ConsoleLevel.Error)
            .cleared()

        assertEquals(emptyList(), state.entries)
        assertEquals(0, state.errorCount)
        assertEquals(ConsoleLevel.Error, state.filter)
    }
}
