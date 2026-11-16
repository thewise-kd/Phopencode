package io.github.thewisekd.phopencode.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideEvent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuidePresenter
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesEvent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesPresenter
import io.github.thewisekd.phopencode.feature.settings.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.settings.ui.guide.GuideScreen
import io.github.thewisekd.phopencode.feature.settings.ui.licenses.LicensesScreen
import io.github.thewisekd.phopencode.feature.settings.ui.prefs.PreferencesScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The settings screens, one destination per [SettingsRoute]. The app's NavHost calls this; a new
 * screen of the feature is added here only.
 *
 * The preferences get their presenter from `settingsModule`; the graph turns its one-time events
 * into navigation, and the screens never see [navController]:
 * - The preferences open the licenses, which have no presenter and go back on their own, and the
 *   usage guide.
 * - The guide, once finished, removes its own entry and goes back to the screen below it: the
 *   server list when the app opened it on the first launch, the preferences when they did. Alone
 *   on the back stack, it opens the server list. If a deep link put a screen above it while it was
 *   saving, that screen stays; the guide leaves as soon as it is shown again. Navigation cannot
 *   remove an entry from under another without recreating the one on top.
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
                PreferencesEvent.OpenGuide -> navController.navigate(SettingsRoute.Guide) { launchSingleTop = true }
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
    composable<SettingsRoute.Guide> { entry ->
        val route = entry.toRoute<SettingsRoute.Guide>()
        val guide = rememberPresenter<GuidePresenter>(route)
        var finished by rememberSaveable { mutableStateOf(false) }
        CollectEvents(guide) { event ->
            when (event) {
                GuideEvent.Finished -> finished = true
            }
        }
        if (finished) {
            LaunchedEffect(entry) {
                // Only the guide's own entry leaves, once it is the shown one again: a screen a deep
                // link put above it meanwhile stays where it is.
                entry.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                navController.leaveGuide(entry)
            }
        }
        val state by guide.state.collectAsStateWithLifecycle()
        GuideScreen(route, state, guide::onIntent)
    }
}

/**
 * Removes the guide's [entry], which must be the current one: back to the screen below, or, with
 * nothing below, to the server list alone. Any other current entry is left untouched.
 */
private fun NavController.leaveGuide(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id != entry.id) return
    if (previousBackStackEntry != null) {
        popBackStack()
    } else {
        navigate(ConnectRoute.ServerList) { popUpTo(graph.id) { inclusive = true } }
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
