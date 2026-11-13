package io.github.thewisekd.phopencode.feature.preview.logic.console

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One open preview's console: memory only, safe to feed from several threads, dropped on close. */
class PreviewConsoleTest {
    private val pageText = "token=" + "abc123-" + "page-said-this"

    @Test
    fun `it starts empty, unfiltered and open`() {
        val console = PreviewConsole()

        assertEquals(ConsoleState(), console.state.value)
        assertFalse(console.isClosed)
    }

    @Test
    fun `it records, filters, counts errors and clears`() {
        val console = PreviewConsole()

        console.message(ConsoleLevel.Log, "hello", "/app.js", 3)
        console.loadError("http://127.0.0.1:4000/x.css", "net::ERR_FAILED")
        console.httpError("http://127.0.0.1:4000/api", 503, "Service Unavailable")
        console.refused("https://cdn.example/x.js")
        assertEquals(4, console.state.value.entries.size)
        assertEquals(3, console.state.value.errorCount)

        console.filter(ConsoleLevel.Log)
        assertEquals(listOf("hello"), console.state.value.shown.map { it.text })

        console.clear()
        assertEquals(ConsoleState(filter = ConsoleLevel.Log), console.state.value)
    }

    @Test
    fun `record cuts and rings like the pure steps`() {
        val console = PreviewConsole()
        repeat(ConsoleState.MAX_ENTRIES + 1) {
            console.record(ConsoleEntries.message(ConsoleLevel.Log, "m$it", null, null))
        }
        console.message(ConsoleLevel.Log, "q".repeat(ConsoleState.MAX_TEXT_LENGTH + 1), null, null)

        assertEquals(ConsoleState.MAX_ENTRIES, console.state.value.entries.size)
        assertEquals("m2", console.state.value.entries.first().text)
        assertEquals(ConsoleState.MAX_TEXT_LENGTH, console.state.value.entries.last().text.length)
    }

    @Test
    fun `closing drops every entry and the filter, and nothing is recorded after`() {
        val console = PreviewConsole()
        console.message(ConsoleLevel.Error, pageText, null, null)
        console.filter(ConsoleLevel.Error)

        console.close()
        console.message(ConsoleLevel.Error, "late", null, null)
        console.loadError("http://127.0.0.1:4000/", "net::ERR_FAILED")
        console.httpError("http://127.0.0.1:4000/", 500, null)
        console.refused("https://cdn.example/")
        console.filter(ConsoleLevel.Warning)
        console.clear()

        assertTrue(console.isClosed)
        assertEquals(ConsoleState(), console.state.value)
    }

    @Test
    fun `closing twice is harmless`() {
        val console = PreviewConsole()
        console.close()
        console.close()

        assertEquals(ConsoleState(), console.state.value)
    }

    @Test
    fun `it never prints what the page said`() {
        val console = PreviewConsole()
        console.message(ConsoleLevel.Error, pageText, "/src?$pageText", 1)

        assertFalse(pageText in console.toString())
        assertFalse(pageText in console.state.value.toString())
    }

    @Test
    fun `entries from several threads are all counted up to the ring size`() {
        val console = PreviewConsole()
        val threads = 8
        val each = 100
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            repeat(threads) { t ->
                pool.execute {
                    start.await()
                    repeat(each) { console.message(ConsoleLevel.Error, "t$t-$it", null, null) }
                }
            }
            start.countDown()
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }

        assertEquals(ConsoleState.MAX_ENTRIES, console.state.value.entries.size)
        assertEquals(ConsoleState.MAX_ENTRIES, console.state.value.entries.toSet().size)
    }
}
