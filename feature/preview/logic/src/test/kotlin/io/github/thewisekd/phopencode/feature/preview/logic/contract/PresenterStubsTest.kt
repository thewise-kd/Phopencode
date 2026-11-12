package io.github.thewisekd.phopencode.feature.preview.logic.contract

import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.feature.preview.logic.page.DefaultPagePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.ports.DefaultPortsPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Every presenter is built with the constructor the contract fixes, as `PreviewModule` builds it,
 * and shows its screen's initial state before anything has run. The logic tasks fill them in; these
 * hold for the filled-in ones too. Every dependency fails if called: nothing may be listed, no
 * tunnel opened and no bridge bound before the scope runs.
 */
class PresenterStubsTest {
    private val ports = object : PreviewPortsSource {
        override suspend fun list(): PreviewPortList = error("Not called before the scope runs")
    }

    private val tunnels = object : PreviewTunnelSource {
        override suspend fun open(port: Int): PreviewTunnel = error("Not called before the scope runs")
    }

    private val bridges = object : PreviewBridgeFactory {
        override fun open(tunnel: PreviewTunnel): PreviewBridge = error("Not called before the scope runs")
    }

    @Test
    fun `the port list starts loading`() = runTest {
        val presenter: PortsPresenter = DefaultPortsPresenter(ports = ports, scope = backgroundScope)

        assertEquals(PortsState(), presenter.state.value)
    }

    @Test
    fun `the page starts opening on its route's port`() = runTest {
        val presenter: PagePresenter = DefaultPagePresenter(
            route = PreviewRoute.Page(8080),
            tunnels = tunnels,
            bridges = bridges,
            scope = backgroundScope,
        )

        assertEquals(PageState(port = 8080), presenter.state.value)
    }

    @Test
    fun `the page refuses every scheme other than http and https`() = runTest {
        val presenter: PagePresenter =
            DefaultPagePresenter(route = PreviewRoute.Page(8080), tunnels = tunnels, bridges = bridges, scope = backgroundScope)

        listOf(
            "file:///data/data/app/shared_prefs/x.xml",
            "content://media/external/images/1",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "data:text/html,<p>x</p>",
            "about:blank#x",
            "",
        ).forEach { url ->
            assertEquals(NavigationDecision.Refuse, presenter.decide(url), url)
        }
    }
}
