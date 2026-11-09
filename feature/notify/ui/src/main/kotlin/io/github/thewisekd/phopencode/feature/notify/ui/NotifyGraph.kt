package io.github.thewisekd.phopencode.feature.notify.ui

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
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.DeepLinks
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingEvent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupEvent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupPresenter
import io.github.thewisekd.phopencode.feature.notify.ui.pending.PendingScreen
import io.github.thewisekd.phopencode.feature.notify.ui.setup.SetupScreen
import kotlinx.coroutines.CoroutineScope
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The notify screens, one destination per [NotifyRoute]. The app's NavHost calls this; a new screen
 * of the feature is added here only.
 *
 * Each screen gets its presenter from `notifyModule`; the graph turns the presenters' one-time
 * events into navigation, and the screens never see [navController]:
 * - The setup goes back when closed.
 * - The pending list opens an item's session above itself, as `ChatRoute.ChildSession` for a child
 *   session, so going back returns to the list; without a connection it goes to the server list,
 *   leaving only that on the back stack.
 * - [DeepLinks.PENDING] opens the pending list. The notification's tap reaches the app through the
 *   intent filter in the notify ui manifest (P11.12); the NavHost hands the intent to this deep link.
 *
 * Both presenters follow their sources, the pending list the event stream, so neither needs a
 * refresh when shown again.
 */
fun NavGraphBuilder.notifyGraph(navController: NavController) {
    composable<NotifyRoute.Setup> { entry ->
        val route = entry.toRoute<NotifyRoute.Setup>()
        val setup = rememberPresenter<SetupPresenter>(route)
        CollectEvents(setup) { event ->
            when (event) {
                SetupEvent.Close -> navController.popBackStack()
            }
        }
        val state by setup.state.collectAsStateWithLifecycle()
        SetupScreen(route, state, setup::onIntent)
    }
    composable<NotifyRoute.Pending>(deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.PENDING })) { entry ->
        val route = entry.toRoute<NotifyRoute.Pending>()
        val pending = rememberPresenter<PendingPresenter>(route)
        CollectEvents(pending) { event ->
            when (event) {
                is PendingEvent.OpenSession -> navController.navigate(
                    if (event.isChild) ChatRoute.ChildSession(event.sessionId.value) else ChatRoute.Chat(event.sessionId.value),
                )
                PendingEvent.OpenServerList -> navController.backToServerList()
                PendingEvent.Close -> navController.popBackStack()
            }
        }
        val state by pending.state.collectAsStateWithLifecycle()
        PendingScreen(route, state, pending::onIntent)
    }
}

/** Back to the server list, dropping everything above it; opens it alone when it is not on the stack. */
private fun NavController.backToServerList() {
    if (!popBackStack<ConnectRoute.ServerList>(inclusive = false)) {
        navigate(ConnectRoute.ServerList) { popUpTo(graph.id) { inclusive = true } }
    }
}

/** Keeps a presenter, and the scope it runs in, for as long as its back stack entry lives. */
private class PresenterHolder<P : Any>(create: (CoroutineScope) -> P) : ViewModel() {
    val presenter: P = create(viewModelScope)
}

/**
 * The presenter of the destination showing [route], from Koin with the parameters `notifyModule`
 * expects: the route and the scope of the destination's view model. It survives configuration
 * changes and stops when the destination leaves the back stack, which cancels whatever it runs.
 *
 * Koin is the one the app started, looked up when the presenter is made. Compose's `getKoin()` is
 * not used: it can keep a Koin that was stopped since, as Robolectric tests stop and start it.
 */
@Composable
private inline fun <reified P : Any> rememberPresenter(route: NotifyRoute): P {
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
