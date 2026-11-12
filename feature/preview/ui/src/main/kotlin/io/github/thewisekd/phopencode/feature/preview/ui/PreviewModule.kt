package io.github.thewisekd.phopencode.feature.preview.ui

import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PagePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsPresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridge
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridgeFactory
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortList
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortsSource
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelSource
import io.github.thewisekd.phopencode.feature.preview.logic.page.DefaultPagePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.ports.DefaultPortsPresenter
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The live preview's definitions, which the app loads at startup.
 *
 * - [PreviewPortsSource], [PreviewTunnelSource] and [PreviewBridgeFactory] are one each for the
 *   app. Until P13.7 wires the first two to the connection manager's port listing and preview
 *   tunnel, and the bridge of P13.10 is bound, they are [Unwired]: every call fails, and nothing is
 *   listed, opened or bound. None takes a scope.
 * - Each presenter is a factory under its contract interface, built per destination with two
 *   parameters: the destination's route and its coroutine scope (see `rememberPresenter` in
 *   PreviewGraph). The scope is a parameter, never `get()`: the app binds no bare scope.
 */
val previewModule: Module = module {
    single<PreviewPortsSource> { Unwired.Ports }
    single<PreviewTunnelSource> { Unwired.Tunnels }
    single<PreviewBridgeFactory> { Unwired.Bridges }

    factory<PortsPresenter> { (_: PreviewRoute.Ports, scope: CoroutineScope) ->
        DefaultPortsPresenter(ports = get(), scope = scope)
    }
    factory<PagePresenter> { (route: PreviewRoute.Page, scope: CoroutineScope) ->
        DefaultPagePresenter(route = route, tunnels = get(), bridges = get(), scope = scope)
    }
}

/** The service ports before they are wired: each fails as if no connection stood, or no bridge could listen. */
internal object Unwired {
    val Ports: PreviewPortsSource = object : PreviewPortsSource {
        override suspend fun list(): PreviewPortList = throw PreviewException(PreviewFailure.NotConnected)
    }

    val Tunnels: PreviewTunnelSource = object : PreviewTunnelSource {
        override suspend fun open(port: Int): PreviewTunnel {
            require(port in 1..65535) { "Port is out of range" }
            throw PreviewException(PreviewFailure.NotConnected)
        }
    }

    val Bridges: PreviewBridgeFactory = object : PreviewBridgeFactory {
        override fun open(tunnel: PreviewTunnel): PreviewBridge = throw PreviewException(PreviewFailure.BridgeFailed)
    }
}
