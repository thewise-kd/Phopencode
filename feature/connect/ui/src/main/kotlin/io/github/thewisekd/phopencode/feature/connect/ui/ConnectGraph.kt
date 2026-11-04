package io.github.thewisekd.phopencode.feature.connect.ui

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
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
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListPresenter
import io.github.thewisekd.phopencode.feature.connect.ui.folder.FolderScreen
import io.github.thewisekd.phopencode.feature.connect.ui.hostkey.HostKeyScreen
import io.github.thewisekd.phopencode.feature.connect.ui.keys.KeysScreen
import io.github.thewisekd.phopencode.feature.connect.ui.progress.ProgressScreen
import io.github.thewisekd.phopencode.feature.connect.ui.serverform.ServerFormScreen
import io.github.thewisekd.phopencode.feature.connect.ui.serverlist.ServerListScreen
import kotlinx.coroutines.CoroutineScope
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The connect screens, one destination per [ConnectRoute]. The app's NavHost calls this; a new screen
 * of the feature is added here only.
 *
 * Each destination gets its presenter from `connectModule`, hands the screen the presenter's state
 * and intents, and turns the presenter's one-time events into navigation. The screens never see
 * [navController].
 *
 * The flow: server list, connection progress (with host key approval on the way), then the folder
 * picker, which opens the sessions of the picked folder.
 */
fun NavGraphBuilder.connectGraph(navController: NavController) {
    composable<ConnectRoute.ServerList> { entry ->
        val route = entry.toRoute<ConnectRoute.ServerList>()
        val presenter = rememberPresenter<ServerListPresenter>(route)
        CollectEvents(presenter) { event ->
            when (event) {
                is ServerListEvent.OpenProgress -> navController.navigate(ConnectRoute.Progress(event.id.value))
                is ServerListEvent.OpenForm -> navController.navigate(ConnectRoute.ServerForm(event.id?.value))
                ServerListEvent.OpenKeys -> navController.navigate(ConnectRoute.Keys)
                ServerListEvent.OpenSettings -> navController.navigate(SettingsRoute.Preferences)
            }
        }
        val state by presenter.state.collectAsStateWithLifecycle()
        ServerListScreen(route, state, presenter::onIntent)
    }
    composable<ConnectRoute.ServerForm> { entry ->
        val route = entry.toRoute<ConnectRoute.ServerForm>()
        val presenter = rememberPresenter<ServerFormPresenter>(route)
        CollectEvents(presenter) { event ->
            when (event) {
                is ServerFormEvent.Saved, ServerFormEvent.Close -> navController.popBackStack()
                ServerFormEvent.OpenKeys -> navController.navigate(ConnectRoute.Keys)
            }
        }
        val state by presenter.state.collectAsStateWithLifecycle()
        ServerFormScreen(route, state, presenter::onIntent)
    }
    composable<ConnectRoute.Keys> { entry ->
        val route = entry.toRoute<ConnectRoute.Keys>()
        val presenter = rememberPresenter<KeysPresenter>(route)
        val clipboard = LocalClipboard.current
        CollectEvents(presenter) { event ->
            when (event) {
                // A public key: safe on the clipboard, and meant to be pasted into authorized_keys.
                is KeysEvent.CopyPublicKey -> clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, event.publicKey)))
                KeysEvent.Close -> navController.popBackStack()
            }
        }
        val state by presenter.state.collectAsStateWithLifecycle()
        KeysScreen(route, state, presenter::onIntent)
    }
    composable<ConnectRoute.HostKey> { entry ->
        val route = entry.toRoute<ConnectRoute.HostKey>()
        val presenter = rememberPresenter<HostKeyPresenter>(route)
        CollectEvents(presenter) { event ->
            when (event) {
                HostKeyEvent.Close -> navController.popBackStack()
            }
        }
        val state by presenter.state.collectAsStateWithLifecycle()
        HostKeyScreen(route, state, presenter::onIntent)
    }
    composable<ConnectRoute.FolderPicker> { entry ->
        val route = entry.toRoute<ConnectRoute.FolderPicker>()
        val presenter = rememberPresenter<FolderPresenter>(route)
        CollectEvents(presenter) { event ->
            when (event) {
                is FolderEvent.Selected -> navController.navigate(SessionsRoute.SessionList(directory = event.directory))
                FolderEvent.Close -> navController.popBackStack()
            }
        }
        val state by presenter.state.collectAsStateWithLifecycle()
        FolderScreen(route, state, presenter::onIntent)
    }
    composable<ConnectRoute.Progress> { entry ->
        val route = entry.toRoute<ConnectRoute.Progress>()
        val presenter = rememberPresenter<ProgressPresenter>(route)
        CollectEvents(presenter) { event ->
            when (event) {
                // The progress screen is done once connected; back from the picker skips it.
                ProgressEvent.Connected -> navController.navigate(ConnectRoute.FolderPicker) {
                    popUpTo<ConnectRoute.Progress> { inclusive = true }
                }
                ProgressEvent.OpenHostKey -> navController.navigate(ConnectRoute.HostKey(route.serverId))
                ProgressEvent.OpenServerForm -> navController.navigate(ConnectRoute.ServerForm(route.serverId))
                ProgressEvent.Close -> navController.popBackStack()
            }
        }
        val state by presenter.state.collectAsStateWithLifecycle()
        ProgressScreen(route, state, presenter::onIntent)
    }
}

/** Keeps a presenter, and the scope it runs in, for as long as its back stack entry lives. */
private class PresenterHolder<P : Any>(create: (CoroutineScope) -> P) : ViewModel() {
    val presenter: P = create(viewModelScope)
}

/**
 * The presenter of the destination showing [route], from Koin with the parameters `connectModule`
 * expects: the route and the scope of the destination's view model. It survives configuration
 * changes and stops when the destination leaves the back stack.
 *
 * Koin is the one the app started, looked up when the presenter is made. Compose's `getKoin()` is
 * not used: it can keep a Koin that was stopped since, as Robolectric tests stop and start it.
 */
@Composable
private inline fun <reified P : Any> rememberPresenter(route: ConnectRoute): P {
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
