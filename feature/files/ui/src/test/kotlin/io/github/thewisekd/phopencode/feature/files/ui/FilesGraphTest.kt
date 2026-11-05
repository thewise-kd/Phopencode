package io.github.thewisekd.phopencode.feature.files.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsRequest
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import io.github.thewisekd.phopencode.feature.files.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreePresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOT = "/srv/çalışma alanı"
private const val SRC = "$ROOT/src"
private const val FILE = "$ROOT/src/main kodu.kt"

/** A presenter that records the intents it gets and sends the events a test puts in [events]. */
private class Recording<S, I, E>(initial: S) : Presenter<S, I, E> {
    val sent = Channel<E>(Channel.UNLIMITED)
    val intents: MutableList<I> = Collections.synchronizedList(mutableListOf())

    override val state: StateFlow<S> = MutableStateFlow(initial)
    override val events: Flow<E> = sent.receiveAsFlow()

    override fun onIntent(intent: I) {
        intents += intent
    }
}

/**
 * The graph turns each part's events into navigation, forwards between the parts of the tree's
 * destination, hands a download and a found folder to the screen that takes them, and refreshes a
 * screen shown again. The presenters are fakes; one instance per part serves every destination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class FilesGraphTest {
    @get:Rule
    val compose = createComposeRule()

    private val tree = Recording<TreeState, TreeIntent, TreeEvent>(TreeState(directory = ROOT))
    private val ops = Recording<FileOpsState, FileOpsIntent, FileOpsEvent>(FileOpsState())
    private val viewer = Recording<ViewerState, ViewerIntent, ViewerEvent>(ViewerState(path = FILE))
    private val editor = Recording<EditorState, EditorIntent, EditorEvent>(EditorState(path = FILE))
    private val search = Recording<SearchState, SearchIntent, SearchEvent>(SearchState(directory = ROOT))
    private val transfer = Recording<TransferState, TransferIntent, TransferEvent>(TransferState(ROOT, ROOT))

    private lateinit var navController: NavHostController

    @Before
    fun startDi() {
        startKoin {
            modules(
                module {
                    factory<TreePresenter> { object : TreePresenter, Presenter<TreeState, TreeIntent, TreeEvent> by tree {} }
                    factory<FileOpsPresenter> {
                        object : FileOpsPresenter, Presenter<FileOpsState, FileOpsIntent, FileOpsEvent> by ops {}
                    }
                    factory<ViewerPresenter> {
                        object : ViewerPresenter, Presenter<ViewerState, ViewerIntent, ViewerEvent> by viewer {}
                    }
                    factory<EditorPresenter> {
                        object : EditorPresenter, Presenter<EditorState, EditorIntent, EditorEvent> by editor {}
                    }
                    factory<SearchPresenter> {
                        object : SearchPresenter, Presenter<SearchState, SearchIntent, SearchEvent> by search {}
                    }
                    factory<TransferPresenter> {
                        object : TransferPresenter, Presenter<TransferState, TransferIntent, TransferEvent> by transfer {}
                    }
                },
            )
        }
    }

    @After
    fun stopDi() {
        stopKoin()
    }

    private fun setNavHost() {
        compose.setContent {
            PhopencodeTheme {
                navController = rememberNavController()
                NavHost(navController = navController, startDestination = ConnectRoute.ServerList) {
                    composable<ConnectRoute.ServerList> { Stand("start") }
                    filesGraph(navController)
                }
            }
        }
    }

    @Composable
    private fun Stand(tag: String) {
        Box(Modifier.fillMaxSize().testTag(tag))
    }

    private fun open(vararg routes: Route) {
        routes.forEach { route -> compose.runOnIdle { navController.navigate(route) } }
        compose.waitForIdle()
    }

    private fun <E> send(presenter: Recording<*, *, E>, event: E) {
        presenter.sent.trySend(event)
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle { navController.popBackStack() }
        compose.waitForIdle()
    }

    private fun routeOf(entry: NavBackStackEntry): Route? {
        val destination = entry.destination
        return when {
            destination.hasRoute<ConnectRoute.ServerList>() -> ConnectRoute.ServerList
            destination.hasRoute<FilesRoute.Tree>() -> entry.toRoute<FilesRoute.Tree>()
            destination.hasRoute<FilesRoute.Viewer>() -> entry.toRoute<FilesRoute.Viewer>()
            destination.hasRoute<FilesRoute.Editor>() -> entry.toRoute<FilesRoute.Editor>()
            destination.hasRoute<FilesRoute.Search>() -> entry.toRoute<FilesRoute.Search>()
            destination.hasRoute<FilesRoute.Transfer>() -> entry.toRoute<FilesRoute.Transfer>()
            else -> null // The graph itself.
        }
    }

    private fun backStack(): List<Route> = compose.runOnIdle {
        navController.currentBackStack.value.mapNotNull(::routeOf)
    }

    private fun <I> intents(presenter: Recording<*, I, *>): List<I> = compose.runOnIdle { presenter.intents.toList() }

    @Test
    fun `the tree opens a file, the search and the transfers of its folder`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT))

        send(tree, TreeEvent.OpenFile(FILE))
        assertEquals(listOf(ConnectRoute.ServerList, FilesRoute.Tree(ROOT), FilesRoute.Viewer(ROOT, FILE)), backStack())

        back()
        send(tree, TreeEvent.OpenSearch)
        assertEquals(listOf(ConnectRoute.ServerList, FilesRoute.Tree(ROOT), FilesRoute.Search(ROOT)), backStack())

        back()
        send(tree, TreeEvent.OpenTransfer(SRC))
        assertEquals(listOf(ConnectRoute.ServerList, FilesRoute.Tree(ROOT), FilesRoute.Transfer(ROOT, SRC)), backStack())
    }

    @Test
    fun `closing the tree goes back`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT))

        send(tree, TreeEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }

    @Test
    fun `the tree's first showing asks for nothing`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT))

        assertTrue(intents(tree).isEmpty())
    }

    @Test
    fun `the tree's menu requests reach the file operations`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT))
        val entry = FileEntry(path = FILE, type = FileEntryType.File)

        send(tree, TreeEvent.OpenActions(FileOpsRequest.Actions(entry, SRC)))
        send(tree, TreeEvent.OpenActions(FileOpsRequest.NewFolder(SRC)))

        assertEquals(
            listOf(
                FileOpsIntent.Open(FileOpsRequest.Actions(entry, SRC)),
                FileOpsIntent.Open(FileOpsRequest.NewFolder(SRC)),
            ),
            intents(ops),
        )
    }

    @Test
    fun `a file operation refreshes the tree`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT))

        send(ops, FileOpsEvent.Changed(SRC))

        assertEquals(listOf<TreeIntent>(TreeIntent.Refresh), intents(tree))
    }

    @Test
    fun `editing from the menu opens the editor`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT))

        send(ops, FileOpsEvent.OpenEditor(FILE))

        assertEquals(listOf(ConnectRoute.ServerList, FilesRoute.Tree(ROOT), FilesRoute.Editor(ROOT, FILE)), backStack())
    }

    @Test
    fun `a download from the menu opens the transfers of the folder with the file waiting`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT))

        send(ops, FileOpsEvent.Download(FILE, SRC))

        assertEquals(listOf(ConnectRoute.ServerList, FilesRoute.Tree(ROOT), FilesRoute.Transfer(ROOT, SRC)), backStack())
        assertEquals(listOf<TransferIntent>(TransferIntent.RequestDownload(FILE)), intents(transfer))
    }

    @Test
    fun `a handed download is taken once`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT))
        send(ops, FileOpsEvent.Download(FILE, SRC))

        // Shown again after another screen: the download is not asked for twice.
        open(FilesRoute.Viewer(ROOT, FILE))
        back()

        assertEquals(listOf<TransferIntent>(TransferIntent.RequestDownload(FILE)), intents(transfer))
    }

    @Test
    fun `a tree shown again refreshes`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT))

        send(tree, TreeEvent.OpenFile(FILE))
        back()

        assertEquals(listOf<TreeIntent>(TreeIntent.Refresh), intents(tree))
    }

    @Test
    fun `the viewer opens the editor, which reloads the viewer when it closes`() {
        setNavHost()
        open(FilesRoute.Viewer(ROOT, FILE, line = 7))
        assertTrue(intents(viewer).isEmpty())

        send(viewer, ViewerEvent.OpenEditor)
        assertEquals(
            listOf(ConnectRoute.ServerList, FilesRoute.Viewer(ROOT, FILE, line = 7), FilesRoute.Editor(ROOT, FILE)),
            backStack(),
        )

        send(editor, EditorEvent.Close)
        assertEquals(listOf(ConnectRoute.ServerList, FilesRoute.Viewer(ROOT, FILE, line = 7)), backStack())
        assertEquals(listOf<ViewerIntent>(ViewerIntent.Refresh), intents(viewer))
    }

    @Test
    fun `closing the viewer goes back`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT), FilesRoute.Viewer(ROOT, FILE))

        send(viewer, ViewerEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList, FilesRoute.Tree(ROOT)), backStack())
    }

    @Test
    fun `a content result opens the viewer at its line`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT), FilesRoute.Search(ROOT))

        send(search, SearchEvent.OpenFile(FILE, line = 12))

        assertEquals(
            listOf(ConnectRoute.ServerList, FilesRoute.Tree(ROOT), FilesRoute.Search(ROOT), FilesRoute.Viewer(ROOT, FILE, 12)),
            backStack(),
        )
    }

    @Test
    fun `a folder result goes back to the tree it was searched from and opens there`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT), FilesRoute.Search(ROOT))

        send(search, SearchEvent.OpenFolder(SRC))

        assertEquals(listOf(ConnectRoute.ServerList, FilesRoute.Tree(ROOT)), backStack())
        assertEquals(listOf<TreeIntent>(TreeIntent.OpenFolder(SRC)), intents(tree))
    }

    @Test
    fun `a folder result without a tree below opens a new tree there`() {
        setNavHost()
        open(FilesRoute.Search(ROOT))

        send(search, SearchEvent.OpenFolder(SRC))

        assertEquals(listOf(ConnectRoute.ServerList, FilesRoute.Search(ROOT), FilesRoute.Tree(ROOT)), backStack())
        assertEquals(listOf<TreeIntent>(TreeIntent.OpenFolder(SRC)), intents(tree))
    }

    @Test
    fun `a tree of another project below is not used for a folder result`() {
        setNavHost()
        open(FilesRoute.Tree("/srv/other"), FilesRoute.Search(ROOT))

        send(search, SearchEvent.OpenFolder(SRC))

        assertEquals(
            listOf(ConnectRoute.ServerList, FilesRoute.Tree("/srv/other"), FilesRoute.Search(ROOT), FilesRoute.Tree(ROOT)),
            backStack(),
        )
        assertEquals(listOf<TreeIntent>(TreeIntent.OpenFolder(SRC)), intents(tree))
    }

    @Test
    fun `closing the search, the editor and the transfers goes back`() {
        setNavHost()
        open(FilesRoute.Tree(ROOT), FilesRoute.Search(ROOT), FilesRoute.Editor(ROOT, FILE), FilesRoute.Transfer(ROOT, ROOT))

        send(transfer, TransferEvent.Close)
        assertEquals(
            listOf(ConnectRoute.ServerList, FilesRoute.Tree(ROOT), FilesRoute.Search(ROOT), FilesRoute.Editor(ROOT, FILE)),
            backStack(),
        )
        send(editor, EditorEvent.Close)
        send(search, SearchEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList, FilesRoute.Tree(ROOT)), backStack())
    }
}
