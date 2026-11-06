package io.github.thewisekd.phopencode.feature.git.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.git.ui.branch.BranchesScreen
import io.github.thewisekd.phopencode.feature.git.ui.commit.CommitScreen
import io.github.thewisekd.phopencode.feature.git.ui.diff.DiffScreen
import io.github.thewisekd.phopencode.feature.git.ui.log.CommitDetailScreen
import io.github.thewisekd.phopencode.feature.git.ui.log.LogScreen
import io.github.thewisekd.phopencode.feature.git.ui.remote.RemoteScreen
import io.github.thewisekd.phopencode.feature.git.ui.status.StatusScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ROOT = "/srv/çalışma alanı"
private const val HASH = "0123456789abcdef0123456789abcdef01234567"
private const val FILE = "src/main kodu.kt"

/**
 * Each screen keeps, on its root, the `placeholder:<route>` tag the app's navigation test looks for,
 * with the screen's initial state. The screen tasks fill the screens in; this holds for them too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ScreenStubsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the status keeps its route's tag`() {
        val route = GitRoute.Status(ROOT)
        compose.setContent { PhopencodeTheme { StatusScreen(route, StatusState(directory = ROOT), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `a working or staged diff keeps its route's tag`() {
        val route = GitRoute.FileDiff(ROOT, FILE, staged = true)
        compose.setContent {
            PhopencodeTheme { DiffScreen(route, DiffState(target = DiffTarget.of(route)), onIntent = {}) }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `a commit's diff keeps its route's tag`() {
        val route = GitRoute.CommitFileDiff(ROOT, HASH, FILE)
        compose.setContent {
            PhopencodeTheme { DiffScreen(route, DiffState(target = DiffTarget.of(route)), onIntent = {}) }
        }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the log keeps its route's tag`() {
        val route = GitRoute.Log(ROOT)
        compose.setContent { PhopencodeTheme { LogScreen(route, LogState(directory = ROOT), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `commit details keep their route's tag`() {
        val route = GitRoute.CommitDetail(ROOT, HASH)
        compose.setContent { PhopencodeTheme { CommitDetailScreen(route, CommitDetailState(commit = HASH), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the commit screen keeps its route's tag`() {
        val route = GitRoute.Commit(ROOT)
        compose.setContent { PhopencodeTheme { CommitScreen(route, CommitState(directory = ROOT), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the branches keep their route's tag`() {
        val route = GitRoute.Branches(ROOT)
        compose.setContent { PhopencodeTheme { BranchesScreen(route, BranchesState(directory = ROOT), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the remote operations keep their route's tag`() {
        val route = GitRoute.Remote(ROOT)
        compose.setContent { PhopencodeTheme { RemoteScreen(route, RemoteState(directory = ROOT), onIntent = {}) } }

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }
}
