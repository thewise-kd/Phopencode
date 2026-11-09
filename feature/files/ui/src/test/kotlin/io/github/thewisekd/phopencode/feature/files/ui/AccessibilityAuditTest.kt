package io.github.thewisekd.phopencode.feature.files.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
import io.github.thewisekd.phopencode.feature.files.ui.editor.CLEAN
import io.github.thewisekd.phopencode.feature.files.ui.editor.EditorScreen
import io.github.thewisekd.phopencode.feature.files.ui.editor.LOADING
import io.github.thewisekd.phopencode.feature.files.ui.ops.FileOpsLayer
import io.github.thewisekd.phopencode.feature.files.ui.ops.deletingFolder
import io.github.thewisekd.phopencode.feature.files.ui.ops.fileMenu
import io.github.thewisekd.phopencode.feature.files.ui.ops.renaming
import io.github.thewisekd.phopencode.feature.files.ui.search.CONTENT
import io.github.thewisekd.phopencode.feature.files.ui.search.IDLE
import io.github.thewisekd.phopencode.feature.files.ui.search.NAMES
import io.github.thewisekd.phopencode.feature.files.ui.search.SEARCHING
import io.github.thewisekd.phopencode.feature.files.ui.search.SearchScreen
import io.github.thewisekd.phopencode.feature.files.ui.transfer.AWAITING
import io.github.thewisekd.phopencode.feature.files.ui.transfer.DOWNLOADING_UNKNOWN
import io.github.thewisekd.phopencode.feature.files.ui.transfer.FAILED
import io.github.thewisekd.phopencode.feature.files.ui.transfer.QUEUED
import io.github.thewisekd.phopencode.feature.files.ui.transfer.TransferScreen
import io.github.thewisekd.phopencode.feature.files.ui.transfer.UPLOADED
import io.github.thewisekd.phopencode.feature.files.ui.transfer.UPLOADING
import io.github.thewisekd.phopencode.feature.files.ui.tree.TreeScreen
import io.github.thewisekd.phopencode.feature.files.ui.tree.listing
import io.github.thewisekd.phopencode.feature.files.ui.viewer.CODE
import io.github.thewisekd.phopencode.feature.files.ui.viewer.ViewerScreen
import io.github.thewisekd.phopencode.feature.files.ui.viewer.routeOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.thewisekd.phopencode.feature.files.ui.editor.ROUTE as EDITOR_ROUTE
import io.github.thewisekd.phopencode.feature.files.ui.search.EMPTY as NO_HITS
import io.github.thewisekd.phopencode.feature.files.ui.search.ROUTE as SEARCH_ROUTE
import io.github.thewisekd.phopencode.feature.files.ui.transfer.EMPTY as NO_TRANSFERS
import io.github.thewisekd.phopencode.feature.files.ui.transfer.ROUTE as TRANSFER_ROUTE
import io.github.thewisekd.phopencode.feature.files.ui.tree.route as TREE_ROUTE

/**
 * Every files screen, loaded, empty, failed and loading, at the largest font: every control is named
 * and big enough to tap, and no text is cut off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class AccessibilityAuditTest {
    @get:Rule
    val compose = createComposeRule()

    private val failure = FilesFailure.Unreachable

    private fun audit(content: @Composable () -> Unit) {
        compose.setThemedAt { Surface(content = content) }
        compose.assertAccessible()
    }

    private fun tree(state: TreeState) = audit { TreeScreen(TREE_ROUTE, state, {}) }

    @Test
    fun `a folder`() = tree(listing)

    @Test
    fun `a folder while loading`() = tree(TreeState(directory = listing.directory))

    @Test
    fun `an empty folder`() = tree(listing.copy(entries = emptyList()))

    @Test
    fun `a folder failed`() {
        tree(listing.copy(entries = emptyList(), error = failure))
        compose.assertErrorAnnounced(R.string.files_tree_error_unreachable)
    }

    private fun search(state: SearchState) = audit { SearchScreen(SEARCH_ROUTE, state, {}) }

    @Test
    fun `a search not started`() = search(IDLE)

    @Test
    fun `a search running`() = search(SEARCHING)

    @Test
    fun `names found`() = search(NAMES)

    @Test
    fun `lines found`() = search(CONTENT)

    @Test
    fun `nothing found`() = search(NO_HITS)

    @Test
    fun `a search failed`() {
        search(NO_HITS.copy(error = failure))
        compose.assertErrorAnnounced(R.string.files_search_reason_unreachable)
    }

    private fun viewer(state: ViewerState) = audit { ViewerScreen(routeOf(state.path), state, {}) }

    @Test
    fun `a file`() = viewer(CODE)

    @Test
    fun `a file while loading`() = viewer(ViewerState(path = CODE.path))

    @Test
    fun `a file failed`() {
        viewer(ViewerState(path = CODE.path, loading = false, error = failure))
        compose.assertErrorAnnounced(R.string.files_viewer_reason_unreachable)
    }

    @Test
    fun `the editor`() = audit { EditorScreen(EDITOR_ROUTE, CLEAN, {}) }

    @Test
    fun `the editor while loading`() = audit { EditorScreen(EDITOR_ROUTE, LOADING, {}) }

    @Test
    fun `the editor failing to load`() {
        audit { EditorScreen(EDITOR_ROUTE, LOADING.copy(loading = false, loadError = failure), {}) }
        compose.assertErrorAnnounced(R.string.files_editor_reason_unreachable)
    }

    @Test
    fun `the editor failing to save`() {
        audit { EditorScreen(EDITOR_ROUTE, CLEAN.copy(saveError = failure), {}) }
        compose.assertErrorAnnounced(R.string.files_editor_reason_unreachable)
    }

    private fun transfers(state: TransferState) = audit { TransferScreen(TRANSFER_ROUTE, state, {}) }

    @Test
    fun `transfers`() = transfers(NO_TRANSFERS.copy(jobs = listOf(UPLOADING, DOWNLOADING_UNKNOWN, QUEUED, UPLOADED, FAILED)))

    @Test
    fun `no transfers`() = transfers(NO_TRANSFERS)

    @Test
    fun `a transfer asking to overwrite`() = transfers(NO_TRANSFERS.copy(jobs = listOf(AWAITING)))

    @Test
    fun `a file's actions`() = audit { FileOpsLayer(fileMenu, {}) }

    @Test
    fun `renaming a file`() = audit { FileOpsLayer(renaming, {}) }

    @Test
    fun `deleting a folder`() = audit { FileOpsLayer(deletingFolder, {}) }
}
