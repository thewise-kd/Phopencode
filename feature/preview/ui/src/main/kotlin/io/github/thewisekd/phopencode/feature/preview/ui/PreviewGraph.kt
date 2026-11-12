package io.github.thewisekd.phopencode.feature.preview.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PagePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsPresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.preview.ui.page.PageScreen
import io.github.thewisekd.phopencode.feature.preview.ui.ports.PortsScreen
import kotlinx.coroutines.CoroutineScope
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The live preview's screens, one destination per [PreviewRoute]. The app's NavHost calls this; a
 * new screen of the feature is added here only.
 *
 * Each screen gets its presenter from `previewModule`; the graph turns the presenters' one-time
 * events into navigation, and the screens never see [navController]:
 * - The port picker opens `PreviewRoute.Page(port)` above itself, so going back returns to the
 *   list, and goes back when closed.
 * - The page goes back when closed. A confirmed external address ([PageEvent.OpenExternal]) is
 *   handed to another app by the page screen's task (P13.13); until then the graph drops it, and
 *   the stub presenter never sends one.
 *
 * The page presenter lives as long as its back stack entry: across rotation, so the viewport and
 * the console hold, and no longer, so its scope's end closes the tunnel and the bridge.
 */
fun NavGraphBuilder.previewGraph(navController: NavController) {
    composable<PreviewRoute.Ports> { entry ->
        val route = entry.toRoute<PreviewRoute.Ports>()
        val ports = rememberPresenter<PortsPresenter>(route)
        CollectEvents(ports) { event ->
            when (event) {
                is PortsEvent.OpenPage -> navController.navigate(PreviewRoute.Page(event.port))
                PortsEvent.Close -> navController.popBackStack()
            }
        }
        val state by ports.state.collectAsStateWithLifecycle()
        PortsScreen(route, state, ports::onIntent)
    }
    composable<PreviewRoute.Page> { entry ->
        val route = entry.toRoute<PreviewRoute.Page>()
        val page = rememberPresenter<PagePresenter>(route)
        CollectEvents(page) { event ->
            when (event) {
                is PageEvent.OpenExternal -> Unit
                PageEvent.Close -> navController.popBackStack()
            }
        }
        val state by page.state.collectAsStateWithLifecycle()
        PageScreen(route, state, page::onIntent, page::decide)
    }
}

/** Keeps a presenter, and the scope it runs in, for as long as its back stack entry lives. */
private class PresenterHolder<P : Any>(create: (CoroutineScope) -> P) : ViewModel() {
    val presenter: P = create(viewModelScope)
}

/**
 * The presenter of the destination showing [route], from Koin with the parameters `previewModule`
 * expects: the route and the scope of the destination's view model. It survives configuration
 * changes and stops when the destination leaves the back stack, which cancels whatever it runs.
 *
 * Koin is the one the app started, looked up when the presenter is made. Compose's `getKoin()` is
 * not used: it can keep a Koin that was stopped since, as Robolectric tests stop and start it.
 */
@Composable
private inline fun <reified P : Any> rememberPresenter(route: PreviewRoute): P {
    val holder = viewModel<PresenterHolder<P>>(key = P::class.java.name) {
        PresenterHolder { scope -> KoinPlatform.getKoin().get<P> { parametersOf(route, scope) } }
    }
    return holder.presenter
}

/** Delivers [presenter]'s one-time events while the destination is at least started. */
@Composable
private fun <E> CollectEvents(presenter: Presenter<*, *, E>, onEvent: suspend (E) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val handle by rememberUpdatedState(onEvent)
    LaunchedEffect(presenter, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            presenter.events.collect { handle(it) }
        }
    }
}
