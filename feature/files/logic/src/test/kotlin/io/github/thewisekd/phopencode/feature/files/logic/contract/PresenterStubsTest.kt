package io.github.thewisekd.phopencode.feature.files.logic.contract

import io.github.thewisekd.phopencode.feature.files.logic.editor.DefaultEditorPresenter
import io.github.thewisekd.phopencode.feature.files.logic.ops.DefaultFileOpsPresenter
import io.github.thewisekd.phopencode.feature.files.logic.search.DefaultSearchPresenter
import io.github.thewisekd.phopencode.feature.files.logic.transfer.DefaultTransferPresenter
import io.github.thewisekd.phopencode.feature.files.logic.transfer.DefaultTransferQueue
import io.github.thewisekd.phopencode.feature.files.logic.tree.DefaultTreePresenter
import io.github.thewisekd.phopencode.feature.files.logic.viewer.DefaultViewerPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val ROOT = "/srv/çalışma alanı"

/**
 * Every presenter is built with the constructor the contract fixes, as `FilesModule` builds it, and
 * shows its screen's initial state before anything has run. The logic tasks fill the presenters in;
 * these hold for the filled-in ones too. The ports are [NoFileService], which fails every call: a
 * presenter may not call one before its scope runs.
 */
class PresenterStubsTest {
    private val git = GitChanges { error("Not called before the scope runs") }

    @Test
    fun `the tree starts loading the project's root`() = runTest {
        val presenter: TreePresenter = DefaultTreePresenter(
            directory = ROOT,
            files = NoFileService,
            git = git,
            scope = backgroundScope,
        )

        assertEquals(TreeState(directory = ROOT), presenter.state.value)
    }

    @Test
    fun `file operations start closed`() = runTest {
        val presenter: FileOpsPresenter = DefaultFileOpsPresenter(
            directory = ROOT,
            files = NoFileService,
            scope = backgroundScope,
        )

        assertEquals(FileOpsState(), presenter.state.value)
    }

    @Test
    fun `the viewer starts loading the file at its line`() = runTest {
        val presenter: ViewerPresenter = DefaultViewerPresenter(
            directory = ROOT,
            path = "src/main kodu.kt",
            line = 42,
            files = NoFileService,
            scope = backgroundScope,
        )

        assertEquals(ViewerState(path = "src/main kodu.kt", line = 42), presenter.state.value)
    }

    @Test
    fun `the viewer's limits default to a warning below the hard limit`() {
        assertTrue(DefaultViewerPresenter.DEFAULT_WARN_BYTES < DefaultViewerPresenter.DEFAULT_MAX_BYTES)
        assertTrue(DefaultEditorPresenter.DEFAULT_MAX_BYTES <= DefaultViewerPresenter.DEFAULT_MAX_BYTES)
    }

    @Test
    fun `the editor starts loading the file`() = runTest {
        val presenter: EditorPresenter = DefaultEditorPresenter(
            directory = ROOT,
            path = "src/main.kt",
            files = NoFileService,
            scope = backgroundScope,
        )

        assertEquals(EditorState(path = "src/main.kt"), presenter.state.value)
    }

    @Test
    fun `transfers start with the queue's jobs in the folder`() = runTest {
        val queue: TransferQueue = DefaultTransferQueue(transfers = NoFileService, scope = backgroundScope)
        val presenter: TransferPresenter = DefaultTransferPresenter(
            directory = ROOT,
            folder = "$ROOT/docs",
            queue = queue,
            scope = backgroundScope,
        )

        assertEquals(TransferState(directory = ROOT, folder = "$ROOT/docs"), presenter.state.value)
        assertTrue(queue.jobs.value.isEmpty())
    }

    @Test
    fun `search starts empty`() = runTest {
        val presenter: SearchPresenter = DefaultSearchPresenter(
            directory = ROOT,
            search = NoFileService,
            scope = backgroundScope,
        )

        assertEquals(SearchState(directory = ROOT), presenter.state.value)
        assertTrue(DefaultSearchPresenter.DEFAULT_LIMIT > 0)
    }
}
