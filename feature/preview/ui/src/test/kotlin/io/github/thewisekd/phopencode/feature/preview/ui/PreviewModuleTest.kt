package io.github.thewisekd.phopencode.feature.preview.ui

import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.remote.connection.ServerTools
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PagePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsPresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridgeFactory
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortsSource
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelSource
import io.github.thewisekd.phopencode.feature.preview.logic.service.ConnectionPreviewPorts
import io.github.thewisekd.phopencode.feature.preview.logic.service.ConnectionPreviewTunnels
import java.lang.reflect.Proxy
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
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** An implementation that fails every call; the idle manager below never makes one. */
private inline fun <reified T : Any> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Not used: ${method.name}")
    } as T

/** A manager that is never told to connect, as the app's is before the user picks a server. */
private fun idleConnection(scope: CoroutineScope) = ConnectionManager(
    connector = unused<SshConnector>(),
    secrets = unused<SecretStore>(),
    knownHosts = unused<KnownHostsStore>(),
    servers = ServerResolver(unused<SecretStore>(), unused<ServerTools>()),
    clients = OpencodeClientFactory { _, _, _ -> error("Not used") },
    scope = scope,
)

/**
 * `previewModule` builds each presenter from the parameters the graph passes, the destination's
 * route and scope, and keeps one of each service port for the app. The port listing and the
 * tunnels run on the connection manager, which comes from the app's core modules; here it is an idle
 * one, so they fail as not connected and open nothing. Plain JVM: nothing here runs Android code.
 */
class PreviewModuleTest {
    private val appScope = CoroutineScope(Job())
    private val screenScope = CoroutineScope(Job())

    private fun koin(): Koin = startKoin {
        modules(previewModule, module { single { idleConnection(appScope) } })
    }.koin

    @After
    fun stop() {
        stopKoin()
        screenScope.cancel()
        appScope.cancel()
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
    fun `the port listing and the tunnels run on the connection manager`() {
        val koin = koin()

        assertIs<ConnectionPreviewPorts>(koin.get<PreviewPortsSource>())
        assertIs<ConnectionPreviewTunnels>(koin.get<PreviewTunnelSource>())
    }

    @Test
    fun `without a connection, listing and tunnels fail as not connected`() {
        val koin = koin()

        val listing = assertFailsWith<PreviewException> { runBlocking { koin.get<PreviewPortsSource>().list() } }
        val tunnel = assertFailsWith<PreviewException> { runBlocking { koin.get<PreviewTunnelSource>().open(8080) } }

        assertEquals(PreviewFailure.NotConnected, listing.failure)
        assertEquals(PreviewFailure.NotConnected, tunnel.failure)
        assertFailsWith<IllegalArgumentException> { runBlocking { koin.get<PreviewTunnelSource>().open(0) } }
    }
}
