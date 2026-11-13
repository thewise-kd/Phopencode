package io.github.thewisekd.phopencode.feature.preview.logic.page

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.feature.preview.logic.contract.BridgeEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.BridgeProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleKind
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ExternalLink
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadError
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridge
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridgeFactory
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelEnd
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelSource
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val PORT = 5173

/** Assembled at runtime, so that no literal looks like a real secret. */
private val SECRET = listOf("s3cr3t", "value").joinToString("-")

private class FakeTunnel(override val port: Int) : PreviewTunnel {
    var closes = 0
        private set

    override val end = MutableStateFlow<PreviewTunnelEnd?>(null)

    override val loopback = object : SshSession.LoopbackTunnel {
        override val port: Int = this@FakeTunnel.port

        override fun socketFactory(): SocketFactory = error("The page logic opens no socket")

        override fun close() = this@FakeTunnel.close()
    }

    val closed: Boolean
        get() = closes > 0

    /** The SSH connection drops. */
    fun lose() {
        if (end.value == null) end.value = PreviewTunnelEnd.ConnectionLost
    }

    override fun close() {
        closes++
        if (end.value == null) end.value = PreviewTunnelEnd.Closed
    }
}

/** Answers each `open` from a queue; a test can hold one open with a [CompletableDeferred]. */
private class FakeTunnels : PreviewTunnelSource {
    val answers = ArrayDeque<suspend (Int) -> PreviewTunnel>()
    val opened = mutableListOf<FakeTunnel>()
    val ports = mutableListOf<Int>()

    fun opens() = apply { answers.addLast { port -> FakeTunnel(port).also(opened::add) } }

    fun fails(failure: PreviewFailure) = apply { answers.addLast { throw PreviewException(failure) } }

    fun throws(error: Exception) = apply { answers.addLast { throw error } }

    fun held(gate: CompletableDeferred<Unit>) = apply {
        answers.addLast { port ->
            gate.await()
            FakeTunnel(port).also(opened::add)
        }
    }

    override suspend fun open(port: Int): PreviewTunnel {
        ports += port
        val answer = answers.removeFirstOrNull() ?: error("No tunnel scripted")
        return answer(port)
    }
}

private class FakeBridge(val tunnel: PreviewTunnel, bridgePort: Int) : PreviewBridge {
    override val origin = PreviewOrigin(bridgePort)
    override val entry = BridgeEntry(origin, "${origin.url}/?phopencode_secret=$SECRET")
    val reports = Channel<BridgeProblem>(Channel.UNLIMITED)
    override val problems: Flow<BridgeProblem> = reports.receiveAsFlow()

    var closes = 0
        private set

    val closed: Boolean
        get() = closes > 0

    override fun close() {
        closes++
        reports.close()
    }
}

private class FakeBridges : PreviewBridgeFactory {
    val opened = mutableListOf<FakeBridge>()
    var failure: Exception? = null
    private var nextPort = 40000

    override fun open(tunnel: PreviewTunnel): PreviewBridge {
        failure?.let { throw it }
        return FakeBridge(tunnel, nextPort++).also(opened::add)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPagePresenterTest {
    private val tunnels = FakeTunnels()
    private val bridges = FakeBridges()

    private fun TestScope.presenter(scope: CoroutineScope = backgroundScope) =
        DefaultPagePresenter(route = PreviewRoute.Page(PORT), tunnels = tunnels, bridges = bridges, scope = scope)

    /** A presenter whose tunnel and bridge are open. */
    private fun TestScope.opened(): DefaultPagePresenter {
        tunnels.opens()
        val presenter = presenter()
        runCurrent()
        return presenter
    }

    /** A presenter whose first page loaded at [path]. */
    private fun TestScope.loaded(path: String = "/"): DefaultPagePresenter {
        val presenter = opened()
        presenter.onIntent(PageIntent.LoadStarted)
        presenter.onIntent(PageIntent.LoadFinished(bridges.opened.last().origin.url + path))
        return presenter
    }

    private val DefaultPagePresenter.status get() = state.value.status

    // Opening

    @Test
    fun `nothing opens before the scope runs`() = runTest {
        tunnels.opens()
        val presenter = presenter()

        assertEquals(PageState(port = PORT), presenter.state.value)
        assertEquals(emptyList(), tunnels.ports)
        assertEquals(emptyList(), bridges.opened)
    }

    @Test
    fun `opening asks for a tunnel to the route's port and a bridge on it, then loads the entry`() = runTest {
        val presenter = opened()

        assertEquals(listOf(PORT), tunnels.ports)
        val bridge = bridges.opened.single()
        assertEquals(tunnels.opened.single(), bridge.tunnel)
        val state = presenter.state.value
        assertEquals(PageStatus.Opening, state.status)
        assertEquals(bridge.origin, state.origin)
        assertEquals(bridge.entry.url, state.load?.url)
        assertEquals("/", state.addressText)
    }

    @Test
    fun `the page is loaded when the main frame finishes on the origin`() = runTest {
        val presenter = opened()
        val origin = bridges.opened.single().origin

        presenter.onIntent(PageIntent.LoadStarted)
        assertEquals(PageStatus.Opening, presenter.status)
        presenter.onIntent(PageIntent.LoadFinished("${origin.url}/app/?x=1&phopencode_secret=$SECRET#top"))

        assertEquals(PageStatus.Loaded, presenter.status)
        assertEquals("/app/?x=1#top", presenter.state.value.addressText)
    }

    @Test
    fun `a finished load off the origin changes nothing`() = runTest {
        val presenter = opened()

        presenter.onIntent(PageIntent.LoadFinished("chrome-error://chromewebdata/"))
        presenter.onIntent(PageIntent.LoadFinished("https://example.com/"))

        assertEquals(PageStatus.Opening, presenter.status)
        assertEquals("/", presenter.state.value.addressText)
    }

    @Test
    fun `a new navigation from a loaded page is opening again`() = runTest {
        val presenter = loaded()

        presenter.onIntent(PageIntent.LoadStarted)

        assertEquals(PageStatus.Opening, presenter.status)
    }

    @Test
    fun `the main frame's HTTP status is a load error that a finished load keeps`() = runTest {
        val presenter = opened()
        val origin = bridges.opened.single().origin

        presenter.onIntent(PageIntent.LoadStarted)
        presenter.onIntent(PageIntent.LoadFailed(LoadError.HttpStatus(404)))
        presenter.onIntent(PageIntent.LoadFinished("${origin.url}/missing"))

        assertEquals(PageStatus.LoadFailed(LoadError.HttpStatus(404)), presenter.status)
        assertEquals("/missing", presenter.state.value.addressText)
    }

    @Test
    fun `a network error is a load error`() = runTest {
        val presenter = opened()

        presenter.onIntent(PageIntent.LoadFailed(LoadError.Network))

        assertEquals(PageStatus.LoadFailed(LoadError.Network), presenter.status)
    }

    @Test
    fun `a bad gateway with no problem reported is an HTTP status`() = runTest {
        val presenter = opened()

        presenter.onIntent(PageIntent.LoadFailed(LoadError.HttpStatus(502)))

        assertEquals(PageStatus.LoadFailed(LoadError.HttpStatus(502)), presenter.status)
    }

    @Test
    fun `nothing listening on the port fails the load`() = runTest {
        val presenter = opened()
        val bridge = bridges.opened.single()

        bridge.reports.trySend(BridgeProblem.NothingListens)
        runCurrent()
        assertEquals(PageStatus.LoadFailed(LoadError.NothingListens), presenter.status)

        // The WebView then reports the bridge's 502 and finishes the error page.
        presenter.onIntent(PageIntent.LoadStarted)
        presenter.onIntent(PageIntent.LoadFailed(LoadError.HttpStatus(502)))
        presenter.onIntent(PageIntent.LoadFinished(bridge.origin.url + "/"))
        assertEquals(PageStatus.LoadFailed(LoadError.NothingListens), presenter.status)
    }

    @Test
    fun `a bad gateway reported before the problem becomes nothing listens`() = runTest {
        val presenter = opened()

        presenter.onIntent(PageIntent.LoadFailed(LoadError.HttpStatus(502)))
        bridges.opened.single().reports.trySend(BridgeProblem.NothingListens)
        runCurrent()

        assertEquals(PageStatus.LoadFailed(LoadError.NothingListens), presenter.status)
    }

    @Test
    fun `a problem on a sub-resource of a loaded page does not fail it`() = runTest {
        val presenter = loaded()
        val bridge = bridges.opened.single()

        listOf(BridgeProblem.NothingListens, BridgeProblem.ResponseTooLarge, BridgeProblem.MalformedResponse).forEach {
            bridge.reports.trySend(it)
        }
        runCurrent()

        assertEquals(PageStatus.Loaded, presenter.status)
    }

    @Test
    fun `a sub-resource nobody served does not turn a later bad gateway into nothing listens`() = runTest {
        val presenter = loaded()
        bridges.opened.single().reports.trySend(BridgeProblem.NothingListens)
        runCurrent()

        presenter.onIntent(PageIntent.LoadStarted)
        presenter.onIntent(PageIntent.LoadFailed(LoadError.HttpStatus(502)))

        assertEquals(PageStatus.LoadFailed(LoadError.HttpStatus(502)), presenter.status)
    }

    @Test
    fun `a page whose last load failed does not carry nothing listens into the next one`() = runTest {
        val presenter = opened()
        val origin = bridges.opened.single().origin
        presenter.onIntent(PageIntent.LoadFailed(LoadError.HttpStatus(404)))
        presenter.onIntent(PageIntent.LoadFinished(origin.url + "/missing"))
        bridges.opened.single().reports.trySend(BridgeProblem.NothingListens)
        runCurrent()
        assertEquals(PageStatus.LoadFailed(LoadError.HttpStatus(404)), presenter.status)

        presenter.onIntent(PageIntent.LoadStarted)
        presenter.onIntent(PageIntent.LoadFailed(LoadError.HttpStatus(502)))

        assertEquals(PageStatus.LoadFailed(LoadError.HttpStatus(502)), presenter.status)
    }

    @Test
    fun `a reload forgets that nothing listened`() = runTest {
        val presenter = opened()
        val bridge = bridges.opened.single()
        bridge.reports.trySend(BridgeProblem.NothingListens)
        runCurrent()

        presenter.onIntent(PageIntent.Reload)
        presenter.onIntent(PageIntent.LoadStarted)
        presenter.onIntent(PageIntent.LoadFailed(LoadError.HttpStatus(502)))
        assertEquals(PageStatus.LoadFailed(LoadError.HttpStatus(502)), presenter.status)
    }

    @Test
    fun `a tunnel that cannot open is a failed load and opens no bridge`() = runTest {
        listOf(
            PreviewFailure.NotConnected,
            PreviewFailure.ConnectionLost,
            PreviewFailure.OpencodePort,
            PreviewFailure.NothingListens,
        ).forEach { failure ->
            tunnels.fails(failure)
            val scope = CoroutineScope(coroutineContext + Job())
            val presenter = presenter(scope)
            runCurrent()

            assertEquals(PageStatus.LoadFailed(LoadError.Failed(failure)), presenter.status, failure.name)
            assertNull(presenter.state.value.origin)
            assertNull(presenter.state.value.load)
            scope.cancel()
        }
        assertEquals(emptyList(), bridges.opened)
    }

    @Test
    fun `an unexpected error opening the tunnel is Unknown`() = runTest {
        tunnels.throws(IllegalStateException("/home/user host.example"))
        val presenter = presenter()
        runCurrent()

        assertEquals(PageStatus.LoadFailed(LoadError.Failed(PreviewFailure.Unknown)), presenter.status)
    }

    @Test
    fun `a bridge that cannot open is a failed load and closes the tunnel`() = runTest {
        bridges.failure = PreviewException(PreviewFailure.BridgeFailed)
        tunnels.opens()
        val presenter = presenter()
        runCurrent()

        assertEquals(PageStatus.LoadFailed(LoadError.Failed(PreviewFailure.BridgeFailed)), presenter.status)
        assertTrue(tunnels.opened.single().closed)
        assertNull(presenter.state.value.origin)
    }

    @Test
    fun `an unexpected bridge error is BridgeFailed and closes the tunnel`() = runTest {
        bridges.failure = IllegalStateException("bind")
        tunnels.opens()
        val presenter = presenter()
        runCurrent()

        assertEquals(PageStatus.LoadFailed(LoadError.Failed(PreviewFailure.BridgeFailed)), presenter.status)
        assertTrue(tunnels.opened.single().closed)
    }

    // Connection lost

    @Test
    fun `a dropped connection is connection lost and nothing retries`() = runTest {
        val presenter = loaded()
        val tunnel = tunnels.opened.single()
        val bridge = bridges.opened.single()

        tunnel.lose()
        runCurrent()

        assertEquals(PageStatus.ConnectionLost, presenter.status)
        assertTrue(bridge.closed)
        assertTrue(tunnel.closed)
        assertNull(presenter.state.value.origin)
        assertNull(presenter.state.value.load)

        advanceUntilIdle()
        assertEquals(listOf(PORT), tunnels.ports)
        assertEquals(1, bridges.opened.size)
        assertEquals(PageStatus.ConnectionLost, presenter.status)
    }

    @Test
    fun `what the WebView reports after the connection dropped does not hide it`() = runTest {
        val presenter = loaded()
        val origin = bridges.opened.single().origin
        tunnels.opened.single().lose()
        runCurrent()

        presenter.onIntent(PageIntent.LoadStarted)
        presenter.onIntent(PageIntent.LoadFailed(LoadError.Network))
        presenter.onIntent(PageIntent.LoadFinished(origin.url + "/"))

        assertEquals(PageStatus.ConnectionLost, presenter.status)
    }

    @Test
    fun `a connection dropped while the bridge opened is connection lost`() = runTest {
        tunnels.answers.addLast { port -> FakeTunnel(port).also { it.lose(); tunnels.opened += it } }
        val presenter = presenter()
        runCurrent()

        assertEquals(PageStatus.ConnectionLost, presenter.status)
        bridges.opened.forEach { assertTrue(it.closed) }
        assertTrue(tunnels.opened.single().closed)
        assertNull(presenter.state.value.origin)
    }

    @Test
    fun `a reload after the reconnect opens a new tunnel and a new bridge`() = runTest {
        val presenter = loaded()
        tunnels.opened.single().lose()
        runCurrent()
        val firstLoad = bridges.opened.single().entry.url

        tunnels.opens()
        presenter.onIntent(PageIntent.Reload)
        assertEquals(PageStatus.Opening, presenter.status)
        runCurrent()

        assertEquals(listOf(PORT, PORT), tunnels.ports)
        assertEquals(2, bridges.opened.size)
        val bridge = bridges.opened.last()
        assertEquals(tunnels.opened.last(), bridge.tunnel)
        assertEquals(bridge.origin, presenter.state.value.origin)
        assertEquals(bridge.entry.url, presenter.state.value.load?.url)
        assertNotEquals(firstLoad, presenter.state.value.load?.url)
        assertEquals(PageStatus.Opening, presenter.status)

        presenter.onIntent(PageIntent.LoadFinished(bridge.origin.url + "/"))
        assertEquals(PageStatus.Loaded, presenter.status)
    }

    @Test
    fun `a reload while still not connected fails again`() = runTest {
        tunnels.fails(PreviewFailure.NotConnected).fails(PreviewFailure.NotConnected)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PageIntent.Reload)
        runCurrent()

        assertEquals(PageStatus.LoadFailed(LoadError.Failed(PreviewFailure.NotConnected)), presenter.status)
        assertEquals(2, tunnels.ports.size)
    }

    @Test
    fun `a reload after a failed opening opens a new tunnel`() = runTest {
        tunnels.fails(PreviewFailure.NotConnected).opens()
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PageIntent.Reload)
        runCurrent()

        assertEquals(1, bridges.opened.size)
        assertEquals(bridges.opened.single().entry.url, presenter.state.value.load?.url)
    }

    // Reload

    @Test
    fun `a reload of a loaded page loads its address again on the same bridge`() = runTest {
        val presenter = loaded("/app?x=1")
        val first = presenter.state.value.load!!

        presenter.onIntent(PageIntent.EditAddress("/typed-but-not-sent"))
        presenter.onIntent(PageIntent.Reload)
        runCurrent()

        val load = presenter.state.value.load!!
        assertTrue(load.id > first.id)
        assertEquals(bridges.opened.single().origin.url + "/app?x=1", load.url)
        assertEquals("/app?x=1", presenter.state.value.addressText)
        assertEquals(PageStatus.Opening, presenter.status)
        assertEquals(1, tunnels.ports.size)
        assertEquals(1, bridges.opened.size)
    }

    @Test
    fun `a reload after an HTTP error on the open bridge loads the address again`() = runTest {
        val presenter = opened()
        val origin = bridges.opened.single().origin
        presenter.onIntent(PageIntent.LoadFailed(LoadError.HttpStatus(500)))
        presenter.onIntent(PageIntent.LoadFinished(origin.url + "/x"))

        presenter.onIntent(PageIntent.Reload)

        assertEquals(origin.url + "/x", presenter.state.value.load?.url)
        assertEquals(PageStatus.Opening, presenter.status)
        assertEquals(1, bridges.opened.size)
    }

    @Test
    fun `a reload before the page was entered loads the entry again`() = runTest {
        val presenter = opened()
        val first = presenter.state.value.load!!
        presenter.onIntent(PageIntent.LoadFailed(LoadError.Network))

        presenter.onIntent(PageIntent.Reload)

        val load = presenter.state.value.load!!
        assertTrue(load.id > first.id)
        assertEquals(first.url, load.url)
        assertEquals(PageStatus.Opening, presenter.status)
        assertEquals(1, bridges.opened.size)
    }

    @Test
    fun `a reload while the tunnel opens does not open another`() = runTest {
        val gate = CompletableDeferred<Unit>()
        tunnels.held(gate)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PageIntent.Reload)
        presenter.onIntent(PageIntent.Reload)
        runCurrent()
        assertEquals(1, tunnels.ports.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, bridges.opened.size)
        assertEquals(bridges.opened.single().origin, presenter.state.value.origin)
    }

    @Test
    fun `load request ids only grow`() = runTest {
        val presenter = loaded()
        val ids = mutableListOf(presenter.state.value.load!!.id)
        repeat(3) {
            presenter.onIntent(PageIntent.Reload)
            ids += presenter.state.value.load!!.id
        }
        tunnels.opened.single().lose()
        runCurrent()
        tunnels.opens()
        presenter.onIntent(PageIntent.Reload)
        runCurrent()
        ids += presenter.state.value.load!!.id

        assertEquals(ids.sorted().distinct(), ids)
    }

    // Address bar

    @Test
    fun `the address bar loads a normalized path on the origin`() = runTest {
        val presenter = loaded()
        val origin = bridges.opened.single().origin
        val first = presenter.state.value.load!!.id

        presenter.onIntent(PageIntent.EditAddress("app/../docs/ a"))
        assertEquals("app/../docs/ a", presenter.state.value.addressText)
        presenter.onIntent(PageIntent.GoToAddress)

        assertEquals("/docs/%20a", presenter.state.value.addressText)
        assertEquals(origin.url + "/docs/%20a", presenter.state.value.load?.url)
        assertTrue(presenter.state.value.load!!.id > first)
        assertEquals(PageStatus.Opening, presenter.status)
    }

    @Test
    fun `an address off the origin is refused and the bar shows the current path`() = runTest {
        val presenter = loaded("/here")
        val load = presenter.state.value.load

        listOf("https://example.com/", "javascript:alert(1)", "file:///etc/hosts", "/a\nb").forEach { text ->
            presenter.onIntent(PageIntent.EditAddress(text))
            presenter.onIntent(PageIntent.GoToAddress)

            assertEquals("/here", presenter.state.value.addressText, text)
            assertEquals(load, presenter.state.value.load, text)
        }
    }

    @Test
    fun `the address bar loads nothing while no page was entered`() = runTest {
        tunnels.fails(PreviewFailure.NotConnected)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PageIntent.EditAddress("docs"))
        presenter.onIntent(PageIntent.GoToAddress)

        assertEquals("/docs", presenter.state.value.addressText)
        assertNull(presenter.state.value.load)
        assertEquals(1, tunnels.ports.size)
    }

    // Navigation policy

    @Test
    fun `decide keeps the origin, asks for other http addresses and refuses the rest`() = runTest {
        val presenter = opened()
        val origin = bridges.opened.single().origin

        assertEquals(NavigationDecision.Allow, presenter.decide(origin.url + "/next"))
        assertEquals(
            NavigationDecision.AskExternal(ExternalLink("https://example.com/a")),
            presenter.decide("https://example.com/a"),
        )
        listOf("file:///x", "content://x/y", "intent://x#Intent;end", "javascript:void(0)", "data:,x").forEach {
            assertEquals(NavigationDecision.Refuse, presenter.decide(it), it)
        }
    }

    @Test
    fun `decide changes nothing`() = runTest {
        val presenter = opened()
        val before = presenter.state.value

        presenter.decide("https://example.com/")
        presenter.decide(before.origin!!.url + "/")
        presenter.decide("javascript:alert(1)")

        assertEquals(before, presenter.state.value)
    }

    @Test
    fun `the old bridge is no longer the origin once the connection dropped`() = runTest {
        val presenter = loaded()
        val origin = bridges.opened.single().origin
        tunnels.opened.single().lose()
        runCurrent()

        assertNotEquals(NavigationDecision.Allow, presenter.decide(origin.url + "/"))
    }

    @Test
    fun `an external address opens outside the app only after confirmation`() = runTest {
        val presenter = loaded()
        val url = "https://example.com/docs?q=1"

        presenter.events.test {
            presenter.onIntent(PageIntent.RequestExternal(url))
            assertEquals(ExternalLink(url), presenter.state.value.pendingExternal)
            expectNoEvents()

            presenter.onIntent(PageIntent.ConfirmExternal)
            assertEquals(PageEvent.OpenExternal(ExternalLink(url)), awaitItem())
            assertNull(presenter.state.value.pendingExternal)

            presenter.onIntent(PageIntent.ConfirmExternal)
            expectNoEvents()
        }
    }

    @Test
    fun `a dismissed external address is not opened`() = runTest {
        val presenter = loaded()

        presenter.events.test {
            presenter.onIntent(PageIntent.RequestExternal("https://example.com/"))
            presenter.onIntent(PageIntent.DismissExternal)
            assertNull(presenter.state.value.pendingExternal)
            presenter.onIntent(PageIntent.ConfirmExternal)
            expectNoEvents()
        }
    }

    @Test
    fun `only http addresses off the origin can be requested outside the app`() = runTest {
        val presenter = loaded()
        val origin = bridges.opened.single().origin

        listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///x", origin.url + "/", "").forEach {
            presenter.onIntent(PageIntent.RequestExternal(it))
            assertNull(presenter.state.value.pendingExternal, it)
        }
    }

    // Viewport (P13.15)

    @Test
    fun `switching the viewport keeps the mode and reloads the page on the open bridge`() = runTest {
        val presenter = loaded("/app")
        val bridge = bridges.opened.single()
        val before = presenter.state.value.load!!.id

        presenter.onIntent(PageIntent.SetViewport(ViewportMode.Desktop))

        val state = presenter.state.value
        assertEquals(ViewportMode.Desktop, state.viewport)
        assertEquals(PageStatus.Opening, state.status)
        assertTrue(state.load!!.id > before)
        assertEquals(bridge.origin.url + "/app", state.load!!.url)
        assertEquals(1, tunnels.ports.size)

        presenter.onIntent(PageIntent.LoadFinished(bridge.origin.url + "/app"))
        val desktopLoad = presenter.state.value.load!!.id
        presenter.onIntent(PageIntent.SetViewport(ViewportMode.Mobile))
        assertEquals(ViewportMode.Mobile, presenter.state.value.viewport)
        assertTrue(presenter.state.value.load!!.id > desktopLoad)
    }

    @Test
    fun `the mode the page has already changes nothing`() = runTest {
        val presenter = loaded()
        val before = presenter.state.value

        presenter.onIntent(PageIntent.SetViewport(ViewportMode.Mobile))

        assertEquals(before, presenter.state.value)
    }

    @Test
    fun `the viewport holds while the preview is open, across reloads, navigation and a reconnect`() = runTest {
        val presenter = loaded()
        presenter.onIntent(PageIntent.SetViewport(ViewportMode.Desktop))

        presenter.onIntent(PageIntent.Reload)
        presenter.onIntent(PageIntent.EditAddress("/other"))
        presenter.onIntent(PageIntent.LoadFinished(bridges.opened.single().origin.url + "/"))
        presenter.onIntent(PageIntent.GoToAddress)
        assertEquals(ViewportMode.Desktop, presenter.state.value.viewport)

        tunnels.opened.single().lose()
        runCurrent()
        assertEquals(PageStatus.ConnectionLost, presenter.status)
        assertEquals(ViewportMode.Desktop, presenter.state.value.viewport)

        tunnels.opens()
        presenter.onIntent(PageIntent.Reload)
        runCurrent()
        assertEquals(2, bridges.opened.size)
        assertEquals(ViewportMode.Desktop, presenter.state.value.viewport)
    }

    @Test
    fun `without a bridge the mode is kept for the next load and nothing reconnects by itself`() = runTest {
        val presenter = loaded()
        tunnels.opened.single().lose()
        runCurrent()

        presenter.onIntent(PageIntent.SetViewport(ViewportMode.Desktop))
        runCurrent()

        assertEquals(PageStatus.ConnectionLost, presenter.status)
        assertEquals(ViewportMode.Desktop, presenter.state.value.viewport)
        assertNull(presenter.state.value.load)
        assertEquals(1, tunnels.ports.size)
    }

    @Test
    fun `closing resets the viewport, and a closed page takes no other mode`() = runTest {
        val presenter = loaded()
        presenter.onIntent(PageIntent.SetViewport(ViewportMode.Desktop))

        presenter.onIntent(PageIntent.Close)
        assertEquals(ViewportMode.Mobile, presenter.state.value.viewport)

        presenter.onIntent(PageIntent.SetViewport(ViewportMode.Desktop))
        runCurrent()
        assertEquals(ViewportMode.Mobile, presenter.state.value.viewport)
        assertEquals(PageStatus.Closed, presenter.status)
        assertNull(presenter.state.value.load)
    }

    @Test
    fun `a new preview starts in mobile`() = runTest {
        val first = loaded()
        first.onIntent(PageIntent.SetViewport(ViewportMode.Desktop))
        first.onIntent(PageIntent.Close)

        tunnels.opens()
        val second = presenter()
        runCurrent()

        assertEquals(ViewportMode.Mobile, second.state.value.viewport)
    }

    // Closing

    @Test
    fun `closing releases the tunnel and the bridge and asks the screen to clear its data`() = runTest {
        val presenter = loaded()
        presenter.onIntent(PageIntent.RequestExternal("https://example.com/"))
        val tunnel = tunnels.opened.single()
        val bridge = bridges.opened.single()

        presenter.events.test {
            presenter.onIntent(PageIntent.Close)

            assertTrue(bridge.closed)
            assertTrue(tunnel.closed)
            val state = presenter.state.value
            assertEquals(PageStatus.Closed, state.status)
            assertNull(state.origin)
            assertNull(state.load)
            assertNull(state.pendingExternal)
            assertEquals(ConsoleState(), state.console)
            assertEquals(ViewportMode.Mobile, state.viewport)
            assertEquals(PageEvent.Close, awaitItem())
        }
        runCurrent()
        assertEquals(PageStatus.Closed, presenter.status)
    }

    @Test
    fun `nothing reopens after closing`() = runTest {
        val presenter = loaded()
        presenter.onIntent(PageIntent.Close)
        tunnels.opens()

        presenter.onIntent(PageIntent.Reload)
        presenter.onIntent(PageIntent.LoadStarted)
        presenter.onIntent(PageIntent.LoadFailed(LoadError.Network))
        presenter.onIntent(PageIntent.EditAddress("/x"))
        presenter.onIntent(PageIntent.GoToAddress)
        presenter.onIntent(PageIntent.RequestExternal("https://example.com/"))
        runCurrent()

        assertEquals(PageStatus.Closed, presenter.status)
        assertNull(presenter.state.value.load)
        assertNull(presenter.state.value.pendingExternal)
        assertEquals(1, tunnels.ports.size)
        assertEquals(NavigationDecision.Refuse, presenter.decide("https://example.com/"))
    }

    @Test
    fun `closing before the scope runs opens nothing`() = runTest {
        tunnels.opens()
        val presenter = presenter()

        presenter.onIntent(PageIntent.Close)
        runCurrent()

        assertEquals(PageStatus.Closed, presenter.status)
        assertEquals(emptyList(), tunnels.ports)
        assertEquals(emptyList(), bridges.opened)
    }

    @Test
    fun `a tunnel that opens after closing is closed at once and gets no bridge`() = runTest {
        val gate = CompletableDeferred<Unit>()
        tunnels.held(gate)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PageIntent.Close)
        gate.complete(Unit)
        runCurrent()

        assertEquals(PageStatus.Closed, presenter.status)
        assertEquals(emptyList(), bridges.opened)
        tunnels.opened.forEach { assertTrue(it.closed) }
    }

    @Test
    fun `closing after the connection dropped still says closed`() = runTest {
        val presenter = loaded()
        tunnels.opened.single().lose()
        runCurrent()

        presenter.onIntent(PageIntent.Close)

        assertEquals(PageStatus.Closed, presenter.status)
    }

    @Test
    fun `an unconfined scope opens the page`() = runTest {
        tunnels.opens()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val presenter = presenter(scope)
        runCurrent()

        val bridge = bridges.opened.single()
        assertEquals(PageStatus.Opening, presenter.status)
        assertEquals(bridge.origin, presenter.state.value.origin)
        assertEquals(bridge.entry.url, presenter.state.value.load?.url)

        tunnels.opened.single().lose()
        runCurrent()
        assertEquals(PageStatus.ConnectionLost, presenter.status)

        tunnels.opens()
        presenter.onIntent(PageIntent.Reload)
        runCurrent()
        val again = bridges.opened.last()
        assertEquals(2, bridges.opened.size)
        assertEquals(again.origin, presenter.state.value.origin)
        assertEquals(again.entry.url, presenter.state.value.load?.url)
        scope.cancel()
    }

    @Test
    fun `the scope ending releases the tunnel and the bridge`() = runTest {
        tunnels.opens()
        val scope = CoroutineScope(coroutineContext + Job())
        presenter(scope)
        runCurrent()

        scope.cancel()
        runCurrent()

        assertTrue(bridges.opened.single().closed)
        assertTrue(tunnels.opened.single().closed)
    }

    @Test
    fun `closing the tunnel ourselves is not a lost connection`() = runTest {
        val presenter = loaded()

        presenter.onIntent(PageIntent.Reload)
        presenter.onIntent(PageIntent.Close)
        runCurrent()

        assertEquals(PageStatus.Closed, presenter.status)
    }

    // Privacy

    @Test
    fun `the state never prints the secret or the page's text`() = runTest {
        val presenter = loaded("/private?token=abc")
        presenter.onIntent(PageIntent.RequestExternal("https://example.com/?q=abc"))
        presenter.onIntent(
            PageIntent.Report(ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Log, "abc", null, null)),
        )

        val text = presenter.state.value.toString()
        assertFalse(SECRET in text)
        assertFalse("abc" in text)
        assertFalse(SECRET in presenter.state.value.addressText)
    }
}
