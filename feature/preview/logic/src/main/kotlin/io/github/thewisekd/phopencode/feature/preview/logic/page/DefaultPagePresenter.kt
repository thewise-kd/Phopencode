package io.github.thewisekd.phopencode.feature.preview.logic.page

import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PagePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridgeFactory
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnelSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P13.5): shows the initial state, ignores intents and refuses every
 * address. P13.11 fills it in without changing the constructor; opening the tunnel and the bridge
 * starts in [scope] after a `yield()`.
 *
 * @param route The page's route, whose port the preview opens.
 * @param tunnels Opens the tunnel to the port on the active connection.
 * @param bridges Opens the bridge on the phone's loopback onto that tunnel.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultPagePresenter(
    private val route: PreviewRoute.Page,
    private val tunnels: PreviewTunnelSource,
    private val bridges: PreviewBridgeFactory,
    private val scope: CoroutineScope,
) : PagePresenter {
    private val mutableState = MutableStateFlow(PageState(port = route.port))

    override val state: StateFlow<PageState> = mutableState.asStateFlow()

    override val events: Flow<PageEvent> = emptyFlow()

    override fun onIntent(intent: PageIntent) = Unit

    override fun decide(url: String): NavigationDecision = NavigationDecision.Refuse
}
