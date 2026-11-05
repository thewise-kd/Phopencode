package io.github.thewisekd.phopencode.feature.files.ui.ops

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOp
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpError
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsDialog
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.NameProblem
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

/**
 * The file operations come to rest in a plain activity in every state, the way the app's
 * ActivityScenario tests open them. A spinner while an operation runs, or a blinking cursor in a
 * focused name field, would keep posting frames, and Robolectric's `idle()` would never return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class FileOpsIdleTest {
    private fun assertComesToRest(state: FileOpsState) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            PhopencodeTheme { FileOpsLayer(state, onIntent = {}) }
        }
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

    private val failed = FileOpError(FileOp.Rename, FilesFailure.PermissionDenied)

    @Test
    fun `nothing open comes to rest`() = assertComesToRest(FileOpsState())

    @Test
    fun `a file's menu comes to rest`() = assertComesToRest(fileMenu)

    @Test
    fun `a folder's menu comes to rest`() = assertComesToRest(folderMenu)

    @Test
    fun `the rename dialog comes to rest`() = assertComesToRest(renaming)

    @Test
    fun `a name problem comes to rest`() = assertComesToRest(
        FileOpsState(dialog = FileOpsDialog.Rename(opsFile, "a/b", NameProblem.Separator)),
    )

    @Test
    fun `a running rename comes to rest`() = assertComesToRest(renaming.copy(busy = FileOp.Rename))

    @Test
    fun `a failed rename comes to rest`() = assertComesToRest(renaming.copy(error = failed))

    @Test
    fun `the new folder dialog comes to rest`() = assertComesToRest(newFolder)

    @Test
    fun `a running new folder comes to rest`() = assertComesToRest(newFolder.copy(busy = FileOp.CreateFolder))

    @Test
    fun `a file's delete question comes to rest`() = assertComesToRest(deletingFile)

    @Test
    fun `a folder's delete question comes to rest`() = assertComesToRest(deletingFolder)

    @Test
    fun `a running delete comes to rest`() = assertComesToRest(deletingFolder.copy(busy = FileOp.Delete))

    @Test
    fun `a failure with nothing open comes to rest`() = assertComesToRest(
        FileOpsState(error = FileOpError(FileOp.Delete, FilesFailure.NotEmpty)),
    )

    @Test
    fun `an operation running with nothing open comes to rest`() = assertComesToRest(FileOpsState(busy = FileOp.Delete))
}
