package io.github.thewisekd.phopencode.feature.preview.ui

import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PagePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsPresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridgeFactory
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortsSource
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * `previewModule` builds each presenter from the parameters the graph passes, the destination's
 * route and scope, and keeps one of each service port for the app. Until they are wired, the ports
 * fail as not connected and open nothing. Plain JVM: nothing here runs Android code.
 */
class PreviewModuleTest {
    private val screenScope = CoroutineScope(Job())

    private fun koin(): Koin = startKoin { modules(previewModule) }.koin

    @After
    fun stop() {
        stopKoin()
        screenScope.cancel()
    }

    @Test
    fun `every presenter starts from its route`() {
        val koin = koin()

        assertEquals(PortsState(), koin.get<PortsPresenter> { parametersOf(PreviewRoute.Ports, screenScope) }.state.value)
        assertEquals(
            PageState(port = 5173),
            koin.get<PagePresenter> { parametersOf(PreviewRoute.Page(5173), screenScope) }.state.value,
        )
    }

    @Test
    fun `each destination gets its own presenter`() {
        val koin = koin()

        assertNotSame(
            koin.get<PortsPresenter> { parametersOf(PreviewRoute.Ports, screenScope) },
            koin.get<PortsPresenter> { parametersOf(PreviewRoute.Ports, screenScope) },
        )
        assertNotSame(
            koin.get<PagePresenter> { parametersOf(PreviewRoute.Page(3000), screenScope) },
            koin.get<PagePresenter> { parametersOf(PreviewRoute.Page(3000), screenScope) },
        )
    }

    @Test
    fun `the service ports are one each, for the app`() {
        val koin = koin()

        assertSame(koin.get<PreviewPortsSource>(), koin.get<PreviewPortsSource>())
        assertSame(koin.get<PreviewTunnelSource>(), koin.get<PreviewTunnelSource>())
        assertSame(koin.get<PreviewBridgeFactory>(), koin.get<PreviewBridgeFactory>())
    }

    @Test
    fun `until wired, listing and tunnels fail as not connected`() {
        val koin = koin()

        val listing = assertFailsWith<PreviewException> { runBlocking { koin.get<PreviewPortsSource>().list() } }
        val tunnel = assertFailsWith<PreviewException> { runBlocking { koin.get<PreviewTunnelSource>().open(8080) } }

        assertEquals(PreviewFailure.NotConnected, listing.failure)
        assertEquals(PreviewFailure.NotConnected, tunnel.failure)
        assertFailsWith<IllegalArgumentException> { runBlocking { koin.get<PreviewTunnelSource>().open(0) } }
    }
}
