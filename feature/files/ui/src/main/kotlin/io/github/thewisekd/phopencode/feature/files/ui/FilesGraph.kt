package io.github.thewisekd.phopencode.feature.files.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
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
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreePresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerPresenter
import io.github.thewisekd.phopencode.feature.files.ui.editor.EditorScreen
import io.github.thewisekd.phopencode.feature.files.ui.ops.FileOpsLayer
import io.github.thewisekd.phopencode.feature.files.ui.search.SearchScreen
import io.github.thewisekd.phopencode.feature.files.ui.transfer.TransferScreen
import io.github.thewisekd.phopencode.feature.files.ui.tree.TreeScreen
import io.github.thewisekd.phopencode.feature.files.ui.viewer.ViewerScreen
import kotlinx.coroutines.CoroutineScope
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/** A folder the tree shows next: from a search result. */
private const val OPEN_FOLDER = "files.openFolder"

/** A file the transfer screen offers to download: from the file operations' menu. */
private const val DOWNLOAD = "files.download"

/**
 * The files screens, one destination per [FilesRoute]. The app's NavHost calls this; a new screen of
 * the feature is added here only.
 *
 * Each destination gets its presenters from `filesModule`, hands the screen their state and
 * intents, and turns their one-time events into navigation. The screens never see
 * [navController].
 *
 * The flow:
 * - The tree opens a file in the viewer, the search, and the transfers of its folder. Its
 *   destination also runs the file operations: the tree's menu requests go to them, and a change
 *   they made refreshes the tree. Their download opens the transfers with the file waiting; their
 *   edit opens the editor.
 * - The viewer opens the editor; the search opens a file in the viewer at its line, or a folder in
 *   the tree it was opened from (or in a new tree when there is none of the same project below).
 * - The tree and the viewer refresh whenever they are shown again, as after the editor saved or
 *   transfers ran, since files on the server change meanwhile.
 *
 * A path handed between destinations goes through the receiving entry's saved state and is taken
 * once; like the route arguments, it is never logged.
 */
fun NavGraphBuilder.filesGraph(navController: NavController) {
    composable<FilesRoute.Tree> { entry ->
        val route = entry.toRoute<FilesRoute.Tree>()
        val tree = rememberPresenter<TreePresenter>(route)
        val ops = rememberPresenter<FileOpsPresenter>(route)
        OnShown { again ->
            val folder = entry.savedStateHandle.remove<String>(OPEN_FOLDER)
            when {
                folder != null -> tree.onIntent(TreeIntent.OpenFolder(folder))
                again -> tree.onIntent(TreeIntent.Refresh)
            }
        }
        CollectEvents(tree) { event ->
            when (event) {
                is TreeEvent.OpenFile -> navController.navigate(FilesRoute.Viewer(route.directory, event.path))
                TreeEvent.OpenSearch -> navController.navigate(FilesRoute.Search(route.directory))
                is TreeEvent.OpenTransfer -> navController.navigate(FilesRoute.Transfer(route.directory, event.folder))
                is TreeEvent.OpenActions -> ops.onIntent(FileOpsIntent.Open(event.request))
                TreeEvent.Close -> navController.popBackStack()
            }
        }
        CollectEvents(ops) { event ->
            when (event) {
                is FileOpsEvent.Changed -> tree.onIntent(TreeIntent.Refresh)
                is FileOpsEvent.Download ->
                    navController.navigateHanding(FilesRoute.Transfer(route.directory, event.folder), DOWNLOAD, event.path)
                is FileOpsEvent.OpenEditor -> navController.navigate(FilesRoute.Editor(route.directory, event.path))
            }
        }
        val treeState by tree.state.collectAsStateWithLifecycle()
        val opsState by ops.state.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize()) {
            TreeScreen(route, treeState, tree::onIntent)
            FileOpsLayer(opsState, ops::onIntent)
        }
    }
    composable<FilesRoute.Viewer> { entry ->
        val route = entry.toRoute<FilesRoute.Viewer>()
        val viewer = rememberPresenter<ViewerPresenter>(route)
        OnShown { again -> if (again) viewer.onIntent(ViewerIntent.Refresh) }
        CollectEvents(viewer) { event ->
            when (event) {
                ViewerEvent.OpenEditor -> navController.navigate(FilesRoute.Editor(route.directory, route.path))
                ViewerEvent.Close -> navController.popBackStack()
            }
        }
        val state by viewer.state.collectAsStateWithLifecycle()
        ViewerScreen(route, state, viewer::onIntent)
    }
    composable<FilesRoute.Editor> { entry ->
        val route = entry.toRoute<FilesRoute.Editor>()
        val editor = rememberPresenter<EditorPresenter>(route)
        CollectEvents(editor) { event ->
            when (event) {
                EditorEvent.Close -> navController.popBackStack()
            }
        }
        val state by editor.state.collectAsStateWithLifecycle()
        EditorScreen(route, state, editor::onIntent)
    }
    composable<FilesRoute.Search> { entry ->
        val route = entry.toRoute<FilesRoute.Search>()
        val search = rememberPresenter<SearchPresenter>(route)
        CollectEvents(search) { event ->
            when (event) {
                is SearchEvent.OpenFile -> navController.navigate(FilesRoute.Viewer(route.directory, event.path, event.line))
                is SearchEvent.OpenFolder -> navController.showFolder(route.directory, event.path)
                SearchEvent.Close -> navController.popBackStack()
            }
        }
        val state by search.state.collectAsStateWithLifecycle()
        SearchScreen(route, state, search::onIntent)
    }
    composable<FilesRoute.Transfer> { entry ->
        val route = entry.toRoute<FilesRoute.Transfer>()
        val transfer = rememberPresenter<TransferPresenter>(route)
        OnShown {
            entry.savedStateHandle.remove<String>(DOWNLOAD)?.let { transfer.onIntent(TransferIntent.RequestDownload(it)) }
        }
        CollectEvents(transfer) { event ->
            when (event) {
                TransferEvent.Close -> navController.popBackStack()
            }
        }
        val state by transfer.state.collectAsStateWithLifecycle()
        TransferScreen(route, state, transfer::onIntent)
    }
}

/** Opens [route] and leaves [value] under [key] in the new entry's saved state, for it to take. */
private fun NavController.navigateHanding(route: FilesRoute, key: String, value: String) {
    navigate(route)
    currentBackStackEntry?.savedStateHandle?.set(key, value)
}

/**
 * Shows [path] in the tree of [directory]: the one the search was opened from, if it is directly
 * below, or else a new one above the search.
 */
private fun NavController.showFolder(directory: String, path: String) {
    val below = previousBackStackEntry
    if (below != null && below.isTreeOf(directory)) {
        below.savedStateHandle[OPEN_FOLDER] = path
        popBackStack()
    } else {
        navigateHanding(FilesRoute.Tree(directory), OPEN_FOLDER, path)
    }
}

private fun NavBackStackEntry.isTreeOf(directory: String): Boolean =
    destination.hasRoute<FilesRoute.Tree>() && toRoute<FilesRoute.Tree>().directory == directory

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
 * The presenter of the destination showing [route], from Koin with the parameters `filesModule`
 * expects: the route and the scope of the destination's view model. It survives configuration
 * changes and stops when the destination leaves the back stack. One per presenter type, so the
 * tree's destination holds the tree's and the file operations' side by side.
 *
 * Koin is the one the app started, looked up when the presenter is made. Compose's `getKoin()` is
 * not used: it can keep a Koin that was stopped since, as Robolectric tests stop and start it.
 */
@Composable
private inline fun <reified P : Any> rememberPresenter(route: FilesRoute): P {
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
