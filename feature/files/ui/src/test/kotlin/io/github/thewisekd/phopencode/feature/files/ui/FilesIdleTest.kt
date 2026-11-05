package io.github.thewisekd.phopencode.feature.files.ui

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.EditorState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOp
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsDialog
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferDirection
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferId
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferJob
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferStatus
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
import io.github.thewisekd.phopencode.feature.files.ui.editor.EditorScreen
import io.github.thewisekd.phopencode.feature.files.ui.ops.FileOpsLayer
import io.github.thewisekd.phopencode.feature.files.ui.search.SearchScreen
import io.github.thewisekd.phopencode.feature.files.ui.transfer.TransferScreen
import io.github.thewisekd.phopencode.feature.files.ui.tree.TreeScreen
import io.github.thewisekd.phopencode.feature.files.ui.viewer.ViewerScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.fail

/** Far more main thread steps than drawing a still screen takes; an endless animation never runs out. */
private const val STEP_LIMIT = 2_000

/** A task further ahead than this is not the next frame of an animation, so the screen is at rest. */
private val REST_HORIZON: Duration = Duration.ofSeconds(1)

private const val ROOT = "/srv/çalışma alanı"

/**
 * Every files screen comes to rest in a plain activity, the way the app's ActivityScenario tests
 * open them, while loading and while busy. An endless animation, such as an indeterminate spinner
 * while loading, searching, saving or transferring, would keep posting frames, and Robolectric's
 * `idle()` would never return. The looper is driven by hand as in the other features' idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class FilesIdleTest {
    private fun assertComesToRest(content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { PhopencodeTheme { content() } }
        val looper = shadowOf(Looper.getMainLooper())
        var steps = 0
        while (steps < STEP_LIMIT) {
            if (!looper.isIdle) {
                looper.runOneTask()
            } else {
                val wait = untilNextTask(looper) ?: return
                if (wait > REST_HORIZON) return
                ShadowSystemClock.advanceBy(wait)
            }
            steps++
        }
        fail("the main thread still had work due within $REST_HORIZON after $steps steps")
    }

    /** How long until the looper's next task, or null when nothing is scheduled. */
    private fun untilNextTask(looper: ShadowLooper): Duration? {
        val next = looper.nextScheduledTaskTime
        if (next == Duration.ZERO) return null
        return next.minusMillis(SystemClock.uptimeMillis()).coerceAtLeast(Duration.ofMillis(1))
    }

    private val folder = FileEntry(path = "$ROOT/src", type = FileEntryType.Directory)

    @Test
    fun `a loading tree comes to rest`() {
        assertComesToRest { TreeScreen(FilesRoute.Tree(ROOT), TreeState(directory = ROOT), onIntent = {}) }
    }

    @Test
    fun `a refreshing tree with a running file operation comes to rest`() {
        assertComesToRest {
            TreeScreen(FilesRoute.Tree(ROOT), TreeState(directory = ROOT, loading = false, refreshing = true), onIntent = {})
            FileOpsLayer(FileOpsState(dialog = FileOpsDialog.ConfirmDelete(folder), busy = FileOp.Delete), onIntent = {})
        }
    }

    @Test
    fun `a loading viewer comes to rest`() {
        assertComesToRest {
            ViewerScreen(FilesRoute.Viewer(ROOT, "src/main.kt", line = 3), ViewerState(path = "src/main.kt", line = 3), onIntent = {})
        }
    }

    @Test
    fun `a loading editor comes to rest`() {
        assertComesToRest { EditorScreen(FilesRoute.Editor(ROOT, "src/main.kt"), EditorState(path = "src/main.kt"), onIntent = {}) }
    }

    @Test
    fun `a saving editor comes to rest`() {
        val saving = EditorState(path = "src/main.kt", loading = false, text = "x", dirty = true, saving = true)
        assertComesToRest { EditorScreen(FilesRoute.Editor(ROOT, "src/main.kt"), saving, onIntent = {}) }
    }

    @Test
    fun `a running search comes to rest`() {
        assertComesToRest {
            SearchScreen(FilesRoute.Search(ROOT), SearchState(directory = ROOT, query = "main", searching = true), onIntent = {})
        }
    }

    @Test
    fun `an empty transfer screen comes to rest`() {
        assertComesToRest { TransferScreen(FilesRoute.Transfer(ROOT, ROOT), TransferState(ROOT, ROOT), onIntent = {}) }
    }

    @Test
    fun `running transfers come to rest`() {
        val jobs = listOf(
            TransferJob(TransferId(1), TransferDirection.Upload, ROOT, "$ROOT/a.txt", "a.txt", status = TransferStatus.Running),
            TransferJob(TransferId(2), TransferDirection.Download, ROOT, "$ROOT/b.txt", "b.txt", status = TransferStatus.Queued),
        )
        assertComesToRest { TransferScreen(FilesRoute.Transfer(ROOT, ROOT), TransferState(ROOT, ROOT, jobs = jobs), onIntent = {}) }
    }
}
