package io.github.thewisekd.phopencode.feature.preview.ui

import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PagePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsPresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridgeFactory
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortsSource
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelSource
import io.github.thewisekd.phopencode.feature.preview.logic.page.DefaultPagePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.ports.DefaultPortsPresenter
import io.github.thewisekd.phopencode.feature.preview.logic.service.ConnectionPreviewPorts
import io.github.thewisekd.phopencode.feature.preview.logic.service.ConnectionPreviewTunnels
import io.github.thewisekd.phopencode.feature.preview.logic.transport.LoopbackBridgeFactory
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The live preview's definitions, which the app loads at startup.
 *
 * - [PreviewPortsSource], [PreviewTunnelSource] and [PreviewBridgeFactory] are one each for the
 *   app. The first two run on the app's one [io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager],
 *   from the core modules: [ConnectionPreviewPorts] lists the connected server's ports and
 *   [ConnectionPreviewTunnels] opens its preview tunnels; without a connection both fail as not
 *   connected. [PreviewBridgeFactory] is P13.10's [LoopbackBridgeFactory] (D9, option (b)): each
 *   `open` listens on `127.0.0.1` only, on a new random port with a new secret, until the bridge
 *   is closed. None takes a scope from Koin.
 * - Each presenter is a factory under its contract interface, built per destination with two
 *   parameters: the destination's route and its coroutine scope (see `rememberPresenter` in
 *   PreviewGraph). The scope is a parameter, never `get()`: the app binds no bare scope.
 */
val previewModule: Module = module {
    single<PreviewPortsSource> { ConnectionPreviewPorts(connection = get()) }
    single<PreviewTunnelSource> { ConnectionPreviewTunnels(connection = get()) }
    single<PreviewBridgeFactory> { LoopbackBridgeFactory() }

    factory<PortsPresenter> { (_: PreviewRoute.Ports, scope: CoroutineScope) ->
        DefaultPortsPresenter(ports = get(), scope = scope)
    }
    factory<PagePresenter> { (route: PreviewRoute.Page, scope: CoroutineScope) ->
        DefaultPagePresenter(route = route, tunnels = get(), bridges = get(), scope = scope)
    }
}
