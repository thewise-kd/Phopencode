package io.github.thewisekd.phopencode.feature.settings.ui

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
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesEvent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesPresenter
import io.github.thewisekd.phopencode.feature.settings.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.settings.ui.licenses.LicensesScreen
import io.github.thewisekd.phopencode.feature.settings.ui.prefs.PreferencesScreen
import kotlinx.coroutines.CoroutineScope
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The settings screens, one destination per [SettingsRoute]. The app's NavHost calls this; a new
 * screen of the feature is added here only.
 *
 * The preferences get their presenter from `settingsModule`; the graph turns its one-time events
 * into navigation, and the screens never see [navController]:
 * - The preferences open the licenses, which have no presenter and go back on their own.
 * - Once all data is deleted, only the server list is left on the back stack: every screen above it
 *   showed data that is gone.
 *
 * The preferences follow the settings store, so they need no refresh when shown again.
 */
fun NavGraphBuilder.settingsGraph(navController: NavController) {
    composable<SettingsRoute.Preferences> { entry ->
        val route = entry.toRoute<SettingsRoute.Preferences>()
        val preferences = rememberPresenter<PreferencesPresenter>(route)
        CollectEvents(preferences) { event ->
            when (event) {
                PreferencesEvent.OpenLicenses -> navController.navigate(SettingsRoute.Licenses)
                PreferencesEvent.DataDeleted -> navController.backToServerList()
                PreferencesEvent.Close -> navController.popBackStack()
            }
        }
        val state by preferences.state.collectAsStateWithLifecycle()
        PreferencesScreen(route, state, preferences::onIntent)
    }
    composable<SettingsRoute.Licenses> { entry ->
        LicensesScreen(entry.toRoute<SettingsRoute.Licenses>(), onClose = { navController.popBackStack() })
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
 * The presenter of the destination showing [route], from Koin with the parameters `settingsModule`
 * expects: the route and the scope of the destination's view model. It survives configuration
 * changes and stops when the destination leaves the back stack, which cancels whatever it runs.
 *
 * Koin is the one the app started, looked up when the presenter is made. Compose's `getKoin()` is
 * not used: it can keep a Koin that was stopped since, as Robolectric tests stop and start it.
 */
@Composable
private inline fun <reified P : Any> rememberPresenter(route: SettingsRoute): P {
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
