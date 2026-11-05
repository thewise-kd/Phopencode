package io.github.thewisekd.phopencode.feature.files.ui

import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileListing
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileSearch
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileTransfers
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.NoFileService
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferQueue
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreePresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

private const val ROOT = "/srv/çalışma alanı"

/**
 * `filesModule` builds every presenter from the parameters the graph passes, the destination's
 * route and scope, and shares one transfer queue. The connection manager, which the git port takes,
 * comes from the app's core modules; here the git port is replaced, as the app's own Koin check
 * covers the real one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class FilesModuleTest {
    private val appScope = CoroutineScope(Job())
    private val screenScope = CoroutineScope(Job())

    private fun koin(): Koin = startKoin {
        allowOverride(true)
        modules(
            filesModule,
            module {
                single<CoroutineScope> { appScope }
                single<GitChanges> { GitChanges { emptyList() } }
            },
        )
    }.koin

    @After
    fun stop() {
        stopKoin()
        appScope.cancel()
        screenScope.cancel()
    }

    @Test
    fun `every presenter starts from its route`() {
        val koin = koin()
        val tree = FilesRoute.Tree(ROOT)
        val viewer = FilesRoute.Viewer(ROOT, "src/main kodu.kt", line = 4)
        val editor = FilesRoute.Editor(ROOT, "src/main.kt")
        val search = FilesRoute.Search(ROOT)
        val transfer = FilesRoute.Transfer(ROOT, "$ROOT/docs")

        assertEquals(TreeState(directory = ROOT), koin.get<TreePresenter> { parametersOf(tree, screenScope) }.state.value)
        assertEquals(FileOpsState(), koin.get<FileOpsPresenter> { parametersOf(tree, screenScope) }.state.value)
        assertEquals(
            ViewerState(path = viewer.path, line = 4),
            koin.get<ViewerPresenter> { parametersOf(viewer, screenScope) }.state.value,
        )
        assertEquals(EditorState(path = editor.path), koin.get<EditorPresenter> { parametersOf(editor, screenScope) }.state.value)
        assertEquals(SearchState(directory = ROOT), koin.get<SearchPresenter> { parametersOf(search, screenScope) }.state.value)
        assertEquals(
            TransferState(directory = ROOT, folder = transfer.path),
            koin.get<TransferPresenter> { parametersOf(transfer, screenScope) }.state.value,
        )
    }

    @Test
    fun `each destination gets its own presenter`() {
        val koin = koin()
        val route = FilesRoute.Tree(ROOT)

        assertNotSame(
            koin.get<TreePresenter> { parametersOf(route, screenScope) },
            koin.get<TreePresenter> { parametersOf(route, screenScope) },
        )
    }

    @Test
    fun `the app has one transfer queue`() {
        val koin = koin()

        assertSame(koin.get<TransferQueue>(), koin.get<TransferQueue>())
    }

    @Test
    fun `until the file service is wired, the ports fail every call`() {
        val koin = koin()

        assertSame(NoFileService, koin.get<FileListing>())
        assertSame(NoFileService, koin.get<FileContent>())
        assertSame(NoFileService, koin.get<FileChanges>())
        assertSame(NoFileService, koin.get<FileTransfers>())
        assertSame(NoFileService, koin.get<FileSearch>())
    }
}
