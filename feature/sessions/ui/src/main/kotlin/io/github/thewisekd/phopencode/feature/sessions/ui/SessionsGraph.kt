package io.github.thewisekd.phopencode.feature.sessions.ui

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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreatePresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListPresenter
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManageEvent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionManagePresenter
import io.github.thewisekd.phopencode.feature.sessions.ui.create.SessionCreateScreen
import io.github.thewisekd.phopencode.feature.sessions.ui.list.SessionListScreen
import io.github.thewisekd.phopencode.feature.sessions.ui.manage.SessionManageScreen
import kotlinx.coroutines.CoroutineScope
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The sessions screens, one destination per [SessionsRoute]. The app's NavHost calls this; a new
 * screen of the feature is added here only.
 *
 * Each destination gets its presenter from `sessionsModule`, hands the screen the presenter's state
 * and intents, and turns the presenter's one-time events into navigation. The screens never see
 * [navController].
 *
 * The flow: the list opens a session's chat, the creation screen or a session's management, and a
 * folder's list also that folder's files ([FilesRoute.Tree]) and git ([GitRoute.Status]). A
 * created session's chat replaces the creation screen; a deleted session's screens are left behind.
 */
fun NavGraphBuilder.sessionsGraph(navController: NavController) {
    composable<SessionsRoute.SessionList> { entry ->
        val route = entry.toRoute<SessionsRoute.SessionList>()
        val presenter = rememberPresenter<SessionListPresenter>(route)
        CollectEvents(presenter) { event ->
            when (event) {
                is SessionListEvent.OpenChat -> navController.navigate(ChatRoute.Chat(event.id.value))
                is SessionListEvent.OpenManage -> navController.navigate(SessionsRoute.Manage(event.id.value))
                is SessionListEvent.OpenCreate -> navController.navigate(SessionsRoute.Create(directory = event.directory))
                is SessionListEvent.OpenFiles -> navController.navigate(FilesRoute.Tree(event.directory))
                is SessionListEvent.OpenGit -> navController.navigate(GitRoute.Status(event.directory))
            }
        }
        val state by presenter.state.collectAsStateWithLifecycle()
        SessionListScreen(route, state, presenter::onIntent)
    }
    composable<SessionsRoute.Create> { entry ->
        val route = entry.toRoute<SessionsRoute.Create>()
        val presenter = rememberPresenter<SessionCreatePresenter>(route)
        CollectEvents(presenter) { event ->
            when (event) {
                // Back from the chat goes to where creation started, not to the filled-in form.
                is SessionCreateEvent.OpenChat -> navController.navigate(ChatRoute.Chat(event.id.value)) {
                    popUpTo<SessionsRoute.Create> { inclusive = true }
                }
                SessionCreateEvent.Close -> navController.popBackStack()
            }
        }
        val state by presenter.state.collectAsStateWithLifecycle()
        SessionCreateScreen(route, state, presenter::onIntent)
    }
    composable<SessionsRoute.Manage> { entry ->
        val route = entry.toRoute<SessionsRoute.Manage>()
        val presenter = rememberPresenter<SessionManagePresenter>(route)
        CollectEvents(presenter) { event ->
            when (event) {
                is SessionManageEvent.Deleted ->
                    navController.leaveDeletedSession(route.sessionId, event.directory)
                SessionManageEvent.Close -> navController.popBackStack()
            }
        }
        val state by presenter.state.collectAsStateWithLifecycle()
        SessionManageScreen(route, state, presenter::onIntent)
    }
}

/**
 * Leaves the deleted session [sessionId] for a session list, so that back never reaches one of its
 * screens.
 *
 * Pops from the lowest back stack entry that shows the session (its chat, subagents, turn diff,
 * token use or management screen) up to the top: whatever lies above that entry was opened from
 * the session, its child sessions included, and they are deleted with it. Then goes back to the
 * nearest session list still on the back stack; without one, opens the list of [directory].
 *
 * Every screen that can show a session is a composable destination, so the composable navigator's
 * back stack, bottom first, finds the lowest one; the controller's whole back stack is not public API.
 */
private fun NavController.leaveDeletedSession(sessionId: String, directory: String?) {
    val lowest = navigatorProvider.getNavigator(ComposeNavigator::class.java)
        .backStack.value
        .firstOrNull { it.showsSession(sessionId) }
    if (lowest != null) {
        while (true) {
            val top = currentBackStackEntry ?: break
            if (!popBackStack() || top === lowest) break
        }
    }
    // popBackStack returns false when the list is already on top, so look for it instead.
    if (hasSessionList()) {
        popBackStack<SessionsRoute.SessionList>(inclusive = false)
    } else {
        navigate(SessionsRoute.SessionList(directory = directory))
    }
}

/** Whether a session list is on the back stack; the lookup throws when there is none. */
private fun NavController.hasSessionList(): Boolean = try {
    getBackStackEntry<SessionsRoute.SessionList>()
    true
} catch (_: IllegalArgumentException) {
    false
}

private fun NavBackStackEntry.showsSession(sessionId: String): Boolean = when {
    destination.hasRoute<SessionsRoute.Manage>() -> toRoute<SessionsRoute.Manage>().sessionId == sessionId
    destination.hasRoute<ChatRoute.Chat>() -> toRoute<ChatRoute.Chat>().sessionId == sessionId
    destination.hasRoute<ChatRoute.Subagents>() -> toRoute<ChatRoute.Subagents>().sessionId == sessionId
    destination.hasRoute<ChatRoute.ChildSession>() -> toRoute<ChatRoute.ChildSession>().sessionId == sessionId
    destination.hasRoute<ChatRoute.TurnDiff>() -> toRoute<ChatRoute.TurnDiff>().sessionId == sessionId
    destination.hasRoute<ChatRoute.TokenUsage>() -> toRoute<ChatRoute.TokenUsage>().sessionId == sessionId
    else -> false
}

/** Keeps a presenter, and the scope it runs in, for as long as its back stack entry lives. */
private class PresenterHolder<P : Any>(create: (CoroutineScope) -> P) : ViewModel() {
    val presenter: P = create(viewModelScope)
}

/**
 * The presenter of the destination showing [route], from Koin with the parameters `sessionsModule`
 * expects: the route and the scope of the destination's view model. It survives configuration
 * changes and stops when the destination leaves the back stack.
 *
 * Koin is the one the app started, looked up when the presenter is made. Compose's `getKoin()` is
 * not used: it can keep a Koin that was stopped since, as Robolectric tests stop and start it.
 */
@Composable
private inline fun <reified P : Any> rememberPresenter(route: SessionsRoute): P {
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
