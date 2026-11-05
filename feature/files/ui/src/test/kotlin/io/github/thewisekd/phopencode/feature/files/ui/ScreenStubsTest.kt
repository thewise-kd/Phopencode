package io.github.thewisekd.phopencode.feature.files.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
import io.github.thewisekd.phopencode.feature.files.ui.editor.EditorScreen
import io.github.thewisekd.phopencode.feature.files.ui.ops.FileOpsLayer
import io.github.thewisekd.phopencode.feature.files.ui.search.SearchScreen
import io.github.thewisekd.phopencode.feature.files.ui.transfer.TransferScreen
import io.github.thewisekd.phopencode.feature.files.ui.tree.TreeScreen
import io.github.thewisekd.phopencode.feature.files.ui.viewer.ViewerScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ROOT = "/srv/çalışma alanı"

/**
 * Each screen keeps, on its root, the `placeholder:<route>` tag the app's navigation test looks for,
 * with the screen's initial state; the tree keeps it with the file operations drawn over it. The
 * screen tasks fill the screens in; this holds for them too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ScreenStubsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the tree keeps its route's tag under the file operations`() {
        val route = FilesRoute.Tree(directory = ROOT)
        compose.setContent {
            PhopencodeTheme {
                TreeScreen(route, TreeState(directory = ROOT), onIntent = {})
                FileOpsLayer(FileOpsState(), onIntent = {})
            }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the viewer keeps its route's tag`() {
        val route = FilesRoute.Viewer(directory = ROOT, path = "src/main kodu.kt", line = 42)
        compose.setContent {
            PhopencodeTheme { ViewerScreen(route, ViewerState(path = route.path, line = 42), onIntent = {}) }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the editor keeps its route's tag`() {
        val route = FilesRoute.Editor(directory = ROOT, path = "src/main.kt")
        compose.setContent {
            PhopencodeTheme { EditorScreen(route, EditorState(path = route.path), onIntent = {}) }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the search keeps its route's tag`() {
        val route = FilesRoute.Search(directory = ROOT)
        compose.setContent {
            PhopencodeTheme { SearchScreen(route, SearchState(directory = ROOT), onIntent = {}) }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the transfers keep their route's tag`() {
        val route = FilesRoute.Transfer(directory = ROOT, path = "$ROOT/docs")
        compose.setContent {
            PhopencodeTheme { TransferScreen(route, TransferState(directory = ROOT, folder = route.path), onIntent = {}) }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }
}
