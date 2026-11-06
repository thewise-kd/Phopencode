package io.github.thewisekd.phopencode.feature.git.ui

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
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogPresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemotePresenter
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.git.ui.branch.BranchesScreen
import io.github.thewisekd.phopencode.feature.git.ui.commit.CommitScreen
import io.github.thewisekd.phopencode.feature.git.ui.diff.DiffScreen
import io.github.thewisekd.phopencode.feature.git.ui.log.CommitDetailScreen
import io.github.thewisekd.phopencode.feature.git.ui.log.LogScreen
import io.github.thewisekd.phopencode.feature.git.ui.remote.RemoteScreen
import io.github.thewisekd.phopencode.feature.git.ui.status.StatusScreen
import kotlinx.coroutines.CoroutineScope
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The git screens, one destination per [GitRoute]. The app's NavHost calls this; a new screen of the
 * feature is added here only.
 *
 * Each destination gets its presenter from `gitModule`, hands the screen its state and intents, and
 * turns its one-time events into navigation. The screens never see [navController].
 *
 * The flow:
 * - The status opens a file's diff (staged or working), the commit screen, the history, the
 *   branches and the remote operations.
 * - The history opens a commit's details, whose files open their diff in that commit.
 * - The commit screen opens a file's diff, and goes back once it committed; a branch switch goes
 *   back too.
 * - The status, a diff, the commit, branch and remote screens read the repository again whenever
 *   they are shown again, as after a commit, a switch or a pull, or after the app came back, since
 *   the repository on the server changes meanwhile. The history and the commit details do not:
 *   reloading would lose the pages read and the place in the list.
 *
 * Route arguments are never logged.
 */
fun NavGraphBuilder.gitGraph(navController: NavController) {
    composable<GitRoute.Status> { entry ->
        val route = entry.toRoute<GitRoute.Status>()
        val status = rememberPresenter<StatusPresenter>(route)
        OnShown { again -> if (again) status.onIntent(StatusIntent.Refresh) }
        CollectEvents(status) { event ->
            when (event) {
                is StatusEvent.OpenDiff -> navController.navigate(GitRoute.FileDiff(route.directory, event.path, event.staged))
                StatusEvent.OpenCommit -> navController.navigate(GitRoute.Commit(route.directory))
                StatusEvent.OpenLog -> navController.navigate(GitRoute.Log(route.directory))
                StatusEvent.OpenBranches -> navController.navigate(GitRoute.Branches(route.directory))
                StatusEvent.OpenRemote -> navController.navigate(GitRoute.Remote(route.directory))
                StatusEvent.Close -> navController.popBackStack()
            }
        }
        val state by status.state.collectAsStateWithLifecycle()
        StatusScreen(route, state, status::onIntent)
    }
    composable<GitRoute.FileDiff> { entry ->
        DiffDestination(entry.toRoute<GitRoute.FileDiff>(), navController)
    }
    composable<GitRoute.CommitFileDiff> { entry ->
        DiffDestination(entry.toRoute<GitRoute.CommitFileDiff>(), navController)
    }
    composable<GitRoute.Log> { entry ->
        val route = entry.toRoute<GitRoute.Log>()
        val log = rememberPresenter<LogPresenter>(route)
        CollectEvents(log) { event ->
            when (event) {
                is LogEvent.OpenCommit -> navController.navigate(GitRoute.CommitDetail(route.directory, event.hash))
                LogEvent.Close -> navController.popBackStack()
            }
        }
        val state by log.state.collectAsStateWithLifecycle()
        LogScreen(route, state, log::onIntent)
    }
    composable<GitRoute.CommitDetail> { entry ->
        val route = entry.toRoute<GitRoute.CommitDetail>()
        val detail = rememberPresenter<CommitDetailPresenter>(route)
        CollectEvents(detail) { event ->
            when (event) {
                is CommitDetailEvent.OpenFileDiff ->
                    navController.navigate(GitRoute.CommitFileDiff(route.directory, route.commit, event.path))
                CommitDetailEvent.Close -> navController.popBackStack()
            }
        }
        val state by detail.state.collectAsStateWithLifecycle()
        CommitDetailScreen(route, state, detail::onIntent)
    }
    composable<GitRoute.Commit> { entry ->
        val route = entry.toRoute<GitRoute.Commit>()
        val commit = rememberPresenter<CommitPresenter>(route)
        OnShown { again -> if (again) commit.onIntent(CommitIntent.Refresh) }
        CollectEvents(commit) { event ->
            when (event) {
                is CommitEvent.OpenDiff -> navController.navigate(GitRoute.FileDiff(route.directory, event.path, event.staged))
                is CommitEvent.Committed, CommitEvent.Close -> navController.popBackStack()
            }
        }
        val state by commit.state.collectAsStateWithLifecycle()
        CommitScreen(route, state, commit::onIntent)
    }
    composable<GitRoute.Branches> { entry ->
        val route = entry.toRoute<GitRoute.Branches>()
        val branches = rememberPresenter<BranchesPresenter>(route)
        OnShown { again -> if (again) branches.onIntent(BranchIntent.Refresh) }
        CollectEvents(branches) { event ->
            when (event) {
                BranchEvent.Switched, BranchEvent.Close -> navController.popBackStack()
            }
        }
        val state by branches.state.collectAsStateWithLifecycle()
        BranchesScreen(route, state, branches::onIntent)
    }
    composable<GitRoute.Remote> { entry ->
        val route = entry.toRoute<GitRoute.Remote>()
        val remote = rememberPresenter<RemotePresenter>(route)
        OnShown { again -> if (again) remote.onIntent(RemoteIntent.Refresh) }
        CollectEvents(remote) { event ->
            when (event) {
                RemoteEvent.Close -> navController.popBackStack()
            }
        }
        val state by remote.state.collectAsStateWithLifecycle()
        RemoteScreen(route, state, remote::onIntent)
    }
}

/** The diff screen, for [route] a `GitRoute.FileDiff` or a `GitRoute.CommitFileDiff`. */
@Composable
private fun DiffDestination(route: GitRoute, navController: NavController) {
    val diff = rememberPresenter<DiffPresenter>(route)
    OnShown { again -> if (again) diff.onIntent(DiffIntent.Refresh) }
    CollectEvents(diff) { event ->
        when (event) {
            DiffEvent.Close -> navController.popBackStack()
        }
    }
    val state by diff.state.collectAsStateWithLifecycle()
    DiffScreen(route, state, diff::onIntent)
}

/** Whether the destination was shown before; lives as long as its back stack entry. */
private class ShownBefore : ViewModel() {
    var value: Boolean = false
}

/**
 * Runs [action] each time the destination is resumed: `again` is false the first time, true when it
 * is shown again, as after the screen above it closed or the app came back to the front. A covered
 * destination leaves the composition, so the flag lives in the entry's view model, which also keeps
 * it over a configuration change: a rotation counts as shown again.
 */
@Composable
private fun OnShown(action: (again: Boolean) -> Unit) {
    val shown = viewModel<ShownBefore>(key = ShownBefore::class.java.name) { ShownBefore() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val handle by rememberUpdatedState(action)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            handle(shown.value)
            shown.value = true
        }
    }
}

/** Keeps a presenter, and the scope it runs in, for as long as its back stack entry lives. */
private class PresenterHolder<P : Any>(create: (CoroutineScope) -> P) : ViewModel() {
    val presenter: P = create(viewModelScope)
}

/**
 * The presenter of the destination showing [route], from Koin with the parameters `gitModule`
 * expects: the route and the scope of the destination's view model. It survives configuration
 * changes and stops when the destination leaves the back stack, which cancels whatever it runs.
 *
 * Koin is the one the app started, looked up when the presenter is made. Compose's `getKoin()` is
 * not used: it can keep a Koin that was stopped since, as Robolectric tests stop and start it.
 */
@Composable
private inline fun <reified P : Any> rememberPresenter(route: GitRoute): P {
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
