package io.github.thewisekd.phopencode.feature.preview.logic.console

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleKind
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState
import io.github.thewisekd.phopencode.feature.preview.logic.transport.BridgeSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** Assembled at runtime, so that no literal looks like a real secret. */
private val SECRET = listOf("Zm9v", "YmFy", "c2VjcmV0").joinToString("_")

private const val ORIGIN = "http://127.0.0.1:41234"

private val PARAM = BridgeSecret.PARAM

/** The bridge's first URL, which carries the secret once. */
private val ENTRY_URL = "$ORIGIN/?$PARAM=$SECRET"

/** No entry the console keeps may hold the bridge's secret: the entry URL is the page's first address. */
class ConsoleSecretTest {
    private fun assertNoSecret(entry: ConsoleEntry) {
        assertFalse(SECRET in entry.text, "the text holds the secret")
        assertFalse(SECRET in entry.source.orEmpty(), "the source holds the secret")
        assertFalse(PARAM in entry.text, "the text names the secret's parameter")
        assertFalse(PARAM in entry.source.orEmpty(), "the source names the secret's parameter")
    }

    @Test
    fun `a load error of the entry URL keeps the address without the secret`() {
        val described = ConsoleEntries.loadError(ENTRY_URL, description = "net::ERR_FAILED")
        val bare = ConsoleEntries.loadError(ENTRY_URL, description = null)

        assertNoSecret(described)
        assertNoSecret(bare)
        assertEquals("$ORIGIN/", described.source)
        assertEquals("$ORIGIN/", bare.text)
    }

    @Test
    fun `an http error of the entry URL keeps the address without the secret`() {
        val entry = ConsoleEntries.httpError(ENTRY_URL, status = 502, reason = "Bad Gateway")

        assertNoSecret(entry)
        assertEquals("$ORIGIN/", entry.source)
    }

    @Test
    fun `a refused request naming the secret keeps the address without it`() {
        val entry = ConsoleEntries.refused("https://cdn.example.invalid/x.js?$PARAM=$SECRET")

        assertNoSecret(entry)
        assertEquals("https://cdn.example.invalid/x.js", entry.text)
    }

    @Test
    fun `a console message from the entry URL, or quoting it, keeps neither`() {
        val entry = ConsoleEntries.message(ConsoleLevel.Log, "location is $ENTRY_URL now", source = ENTRY_URL, line = 3)

        assertNoSecret(entry)
        assertEquals("$ORIGIN/", entry.source)
        assertEquals("location is $ORIGIN/ now", entry.text)
        assertEquals(3, entry.line)
    }

    @Test
    fun `only the secret's pair leaves the query, wherever it stands`() {
        fun source(url: String) = ConsoleEntries.message(ConsoleLevel.Log, "x", source = url, line = null).source

        assertEquals("$ORIGIN/app?a=1", source("$ORIGIN/app?$PARAM=$SECRET&a=1"))
        assertEquals("$ORIGIN/app?a=1", source("$ORIGIN/app?a=1&$PARAM=$SECRET"))
        assertEquals("$ORIGIN/app?a=1&b=2", source("$ORIGIN/app?a=1&$PARAM=$SECRET&b=2"))
        assertEquals("$ORIGIN/app#top", source("$ORIGIN/app?$PARAM=$SECRET#top"))
        assertEquals("$ORIGIN/app?a=1#top", source("$ORIGIN/app?a=1&$PARAM=$SECRET#top"))
        assertEquals("$ORIGIN/app?a=1", source("$ORIGIN/app?$PARAM=$SECRET&$PARAM=$SECRET&a=1"))
        assertEquals("$ORIGIN/app?other=1", source("$ORIGIN/app?other=1"))
    }

    @Test
    fun `only the secret's own characters go, so the text around the address stays`() {
        val json = """{"u":"$ENTRY_URL","next":1}"""
        val quoted = "open '$ORIGIN/app?$PARAM=$SECRET', then (\"$ORIGIN/?a=1&$PARAM=$SECRET\")"

        assertEquals("""{"u":"$ORIGIN/","next":1}""", ConsoleEntries.message(ConsoleLevel.Log, json, null, null).text)
        assertEquals(
            "open '$ORIGIN/app', then (\"$ORIGIN/?a=1\")",
            ConsoleEntries.message(ConsoleLevel.Log, quoted, null, null).text,
        )
    }

    @Test
    fun `an entry recorded as it is reported loses the secret too`() {
        val reported = ConsoleEntry(ConsoleKind.LoadError, ConsoleLevel.Error, "failed $ENTRY_URL", ENTRY_URL, line = null)

        val kept = ConsoleState().recorded(reported).entries.single()

        assertNoSecret(kept)
    }

    @Test
    fun `the secret is gone before a long text is cut`() {
        val pad = "x".repeat(ConsoleState.MAX_TEXT_LENGTH - 30)
        val entry = ConsoleEntries.message(ConsoleLevel.Log, "$pad $ENTRY_URL", source = null, line = null)

        assertNoSecret(entry)
        assertFalse(SECRET.take(8) in entry.text)
    }

    @Test
    fun `an entry without the secret is kept as it is`() {
        val entry = ConsoleEntries.message(ConsoleLevel.Log, "hello", source = "$ORIGIN/app.js", line = 1)

        assertSame(entry, ConsoleEntries.bounded(entry))
    }
}
