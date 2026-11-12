package io.github.thewisekd.phopencode.feature.preview.logic.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the preview contract fixes for every task after it: ports stay in 1..65535, the bridge's
 * entry URL (which holds the per-session secret) and whatever the page says never print, failures
 * carry no text, and the console and viewport start from known defaults.
 */
class ContractTypesTest {
    private val secret = "s" + "3cr3t-" + "value-" + "0123456789abcdef"
    private val origin = PreviewOrigin(bridgePort = 41234)
    private val entry = BridgeEntry(origin, "${origin.url}/?k=$secret")

    private fun assertMasked(value: Any, vararg hidden: String) {
        val text = value.toString()
        hidden.forEach { assertFalse(it in text, "$text shows $it") }
    }

    @Test
    fun `ports outside 1 to 65535 are refused everywhere a port is named`() {
        listOf(0, -1, 65536).forEach { port ->
            assertFailsWith<IllegalArgumentException> { PreviewPort(port, isOpencode = false) }
            assertFailsWith<IllegalArgumentException> { PreviewOrigin(port) }
            assertFailsWith<IllegalArgumentException> { PortsEvent.OpenPage(port) }
            assertFailsWith<IllegalArgumentException> { PageState(port = port) }
        }
        assertEquals(65535, PreviewPort(65535, isOpencode = true).port)
        assertEquals(1, PortsEvent.OpenPage(1).port)
    }

    @Test
    fun `the bridge origin is the phone's loopback over http`() {
        assertEquals("127.0.0.1", PreviewOrigin.HOST)
        assertEquals("http://127.0.0.1:41234", origin.url)
    }

    @Test
    fun `the secret is at least 128 bits`() {
        assertTrue(PreviewBridge.MIN_SECRET_BITS >= 128)
    }

    @Test
    fun `the entry URL stays on the bridge origin`() {
        assertEquals("${origin.url}/?k=$secret", entry.url)
        listOf(
            "http://127.0.0.1:41235/?k=x",
            "http://127.0.0.1:41234.evil.example/",
            "http://127.0.0.1:41234@evil.example/",
            "https://127.0.0.1:41234/",
            "http://localhost:41234/",
            origin.url,
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { BridgeEntry(origin, url) }
        }
    }

    @Test
    fun `nothing holding the secret prints it`() {
        val load = LoadRequest(id = 1, url = entry.url)

        assertMasked(entry, secret)
        assertMasked(load, secret)
        assertMasked(PageState(port = 3000, origin = origin, load = load), secret)
    }

    @Test
    fun `a refused entry URL does not repeat it`() {
        val failure = assertFailsWith<IllegalArgumentException> { BridgeEntry(origin, "http://elsewhere.example/?k=$secret") }

        assertFalse(secret in failure.message.orEmpty())
    }

    @Test
    fun `what the page says or asks for never prints`() {
        val text = "token=" + "abc" + "123"
        val link = ExternalLink("https://example.com/?q=$text")
        val console = ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Error, text, source = "/app.js?$text", line = 3)

        assertMasked(link, text)
        assertMasked(console, text)
        assertMasked(ConsoleState(entries = listOf(console)), text)
        assertMasked(PageIntent.EditAddress("/a?$text"), text)
        assertMasked(PageIntent.Report(console), text)
        assertMasked(PageIntent.RequestExternal(link.url), text)
        assertMasked(PageIntent.LoadFinished("http://127.0.0.1:1/?$text"), text)
        assertMasked(PageEvent.OpenExternal(link), text)
        assertMasked(NavigationDecision.AskExternal(link), text)
        assertMasked(PageState(port = 3000, addressText = "/a?$text", pendingExternal = link), text)
        assertMasked(PortsIntent.EditManualPort(text), text)
        assertMasked(PortsState(manualText = text), text)
    }

    @Test
    fun `failures carry no text but their name`() {
        PreviewFailure.entries.forEach { failure ->
            val exception = PreviewException(failure)

            assertEquals(failure, exception.failure)
            assertEquals("Preview failure: $failure", exception.message)
            assertNull(exception.cause)
        }
    }

    @Test
    fun `the console starts empty, unfiltered, and counts errors`() {
        val error = ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Error, "boom", source = null, line = null)
        val warning = ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Warning, "careful", source = null, line = null)
        val refused = ConsoleEntry(ConsoleKind.RefusedRequest, ConsoleLevel.Error, "https://cdn.example/x.js", null, null)
        val state = ConsoleState(entries = listOf(error, warning, refused))

        assertEquals(ConsoleState(), ConsoleState(entries = emptyList(), filter = null))
        assertEquals(0, ConsoleState().errorCount)
        assertEquals(2, state.errorCount)
        assertEquals(listOf(error, warning, refused), state.shown)
        assertEquals(listOf(warning), state.copy(filter = ConsoleLevel.Warning).shown)
    }

    @Test
    fun `the console limits are the ones the console logic keeps`() {
        assertEquals(500, ConsoleState.MAX_ENTRIES)
        assertEquals(4 * 1024, ConsoleState.MAX_TEXT_LENGTH)
    }

    @Test
    fun `a page starts opening, on the mobile viewport, with the console empty and nothing to load`() {
        val state = PageState(port = 5173)

        assertEquals(5173, state.port)
        assertEquals(PageStatus.Opening, state.status)
        assertEquals(ViewportMode.Mobile, state.viewport)
        assertEquals(ConsoleState(), state.console)
        assertNull(state.origin)
        assertNull(state.load)
        assertNull(state.pendingExternal)
        assertEquals("/", state.addressText)
    }

    @Test
    fun `the port list starts loading with no manual entry`() {
        val state = PortsState()

        assertTrue(state.loading)
        assertTrue(state.busy)
        assertEquals(emptyList(), state.ports)
        assertEquals("", state.manualText)
        assertNull(state.manualProblem)
        assertNull(state.error)
        assertFalse(state.unsupported)
        assertTrue(PortsState(loading = false, refreshing = true).busy)
        assertFalse(PortsState(loading = false).busy)
    }

    @Test
    fun `a load error says why the main frame failed`() {
        assertEquals(404, LoadError.HttpStatus(404).code)
        assertFailsWith<IllegalArgumentException> { LoadError.HttpStatus(99) }
        assertFailsWith<IllegalArgumentException> { LoadError.HttpStatus(600) }
        assertEquals(PreviewFailure.NotConnected, LoadError.Failed(PreviewFailure.NotConnected).reason)
    }
}
